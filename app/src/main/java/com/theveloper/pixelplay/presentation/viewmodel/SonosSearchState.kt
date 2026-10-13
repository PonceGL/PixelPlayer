package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.sonos.model.SonosDevice

/** Everything the device sheet needs to know about the speaker search; one state, so no part of the screen can disagree. */
internal sealed interface SonosSearchState {
    data object Idle : SonosSearchState
    data class Searching(val devices: List<SonosDevice>) : SonosSearchState

    /** The search ended; an empty list means no speaker answered. */
    data class Finished(val devices: List<SonosDevice>) : SonosSearchState
    data class Blocked(val reason: BlockReason) : SonosSearchState
}

internal enum class BlockReason {
    NO_WIFI,
    LOCAL_NETWORK_DENIED,

    /** The permission was just granted and the system may take a while to apply it. */
    PERMISSION_PENDING,
    UNAVAILABLE,
}
