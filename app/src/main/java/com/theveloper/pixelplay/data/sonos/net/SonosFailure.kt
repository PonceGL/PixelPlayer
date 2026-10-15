package com.theveloper.pixelplay.data.sonos.net

import com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind

/** Expected ways a conversation with a speaker can fail; each one has a distinct way out for the user. */
internal sealed interface SonosFailure {
    data object NoWifi : SonosFailure

    /** The OS refused local traffic: the local network permission is missing or not yet in effect. */
    data object LocalNetworkDenied : SonosFailure
    data object Timeout : SonosFailure
    data object ConnectionRefused : SonosFailure
    data object NetworkUnreachable : SonosFailure

    /** The app's network policy forbids plain HTTP: a configuration bug, not a user problem. */
    data object CleartextBlocked : SonosFailure
    data class Upnp(val action: String, val kind: UpnpErrorKind) : SonosFailure
    /** A newer load of the same speaker took over before this one finished. */
    data object Superseded : SonosFailure
    data object MalformedResponse : SonosFailure
    data class Network(val detail: String) : SonosFailure
}

internal sealed interface SonosResult<out T> {
    data class Ok<T>(val value: T) : SonosResult<T>
    data class Err(val failure: SonosFailure) : SonosResult<Nothing>
}
