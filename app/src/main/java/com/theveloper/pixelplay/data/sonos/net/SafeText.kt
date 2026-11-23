package com.theveloper.pixelplay.data.sonos.net

/** Only the kind of failure, never what the network said: that can hold the address of the speaker. */
internal fun SonosFailure.safeName(): String = when (this) {
    // Which refusal it was helps to tell a speaker that disagrees with us from one that is busy; it holds no address.
    is SonosFailure.Upnp -> "Upnp(${kind::class.simpleName ?: "?"}${(kind as? com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind.Other)?.code?.let { ", $it" }.orEmpty()})"
    else -> this::class.simpleName ?: "SonosFailure"
}

private val PRIVATE_RANGES = listOf(
    (10 to 0) to 8, // 10.0.0.0/8
    (172 to 16) to 12, // 172.16.0.0/12
    (192 to 168) to 16, // 192.168.0.0/16
    (169 to 254) to 16, // link-local
)

/** True for an IPv4 address of a home network. A speaker is never anywhere else, so nothing else is talked to. */
internal fun isLanHost(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { part -> part.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it in 0..255 } ?: return false }
    val address = octets.fold(0) { acc, octet -> (acc shl 8) or octet }
    return PRIVATE_RANGES.any { (network, prefix) ->
        val base = (network.first shl 24) or (network.second shl 16)
        val mask = -1 shl (32 - prefix)
        (address and mask) == (base and mask)
    }
}
