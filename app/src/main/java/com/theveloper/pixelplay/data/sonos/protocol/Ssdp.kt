package com.theveloper.pixelplay.data.sonos.protocol

import com.theveloper.pixelplay.data.sonos.model.SonosUdn

/** A Sonos player that answered the SSDP search. [host] is where the reply came from. */
internal data class SsdpResponse(
    val host: String,
    val udn: SonosUdn,
    val household: String?,
    val serverBanner: String?,
)

internal object Ssdp {

    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val PORT = 1900
    const val ZONE_PLAYER_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1"

    /** Real replies are under 1 KB; anything bigger is not worth parsing. */
    private const val MAX_RESPONSE_CHARS = 8 * 1024
    private const val STATUS_OK = "HTTP/1.1 200"
    private const val UDN_PREFIX = "uuid:RINCON_"

    fun searchRequest(maxWaitSeconds: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\nHOST: $MULTICAST_ADDRESS:$PORT\r\nMAN: \"ssdp:discover\"\r\n" +
            "MX: $maxWaitSeconds\r\nST: $ZONE_PLAYER_TARGET\r\n\r\n"

    /**
     * Reads the reply of a speaker, or returns null when [raw] is not a Sonos player's answer to the
     * search. The host is the packet's source because the LOCATION header is advertised by the sender
     * and could point anywhere.
     */
    fun parseResponse(raw: String, sourceHost: String): SsdpResponse? {
        if (raw.length > MAX_RESPONSE_CHARS) return null
        // Header values are trimmed below, so a stray \r from CRLF endings needs no special case.
        val lines = raw.split('\n')
        if (lines.firstOrNull()?.startsWith(STATUS_OK) != true) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
        }.toMap()
        if (!headers["st"].equals(ZONE_PLAYER_TARGET, ignoreCase = true)) return null
        val usn = headers["usn"]?.takeIf { it.startsWith(UDN_PREFIX) } ?: return null
        return SsdpResponse(
            host = sourceHost,
            udn = SonosUdn(usn.substringBefore("::").removePrefix("uuid:")),
            household = headers["x-rincon-household"]?.takeIf { it.isNotBlank() },
            serverBanner = headers["server"]?.takeIf { it.isNotBlank() },
        )
    }
}
