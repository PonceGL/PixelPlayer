package com.theveloper.pixelplay.data.sonos.net

import java.io.IOException

internal class SsdpPacket(val sourceHost: String, val payload: ByteArray)

/** A UDP socket that can send the multicast search and read unicast replies. */
internal interface SsdpSocket : AutoCloseable {
    @Throws(IOException::class)
    suspend fun send(payload: ByteArray)

    /** The next packet, or null when nothing arrived within [timeoutMs]. */
    @Throws(IOException::class)
    suspend fun receive(timeoutMs: Long): SsdpPacket?

    override fun close()
}
