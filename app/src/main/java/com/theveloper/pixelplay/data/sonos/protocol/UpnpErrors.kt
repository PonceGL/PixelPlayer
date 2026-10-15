package com.theveloper.pixelplay.data.sonos.protocol

internal sealed interface UpnpErrorKind {
    /** Previous/Next at the end of the queue in NORMAL mode: not a failure. */
    data object NoMoreTracks : UpnpErrorKind
    /** Sonos answers 800 to commands sent to a player that is not its group's coordinator. */
    data object NotCoordinator : UpnpErrorKind

    /** The action is not allowed in the current transport state (UPnP 701). */
    data object TransitionNotAvailable : UpnpErrorKind
    /** HTTP 412 on a subscription request: the speaker no longer knows that SID. */
    data object PreconditionFailed : UpnpErrorKind
    data object InvalidArguments : UpnpErrorKind
    data object UnsupportedMedia : UpnpErrorKind
    data object ResourceNotFound : UpnpErrorKind
    data class Other(val code: Int?) : UpnpErrorKind
}

internal object UpnpErrors {

    private const val INVALID_ARGUMENTS = 402
    private const val TRANSITION_NOT_AVAILABLE = 701
    private const val FORMAT_NOT_SUPPORTED = 704
    private const val ILLEGAL_SEEK_TARGET = 711
    private const val ILLEGAL_MIME_TYPE = 714
    private const val RESOURCE_NOT_FOUND = 716
    private const val NOT_COORDINATOR = 800
    private val NAVIGATION_ACTIONS = setOf("Next", "Previous")

    fun classify(action: String, code: Int?): UpnpErrorKind = when {
        code == ILLEGAL_SEEK_TARGET && action in NAVIGATION_ACTIONS -> UpnpErrorKind.NoMoreTracks
        code == NOT_COORDINATOR -> UpnpErrorKind.NotCoordinator
        code == TRANSITION_NOT_AVAILABLE -> UpnpErrorKind.TransitionNotAvailable
        code == INVALID_ARGUMENTS -> UpnpErrorKind.InvalidArguments
        code == FORMAT_NOT_SUPPORTED || code == ILLEGAL_MIME_TYPE -> UpnpErrorKind.UnsupportedMedia
        code == RESOURCE_NOT_FOUND -> UpnpErrorKind.ResourceNotFound
        else -> UpnpErrorKind.Other(code)
    }
}
