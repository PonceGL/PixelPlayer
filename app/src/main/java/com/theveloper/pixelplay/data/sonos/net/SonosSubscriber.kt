package com.theveloper.pixelplay.data.sonos.net

import com.theveloper.pixelplay.data.sonos.protocol.SonosService

/** A live UPnP event subscription: [sid] identifies it and it lapses after [timeoutSeconds] unless renewed. */
internal data class Subscription(val sid: String, val timeoutSeconds: Int)

internal interface SonosSubscriber {

    suspend fun subscribe(
        host: String,
        service: SonosService,
        callbackUrl: String,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    ): SonosResult<Subscription>

    /** A 412 answer, as `Err(Upnp(_, PreconditionFailed))`, means the speaker forgot [sid] and it must be subscribed again. */
    suspend fun renew(
        host: String,
        service: SonosService,
        sid: String,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    ): SonosResult<Subscription>

    /** Unsubscribing a SID the speaker already forgot counts as done. */
    suspend fun unsubscribe(host: String, service: SonosService, sid: String): SonosResult<Unit>

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 120
    }
}
