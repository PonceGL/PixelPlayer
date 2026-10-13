package com.theveloper.pixelplay.data.sonos.net

import java.io.IOException
import java.net.DatagramSocket
import javax.net.SocketFactory

/** The Wi-Fi network that carries every packet exchanged with a speaker. */
internal interface LanNetwork {
    val socketFactory: SocketFactory

    @Throws(IOException::class)
    fun bind(socket: DatagramSocket)
}

internal interface LanNetworkProvider {
    /** The usable Wi-Fi network, or null when there is none (off, mobile data only, or only a VPN). */
    fun wifi(): LanNetwork?
}
