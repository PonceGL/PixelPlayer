package com.theveloper.pixelplay.data.sonos.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.DatagramSocket
import javax.inject.Inject
import javax.net.SocketFactory

/**
 * Picks the Wi-Fi network explicitly instead of the default one, so a VPN or mobile data can never
 * carry the multicast search or the SOAP calls.
 */
internal class AndroidLanNetworkProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : LanNetworkProvider {

    // allNetworks is deprecated since API 31, but the callback-based replacements are asynchronous and
    // cannot answer "is there a Wi-Fi right now" synchronously, which a discovery start needs.
    @Suppress("DEPRECATION")
    override fun wifi(): LanNetwork? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.allNetworks.firstOrNull { candidate ->
            val capabilities = manager.getNetworkCapabilities(candidate)
            capabilities != null &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } ?: return null
        return AndroidLanNetwork(network)
    }

    private class AndroidLanNetwork(private val network: Network) : LanNetwork {
        override val socketFactory: SocketFactory get() = network.socketFactory
        override fun bind(socket: DatagramSocket) = network.bindSocket(socket)
    }
}
