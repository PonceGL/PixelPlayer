package com.theveloper.pixelplay.data.sonos

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.model.TrackMetadata
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.isLanHost
import com.theveloper.pixelplay.data.sonos.net.safeName
import com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind
import com.theveloper.pixelplay.data.sonos.protocol.TransportErrorKind
import com.theveloper.pixelplay.data.sonos.session.PlaybackError
import com.theveloper.pixelplay.data.sonos.session.SonosPlaybackState
import org.junit.jupiter.api.Test

class SensitiveTextTest {

    private val secretUrl = "http://192.168.1.20:8080/sonos/song/42?v=1&auth=SECRET-TOKEN"

    @Test
    fun `printing a track never shows its addresses`() {
        val track = TrackMetadata("Song", "Artist", "Album", "$secretUrl&art", 1_000, "audio/flac", secretUrl)

        val text = track.toString()

        assertThat(text).doesNotContain("SECRET-TOKEN")
        assertThat(text).doesNotContain("192.168.1.20")
    }

    @Test
    fun `printing a playback error never shows the address of the song`() {
        val text = PlaybackError(TransportErrorKind.UNSUPPORTED_FORMAT, secretUrl, "Song").toString()

        assertThat(text).doesNotContain("SECRET-TOKEN")
        assertThat(text).contains("UNSUPPORTED_FORMAT")
    }

    @Test
    fun `printing the playback state never shows the address of the song`() {
        val state = SonosPlaybackState(currentTrackUri = secretUrl, nextTrackUri = secretUrl)

        assertThat(state.toString()).doesNotContain("SECRET-TOKEN")
    }

    @Test
    fun `a failure is logged by its kind and never by what the network said`() {
        val failures = listOf(
            SonosFailure.Network("failed to connect to /192.168.1.50 (port 1400)"),
            SonosFailure.Upnp("Play", UpnpErrorKind.NotCoordinator),
            SonosFailure.Timeout,
        )

        for (failure in failures) {
            assertThat(failure.safeName()).doesNotContain("192.168")
            assertThat(failure.safeName()).doesNotContain("1400")
        }
        assertThat(SonosFailure.Network("x").safeName()).isEqualTo("Network")
        assertThat(SonosFailure.Timeout.safeName()).isEqualTo("Timeout")
        assertThat(SonosFailure.Upnp("Play", UpnpErrorKind.NotCoordinator).safeName()).isEqualTo("Upnp")
    }

    @Test
    fun `only addresses of a home network are accepted as a speaker`() {
        for (host in listOf("192.168.1.50", "10.0.0.5", "172.16.0.1", "172.31.255.254", "169.254.3.4")) {
            assertThat(isLanHost(host)).isTrue()
        }
        for (host in listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.1.1", "11.0.0.1", "127.0.0.1", "0.0.0.0")) {
            assertThat(isLanHost(host)).isFalse()
        }
        for (host in listOf("", " ", "speaker.example.com", "192.168.1", "192.168.1.256", "192.168.1.1.1", "1.192.168.1.1", "fe80::1", "192.168.1.5:1400", "http://192.168.1.5", "192.168.1.5/x")) {
            assertThat(isLanHost(host)).isFalse()
        }
    }
}
