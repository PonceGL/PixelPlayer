package com.theveloper.pixelplay.data.sonos.player

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.RepeatMode
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.server.SonosMediaAccess
import com.theveloper.pixelplay.data.sonos.session.Navigation
import com.theveloper.pixelplay.data.sonos.session.SonosCommander
import com.theveloper.pixelplay.data.sonos.session.SonosSessionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A speaker presented as the media3 player the media session and the whole app already talk to. It holds
 * nothing of its own: the state is what the speaker said ([SonosSessionEngine]) and every command is sent to
 * the speaker ([SonosCommander]); the change comes back as an event.
 *
 * Everything here runs on [looper]'s thread, and [scope] must run on it too. The mapping from the session to
 * what the player says is in [SonosPlayerSnapshot], which is the part with tests.
 *
 * @param queueItems the app's queue as the user had it
 * @param speakerWindow the part of [queueItems] that is on the speaker
 * @param shuffleByApp the queue was sent already in a shuffled order
 * @param onCommandFailed told when the speaker refuses or cannot be reached; the player does not show errors itself
 */
internal class SonosPlayer(
    looper: Looper,
    private val commander: SonosCommander,
    private val engine: SonosSessionEngine,
    private val queueItems: List<MediaItem>,
    private val speakerWindow: IntRange,
    private val access: SonosMediaAccess,
    private val scope: CoroutineScope,
    private var shuffleByApp: Boolean,
    private val onCommandFailed: (SonosFailure) -> Unit = {},
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) : SimpleBasePlayer(looper) {

    private val queue = PlayerQueue(queueItems.map { it.mediaId }, speakerWindow)
    private var previousIndex: Int? = null
    private var observing: Job? = null

    /** Starts following the session: every change of it is a change of the player. */
    fun attach() {
        if (observing != null) return
        observing = scope.launch { engine.state.collect { invalidateState() } }
    }

    fun detach() {
        observing?.cancel()
        observing = null
    }

    private fun snapshot() = SonosPlayerSnapshot.from(engine.state.value, elapsedRealtimeMs(), queue, access, shuffleByApp, previousIndex)
        .also { previousIndex = it.currentIndex ?: previousIndex }

    override fun getState(): State {
        val snapshot = snapshot()
        val playlist = queueItems.mapIndexed { index, item ->
            val current = index == snapshot.currentIndex
            MediaItemData.Builder("$index:${item.mediaId}")
                .setMediaItem(item)
                .setDurationUs(if (current && snapshot.durationMs != null) snapshot.durationMs * 1_000 else C.TIME_UNSET)
                .setIsSeekable(current && snapshot.canSeek)
                .build()
        }
        val builder = State.Builder()
            .setAvailableCommands(commandsFor(snapshot))
            .setPlaylist(playlist)
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
        if (snapshot.currentIndex != null) builder.setCurrentMediaItemIndex(snapshot.currentIndex)
        if (snapshot.positionAdvancing) {
            builder.setContentPositionMs(PositionSupplier.getExtrapolating(snapshot.positionMs, 1f))
        } else {
            builder.setContentPositionMs(snapshot.positionMs)
        }
        return builder.build()
    }

    private fun commandsFor(snapshot: SonosPlayerSnapshot): Player.Commands {
        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
        if (!snapshot.canControl) return commands.build()
        commands
            .addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP)
            .addAll(Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE)
            .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        if (snapshot.canSeek) commands.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        if (snapshot.canNext) commands.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        if (snapshot.canPrevious) commands.addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
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
        // The shuffle of the app lives in the order of the queue; only what the speaker itself shuffles is its mode.
        commander.setPlayMode(PlayMode.of(engine.state.value.playback.playMode?.shuffle ?: false, repeat))
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> = send {
        shuffleByApp = false
        val repeat = engine.state.value.playback.playMode?.repeat ?: RepeatMode.OFF
        commander.setPlayMode(PlayMode.of(shuffleModeEnabled, repeat))
    }

    private suspend fun seekToItem(mediaItemIndex: Int, positionMs: Long): SonosResult<*> {
        // Songs outside the window are not on the speaker.
        if (mediaItemIndex !in speakerWindow) return SonosResult.Err(SonosFailure.NotOnSpeaker)
        return commander.seekToTrack(mediaItemIndex - speakerWindow.first + 1, positionMs).also { engine.requestRead() }
    }

    private fun SonosResult<Navigation>.andRead(): SonosResult<Navigation> = also { engine.requestRead() }

    /** The command goes out in the background; the player answers at once and the speaker's event tells the result. */
    private fun send(command: suspend () -> SonosResult<*>): ListenableFuture<*> {
        scope.launch {
            val result = command()
            if (result is SonosResult.Err) onCommandFailed(result.failure)
        }
        return Futures.immediateVoidFuture()
    }
}
