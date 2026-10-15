package com.theveloper.pixelplay.presentation.components

import androidx.mediarouter.media.MediaRouter
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.theveloper.pixelplay.data.sonos.model.SonosDevice
import com.theveloper.pixelplay.data.sonos.model.SonosUdn
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind
import com.theveloper.pixelplay.data.sonos.session.SonosConnection
import com.theveloper.pixelplay.presentation.viewmodel.BlockReason
import com.theveloper.pixelplay.presentation.viewmodel.SonosSearchState
import org.junit.jupiter.api.Test

class SonosSheetModelTest {

    private val salon = SonosDevice(SonosUdn("RINCON_A"), "Salón", "10.0.0.5", roomCount = 1)

    private fun presentation(
        castRefreshing: Boolean = false,
        sonos: SonosSearchState = SonosSearchState.Idle,
        hasDevices: Boolean = false,
    ) = devicesPresentation(castRefreshing, sonos, hasDevices)

    @Test
    fun `searching with nothing found shows the placeholder under a searching header`() {
        val result = presentation(sonos = SonosSearchState.Searching(emptyList()))

        assertThat(result.isSearching).isTrue()
        assertThat(result.header).isEqualTo(DevicesHeader.SEARCHING)
        assertThat(result.body).isEqualTo(DevicesBody.PLACEHOLDER)
    }

    @Test
    fun `a finished search without devices is an empty state and not an endless search`() {
        val result = presentation(sonos = SonosSearchState.Finished(emptyList()))

        assertThat(result.isSearching).isFalse()
        assertThat(result.header).isEqualTo(DevicesHeader.NO_DEVICES)
        assertThat(result.body).isEqualTo(DevicesBody.EMPTY)
    }

    @Test
    fun `the cast refresh alone also counts as searching`() {
        val result = presentation(castRefreshing = true, sonos = SonosSearchState.Finished(emptyList()))

        assertThat(result.isSearching).isTrue()
        assertThat(result.body).isEqualTo(DevicesBody.PLACEHOLDER)
    }

    @Test
    fun `devices found while still searching keep the progress indicator`() {
        val result = presentation(sonos = SonosSearchState.Searching(listOf(salon)), hasDevices = true)

        assertThat(result.isSearching).isTrue()
        assertThat(result.header).isEqualTo(DevicesHeader.TAP_TO_CONNECT)
        assertThat(result.body).isEqualTo(DevicesBody.LIST)
    }

    @Test
    fun `a blocked search keeps the list visible so the explanation can be shown`() {
        val result = presentation(sonos = SonosSearchState.Blocked(BlockReason.LOCAL_NETWORK_DENIED))

        assertThat(result.blocked).isEqualTo(BlockReason.LOCAL_NETWORK_DENIED)
        assertThat(result.body).isEqualTo(DevicesBody.LIST)
        assertThat(result.header).isEqualTo(DevicesHeader.NO_DEVICES)
        assertThat(result.isSearching).isFalse()
    }

    @Test
    fun `a permission that is being applied shows progress`() {
        val result = presentation(sonos = SonosSearchState.Blocked(BlockReason.PERMISSION_PENDING))

        assertThat(result.isSearching).isTrue()
        assertThat(result.header).isEqualTo(DevicesHeader.SEARCHING)
    }

    @Test
    fun `idle with nothing is the empty state`() {
        val result = presentation()

        assertThat(result.body).isEqualTo(DevicesBody.EMPTY)
        assertThat(result.header).isEqualTo(DevicesHeader.NO_DEVICES)
    }

    @Test
    fun `the header never contradicts the body for any combination of inputs`() {
        val sonosStates = listOf(
            SonosSearchState.Idle,
            SonosSearchState.Searching(emptyList()),
            SonosSearchState.Searching(listOf(salon)),
            SonosSearchState.Finished(emptyList()),
            SonosSearchState.Finished(listOf(salon)),
        ) + BlockReason.entries.map { SonosSearchState.Blocked(it) }

        for (castRefreshing in listOf(false, true)) for (hasDevices in listOf(false, true)) for (sonos in sonosStates) {
            val result = presentation(castRefreshing, sonos, hasDevices)
            val label = "castRefreshing=$castRefreshing hasDevices=$hasDevices sonos=$sonos"

            if (result.body == DevicesBody.PLACEHOLDER) assertWithMessage(label).that(result.header).isEqualTo(DevicesHeader.SEARCHING)
            if (result.body == DevicesBody.EMPTY) assertWithMessage(label).that(result.header).isEqualTo(DevicesHeader.NO_DEVICES)
            if (hasDevices) assertWithMessage(label).that(result.header).isEqualTo(DevicesHeader.TAP_TO_CONNECT)
            if (result.header == DevicesHeader.SEARCHING) assertWithMessage(label).that(result.isSearching).isTrue()
        }
    }

    @Test
    fun `a speaker becomes a device row with a stable id and no connection`() {
        val ui = SonosDevice(SonosUdn("RINCON_A"), "Cocina + Salón", "10.0.0.5", roomCount = 2).toCastDeviceUi()

        assertThat(ui.id).isEqualTo("sonos_RINCON_A")
        assertThat(ui.name).isEqualTo("Cocina + Salón")
        assertThat(ui.isSonos).isTrue()
        assertThat(ui.roomCount).isEqualTo(2)
        assertThat(ui.isSelected).isFalse()
        assertThat(ui.isBluetooth).isFalse()
        assertThat(ui.deviceType).isEqualTo(MediaRouter.RouteInfo.DEVICE_TYPE_REMOTE_SPEAKER)
        assertThat(ui.connectionState).isEqualTo(MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED)
    }

