package com.theveloper.pixelplay.data.sonos.player

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.RepeatMode
import com.theveloper.pixelplay.data.sonos.model.TransportState
import com.theveloper.pixelplay.data.sonos.protocol.TransportErrorKind
import com.theveloper.pixelplay.data.sonos.server.SonosMediaAccess
import com.theveloper.pixelplay.data.sonos.server.SonosServerIdentity
import com.theveloper.pixelplay.data.sonos.session.PlaybackError
import com.theveloper.pixelplay.data.sonos.session.PositionAnchor
import com.theveloper.pixelplay.data.sonos.session.SonosPlaybackState
import com.theveloper.pixelplay.data.sonos.session.SonosSessionState
import org.junit.jupiter.api.Test

class SonosPlayerSnapshotTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val access = SonosMediaAccess { SonosServerIdentity(token, 8080) }

    private fun uri(id: String) = "http://192.168.1.20:8080/sonos/song/$id?v=1&auth=$token"

    private val songs = listOf("a", "b", "c", "d")

    private fun queue(ids: List<String> = songs, window: IntRange = ids.indices) = PlayerQueue(ids, window)

    private fun state(
        transport: TransportState = TransportState.PLAYING,
        number: Int? = 2,
        total: Int? = 4,
        uri: String? = uri("b"),
        mode: PlayMode? = PlayMode.NORMAL,
        durationMs: Long? = 200_000,
        positionMs: Long = 30_000,
        advancing: Boolean = true,
        queueEnded: Boolean = false,
        error: PlaybackError? = null,
    ) = SonosSessionState(
        playback = SonosPlaybackState(
            transportState = transport, playMode = mode, currentTrackNumber = number, numberOfTracks = total,
            currentTrackUri = uri, currentTrackDurationMs = durationMs,
        ),
        anchor = PositionAnchor(positionMs, atElapsedMs = 1_000, isAdvancing = advancing, durationMs = durationMs),
        queueEnded = queueEnded,
        lastError = error,
    )

    private fun snapshot(
        state: SonosSessionState = state(),
        queue: PlayerQueue = queue(),
        now: Long = 1_000,
        shuffleByApp: Boolean = false,
        previousIndex: Int? = null,
    ) = SonosPlayerSnapshot.from(state, now, queue, access, shuffleByApp, previousIndex)

    @Test
    fun `playing is ready and wants to play, paused is ready and does not`() {
        val playing = snapshot(state(TransportState.PLAYING))
        assertThat(playing.phase).isEqualTo(SonosPlayerSnapshot.Phase.READY)
        assertThat(playing.playWhenReady).isTrue()
        assertThat(playing.positionAdvancing).isTrue()

        val paused = snapshot(state(TransportState.PAUSED_PLAYBACK, advancing = false))
        assertThat(paused.phase).isEqualTo(SonosPlayerSnapshot.Phase.READY)
        assertThat(paused.playWhenReady).isFalse()
        assertThat(paused.positionAdvancing).isFalse()
    }

    @Test
    fun `loading the song is buffering and still wants to play`() {
        val result = snapshot(state(TransportState.TRANSITIONING, advancing = false))

        assertThat(result.phase).isEqualTo(SonosPlayerSnapshot.Phase.BUFFERING)
        assertThat(result.playWhenReady).isTrue()
        assertThat(result.positionAdvancing).isFalse()
    }

    @Test
    fun `a stopped speaker with a song ready is ready and paused, so play works`() {
        val result = snapshot(state(TransportState.STOPPED, advancing = false))

        assertThat(result.phase).isEqualTo(SonosPlayerSnapshot.Phase.READY)
        assertThat(result.playWhenReady).isFalse()
        assertThat(result.canControl).isTrue()
    }

    @Test
    fun `the end of the queue is ended and play is still possible`() {
        val result = snapshot(state(TransportState.STOPPED, number = 1, advancing = false, queueEnded = true))

        assertThat(result.phase).isEqualTo(SonosPlayerSnapshot.Phase.ENDED)
        assertThat(result.playWhenReady).isFalse()
        assertThat(result.canControl).isTrue()
    }

    @Test
    fun `an empty or unknown speaker is idle and cannot be controlled`() {
        for (transport in listOf(TransportState.NO_MEDIA_PRESENT, TransportState.UNKNOWN)) {
            val result = snapshot(state(transport, number = null, total = null, uri = null, advancing = false))

            assertWithMessage("$transport").that(result.phase).isEqualTo(SonosPlayerSnapshot.Phase.IDLE)
            assertWithMessage("$transport").that(result.canControl).isFalse()
            assertWithMessage("$transport").that(result.canNext).isFalse()
            assertWithMessage("$transport").that(result.canPrevious).isFalse()
            assertWithMessage("$transport").that(result.currentIndex).isNull()
        }
    }

    @Test
    fun `a playback error is carried and the player is not left playing`() {
        val error = PlaybackError(TransportErrorKind.UNSUPPORTED_FORMAT, "u", "Song")

        val result = snapshot(state(TransportState.STOPPED, advancing = false, error = error))

        assertThat(result.error).isEqualTo(error)
        assertThat(result.playWhenReady).isFalse()
    }

    @Test
    fun `the current song is the one at the speaker's track number inside the window`() {
        val result = snapshot(state(number = 3, uri = uri("c")))

        assertThat(result.currentIndex).isEqualTo(2)
    }

    @Test
    fun `a window that starts later shifts the numbers onto the app's queue`() {
        val ids = List(300) { "s$it" }

        val result = snapshot(state(number = 101, total = 200, uri = uri("s250")), queue(ids, window = 150..299))

        assertThat(result.currentIndex).isEqualTo(250)
    }

    @Test
    fun `the same song twice in the queue is told apart by the number`() {
        val ids = listOf("a", "b", "a", "c")

        assertThat(snapshot(state(number = 1, uri = uri("a")), queue(ids)).currentIndex).isEqualTo(0)
        assertThat(snapshot(state(number = 3, uri = uri("a")), queue(ids)).currentIndex).isEqualTo(2)
    }

    @Test
    fun `when the number does not match the address the song is searched after the last known one`() {
        val ids = listOf("a", "b", "a", "c")

        val result = snapshot(state(number = 9, uri = uri("a")), queue(ids), previousIndex = 0)

        assertThat(result.currentIndex).isEqualTo(2)
    }

    @Test
    fun `the search wraps around and falls back to the first match without a last known song`() {
        val ids = listOf("a", "b", "a", "c")

        assertThat(snapshot(state(number = 9, uri = uri("a")), queue(ids), previousIndex = 2).currentIndex).isEqualTo(0)
        assertThat(snapshot(state(number = 9, uri = uri("a")), queue(ids), previousIndex = null).currentIndex).isEqualTo(0)
    }

    @Test
    fun `a song that is not ours or not in the queue has no index`() {
        assertThat(snapshot(state(number = 2, uri = "x-sonos-spotify:spotify%3atrack%3a1")).currentIndex).isNull()
        assertThat(snapshot(state(number = 2, uri = "http://192.168.1.20:8080/sonos/song/b?auth=someone-else")).currentIndex).isNull()
        assertThat(snapshot(state(number = 2, uri = uri("zzz"))).currentIndex).isNull()
        assertThat(snapshot(state(number = 2, uri = null)).currentIndex).isNull()
    }

    @Test
    fun `numbers outside the window are never trusted`() {
        for (number in listOf(0, -1, 5, 1_000)) {
            val result = snapshot(state(number = number, uri = uri("b")))

            assertWithMessage("number $number").that(result.currentIndex).isEqualTo(1)
        }
        assertThat(snapshot(state(number = null, uri = uri("c"))).currentIndex).isEqualTo(2)
    }

    @Test
    fun `an empty queue has no index and no way to move`() {
        val result = snapshot(state(), PlayerQueue(emptyList(), IntRange.EMPTY))

        assertThat(result.currentIndex).isNull()
    }

    @Test
    fun `every play mode is shown as its shuffle and repeat`() {
        for (mode in PlayMode.entries) {
            val result = snapshot(state(mode = mode))

            assertWithMessage("$mode").that(result.shuffle).isEqualTo(mode.shuffle)
            assertWithMessage("$mode").that(result.repeat).isEqualTo(mode.repeat)
        }
        val unknown = snapshot(state(mode = null))
        assertThat(unknown.shuffle).isFalse()
        assertThat(unknown.repeat).isEqualTo(RepeatMode.OFF)
    }

    @Test
    fun `a queue the app sent already shuffled shows shuffle on until the user changes it`() {
        val result = snapshot(state(mode = PlayMode.REPEAT_ALL), shuffleByApp = true)

        assertThat(result.shuffle).isTrue()
        assertThat(result.repeat).isEqualTo(RepeatMode.ALL)
    }

    @Test
    fun `in a normal queue the ends have no next or no previous`() {
        val first = snapshot(state(number = 1, uri = uri("a"), positionMs = 1_000))
        assertThat(first.canPrevious).isFalse()
        assertThat(first.canNext).isTrue()

        val middle = snapshot(state(number = 2))
        assertThat(middle.canPrevious).isTrue()
        assertThat(middle.canNext).isTrue()

        val last = snapshot(state(number = 4, uri = uri("d")))
        assertThat(last.canNext).isFalse()
        assertThat(last.canPrevious).isTrue()
    }

    @Test
    fun `previous at the first song restarts it after three seconds`() {
        assertThat(snapshot(state(number = 1, uri = uri("a"), positionMs = 3_000)).canPrevious).isFalse()
        assertThat(snapshot(state(number = 1, uri = uri("a"), positionMs = 3_001)).canPrevious).isTrue()
    }

    @Test
    fun `a queue of one song has neither`() {
        val result = snapshot(state(number = 1, total = 1, uri = uri("a"), positionMs = 500), queue(listOf("a")))

        assertThat(result.canNext).isFalse()
        assertThat(result.canPrevious).isFalse()
    }

    @Test
    fun `repeating or shuffling wraps around so both buttons stay`() {
        for (mode in listOf(PlayMode.REPEAT_ALL, PlayMode.REPEAT_ONE, PlayMode.SHUFFLE, PlayMode.SHUFFLE_NOREPEAT)) {
            val atStart = snapshot(state(mode = mode, number = 1, uri = uri("a"), positionMs = 0))
            val atEnd = snapshot(state(mode = mode, number = 4, uri = uri("d")))

            assertWithMessage("$mode previous at start").that(atStart.canPrevious).isTrue()
            assertWithMessage("$mode next at end").that(atEnd.canNext).isTrue()
        }
    }

    @Test
    fun `numbers the speaker does not give leave only what is certain`() {
        val result = snapshot(state(number = null, total = null, uri = uri("b"), positionMs = 0))

        assertThat(result.canNext).isFalse()
        assertThat(result.canPrevious).isFalse()
    }

    @Test
    fun `the position moves with the clock only while it advances and stops at the duration`() {
        val playing = state(positionMs = 30_000, advancing = true, durationMs = 40_000)

        assertThat(snapshot(playing, now = 1_000).positionMs).isEqualTo(30_000)
        assertThat(snapshot(playing, now = 6_000).positionMs).isEqualTo(35_000)
        assertThat(snapshot(playing, now = 60_000).positionMs).isEqualTo(40_000)

        val paused = state(TransportState.PAUSED_PLAYBACK, positionMs = 30_000, advancing = false)
        assertThat(snapshot(paused, now = 60_000).positionMs).isEqualTo(30_000)
    }

    @Test
    fun `the duration is the speaker's, or unknown`() {
        assertThat(snapshot(state(durationMs = 200_000)).durationMs).isEqualTo(200_000)
        assertThat(snapshot(state(durationMs = null)).durationMs).isNull()
        assertThat(snapshot(state(durationMs = 0)).durationMs).isNull()
    }

    @Test
    fun `seeking is possible only with a known duration and a song`() {
        assertThat(snapshot(state(durationMs = 200_000)).canSeek).isTrue()
        assertThat(snapshot(state(durationMs = null)).canSeek).isFalse()
        assertThat(snapshot(state(TransportState.NO_MEDIA_PRESENT, uri = null, number = null)).canSeek).isFalse()
    }
}
