package com.theveloper.pixelplay.data.sonos.protocol

internal sealed interface UpnpErrorKind {
    /** Previous/Next at the end of the queue in NORMAL mode: not a failure. */
    data object NoMoreTracks : UpnpErrorKind
    data object NotCoordinator : UpnpErrorKind
    data object InvalidArguments : UpnpErrorKind
    data object UnsupportedMedia : UpnpErrorKind
    data object ResourceNotFound : UpnpErrorKind
    data class Other(val code: Int?) : UpnpErrorKind
}

internal object UpnpErrors {

    private const val ILLEGAL_SEEK_TARGET = 711
    private val NAVIGATION_ACTIONS = setOf("Next", "Previous")

    fun classify(action: String, code: Int?): UpnpErrorKind = when {
        code == ILLEGAL_SEEK_TARGET && action in NAVIGATION_ACTIONS -> UpnpErrorKind.NoMoreTracks
        code == 701 || code == 800 -> UpnpErrorKind.NotCoordinator
        code == 402 -> UpnpErrorKind.InvalidArguments
        code == 704 || code == 714 -> UpnpErrorKind.UnsupportedMedia
        code == 716 -> UpnpErrorKind.ResourceNotFound
        else -> UpnpErrorKind.Other(code)
    }
}
