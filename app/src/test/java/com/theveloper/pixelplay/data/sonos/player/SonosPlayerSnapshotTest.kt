package com.theveloper.pixelplay.data.sonos.player

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.RepeatMode
import com.theveloper.pixelplay.data.sonos.model.TransportState
import com.theveloper.pixelplay.data.sonos.server.SonosMediaAccess
import com.theveloper.pixelplay.data.sonos.server.SonosServerIdentity
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
    ) = SonosSessionState(
        playback = SonosPlaybackState(
            transportState = transport, playMode = mode, currentTrackNumber = number, numberOfTracks = total,
            currentTrackUri = uri, currentTrackDurationMs = durationMs,
        ),
        anchor = PositionAnchor(positionMs, atElapsedMs = 1_000, isAdvancing = advancing, durationMs = durationMs),
        queueEnded = queueEnded,
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

        val result = snapshot(state(number = 9, uri = uri("a")), queue(ids), previousIndex = 1)

        assertThat(result.currentIndex).isEqualTo(2)
    }

    @Test
    fun `the search wraps around and falls back to the first match without a last known song`() {
        val ids = listOf("a", "b", "a", "c")

        assertThat(snapshot(state(number = 9, uri = uri("a")), queue(ids), previousIndex = 3).currentIndex).isEqualTo(0)
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
    fun `when the speaker plays something that is not in our queue the player is idle and not on a wrong song`() {
        for (foreign in listOf("x-sonos-spotify:spotify%3atrack%3a1", uri("zzz"), null)) {
            val result = snapshot(state(TransportState.PLAYING, uri = foreign))

            assertWithMessage("$foreign").that(result.phase).isEqualTo(SonosPlayerSnapshot.Phase.IDLE)
            assertWithMessage("$foreign").that(result.canControl).isFalse()
            assertWithMessage("$foreign").that(result.currentIndex).isNull()
        }
    }

    @Test
    fun `the end of the queue shows the first song as the current one`() {
        val result = snapshot(state(TransportState.STOPPED, number = 1, uri = uri("a"), advancing = false, queueEnded = true))

        assertThat(result.phase).isEqualTo(SonosPlayerSnapshot.Phase.ENDED)
        assertThat(result.currentIndex).isEqualTo(0)
    }

    @Test
    fun `the index of the same state is the same every time it is asked`() {
        val ids = listOf("a", "b", "a", "c")
        val ambiguous = state(number = 9, uri = uri("a"))

        var index = snapshot(ambiguous, queue(ids), previousIndex = null).currentIndex
        repeat(5) {
            val again = snapshot(ambiguous, queue(ids), previousIndex = index).currentIndex
            assertThat(again).isEqualTo(index)
            index = again
        }
    }

    @Test
    fun `a window that goes past the end of the queue only counts what exists`() {
        val result = snapshot(state(number = 2, uri = uri("b")), queue(songs, window = 0..50))

        assertThat(result.currentIndex).isEqualTo(1)
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
    fun `in a normal queue the ends have no next or no previous song`() {
        val first = snapshot(state(number = 1, uri = uri("a"), positionMs = 1_000))
        assertThat(first.canPreviousItem).isFalse()
        assertThat(first.canNext).isTrue()

        val middle = snapshot(state(number = 2))
        assertThat(middle.canPreviousItem).isTrue()
        assertThat(middle.canNext).isTrue()

        val last = snapshot(state(number = 4, uri = uri("d")))
        assertThat(last.canNext).isFalse()
        assertThat(last.canPreviousItem).isTrue()
    }

    @Test
    fun `previous is always there to restart the song, even at the first one`() {
        assertThat(snapshot(state(number = 1, uri = uri("a"), positionMs = 500)).canPrevious).isTrue()
        assertThat(snapshot(state(number = 1, uri = uri("a"), positionMs = 30_000)).canPrevious).isTrue()
        assertThat(snapshot(state(TransportState.NO_MEDIA_PRESENT, uri = null, number = null)).canPrevious).isFalse()
    }

    @Test
    fun `a queue of one song has no other song to go to`() {
        val result = snapshot(state(number = 1, total = 1, uri = uri("a"), positionMs = 500), queue(listOf("a")))

        assertThat(result.canNext).isFalse()
        assertThat(result.canPreviousItem).isFalse()
    }

    @Test
    fun `repeating or shuffling wraps around so both stay`() {
        for (mode in listOf(PlayMode.REPEAT_ALL, PlayMode.REPEAT_ONE, PlayMode.SHUFFLE, PlayMode.SHUFFLE_NOREPEAT)) {
            val atStart = snapshot(state(mode = mode, number = 1, uri = uri("a"), positionMs = 0))
            val atEnd = snapshot(state(mode = mode, number = 4, uri = uri("d")))

            assertWithMessage("$mode previous at start").that(atStart.canPreviousItem).isTrue()
            assertWithMessage("$mode next at end").that(atEnd.canNext).isTrue()
        }
    }

    @Test
    fun `an order the app shuffled does not wrap around at the end`() {
        val result = snapshot(state(mode = PlayMode.NORMAL, number = 4, uri = uri("d")), shuffleByApp = true)

        assertThat(result.canNext).isFalse()
    }

    @Test
    fun `numbers the speaker does not give leave only what is certain`() {
        val result = snapshot(state(number = null, total = null, uri = uri("b"), positionMs = 0))

        assertThat(result.canNext).isFalse()
        assertThat(result.canPreviousItem).isFalse()
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

class SonosPlayerSnapshotElsewhereTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val access = SonosMediaAccess { SonosServerIdentity(token, 8080) }
    private val queue = PlayerQueue(listOf("a", "b", "c"), 0..2)

    private fun elsewhere(transport: TransportState, uri: String?): Boolean {
        val state = SonosSessionState(
            playback = SonosPlaybackState(transportState = transport, currentTrackNumber = 1, numberOfTracks = 1, currentTrackUri = uri),
        )
        return SonosPlayerSnapshot.from(state, 1_000, queue, access, shuffleByApp = false, previousIndex = null).speakerElsewhere
    }

    @Test
    fun `a speaker playing an address that is not ours is somewhere else`() {
        assertThat(elsewhere(TransportState.PLAYING, "x-sonosapi-stream:s1234?sid=254")).isTrue()
        assertThat(elsewhere(TransportState.TRANSITIONING, "http://192.168.1.50:1400/other.mp3")).isTrue()
    }

    @Test
    fun `a song of ours is never somewhere else`() {
        assertThat(elsewhere(TransportState.PLAYING, "http://192.168.1.20:8080/sonos/song/b?v=1&auth=$token")).isFalse()
    }

    @Test
    fun `a speaker that is not playing, or that says nothing yet, is not somewhere else`() {
        assertThat(elsewhere(TransportState.PAUSED_PLAYBACK, "x-sonosapi-stream:s1234?sid=254")).isFalse()
        assertThat(elsewhere(TransportState.STOPPED, "x-sonosapi-stream:s1234?sid=254")).isFalse()
        assertThat(elsewhere(TransportState.UNKNOWN, null)).isFalse()
        assertThat(elsewhere(TransportState.PLAYING, null)).isFalse()
    }
}

class SonosPlayerSnapshotTakeoverTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val access = SonosMediaAccess { SonosServerIdentity(token, 8080) }
    private val queue = PlayerQueue(listOf("a", "b", "c"), 0..2)
    private val foreign = SonosSessionState(
        playback = SonosPlaybackState(
            transportState = TransportState.PLAYING, currentTrackNumber = 1, numberOfTracks = 1,
            currentTrackUri = "x-sonosapi-stream:s1234?sid=254",
        ),
    )

    @Test
    fun `while somebody else has the speaker the last song stays on screen, paused and out of reach`() {
        val snapshot = SonosPlayerSnapshot.from(foreign, 1_000, queue, access, shuffleByApp = false, previousIndex = 1)

        assertThat(snapshot.phase).isEqualTo(SonosPlayerSnapshot.Phase.READY)
        assertThat(snapshot.currentIndex).isEqualTo(1)
        assertThat(snapshot.playWhenReady).isFalse()
        assertThat(snapshot.positionAdvancing).isFalse()
        assertThat(snapshot.canControl).isFalse()
    }

    @Test
    fun `with no song shown before there is nothing to keep on screen`() {
        val snapshot = SonosPlayerSnapshot.from(foreign, 1_000, queue, access, shuffleByApp = false, previousIndex = null)

        assertThat(snapshot.phase).isEqualTo(SonosPlayerSnapshot.Phase.IDLE)
        assertThat(snapshot.currentIndex).isNull()
    }
}
