package com.theveloper.pixelplay.data.sonos.player

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.RepeatMode
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.server.SonosMediaAccess
import com.theveloper.pixelplay.data.sonos.session.Navigation
import com.theveloper.pixelplay.data.sonos.session.SonosCommander
import com.theveloper.pixelplay.data.sonos.session.SonosSessionEngine
import com.theveloper.pixelplay.utils.MediaItemBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A speaker presented as the media3 player the media session and the whole app already talk to. It holds
 * nothing of its own: the state is what the speaker said ([SonosSessionEngine]) and every command is sent to
 * the speaker ([SonosCommander]); the change comes back as an event.
 *
 * Everything here runs on [looper]'s thread, and [scope] must run on it too. The mapping from the session to
 * what the player says is in [SonosPlayerSnapshot], which is the part with tests.
 *
 * A command's future is done when the speaker has answered with a change of state (or after a short time), not
 * when the command was sent: until then media3 shows the change the user asked for, and not the old state that
 * would make a button jump back and forth.
 *
 * Shuffle belongs to the app in this part: the queue was sent in the order the user had, so the player does not
 * offer to change it.
 *
 * @param queueItems the app's queue as the user had it, not empty
 * @param speakerWindow the part of [queueItems] that is on the speaker
 * @param shuffleByApp the queue was sent already in a shuffled order
 * @param onCommandFailed told when the speaker refuses or cannot be reached; the player does not show errors itself
 */
