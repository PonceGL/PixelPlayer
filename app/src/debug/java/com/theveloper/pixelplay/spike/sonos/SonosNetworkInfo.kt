package com.theveloper.pixelplay.spike.sonos

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * SPIKE (fase A). Foto de las redes del teléfono para el log: sirve para S4 (qué IP/interfaz
 * se usa hacia la LAN, si hay VPN o datos móviles activos) sin tener que adivinarlo después.
 */
object SonosNetworkInfo {

    @Suppress("DEPRECATION")
    fun snapshot(context: Context): List<Map<String, Any?>> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val active = cm.activeNetwork
        return cm.allNetworks.map { network ->
            val caps = cm.getNetworkCapabilities(network)
            val props = cm.getLinkProperties(network)
            mapOf(
                "active" to (network == active),
                "transports" to transports(caps),
                "validated" to caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                "internet" to caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                "not_vpn" to caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                "iface" to props?.interfaceName,
                "addresses" to props?.linkAddresses?.map { "${it.address.hostAddress}/${it.prefixLength}" },
                "dns" to props?.dnsServers?.map { it.hostAddress },
            )
        }
    }

    private fun transports(caps: NetworkCapabilities?): List<String> {
        if (caps == null) return emptyList()
        return buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
        }
    }
}
