package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.net.SonosFailure

/**
 * Whether a failure says the speaker cannot be reached (switched off, out of range, the phone left the network),
 * as opposed to a speaker that answered with a refusal. Nothing the user presses will work until it is back, so the
 * session ends instead of failing on every button.
 */
internal fun SonosFailure.meansSpeakerLost(): Boolean = when (this) {
    SonosFailure.Timeout,
    SonosFailure.ConnectionRefused,
    SonosFailure.NetworkUnreachable,
    SonosFailure.NoWifi,
    is SonosFailure.Network,
    -> true

    SonosFailure.LocalNetworkDenied,
    SonosFailure.CleartextBlocked,
    is SonosFailure.Upnp,
    SonosFailure.ServerUnavailable,
    SonosFailure.Superseded,
    SonosFailure.NotOnSpeaker,
    SonosFailure.MalformedResponse,
    -> false
}
