package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.model.SonosDevice
import com.theveloper.pixelplay.data.sonos.model.SonosUdn
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import org.junit.jupiter.api.Test

class SonosPlayRoutingTest {

    private val device = SonosDevice(id = SonosUdn("RINCON_1"), name = "Move", host = "192.168.1.30", roomCount = 1)

    @Test
    fun `a new play goes to the speaker while a session is on`() {
        assertThat(playTargetOf(SonosConnection.Active(device), castActive = false)).isEqualTo(PlayTarget.Speaker(device))
    }

    @Test
    fun `a new play during connecting goes to the speaker too, it replaces the attempt`() {
        assertThat(playTargetOf(SonosConnection.Connecting(device), castActive = false)).isEqualTo(PlayTarget.Speaker(device))
    }

    @Test
    fun `without a session, or after a failed one, the phone plays`() {
        assertThat(playTargetOf(SonosConnection.Idle, castActive = false)).isEqualTo(PlayTarget.Phone)
        assertThat(playTargetOf(SonosConnection.Failed(device, SonosFailure.Timeout), castActive = false)).isEqualTo(PlayTarget.Phone)
    }

    @Test
    fun `cast keeps what it had`() {
        assertThat(playTargetOf(SonosConnection.Idle, castActive = true)).isEqualTo(PlayTarget.Cast)
    }
}
