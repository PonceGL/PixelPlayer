package com.theveloper.pixelplay.data.sonos.player

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.SonosUdn
import com.theveloper.pixelplay.data.sonos.model.TrackMetadata
import com.theveloper.pixelplay.data.sonos.model.TransportState
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.SonosTransport
import com.theveloper.pixelplay.data.sonos.session.SonosEvent
import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import com.theveloper.pixelplay.data.sonos.server.SonosMediaAccess
import com.theveloper.pixelplay.data.sonos.server.SonosServerIdentity
import com.theveloper.pixelplay.data.sonos.session.SonosCommander
import com.theveloper.pixelplay.data.sonos.session.SonosPlaybackState
import com.theveloper.pixelplay.data.sonos.session.SonosSessionEngine
import com.theveloper.pixelplay.data.sonos.session.SpeakerSnapshot
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The player the system sees while a speaker plays: what it reports from the session, how it takes the edits of the
 * queue (shown at once, sent to the speaker in the background) and what it sends for the transport commands.
 *
 * It runs on the JVM, so the looper is a stand-in whose thread is the test thread. media3's own public methods that
 * change the state (`addMediaItem`, `play`...) cannot run here: they rebuild the timeline with `android.util.Pair`,
 * which is a stub that returns null in unit tests. The handlers they call, which hold this class's logic, are
 * called directly instead.
 */
class SonosPlayerTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val access = SonosMediaAccess { SonosServerIdentity(token, 8080) }
    private val udn = SonosUdn("RINCON_000E58000001001400")

    private class FakeTransport : SonosTransport {
        val actions = CopyOnWriteArrayList<String>()

        override suspend fun call(
            host: String,
            service: SonosService,
            action: String,
            arguments: List<Pair<String, String>>,
            timeoutMs: Long,
        ): SonosResult<Map<String, String>> {
            actions += action
            return SonosResult.Ok(emptyMap())
        }
    }

    private fun uri(id: String) = "http://192.168.1.20:8080/sonos/song/$id?v=1&auth=$token"

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    private fun metadataOf(item: MediaItem): TrackMetadata? =
        if (item.mediaId.startsWith("x")) {
            null // a song the speaker cannot get
        } else {
            TrackMetadata("Song ${item.mediaId}", "Artist", "Album", null, 200_000, "audio/flac", uri(item.mediaId))
        }

    private class Fixture(
        val scope: TestScope,
        val player: SonosPlayer,
        val transport: FakeTransport,
        val failures: MutableList<SonosFailure>,
        val loaded: MutableStateFlow<Boolean>,
    )

    /** A speaker playing the second song of [songs], with the whole queue loaded. */
    private fun TestScope.fixture(songs: List<String> = listOf("a", "b", "c", "d")): Fixture {
        val transport = FakeTransport()
        val failures = mutableListOf<SonosFailure>()
        val loaded = MutableStateFlow(true)
        val looper = mockk<Looper>(relaxed = true)
        every { looper.thread } returns Thread.currentThread()
        val engine = SonosSessionEngine(
            events = MutableSharedFlow<SonosEvent>(extraBufferCapacity = 16),
            readState = {
                SonosResult.Ok(
                    SpeakerSnapshot(
                        SonosPlaybackState(
                            transportState = TransportState.PLAYING,
                            playMode = PlayMode.NORMAL,
                            currentTrackNumber = 2,
                            numberOfTracks = songs.size,
                            currentTrackUri = uri(songs[1]),
                            currentTrackDurationMs = 200_000,
                        ),
                        positionMs = 30_000,
                    ),
                )
            },
            elapsedMs = { currentTime },
            scope = backgroundScope,
        )
        engine.start()
        runCurrent()
        val commander = SonosCommander(transport, "192.168.1.50", udn, now = { currentTime }, recordCommand = {})
        val player = SonosPlayer(
            looper = looper,
            commander = commander,
            engine = engine,
            queueItems = songs.map(::item),
            speakerWindow = if (songs.size <= SonosCommander.MAX_QUEUE) songs.indices else SonosCommander.queueWindow(songs.size, 1),
            access = access,
            loaded = loaded,
            trackOf = ::metadataOf,
            scope = backgroundScope,
            shuffleByApp = false,
            onCommandFailed = { failures += it },
            elapsedRealtimeMs = { currentTime },
        )
        return Fixture(this, player, transport, failures, loaded)
    }

    private fun test(block: suspend TestScope.(Fixture) -> Unit) = runTest { block(fixture()) }

    private fun SonosPlayer.ids() = currentQueue().map { it.mediaId }

    @Test
    fun `it shows the queue and the song the speaker is playing`() = test { f ->
        assertThat(f.player.mediaItemCount).isEqualTo(4)
        assertThat(f.player.currentMediaItemIndex).isEqualTo(1)
        assertThat(f.player.songIdAt(1)).isEqualTo("b")
        assertThat(f.player.songIdAt(9)).isNull()
        assertThat(f.player.playWhenReady).isTrue()
        assertThat(f.player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(f.player.queueEdited).isFalse()
    }

    @Test
    fun `a song twice in the queue keeps one identity for each copy`() = runTest {
        val f = fixture(listOf("a", "b", "a", "d"))
        val window = Timeline.Window()

        val uids = (0 until f.player.currentTimeline.windowCount).map { f.player.currentTimeline.getWindow(it, window).uid }

        assertThat(uids).containsExactly("a#1", "b#1", "a#2", "d#1").inOrder()
    }

    /** Calls a handler the way media3 does after it has checked the command, which needs the Android runtime. */
    private fun SonosPlayer.handle(name: String, types: List<Class<*>>, vararg args: Any?) {
        val method = SonosPlayer::class.java.getDeclaredMethod(name, *types.toTypedArray())
        method.isAccessible = true
        method.invoke(this, *args)
    }

    private fun SonosPlayer.add(index: Int, vararg ids: String) =
        handle("handleAddMediaItems", listOf(Int::class.java, List::class.java), index, ids.map(::item))

    private fun SonosPlayer.move(from: Int, to: Int, newIndex: Int) =
        handle("handleMoveMediaItems", listOf(Int::class.java, Int::class.java, Int::class.java), from, to, newIndex)

    private fun SonosPlayer.remove(from: Int, to: Int) =
        handle("handleRemoveMediaItems", listOf(Int::class.java, Int::class.java), from, to)

    private fun SonosPlayer.replace(from: Int, to: Int, vararg ids: String) =
        handle("handleReplaceMediaItems", listOf(Int::class.java, Int::class.java, List::class.java), from, to, ids.map(::item))

    private fun SonosPlayer.setItems(vararg ids: String) =
        handle("handleSetMediaItems", listOf(List::class.java, Int::class.java, Long::class.java), ids.map(::item), 0, 0L)

    // Not called setPlayWhenReady: the member of the same name would win and media3 would ignore the command.
    private fun SonosPlayer.speakerPlay(on: Boolean) = handle("handleSetPlayWhenReady", listOf(Boolean::class.java), on)

    private fun SonosPlayer.stopSpeaker() = handle("handleStop", emptyList())

    private fun SonosPlayer.seek(index: Int, positionMs: Long, command: Int) =
        handle("handleSeek", listOf(Int::class.java, Long::class.java, Int::class.java), index, positionMs, command)

    private fun SonosPlayer.setRepeat(mode: Int) = handle("handleSetRepeatMode", listOf(Int::class.java), mode)

    private suspend fun TestScope.settle() {
        advanceTimeBy(2_000)
        runCurrent()
    }

    @Test
    fun `an edit is shown at once and marks the queue as edited`() = test { f ->
        f.player.add(4, "e")

        assertThat(f.player.ids()).containsExactly("a", "b", "c", "d", "e").inOrder()
        assertThat(f.player.queueEdited).isTrue()
    }

    @Test
    fun `an edit is sent to the speaker in the background`() = test { f ->
        f.player.add(4, "e")
        settle()

        assertThat(f.transport.actions).contains("AddMultipleURIsToQueue")
    }

    @Test
    fun `a song the speaker cannot get is left out and the user is told`() = test { f ->
        f.player.add(4, "x1")

        assertThat(f.player.ids()).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(f.failures).containsExactly(SonosFailure.NotOnSpeaker)
    }

    @Test
    fun `moving, removing and replacing songs change the queue`() = test { f ->
        f.player.move(0, 1, 2)
        assertThat(f.player.ids()).containsExactly("b", "c", "a", "d").inOrder()

        f.player.remove(3, 4)
        assertThat(f.player.ids()).containsExactly("b", "c", "a").inOrder()

        f.player.replace(2, 3, "z")
        assertThat(f.player.ids()).containsExactly("b", "c", "z").inOrder()
    }

    @Test
    fun `setting a new list replaces the queue`() = test { f ->
        f.player.setItems("p", "b", "q")

        assertThat(f.player.ids()).containsExactly("p", "b", "q").inOrder()
        assertThat(f.player.queueEdited).isTrue()
    }

    @Test
    fun `the playing song is followed to its new place when the queue changes`() = test { f ->
        f.player.remove(0, 1)

        assertThat(f.player.ids()).containsExactly("b", "c", "d").inOrder()
        assertThat(f.player.songIdAt(0)).isEqualTo("b")
    }

    @Test
    fun `a queue longer than a speaker holds is edited as a whole while only a part goes to the speaker`() = runTest {
        val f = fixture((0 until 1_200).map { "s$it" })

        f.player.remove(0, 1)
        settle()

        assertThat(f.player.currentQueue()).hasSize(1_199)
        assertThat(f.player.queueEdited).isTrue()
    }

    @Test
    fun `pause and stop reach the speaker`() = test { f ->
        f.player.speakerPlay(false)
        f.player.stopSpeaker()
        settle()

        assertThat(f.transport.actions).contains("Pause")
        assertThat(f.transport.actions).contains("Stop")
    }

    @Test
    fun `play reaches the speaker`() = test { f ->
        f.player.speakerPlay(true)
        settle()

        assertThat(f.transport.actions).contains("Play")
    }

    @Test
    fun `next and previous reach the speaker`() = test { f ->
        f.player.seek(0, 0L, Player.COMMAND_SEEK_TO_NEXT)
        f.player.seek(0, 0L, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        settle()

        assertThat(f.transport.actions).contains("Next")
        assertThat(f.transport.actions).contains("Previous")
    }

    @Test
    fun `choosing a song of the queue seeks to its place on the speaker`() = test { f ->
        f.player.seek(2, 0L, Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        settle()

        assertThat(f.transport.actions).contains("Seek")
    }

    @Test
    fun `a position inside the song seeks on the speaker`() = test { f ->
        f.player.seek(1, 45_000L, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        settle()

        assertThat(f.transport.actions).contains("Seek")
    }

    @Test
    fun `a song outside the part on the speaker is refused and the user is told`() = runTest {
        val f = fixture((0 until 1_200).map { "s$it" })

        f.player.seek(5, 0L, Player.COMMAND_SEEK_TO_MEDIA_ITEM) // inside the window around the playing song
        settle()
        val sentBefore = f.transport.actions.size
        f.player.seek(1_199, 0L, Player.COMMAND_SEEK_TO_MEDIA_ITEM) // far away from it
        settle()

        assertThat(f.transport.actions.size).isEqualTo(sentBefore)
        assertThat(f.failures).containsExactly(SonosFailure.NotOnSpeaker)
    }

    @Test
    fun `a repeat mode reaches the speaker as its play mode`() = test { f ->
        f.player.setRepeat(Player.REPEAT_MODE_ALL)
        settle()

        assertThat(f.transport.actions).contains("SetPlayMode")
    }

    @Test
    fun `releasing the player stops following the session`() = test { f ->
        f.player.attach()
        f.player.release()

        assertThat(f.player.playbackState).isAnyOf(Player.STATE_IDLE, Player.STATE_READY)
    }
}
