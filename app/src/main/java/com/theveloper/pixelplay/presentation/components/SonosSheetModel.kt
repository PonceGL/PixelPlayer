package com.theveloper.pixelplay.presentation.components

import androidx.mediarouter.media.MediaRouter
import com.theveloper.pixelplay.data.sonos.model.SonosDevice
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind
import com.theveloper.pixelplay.data.sonos.session.SonosConnection
import com.theveloper.pixelplay.presentation.viewmodel.BlockReason
import com.theveloper.pixelplay.presentation.viewmodel.SonosSearchState

internal enum class DevicesHeader { SEARCHING, TAP_TO_CONNECT, NO_DEVICES }

internal enum class DevicesBody {
    /** Looking, nothing yet. */
    PLACEHOLDER,

    /** Finished looking and found nothing. */
    EMPTY,

    /** The device list, possibly preceded by an explanation card. */
    LIST,
}

/** What the devices tab shows; header, progress and body all come from here so they cannot disagree. */
internal data class DevicesPresentation(
    val isSearching: Boolean,
    val header: DevicesHeader,
    val body: DevicesBody,
    val blocked: BlockReason?,
)

internal fun devicesPresentation(
    castRefreshing: Boolean,
    sonos: SonosSearchState,
    hasDevices: Boolean,
): DevicesPresentation {
    val blocked = (sonos as? SonosSearchState.Blocked)?.reason
    val isSearching = castRefreshing ||
        sonos is SonosSearchState.Searching ||
        blocked == BlockReason.PERMISSION_PENDING
    val header = when {
        hasDevices -> DevicesHeader.TAP_TO_CONNECT
        isSearching -> DevicesHeader.SEARCHING
        else -> DevicesHeader.NO_DEVICES
    }
    val body = when {
        hasDevices || blocked != null -> DevicesBody.LIST
        isSearching -> DevicesBody.PLACEHOLDER
        else -> DevicesBody.EMPTY
    }
    return DevicesPresentation(isSearching, header, body, blocked)
}

/** The reason a connection failed, grouped by what the user can do about it. */
internal enum class SonosErrorKind { NETWORK, UNREACHABLE, SERVER, GROUP, GENERIC }

internal fun sonosErrorKind(failure: SonosFailure): SonosErrorKind = when (failure) {
    SonosFailure.NoWifi,
    SonosFailure.LocalNetworkDenied,
    SonosFailure.NetworkUnreachable,
    is SonosFailure.Network -> SonosErrorKind.NETWORK
    SonosFailure.Timeout,
    SonosFailure.ConnectionRefused -> SonosErrorKind.UNREACHABLE
    SonosFailure.ServerUnavailable -> SonosErrorKind.SERVER
    is SonosFailure.Upnp -> if (failure.kind == UpnpErrorKind.NotCoordinator) SonosErrorKind.GROUP else SonosErrorKind.GENERIC
    SonosFailure.CleartextBlocked,
    SonosFailure.MalformedResponse,
    SonosFailure.NotOnSpeaker,
    SonosFailure.Superseded -> SonosErrorKind.GENERIC
}

/** The speaker a session is with or is being made with, for the header of the controls. */
internal data class SonosActiveDevice(val device: SonosDevice, val isConnecting: Boolean)

internal fun SonosConnection.activeDevice(): SonosActiveDevice? = when (this) {
    is SonosConnection.Connecting -> SonosActiveDevice(device, isConnecting = true)
    is SonosConnection.Active -> SonosActiveDevice(device, isConnecting = false)
    SonosConnection.Idle, is SonosConnection.Failed -> null
}

internal fun SonosDevice.toCastDeviceUi(connection: SonosConnection = SonosConnection.Idle): CastDeviceUi {
    val mine = when (connection) {
        is SonosConnection.Connecting -> connection.device.id == id
        is SonosConnection.Active -> connection.device.id == id
        SonosConnection.Idle, is SonosConnection.Failed -> false
    }
    val state = when {
        mine && connection is SonosConnection.Connecting -> MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING
        mine -> MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED
        else -> MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED
    }
    val failure = (connection as? SonosConnection.Failed)?.takeIf { it.device.id == id }?.reason
    return CastDeviceUi(
        id = "sonos_${this.id.value}",
        name = name,
        deviceType = MediaRouter.RouteInfo.DEVICE_TYPE_REMOTE_SPEAKER,
        playbackType = MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE,
        connectionState = state,
        volumeHandling = MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED,
        volume = 0,
        volumeMax = 0,
        isSelected = mine,
        isSonos = true,
        roomCount = roomCount,
        errorKind = failure?.let(::sonosErrorKind),
    )
}

internal data class ResumeSearch(val permissionJustGranted: Boolean)

/**
 * What to do when the user comes back to the sheet from the system settings: look again only if the
 * search was blocked by something they may just have fixed. The permission grace period is for a
 * permission that actually changed, so a visit that fixed nothing does not show a long fake wait.
 */
internal fun resumeSearch(blocked: BlockReason?, localNetworkJustGranted: Boolean): ResumeSearch? = when (blocked) {
    BlockReason.NO_WIFI -> ResumeSearch(permissionJustGranted = false)
    BlockReason.LOCAL_NETWORK_DENIED -> ResumeSearch(permissionJustGranted = localNetworkJustGranted)
    else -> null
}
