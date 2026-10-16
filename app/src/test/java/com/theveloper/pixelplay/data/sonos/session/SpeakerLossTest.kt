package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind
import org.junit.jupiter.api.Test

class SpeakerLossTest {

    @Test
    fun `a speaker that is off, out of range or on another network is lost`() {
        val lost = listOf(
            SonosFailure.Timeout,
            SonosFailure.ConnectionRefused,
            SonosFailure.NetworkUnreachable,
            SonosFailure.NoWifi,
            SonosFailure.Network("reset"),
        )

        lost.forEach { assertThat(it.meansSpeakerLost()).isTrue() }
    }

    @Test
    fun `a speaker that answered with a refusal is not lost`() {
        val answered = listOf(
            SonosFailure.Upnp("Next", UpnpErrorKind.NoMoreTracks),
            SonosFailure.MalformedResponse,
            SonosFailure.NotOnSpeaker,
        )

        answered.forEach { assertThat(it.meansSpeakerLost()).isFalse() }
    }

    @Test
    fun `problems of the phone or of this app are not a lost speaker`() {
        val ours = listOf(
            SonosFailure.LocalNetworkDenied,
            SonosFailure.CleartextBlocked,
            SonosFailure.ServerUnavailable,
            SonosFailure.Superseded,
        )

        ours.forEach { assertThat(it.meansSpeakerLost()).isFalse() }
    }
}
