package com.theveloper.pixelplay.data.sonos.server

import com.theveloper.pixelplay.data.service.http.MediaFileHttpServerService
import com.theveloper.pixelplay.data.service.http.MediaServerLease
import com.theveloper.pixelplay.data.service.http.ServerOwner
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.session.SonosMediaServer
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * Starts the media server for a speaker and waits for its address. The server must be on the speaker's own
 * network, otherwise the speaker could never reach it: if it runs on another one it is restarted, as long as
 * nobody else is using it.
 */
internal class ServiceSonosMediaServer @Inject constructor(
    private val lease: MediaServerLease,
) : SonosMediaServer {

    override suspend fun acquire(speakerHost: String): SonosResult<String> {
        MediaFileHttpServerService.lastFailureReason = null
        MediaFileHttpServerService.lastFailureMessage = null
        if (!start(speakerHost)) return SonosResult.Err(SonosFailure.ServerUnavailable)

        var restarted = false
        repeat(WAIT_STEPS) {
            val address = MediaFileHttpServerService.serverAddress
            if (MediaFileHttpServerService.isServerRunning && address != null) {
                if (sameIpv4Subnet(address, speakerHost, MediaFileHttpServerService.serverPrefixLength)) {
                    return SonosResult.Ok(address)
                }
                if (restarted || !lease.stopForRestart(ServerOwner.SONOS)) return SonosResult.Err(SonosFailure.ServerUnavailable)
                restarted = true
                waitUntilStopped()
                if (!start(speakerHost)) return SonosResult.Err(SonosFailure.ServerUnavailable)
            }
            if (MediaFileHttpServerService.lastFailureReason != null) return SonosResult.Err(SonosFailure.ServerUnavailable)
            delay(STEP_MS)
        }
        return SonosResult.Err(SonosFailure.ServerUnavailable)
    }

    override fun release() = lease.release(ServerOwner.SONOS)

    override fun currentAddress(): String? = MediaFileHttpServerService.serverAddress

    /** The system may refuse to start the service; that is a failure to report, not an exception to throw. */
    private fun start(speakerHost: String): Boolean = try {
        lease.acquire(ServerOwner.SONOS, speakerHost)
        true
    } catch (_: RuntimeException) {
        false
    }

    private suspend fun waitUntilStopped() {
        repeat(WAIT_STEPS) {
            if (!MediaFileHttpServerService.isServerRunning && !MediaFileHttpServerService.isServerStarting) return
            delay(STEP_MS)
        }
    }

    private companion object {
        const val STEP_MS = 100L
        const val WAIT_STEPS = 100
    }
}

/** True when both are IPv4 addresses sharing the first [prefixLength] bits. [first] may carry a scheme and port. */
internal fun sameIpv4Subnet(first: String?, second: String?, prefixLength: Int): Boolean {
    if (prefixLength !in 0..32) return false
    val a = ipv4ToInt(first) ?: return false
    val b = ipv4ToInt(second) ?: return false
    if (prefixLength == 0) return true
    val mask = -1 shl (32 - prefixLength)
    return (a and mask) == (b and mask)
}

private fun ipv4ToInt(raw: String?): Int? {
    val host = raw?.substringAfter("://")?.substringBefore('/')?.substringBefore(':')?.trim().orEmpty()
    val parts = host.split('.')
    if (parts.size != 4) return null
    var value = 0
    for (part in parts) {
        val octet = part.toIntOrNull()?.takeIf { it in 0..255 && part.all(Char::isDigit) } ?: return null
        value = (value shl 8) or octet
    }
    return value
}
