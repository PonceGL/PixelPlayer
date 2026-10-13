package com.theveloper.pixelplay.data.sonos.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TransportStateTest {

    @Test
    fun `known speaker states are recognised`() {
        assertThat(TransportState.fromUpnp("PLAYING")).isEqualTo(TransportState.PLAYING)
        assertThat(TransportState.fromUpnp("PAUSED_PLAYBACK")).isEqualTo(TransportState.PAUSED_PLAYBACK)
        assertThat(TransportState.fromUpnp("STOPPED")).isEqualTo(TransportState.STOPPED)
        assertThat(TransportState.fromUpnp("TRANSITIONING")).isEqualTo(TransportState.TRANSITIONING)
        assertThat(TransportState.fromUpnp("NO_MEDIA_PRESENT")).isEqualTo(TransportState.NO_MEDIA_PRESENT)
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertThat(TransportState.fromUpnp(" PLAYING\n")).isEqualTo(TransportState.PLAYING)
    }

    @Test
    fun `missing or unexpected values are UNKNOWN`() {
        assertThat(TransportState.fromUpnp(null)).isEqualTo(TransportState.UNKNOWN)
        assertThat(TransportState.fromUpnp("")).isEqualTo(TransportState.UNKNOWN)
        assertThat(TransportState.fromUpnp("PAUSED_RECORDING")).isEqualTo(TransportState.UNKNOWN)
    }
}
