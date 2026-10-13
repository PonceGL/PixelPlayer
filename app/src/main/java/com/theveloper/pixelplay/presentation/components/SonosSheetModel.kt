package com.theveloper.pixelplay.presentation.components

import androidx.mediarouter.media.MediaRouter
import com.theveloper.pixelplay.data.sonos.model.SonosDevice
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

internal fun SonosDevice.toCastDeviceUi(): CastDeviceUi = CastDeviceUi(
    id = "sonos_${id.value}",
    name = name,
    deviceType = MediaRouter.RouteInfo.DEVICE_TYPE_REMOTE_SPEAKER,
    playbackType = MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE,
    connectionState = MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED,
    volumeHandling = MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED,
    volume = 0,
    volumeMax = 0,
    isSelected = false,
    isSonos = true,
    roomCount = roomCount,
)