@OptIn(UnstableApi::class)
internal class SonosPlayer(
    looper: Looper,
    private val commander: SonosCommander,
    private val engine: SonosSessionEngine,
    private val queueItems: List<MediaItem>,
    private val speakerWindow: IntRange,
    private val access: SonosMediaAccess,
    private val scope: CoroutineScope,
    private val shuffleByApp: Boolean,
    private val onCommandFailed: (SonosFailure) -> Unit = {},
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) : SimpleBasePlayer(looper) {

    init {
        require(queueItems.isNotEmpty()) { "A speaker session needs a queue" }
    }

    private val queue = PlayerQueue(queueItems.map { it.mediaId }, speakerWindow)

    /** Built once: a new timeline for every change of song would resend the whole queue to every controller. */
    private val playlist: List<MediaItemData> = queueItems.mapIndexed { index, item ->
        MediaItemData.Builder("$index:${item.mediaId}")
            .setMediaItem(item)
            .setDurationUs(appDurationMs(item)?.let { it * 1_000 } ?: C.TIME_UNSET)
            .setIsSeekable(true)
            .build()
    }

    /** The app keeps the length of a song in an extra of its item; the typed field is the fallback. */
    private fun appDurationMs(item: MediaItem): Long? =
        item.mediaMetadata.extras?.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION)?.takeIf { it > 0L }
            ?: item.mediaMetadata.durationMs

    private var previousIndex: Int? = null
    private var observing: Job? = null

    /** Starts following the session: every change of it is a change of the player. */
    fun attach() {
        if (observing != null) return
        observing = scope.launch {
            engine.state.collect {
                previousIndex = snapshot().currentIndex ?: previousIndex
                invalidateState()
            }
        }
    }

    fun detach() {
        observing?.cancel()
        observing = null
    }

    private fun snapshot() = SonosPlayerSnapshot.from(engine.state.value, elapsedRealtimeMs(), queue, access, shuffleByApp, previousIndex)

    override fun getState(): State {
        val snapshot = snapshot()
        val builder = State.Builder()
            .setAvailableCommands(commandsFor(snapshot))
            .setPlayWhenReady(snapshot.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(
                when (snapshot.phase) {
                    SonosPlayerSnapshot.Phase.IDLE -> Player.STATE_IDLE
                    SonosPlayerSnapshot.Phase.BUFFERING -> Player.STATE_BUFFERING
                    SonosPlayerSnapshot.Phase.READY -> Player.STATE_READY
                    SonosPlayerSnapshot.Phase.ENDED -> Player.STATE_ENDED
                },
            )
            .setShuffleModeEnabled(snapshot.shuffle)
            .setRepeatMode(
                when (snapshot.repeat) {
                    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                },
            )
        // media3 cannot say "no song" with a queue: when the speaker plays something else, the player shows no queue.
        val index = snapshot.currentIndex
        if (index != null) {
            builder.setPlaylist(withSpeakerDuration(index, snapshot.durationMs))
            builder.setCurrentMediaItemIndex(index)
        }
        // The session counts the position (it stops at the end of the song and stays still while paused).
        builder.setContentPositionMs { engine.state.value.positionAt(elapsedRealtimeMs()) }
        return builder.build()
    }

    /** The app's own duration is kept; the speaker's is used for a song that had none, which changes only that item. */
    private fun withSpeakerDuration(index: Int, speakerDurationMs: Long?): List<MediaItemData> {
        val current = playlist[index]
        if (speakerDurationMs == null || current.durationUs != C.TIME_UNSET) return playlist
        return playlist.toMutableList().also { it[index] = current.buildUpon().setDurationUs(speakerDurationMs * 1_000).build() }
    }

    private fun commandsFor(snapshot: SonosPlayerSnapshot): Player.Commands {
        // Release is always there: without it the player could never be let go.
        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_RELEASE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
        if (!snapshot.canControl) return commands.build()
        commands
            .addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP)
            .add(Player.COMMAND_SET_REPEAT_MODE)
            .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
        if (snapshot.canSeek) commands.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        if (snapshot.canNext) commands.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        if (snapshot.canPreviousItem) commands.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        return commands.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = send {
        if (playWhenReady) {
            commander.play().also { if (it is SonosResult.Ok) engine.onPlayAccepted() }
        } else {
            commander.pause()
        }
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> = send { commander.stop() }

    override fun handleRelease(): ListenableFuture<*> {
        detach()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = send {
        val position = if (positionMs == C.TIME_UNSET) 0L else positionMs
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> commander.next().andRead()
            Player.COMMAND_SEEK_TO_PREVIOUS -> commander.previousSmart(snapshot().positionMs).andRead()
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> commander.previousSmart(0).andRead()
            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> seekToItem(mediaItemIndex, position)
            else -> commander.seekTo(position).also { engine.requestRead() }
        }
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = send {
        val repeat = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }
        // Only what the speaker itself shuffles is its mode; the app's shuffle lives in the order of the queue.
        commander.setPlayMode(PlayMode.of(engine.state.value.playback.playMode?.shuffle ?: false, repeat))
    }

    private suspend fun seekToItem(mediaItemIndex: Int, positionMs: Long): SonosResult<*> {
        // Songs outside the window are not on the speaker.
        if (mediaItemIndex !in speakerWindow) return SonosResult.Err(SonosFailure.NotOnSpeaker)
        return commander.seekToTrack(mediaItemIndex - speakerWindow.first + 1, positionMs).also { engine.requestRead() }
    }

    private fun SonosResult<Navigation>.andRead(): SonosResult<Navigation> = also { engine.requestRead() }

    /**
     * Sends the command in the background. The future is done once the speaker has answered with a new state, or
     * after [CONFIRMATION_TIMEOUT_MS], and always, even if the command fails or the scope ends.
     */
    private fun send(command: suspend () -> SonosResult<*>): ListenableFuture<*> {
        val done = SettableFuture.create<Any?>()
        val before = engine.state.value
        scope.launch {
            try {
                val result = command()
                if (result is SonosResult.Err) {
                    onCommandFailed(result.failure)
                } else {
                    withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) { engine.state.first { it != before } }
                }
            } finally {
                done.set(null)
            }
        }
        return done
    }

    private companion object {
        const val CONFIRMATION_TIMEOUT_MS = 1_500L
    }
}
