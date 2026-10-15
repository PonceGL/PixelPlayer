package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.model.SonosDevice
import com.theveloper.pixelplay.data.sonos.model.SonosUdn
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import org.junit.jupiter.api.Test

class SonosSessionHolderTest {

    private val device = SonosDevice(SonosUdn("RINCON_1"), "Cocina", "192.168.1.50", 1)

    @Test
    fun `it starts without a connection`() {
        assertThat(SonosSessionHolder().state.value).isEqualTo(SonosConnection.Idle)
    }

    @Test
    fun `a failure that was already shown can be cleared`() {
        val holder = SonosSessionHolder()
        holder.set(SonosConnection.Failed(device, SonosFailure.Timeout))

        holder.clearFailure()

        assertThat(holder.state.value).isEqualTo(SonosConnection.Idle)
    }

    @Test
    fun `clearing a failure never ends a connection that is on or starting`() {
        val holder = SonosSessionHolder()
        holder.set(SonosConnection.Active(device))
        holder.clearFailure()
        assertThat(holder.state.value).isEqualTo(SonosConnection.Active(device))

        holder.set(SonosConnection.Connecting(device))
        holder.clearFailure()
        assertThat(holder.state.value).isEqualTo(SonosConnection.Connecting(device))
    }
}
