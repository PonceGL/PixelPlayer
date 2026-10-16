package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.TrackMetadata
import com.theveloper.pixelplay.data.sonos.model.TransportState
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.protocol.AvTransportChange
import com.theveloper.pixelplay.data.sonos.protocol.RenderingChange
import com.theveloper.pixelplay.data.sonos.protocol.TransportErrorKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SonosSessionEngineTest {

    private fun snap(
        state: TransportState = TransportState.PLAYING,
        uri: String? = "u1",
        number: Int? = 1,
        total: Int? = 3,
        positionMs: Long? = 10_000,
        durationMs: Long? = 200_000,
        mode: PlayMode? = PlayMode.NORMAL,
        title: String? = "My Song",
    ) = SpeakerSnapshot(
        SonosPlaybackState(
            transportState = state,
            playMode = mode,
            currentTrackNumber = number,
            numberOfTracks = total,
            currentTrackUri = uri,
            currentTrackDurationMs = durationMs,
            currentTrack = title?.let { TrackMetadata(it, null, null, null, durationMs, "audio/flac", uri.orEmpty()) },
        ),
        positionMs,
    )

    private class Fixture(val scope: TestScope) {
        val events = MutableSharedFlow<SonosEvent>(extraBufferCapacity = 256)
        var speaker: SonosResult<SpeakerSnapshot> = SonosResult.Ok(SpeakerSnapshot(SonosPlaybackState(), null))
        var readDelayMs = 0L
        val reads = mutableListOf<Long>()
        private var inFlight = 0
        var maxInFlight = 0

        val engine = SonosSessionEngine(
            events = events,
            readState = {
                reads += scope.currentTime
                inFlight++
                maxInFlight = maxOf(maxInFlight, inFlight)
                val answer = speaker // what the speaker says when the read reaches it, not when the answer comes back
                if (readDelayMs > 0) delay(readDelayMs)
                inFlight--
                answer
            },
            elapsedMs = { scope.currentTime },
            scope = scope.backgroundScope,
        )

        val state get() = engine.state.value
        fun emit(event: SonosEvent) { events.tryEmit(event) }
        fun av(change: AvTransportChange) = emit(SonosEvent.AvTransport(change, null))
        fun positionNow() = state.positionAt(scope.currentTime)
    }

    private fun test(block: suspend TestScope.(Fixture) -> Unit) = runTest { block(Fixture(this)) }

    private suspend fun TestScope.started(f: Fixture, speaker: SpeakerSnapshot? = null): Fixture {
        speaker?.let { f.speaker = SonosResult.Ok(it) }
        f.engine.start()
        runCurrent()
        return f
    }

    @Test
    fun `starting reads the full state once and anchors the position`() = test { f ->
        started(f, snap(positionMs = 10_000))

        assertThat(f.reads).containsExactly(0L)
        assertThat(f.state.playback.transportState).isEqualTo(TransportState.PLAYING)
        assertThat(f.state.playback.currentTrackUri).isEqualTo("u1")
        assertThat(f.positionNow()).isEqualTo(10_000)
    }

    @Test
    fun `a playing position advances with the clock and stops at the duration`() = test { f ->
        started(f, snap(positionMs = 10_000, durationMs = 20_000))
        f.speaker = SonosResult.Ok(snap(positionMs = 20_000, durationMs = 20_000)) // what the speaker says when it is looked at

        advanceTimeBy(5_000)
        assertThat(f.positionNow()).isEqualTo(15_000)
        advanceTimeBy(7_000)
        runCurrent()
        assertThat(f.positionNow()).isEqualTo(20_000)
    }

    @Test
    fun `pausing freezes the position where it was, from the event alone`() = test { f ->
        started(f, snap(positionMs = 10_000))
        advanceTimeBy(5_000)
        f.speaker = SonosResult.Err(SonosFailure.Timeout) // no read can mask what the event did

        f.av(AvTransportChange(transportState = TransportState.PAUSED_PLAYBACK))
        runCurrent()
        advanceTimeBy(1_000)
        assertThat(f.positionNow()).isEqualTo(15_000)
        advanceTimeBy(60_000)

        assertThat(f.positionNow()).isEqualTo(15_000)
        assertThat(f.state.playback.transportState).isEqualTo(TransportState.PAUSED_PLAYBACK)
    }

    @Test
    fun `playing again continues from where it stopped, from the event alone`() = test { f ->
        started(f, snap(state = TransportState.PAUSED_PLAYBACK, positionMs = 40_000))
        f.speaker = SonosResult.Err(SonosFailure.Timeout)

        f.av(AvTransportChange(transportState = TransportState.PLAYING))
        runCurrent()
        advanceTimeBy(1_000)

        assertThat(f.positionNow()).isEqualTo(41_000)
    }

    @Test
    fun `a transition freezes the position and playing continues from there`() = test { f ->
        started(f, snap(positionMs = 10_000))
        advanceTimeBy(2_000)
        f.speaker = SonosResult.Err(SonosFailure.Timeout)

        f.av(AvTransportChange(transportState = TransportState.TRANSITIONING))
        runCurrent()
        advanceTimeBy(3_000)
        assertThat(f.positionNow()).isEqualTo(12_000)

        f.av(AvTransportChange(transportState = TransportState.PLAYING))
        runCurrent()
        advanceTimeBy(1_000)
        assertThat(f.positionNow()).isEqualTo(13_000)
    }

    @Test
    fun `a new song restarts the position, forgets the old metadata and reads the speaker`() = test { f ->
        started(f, snap(uri = "u1", positionMs = 90_000))
        advanceTimeBy(1_000)
        f.speaker = SonosResult.Ok(snap(uri = "u2", number = 2, positionMs = 400, title = "Next Song"))

        f.av(AvTransportChange(currentTrackUri = "u2", currentTrackNumber = 2))
        runCurrent()

        assertThat(f.reads).hasSize(2)
        assertThat(f.state.playback.currentTrackUri).isEqualTo("u2")
        assertThat(f.state.playback.currentTrack?.title).isEqualTo("Next Song")
        assertThat(f.positionNow()).isEqualTo(400)
    }

    @Test
    fun `between the event of a new song and the read, position starts at zero and metadata is gone`() = test { f ->
        started(f, snap(uri = "u1", positionMs = 90_000))
        f.readDelayMs = 500
        f.speaker = SonosResult.Ok(snap(uri = "u2", number = 2, positionMs = 400, title = "Next Song"))

        f.av(AvTransportChange(currentTrackUri = "u2", currentTrackNumber = 2, currentTrackDurationMs = 180_000))
        runCurrent()

        assertThat(f.state.playback.currentTrackUri).isEqualTo("u2")
        assertThat(f.state.playback.currentTrack).isNull()
        assertThat(f.positionNow()).isEqualTo(0)
        assertThat(f.state.playback.currentTrackDurationMs).isEqualTo(180_000)
    }

    @Test
    fun `volume events are applied and survive a full read`() = test { f ->
        started(f, snap())

        f.emit(SonosEvent.Rendering(RenderingChange(volume = 17, muted = true), null))
        runCurrent()
        f.engine.requestRead()
        runCurrent()

        assertThat(f.state.playback.volume).isEqualTo(17)
        assertThat(f.state.playback.muted).isTrue()
    }

    @Test
    fun `a change that follows our own command is marked own, one without a command or after the window is external`() = test { f ->
        started(f, snap(uri = "u1"))
        advanceTimeBy(10_000)

        f.av(AvTransportChange(currentTrackUri = "u2"))
        runCurrent()
        assertThat(f.state.lastChange).isEqualTo(ChangeOrigin.EXTERNAL)

        f.engine.noteCommand(f.scope.currentTime)
        advanceTimeBy(3_900)
        f.av(AvTransportChange(currentTrackUri = "u3"))
        runCurrent()
        assertThat(f.state.lastChange).isEqualTo(ChangeOrigin.OWN)

        advanceTimeBy(4_100)
        f.av(AvTransportChange(currentTrackUri = "u4"))
        runCurrent()
        assertThat(f.state.lastChange).isEqualTo(ChangeOrigin.EXTERNAL)
    }

    @Test
    fun `volume events do not change who made the last change`() = test { f ->
        started(f, snap())
        f.engine.noteCommand(f.scope.currentTime)
        f.av(AvTransportChange(currentTrackUri = "u2"))
        runCurrent()
        advanceTimeBy(20_000)

        f.emit(SonosEvent.Rendering(RenderingChange(volume = 5, muted = null), null))
        runCurrent()

        assertThat(f.state.lastChange).isEqualTo(ChangeOrigin.OWN)
    }

    @Test
    fun `three gaps in a row cause a single read`() = test { f ->
        started(f, snap())

        repeat(3) { f.emit(SonosEvent.NeedFullResync) }
        runCurrent()

        assertThat(f.reads).hasSize(2)
    }

    @Test
    fun `requests made while a read runs are answered by one more read`() = test { f ->
        f.readDelayMs = 300
        started(f, snap())

        repeat(5) {
            f.engine.requestRead()
            advanceTimeBy(50)
        }
        advanceTimeBy(5_000)

        assertThat(f.maxInFlight).isEqualTo(1)
        assertThat(f.reads).containsExactly(0L, 300L).inOrder()
    }

    @Test
    fun `three gaps while a read runs cause exactly one more read`() = test { f ->
        f.readDelayMs = 300
        started(f, snap())

        repeat(3) { f.emit(SonosEvent.NeedFullResync) }
        advanceTimeBy(5_000)

        assertThat(f.reads).hasSize(2)
    }

    @Test
    fun `with events flowing the speaker is read only as a safety net every 30 seconds`() = test { f ->
        started(f, snap())

        advanceTimeBy(60_000)
        runCurrent()

        assertThat(f.reads).containsExactly(0L, 30_000L, 60_000L).inOrder()
    }

    @Test
    fun `when the speaker cannot call back and plays, it is read every 5 seconds`() = test { f ->
        started(f, snap())
        f.emit(SonosEvent.CallbackUnreachable("192.168.1.50"))
        runCurrent()
        assertThat(f.state.callbackReachable).isFalse()

        advanceTimeBy(30_000)
        runCurrent()

        assertThat(f.reads).containsExactly(0L, 5_000L, 10_000L, 15_000L, 20_000L, 25_000L, 30_000L).inOrder()
    }

    @Test
    fun `when the speaker cannot call back but is not playing, it is not read every 5 seconds`() = test { f ->
        started(f, snap(state = TransportState.PAUSED_PLAYBACK))
        f.emit(SonosEvent.CallbackUnreachable("192.168.1.50"))
        runCurrent()

        advanceTimeBy(60_000)
        runCurrent()

        assertThat(f.reads).containsExactly(0L, 30_000L, 60_000L).inOrder()
    }

    @Test
    fun `an event proves the callback works again`() = test { f ->
        started(f, snap())
        f.emit(SonosEvent.CallbackUnreachable("192.168.1.50"))
        runCurrent()

        f.emit(SonosEvent.Rendering(RenderingChange(volume = 3, muted = null), null))
        runCurrent()

        assertThat(f.state.callbackReachable).isTrue()
    }

    @Test
    fun `a play that ends stopped without ever playing is an unsupported format of that song`() = test { f ->
        started(f, snap(state = TransportState.STOPPED, uri = "u1", title = "Opus Song"))

        f.engine.onPlayAccepted()
        advanceTimeBy(1_499)
        assertThat(f.state.lastError).isNull()
        advanceTimeBy(2)
        runCurrent()

        assertThat(f.state.lastError).isEqualTo(PlaybackError(TransportErrorKind.UNSUPPORTED_FORMAT, "u1", "Opus Song"))
    }

    @Test
    fun `a stopped state right after the play is still the old one and is not judged yet`() = test { f ->
        started(f, snap(state = TransportState.STOPPED))
        f.engine.onPlayAccepted()
        advanceTimeBy(300)

        f.av(AvTransportChange(transportState = TransportState.TRANSITIONING))
        f.av(AvTransportChange(transportState = TransportState.STOPPED))
        runCurrent()
        assertThat(f.state.lastError).isNull()

        advanceTimeBy(1_300)
        runCurrent()
        assertThat(f.state.lastError?.kind).isEqualTo(TransportErrorKind.UNSUPPORTED_FORMAT)
    }

    @Test
    fun `a speaker that is still loading at 1_5 seconds gets until 5 seconds`() = test { f ->
        started(f, snap(state = TransportState.STOPPED))
        f.engine.onPlayAccepted()
        f.speaker = SonosResult.Ok(snap(state = TransportState.TRANSITIONING))
        advanceTimeBy(1_600)
        assertThat(f.state.lastError).isNull()

        f.speaker = SonosResult.Ok(snap(state = TransportState.STOPPED))
        advanceTimeBy(3_500)
        runCurrent()

        assertThat(f.state.lastError?.kind).isEqualTo(TransportErrorKind.UNSUPPORTED_FORMAT)
    }

    @Test
    fun `a song that played even for a moment and then stopped is not an unsupported format`() = test { f ->
        started(f, snap(state = TransportState.STOPPED))
        f.engine.onPlayAccepted()
        advanceTimeBy(500)
        f.speaker = SonosResult.Ok(snap(state = TransportState.PLAYING))
        f.av(AvTransportChange(transportState = TransportState.PLAYING))
        runCurrent()
        advanceTimeBy(700)
        f.speaker = SonosResult.Ok(snap(state = TransportState.STOPPED))
        f.av(AvTransportChange(transportState = TransportState.STOPPED))
        runCurrent()
        advanceTimeBy(10_000)

        assertThat(f.state.lastError).isNull()
    }

    @Test
    fun `a stopped speaker with no play requested is not an error`() = test { f ->
        started(f, snap(state = TransportState.STOPPED))

        advanceTimeBy(60_000)
        runCurrent()

        assertThat(f.state.lastError).isNull()
    }

    @Test
    fun `an error reported by the speaker names the song by the third field`() = test { f ->
        started(f, snap(uri = "u1"))

        f.av(AvTransportChange(transportStatus = "ERROR_UNSUPPORTED_FORMAT", transportErrorDescription = "715,0,Bad, Song.flac,192.168.1.20:8080,http://x/y"))
        runCurrent()

        // The speaker may already be on the next song when it reports the failed one: only the label is trustworthy.
        assertThat(f.state.lastError).isEqualTo(PlaybackError(TransportErrorKind.UNSUPPORTED_FORMAT, null, "Bad, Song.flac"))
    }

    @Test
    fun `an error goes away when the speaker plays again, even if the event does not say it is healthy`() = test { f ->
        started(f, snap(uri = "u1"))
        f.speaker = SonosResult.Ok(snap(state = TransportState.STOPPED))
        f.av(AvTransportChange(transportState = TransportState.STOPPED, transportStatus = "ERROR_CORRUPT_FILE", transportErrorDescription = "1,2,Song,h:1,u"))
        runCurrent()
        assertThat(f.state.lastError).isNotNull()

        f.speaker = SonosResult.Ok(snap(state = TransportState.PLAYING))
        f.av(AvTransportChange(transportState = TransportState.PLAYING))
        runCurrent()

        assertThat(f.state.lastError).isNull()
    }

    @Test
    fun `an error that reports itself healthy clears without playing`() = test { f ->
        started(f, snap(uri = "u1"))
        f.speaker = SonosResult.Ok(snap(state = TransportState.STOPPED))
        f.av(AvTransportChange(transportState = TransportState.STOPPED, transportStatus = "ERROR_CORRUPT_FILE", transportErrorDescription = "1,2,Song,h:1,u"))
        runCurrent()
        assertThat(f.state.lastError).isNotNull()

        f.av(AvTransportChange(transportStatus = "OK"))
        runCurrent()

        assertThat(f.state.lastError).isNull()
    }

    @Test
    fun `the same error again, after playing in between, is reported again`() = test { f ->
        started(f, snap(uri = "u1"))
        val error = AvTransportChange(transportState = TransportState.STOPPED, transportStatus = "ERROR_CORRUPT_FILE", transportErrorDescription = "1,2,Song,h:1,u")
        f.speaker = SonosResult.Ok(snap(state = TransportState.STOPPED))
        f.av(error)
        runCurrent()
        f.speaker = SonosResult.Ok(snap(state = TransportState.PLAYING))
        f.av(AvTransportChange(transportState = TransportState.PLAYING))
        runCurrent()
        assertThat(f.state.lastError).isNull()

        f.speaker = SonosResult.Ok(snap(state = TransportState.STOPPED))
        f.av(error)
        runCurrent()

        assertThat(f.state.lastError?.trackLabel).isEqualTo("Song")
    }

    @Test
    fun `the verdict of a play never replaces an error the speaker reported`() = test { f ->
        started(f, snap(state = TransportState.STOPPED))
        f.engine.onPlayAccepted()
        f.av(AvTransportChange(transportState = TransportState.STOPPED, transportStatus = "ERROR_CORRUPT_FILE", transportErrorDescription = "1,2,Song,h:1,u"))
        runCurrent()

        advanceTimeBy(2_000)
        runCurrent()

        assertThat(f.state.lastError?.kind).isEqualTo(TransportErrorKind.CORRUPT_FILE)
    }

    @Test
    fun `the end of a normal queue, as the speaker reports it, is the end of the queue and not an error`() = test { f ->
        started(f, snap(number = 3, total = 3, positionMs = 190_000, durationMs = 200_000))
        advanceTimeBy(8_000)
        // At the end the speaker stops with the first song selected again.
        f.speaker = SonosResult.Ok(snap(state = TransportState.STOPPED, number = 1, total = 3, positionMs = 0))

        f.av(AvTransportChange(transportState = TransportState.STOPPED, currentTrackNumber = 1, currentTrackUri = "u1"))
        runCurrent()

        assertThat(f.state.queueEnded).isTrue()
        assertThat(f.state.lastError).isNull()
        assertThat(f.state.lastChange).isEqualTo(ChangeOrigin.EXTERNAL)
    }

    @Test
    fun `with a silent callback the end of the queue is found by polling`() = test { f ->
        started(f, snap(number = 3, total = 3, positionMs = 195_000, durationMs = 200_000))
        f.emit(SonosEvent.CallbackUnreachable("192.168.1.50"))
        runCurrent()
        advanceTimeBy(4_000)
        f.speaker = SonosResult.Ok(snap(state = TransportState.STOPPED, number = 1, total = 3, positionMs = 0))

        advanceTimeBy(1_001)
        runCurrent()

        assertThat(f.state.queueEnded).isTrue()
        assertThat(f.state.lastChange).isEqualTo(ChangeOrigin.EXTERNAL)
    }

    @Test
    fun `a change found only by a read is marked with who caused it`() = test { f ->
        started(f, snap(uri = "u1"))
        f.emit(SonosEvent.CallbackUnreachable("192.168.1.50"))
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        f.speaker = SonosResult.Ok(snap(uri = "u2", number = 2))
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(f.state.lastChange).isEqualTo(ChangeOrigin.EXTERNAL)

        f.speaker = SonosResult.Ok(snap(uri = "u3", number = 3))
        advanceTimeBy(4_000)
        f.engine.noteCommand(f.scope.currentTime)
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(f.state.lastChange).isEqualTo(ChangeOrigin.OWN)
    }

    @Test
    fun `a song with no duration cannot be said to have ended`() = test { f ->
        started(f, snap(number = 3, total = 3, positionMs = 0, durationMs = 0))
        f.speaker = SonosResult.Ok(snap(state = TransportState.STOPPED, number = 1, total = 3, positionMs = 0))

        f.av(AvTransportChange(transportState = TransportState.STOPPED, currentTrackNumber = 1))
        runCurrent()

        assertThat(f.state.queueEnded).isFalse()
    }

    @Test
    fun `stopping is not the end of the queue when it is not the end of a normal queue`() = test { _ ->
        val cases = listOf(
            "repeating all" to snap(number = 3, total = 3, positionMs = 199_000, mode = PlayMode.REPEAT_ALL),
            "repeating one" to snap(number = 3, total = 3, positionMs = 199_000, mode = PlayMode.REPEAT_ONE),
            "shuffling" to snap(number = 3, total = 3, positionMs = 199_000, mode = PlayMode.SHUFFLE_NOREPEAT),
            "mode unknown" to snap(number = 3, total = 3, positionMs = 199_000, mode = null),
            "not the last song" to snap(number = 2, total = 3, positionMs = 199_000),
            "queue length unknown" to snap(number = 3, total = null, positionMs = 199_000),
            "far from the end" to snap(number = 3, total = 3, positionMs = 20_000),
        )
        for ((name, playing) in cases) {
            val g = Fixture(this)
            started(g, playing)
            g.speaker = SonosResult.Ok(playing.copy(playback = playing.playback.copy(transportState = TransportState.STOPPED)))

            g.av(AvTransportChange(transportState = TransportState.STOPPED))
            runCurrent()

            assertWithMessage(name).that(g.state.queueEnded).isFalse()
            assertWithMessage(name).that(g.state.playback.transportState).isEqualTo(TransportState.STOPPED)
        }
    }

    @Test
    fun `a stop that reports an error is not the end of the queue`() = test { f ->
        started(f, snap(number = 3, total = 3, positionMs = 199_000))
        f.speaker = SonosResult.Ok(snap(state = TransportState.STOPPED, number = 1, total = 3, positionMs = 0))

        f.av(AvTransportChange(transportState = TransportState.STOPPED, currentTrackNumber = 1, transportStatus = "ERROR_CORRUPT_FILE", transportErrorDescription = "1,2,S,h:1,u"))
        runCurrent()

        assertThat(f.state.queueEnded).isFalse()
        assertThat(f.state.lastError).isNotNull()
    }

    @Test
    fun `a stop we sent at the very end is not the end of the queue`() = test { f ->
        started(f, snap(number = 3, total = 3, positionMs = 199_000))
        f.engine.noteCommand(f.scope.currentTime)
        f.speaker = SonosResult.Ok(snap(state = TransportState.STOPPED, number = 3, total = 3, positionMs = 199_000))

        f.av(AvTransportChange(transportState = TransportState.STOPPED))
        runCurrent()

        assertThat(f.state.queueEnded).isFalse()
    }

    @Test
    fun `playing again clears the end of the queue`() = test { f ->
        started(f, snap(number = 3, total = 3, positionMs = 199_000))
        f.speaker = SonosResult.Ok(snap(state = TransportState.STOPPED, number = 3, total = 3, positionMs = 199_000))
        f.av(AvTransportChange(transportState = TransportState.STOPPED))
        runCurrent()
        assertThat(f.state.queueEnded).isTrue()

        f.speaker = SonosResult.Ok(snap(state = TransportState.PLAYING, number = 1, total = 3, positionMs = 0))
        f.av(AvTransportChange(transportState = TransportState.PLAYING, currentTrackNumber = 1))
        runCurrent()

        assertThat(f.state.queueEnded).isFalse()
    }

    @Test
    fun `a read that was overtaken by an event is dropped and read again`() = test { f ->
        f.readDelayMs = 100
        started(f, snap(uri = "u1"))
        advanceTimeBy(50)
        f.speaker = SonosResult.Ok(snap(uri = "u2", number = 2, title = "Newer"))
        f.av(AvTransportChange(currentTrackUri = "u2", currentTrackNumber = 2))
        runCurrent()

        advanceTimeBy(60) // the overtaken read answers now, 10 ms ago: it must not bring u1 back
        assertThat(f.state.playback.currentTrackUri).isEqualTo("u2")

        advanceTimeBy(500)
        runCurrent()

        assertThat(f.state.playback.currentTrackUri).isEqualTo("u2")
        assertThat(f.state.playback.currentTrack?.title).isEqualTo("Newer")
        assertThat(f.reads).hasSize(2)
    }

    @Test
    fun `an empty speaker is a quiet state and not a failure`() = test { f ->
        started(f, snap(state = TransportState.NO_MEDIA_PRESENT, uri = null, number = 0, total = 0, positionMs = null, durationMs = null, title = null))

        assertThat(f.state.playback.currentTrackUri).isNull()
        assertThat(f.state.readFailure).isNull()
        assertThat(f.positionNow()).isEqualTo(0)

        advanceTimeBy(40_000)
        assertThat(f.positionNow()).isEqualTo(0)
    }

    @Test
    fun `a failed read is reported and keeps the last known state, and the next good read clears it`() = test { f ->
        started(f, snap(uri = "u1"))
        f.speaker = SonosResult.Err(SonosFailure.Timeout)

        f.engine.requestRead()
        runCurrent()

        assertThat(f.state.readFailure).isEqualTo(SonosFailure.Timeout)
        assertThat(f.state.playback.currentTrackUri).isEqualTo("u1")

        f.speaker = SonosResult.Ok(snap(uri = "u1"))
        f.engine.requestRead()
        runCurrent()
        assertThat(f.state.readFailure).isNull()
    }

    @Test
    fun `a failing speaker is read once more soon, then only by the safety poll`() = test { f ->
        f.speaker = SonosResult.Err(SonosFailure.Timeout)
        f.engine.start()
        runCurrent()

        advanceTimeBy(65_000)
        runCurrent()

        assertThat(f.reads).containsExactly(0L, 2_000L, 35_000L, 65_000L).inOrder()
    }

    @Test
    fun `stopping the engine ends reading and listening`() = test { f ->
        started(f, snap())
        f.emit(SonosEvent.CallbackUnreachable("192.168.1.50"))
        runCurrent()

        f.engine.stop()
        advanceTimeBy(120_000)
        f.emit(SonosEvent.Rendering(RenderingChange(volume = 99, muted = null), null))
        runCurrent()

        assertThat(f.reads).hasSize(1)
        assertThat(f.state.playback.volume).isNull()
    }

    @Test
    fun `a volume event during a read does not throw the read away`() = test { f ->
        f.readDelayMs = 100
        started(f, snap(uri = "u1"))
        advanceTimeBy(50)

        f.emit(SonosEvent.Rendering(RenderingChange(volume = 9, muted = null), null))
        runCurrent()
        advanceTimeBy(500)

        assertThat(f.reads).hasSize(1)
        assertThat(f.state.playback.currentTrackUri).isEqualTo("u1")
        assertThat(f.state.playback.volume).isEqualTo(9)
    }

    @Test
    fun `a second play replaces the watch of the first and keeps its own full window`() = test { f ->
        started(f, snap(state = TransportState.STOPPED))
        f.engine.onPlayAccepted()
        advanceTimeBy(4_000)
        f.engine.onPlayAccepted()
        f.speaker = SonosResult.Ok(snap(state = TransportState.TRANSITIONING))
        advanceTimeBy(4_500)
        f.speaker = SonosResult.Ok(snap(state = TransportState.STOPPED))

        advanceTimeBy(600)
        runCurrent()

        assertThat(f.state.lastError?.kind).isEqualTo(TransportErrorKind.UNSUPPORTED_FORMAT)
    }

    @Test
    fun `stopping the engine cancels what a play was waiting for`() = test { f ->
        started(f, snap(state = TransportState.STOPPED))
        f.engine.onPlayAccepted()
        f.engine.stop()
        f.engine.start()
        runCurrent()

        advanceTimeBy(10_000)
        runCurrent()

        assertThat(f.reads).containsExactly(0L, 0L)
    }

    @Test
    fun `a stop given after the play ends the wait for the song to start`() = test { f ->
        started(f, snap(state = TransportState.STOPPED))
        f.engine.onPlayAccepted()
        advanceTimeBy(500)
        f.engine.noteCommand(f.scope.currentTime)

        advanceTimeBy(6_000)
        runCurrent()

        assertThat(f.state.lastError).isNull()
    }

    @Test
    fun `a read that began before the play does not show what the play did`() = test { f ->
        f.readDelayMs = 2_500
        f.speaker = SonosResult.Ok(snap(state = TransportState.STOPPED))
        f.engine.start()
        runCurrent()
        advanceTimeBy(500)
        f.engine.onPlayAccepted()
        f.speaker = SonosResult.Ok(snap(state = TransportState.PLAYING))

        advanceTimeBy(8_000)
        runCurrent()

        assertThat(f.state.lastError).isNull()
        assertThat(f.state.playback.transportState).isEqualTo(TransportState.PLAYING)
    }

    @Test
    fun `the same song twice in a row restarts the position when the number changes`() = test { f ->
        started(f, snap(uri = "u1", number = 1, positionMs = 90_000))
        advanceTimeBy(1_000)
        f.speaker = SonosResult.Err(SonosFailure.Timeout)

        f.av(AvTransportChange(currentTrackNumber = 2))
        runCurrent()

        assertThat(f.positionNow()).isEqualTo(0)
    }

    @Test
    fun `a song that reaches its end without any event is read again soon`() = test { f ->
        started(f, snap(positionMs = 10_000, durationMs = 20_000, mode = PlayMode.REPEAT_ONE))
        f.speaker = SonosResult.Ok(snap(positionMs = 500, durationMs = 20_000, mode = PlayMode.REPEAT_ONE))

        advanceTimeBy(12_000)
        runCurrent()

        assertThat(f.reads).containsExactly(0L, 10_000L).inOrder()
        assertThat(f.positionNow()).isEqualTo(2_500)
    }

    @Test
    fun `a duration that comes in a later event caps the position`() = test { f ->
        started(f, snap(uri = "u1"))
        f.speaker = SonosResult.Err(SonosFailure.Timeout)
        f.av(AvTransportChange(currentTrackUri = "u2"))
        f.av(AvTransportChange(currentTrackDurationMs = 30_000))
        runCurrent()

        advanceTimeBy(20_000)
        assertThat(f.positionNow()).isEqualTo(20_000)
        advanceTimeBy(20_000)
        assertThat(f.positionNow()).isEqualTo(30_000)
    }

    @Test
    fun `a song with an unknown duration keeps counting`() = test { f ->
        started(f, snap(uri = "u1", positionMs = 0, durationMs = null))

        advanceTimeBy(25_000)

        assertThat(f.positionNow()).isEqualTo(25_000)
    }

    @Test
    fun `a read without position keeps the counted one for the same song and starts at zero for another`() = test { f ->
        started(f, snap(uri = "u1", positionMs = 10_000))
        advanceTimeBy(5_000)

        f.speaker = SonosResult.Ok(snap(uri = "u1", positionMs = null))
        f.engine.requestRead()
        runCurrent()
        assertThat(f.positionNow()).isEqualTo(15_000)

        f.speaker = SonosResult.Ok(snap(uri = "u2", number = 2, positionMs = null))
        f.engine.requestRead()
        runCurrent()
        assertThat(f.positionNow()).isEqualTo(0)
    }

    @Test
    fun `the position of a read counts from the middle of the read`() = test { f ->
        f.readDelayMs = 400
        started(f, snap(positionMs = 10_000))
        advanceTimeBy(400)
        runCurrent()

        assertThat(f.positionNow()).isEqualTo(10_200)
    }

    @Test
    fun `a position moved elsewhere is picked up by the safety read`() = test { f ->
        started(f, snap(positionMs = 10_000))
        f.speaker = SonosResult.Ok(snap(positionMs = 100_000))

        advanceTimeBy(30_000)
        runCurrent()

        assertThat(f.positionNow()).isEqualTo(100_000)
    }

    @Test
    fun `the read pace follows whether the callback works and whether it plays`() = test { f ->
        started(f, snap())
        f.emit(SonosEvent.CallbackUnreachable("192.168.1.50"))
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertThat(f.reads).containsExactly(0L, 5_000L, 10_000L).inOrder()

        f.emit(SonosEvent.Rendering(RenderingChange(volume = 1, muted = null), null))
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(f.reads).hasSize(3)

        f.emit(SonosEvent.CallbackUnreachable("192.168.1.50"))
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertThat(f.reads).containsExactly(0L, 5_000L, 10_000L, 35_000L, 40_000L).inOrder()

        f.speaker = SonosResult.Ok(snap(state = TransportState.PAUSED_PLAYBACK))
        f.av(AvTransportChange(transportState = TransportState.PAUSED_PLAYBACK))
        f.emit(SonosEvent.CallbackUnreachable("192.168.1.50"))
        runCurrent()
        val before = f.reads.size
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(f.reads.size - before).isEqualTo(0) // paused: not every 5 s even though the callback is silent
    }

    @Test
    fun `starting twice starts one engine`() = test { f ->
        started(f, snap())
        f.engine.start()
        runCurrent()

        f.emit(SonosEvent.NeedFullResync)
        runCurrent()

        assertThat(f.reads).hasSize(2)
    }

    @Test
    fun `an engine can be stopped and started again`() = test { f ->
        started(f, snap())
        f.engine.stop()

        f.engine.start()
        runCurrent()
        f.emit(SonosEvent.NeedFullResync)
        runCurrent()

        assertThat(f.reads).hasSize(3)
    }
}