    @Test
    fun `coming back with no wifi searches again without any grace period`() {
        assertThat(resumeSearch(BlockReason.NO_WIFI, localNetworkJustGranted = false))
            .isEqualTo(ResumeSearch(permissionJustGranted = false))
    }

    @Test
    fun `coming back with a denied permission that did not change searches without retries`() {
        assertThat(resumeSearch(BlockReason.LOCAL_NETWORK_DENIED, localNetworkJustGranted = false))
            .isEqualTo(ResumeSearch(permissionJustGranted = false))
    }

    @Test
    fun `coming back after the permission was granted allows the grace period`() {
        assertThat(resumeSearch(BlockReason.LOCAL_NETWORK_DENIED, localNetworkJustGranted = true))
            .isEqualTo(ResumeSearch(permissionJustGranted = true))
    }

    @Test
    fun `other states and no state are left alone on resume`() {
        assertThat(resumeSearch(BlockReason.UNAVAILABLE, localNetworkJustGranted = false)).isNull()
        assertThat(resumeSearch(BlockReason.PERMISSION_PENDING, localNetworkJustGranted = true)).isNull()
        assertThat(resumeSearch(null, localNetworkJustGranted = true)).isNull()
    }

    private val cocina = SonosDevice(SonosUdn("RINCON_B"), "Cocina", "10.0.0.6", roomCount = 2)

    @Test
    fun `a speaker nobody is connecting to shows as available`() {
        val row = salon.toCastDeviceUi(SonosConnection.Idle)

        assertThat(row.connectionState).isEqualTo(MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED)
        assertThat(row.isSelected).isFalse()
        assertThat(row.errorKind).isNull()
    }

    @Test
    fun `the speaker being connected shows connecting and selected, the others stay available`() {
        val connection = SonosConnection.Connecting(salon)

        assertThat(salon.toCastDeviceUi(connection).connectionState).isEqualTo(MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING)
        assertThat(salon.toCastDeviceUi(connection).isSelected).isTrue()
        assertThat(cocina.toCastDeviceUi(connection).connectionState).isEqualTo(MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED)
        assertThat(cocina.toCastDeviceUi(connection).isSelected).isFalse()
    }

    @Test
    fun `the connected speaker shows connected and selected`() {
        val connection = SonosConnection.Active(cocina)

        assertThat(cocina.toCastDeviceUi(connection).connectionState).isEqualTo(MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED)
        assertThat(cocina.toCastDeviceUi(connection).isSelected).isTrue()
        assertThat(salon.toCastDeviceUi(connection).isSelected).isFalse()
    }

    @Test
    fun `a speaker that failed shows why, is not selected and can be tried again`() {
        val connection = SonosConnection.Failed(salon, SonosFailure.Timeout)

        val row = salon.toCastDeviceUi(connection)

        assertThat(row.errorKind).isEqualTo(SonosErrorKind.UNREACHABLE)
        assertThat(row.isSelected).isFalse()
        assertThat(row.connectionState).isEqualTo(MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED)
        assertThat(cocina.toCastDeviceUi(connection).errorKind).isNull()
    }

    @Test
    fun `the active device is the one connecting or connected, and nothing otherwise`() {
        assertThat(SonosConnection.Idle.activeDevice()).isNull()
        assertThat(SonosConnection.Failed(salon, SonosFailure.Timeout).activeDevice()).isNull()
        assertThat(SonosConnection.Connecting(salon).activeDevice()).isEqualTo(SonosActiveDevice(salon, isConnecting = true))
        assertThat(SonosConnection.Active(cocina).activeDevice()).isEqualTo(SonosActiveDevice(cocina, isConnecting = false))
    }

    @Test
    fun `every failure has a message the user can act on`() {
        val expected = mapOf(
            SonosFailure.NoWifi to SonosErrorKind.NETWORK,
            SonosFailure.LocalNetworkDenied to SonosErrorKind.PERMISSION,
            SonosFailure.NetworkUnreachable to SonosErrorKind.NETWORK,
            SonosFailure.Network("x") to SonosErrorKind.NETWORK,
            SonosFailure.Timeout to SonosErrorKind.UNREACHABLE,
            SonosFailure.ConnectionRefused to SonosErrorKind.UNREACHABLE,
            SonosFailure.ServerUnavailable to SonosErrorKind.SERVER,
            SonosFailure.Upnp("Play", UpnpErrorKind.NotCoordinator) to SonosErrorKind.GROUP,
            SonosFailure.Upnp("AddURIToQueue", UpnpErrorKind.ResourceNotFound) to SonosErrorKind.GENERIC,
            SonosFailure.MalformedResponse to SonosErrorKind.GENERIC,
            SonosFailure.NotOnSpeaker to SonosErrorKind.GENERIC,
            SonosFailure.Superseded to SonosErrorKind.GENERIC,
            SonosFailure.CleartextBlocked to SonosErrorKind.GENERIC,
        )
        for ((failure, kind) in expected) {
            assertWithMessage("$failure").that(sonosErrorKind(failure)).isEqualTo(kind)
        }
    }
}
