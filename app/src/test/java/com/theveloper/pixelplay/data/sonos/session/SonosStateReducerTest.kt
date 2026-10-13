package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.SonosFixtures
import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.TrackMetadata
import com.theveloper.pixelplay.data.sonos.model.TransportState
import com.theveloper.pixelplay.data.sonos.protocol.AvTransportChange
import com.theveloper.pixelplay.data.sonos.protocol.RenderingChange
import com.theveloper.pixelplay.data.sonos.protocol.SonosEventParser
import com.theveloper.pixelplay.data.sonos.protocol.TransportError
import com.theveloper.pixelplay.data.sonos.protocol.TransportErrorKind
import org.junit.jupiter.api.Test

class SonosStateReducerTest {

    private fun track(title: String, url: String) = TrackMetadata(
        title = title, artist = null, album = null, albumArtUrl = null,
        durationMs = 180_000L, mimeType = "audio/flac", resourceUrl = url,
    )

    private val playing = SonosPlaybackState(
        transportState = TransportState.PLAYING,
        playMode = PlayMode.REPEAT_ALL,
        currentTrackNumber = 5,
        numberOfTracks = 9,
        currentTrackUri = "http://h/a.flac",
        currentTrackDurationMs = 180_000L,
        currentTrack = track("A", "http://h/a.flac"),
        nextTrackUri = "http://h/b.flac",
        volume = 12,
        muted = false,
    )

    @Test
    fun `absent variables keep the previous values`() {
        val next = SonosStateReducer.reduce(playing, AvTransportChange(transportState = TransportState.PAUSED_PLAYBACK))

        assertThat(next).isEqualTo(playing.copy(transportState = TransportState.PAUSED_PLAYBACK))
    }

    @Test
    fun `the initial event overwrites every transport variable it carries`() {
        val snapshot = SonosEventParser.parseAvTransport(SonosFixtures.notify("AVTransport_0"))!!

        val next = SonosStateReducer.reduce(playing, snapshot)

        assertThat(next.transportState).isEqualTo(TransportState.STOPPED)
        assertThat(next.playMode).isEqualTo(PlayMode.NORMAL)
        assertThat(next.currentTrackNumber).isEqualTo(1)
        assertThat(next.numberOfTracks).isEqualTo(1)
        assertThat(next.currentTrackUri).isEqualTo("http://192.168.1.20:8765/t/testtok2/wma_192.wma")
        assertThat(next.currentTrackDurationMs).isEqualTo(30_000L)
        assertThat(next.currentTrack?.title).isEqualTo("WMA 192 kbps")
        assertThat(next.nextTrackUri).isNull()
        assertThat(next.volume).isEqualTo(12)
    }

    @Test
    fun `the track is identified by its uri even when the index does not move`() {
        val change = AvTransportChange(
            currentTrackNumber = 5,
            currentTrackUri = "http://h/z.flac",
            currentTrack = track("Z", "http://h/z.flac"),
        )

        val next = SonosStateReducer.reduce(playing, change)

        assertThat(next.currentTrackUri).isEqualTo("http://h/z.flac")
        assertThat(next.currentTrack?.title).isEqualTo("Z")
    }

    @Test
    fun `metadata of the previous track is dropped when the uri changes without new metadata`() {
        val next = SonosStateReducer.reduce(playing, AvTransportChange(currentTrackUri = "http://h/z.flac"))

        assertThat(next.currentTrackUri).isEqualTo("http://h/z.flac")
        assertThat(next.currentTrack).isNull()
    }

    @Test
    fun `an empty uri clears the current track`() {
        val next = SonosStateReducer.reduce(playing, AvTransportChange(currentTrackUri = "", numberOfTracks = 0))

        assertThat(next.currentTrackUri).isNull()
        assertThat(next.currentTrack).isNull()
        assertThat(next.currentTrackDurationMs).isNull()
        assertThat(next.numberOfTracks).isEqualTo(0)
    }

    @Test
    fun `a transport error is recorded and survives later events without error fields`() {
        val withError = SonosStateReducer.reduce(
            playing,
            AvTransportChange(
                transportStatus = "ERROR_UNSUPPORTED_FORMAT",
                transportErrorDescription = "8,0,x.aiff,192.168.1.20:8765,h",
            ),
        )

        val later = SonosStateReducer.reduce(withError, AvTransportChange(transportState = TransportState.PLAYING))

        assertThat(later.lastTransportError?.kind).isEqualTo(TransportErrorKind.UNSUPPORTED_FORMAT)
        assertThat(later.lastTransportError?.trackLabel).isEqualTo("x.aiff")
    }

    @Test
    fun `an OK status clears a recorded error`() {
        val withError = playing.copy(
            lastTransportError = TransportError(TransportErrorKind.CORRUPT_FILE, "x"),
        )

        val next = SonosStateReducer.reduce(withError, AvTransportChange(transportStatus = "OK"))

        assertThat(next.lastTransportError).isNull()
    }

    @Test
    fun `rendering changes update only volume and mute`() {
        val next = SonosStateReducer.reduce(playing, RenderingChange(volume = 30))

        assertThat(next).isEqualTo(playing.copy(volume = 30))
        assertThat(SonosStateReducer.reduce(playing, RenderingChange(muted = true)).muted).isTrue()
    }

    @Test
    fun `the next track follows what the speaker reports, including none`() {
        val changed = SonosStateReducer.reduce(playing, AvTransportChange(nextTrackUri = "http://h/c.flac"))
        val none = SonosStateReducer.reduce(playing, AvTransportChange(nextTrackUri = ""))
        val untouched = SonosStateReducer.reduce(playing, AvTransportChange(transportState = TransportState.PLAYING))

        assertThat(changed.nextTrackUri).isEqualTo("http://h/c.flac")
        assertThat(none.nextTrackUri).isNull()
        assertThat(untouched.nextTrackUri).isEqualTo("http://h/b.flac")
    }
}
