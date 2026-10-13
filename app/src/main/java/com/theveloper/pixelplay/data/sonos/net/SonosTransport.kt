package com.theveloper.pixelplay.data.sonos.net

import com.theveloper.pixelplay.data.sonos.protocol.SonosService

internal interface SonosTransport {

    /** Sends one SOAP action to the speaker at [host] and returns the output arguments of its answer. */
    suspend fun call(
        host: String,
        service: SonosService,
        action: String,
        arguments: List<Pair<String, String>> = emptyList(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): SonosResult<Map<String, String>>

    companion object {
        const val DEFAULT_TIMEOUT_MS = 4_000L
    }
}
