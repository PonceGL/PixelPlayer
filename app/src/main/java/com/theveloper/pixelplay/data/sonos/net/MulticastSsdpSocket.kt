package com.theveloper.pixelplay.data.sonos.net

import android.content.Context
import android.net.wifi.WifiManager
import com.theveloper.pixelplay.data.sonos.protocol.Ssdp
import com.theveloper.pixelplay.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * UDP socket bound to the Wi-Fi network. Holds a multicast lock while open: without it many phones
 * drop multicast traffic when the screen is off.
 */
internal class MulticastSsdpSocket private constructor(
    private val socket: MulticastSocket,
    private val lock: WifiManager.MulticastLock?,
    private val io: CoroutineDispatcher,
) : SsdpSocket {

    private val target = InetAddress.getByName(Ssdp.MULTICAST_ADDRESS)
    private val buffer = ByteArray(RECEIVE_BUFFER_BYTES)

    override suspend fun send(payload: ByteArray) = withContext(io) {
        socket.send(DatagramPacket(payload, payload.size, target, Ssdp.PORT))
    }

    override suspend fun receive(timeoutMs: Long): SsdpPacket? = withContext(io) {
        socket.soTimeout = timeoutMs.toInt()
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            socket.receive(packet)
        } catch (_: SocketTimeoutException) {
            return@withContext null
        }
        SsdpPacket(packet.address.hostAddress.orEmpty(), packet.data.copyOf(packet.length))
    }

    override fun close() {
        socket.close()
        lock?.release()
    }

    class Factory @Inject constructor(
        @ApplicationContext private val context: Context,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        @Throws(IOException::class)
        fun open(network: LanNetwork): SsdpSocket {
            val lock = context.getSystemService(WifiManager::class.java)
                ?.createMulticastLock(LOCK_TAG)
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            try {
                val socket = MulticastSocket(0)
                try {
                    socket.timeToLive = MULTICAST_TTL
                    network.bind(socket)
                } catch (e: IOException) {
                    socket.close()
                    throw e
                }
                return MulticastSsdpSocket(socket, lock, io)
            } catch (e: IOException) {
                lock?.release()
                throw e
            }
        }
    }

    private companion object {
        const val LOCK_TAG = "pixelplay-sonos-ssdp"
        const val MULTICAST_TTL = 2
        const val RECEIVE_BUFFER_BYTES = 8 * 1024
    }
}
