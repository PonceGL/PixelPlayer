package com.theveloper.pixelplay.spike.sonos

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * SPIKE (fase B). Todo el tráfico hacia el Sonos se ata a la red Wi-Fi (no a la "predeterminada"),
 * para que un VPN o los datos móviles no desvíen el multicast ni las llamadas SOAP.
 */
object SonosNet {

    @Suppress("DEPRECATION")
    fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps != null &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }

    /** IPv4 local y prefijo de la red Wi-Fi, p. ej. ("192.168.3.211", 24). */
    fun localIpv4(context: Context, network: Network?): Pair<String, Int>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(network ?: cm.activeNetwork) ?: return null
        val la = props.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return (la.address.hostAddress ?: return null) to la.prefixLength
    }

    fun httpClient(context: Context, timeoutMs: Long): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
        wifiNetwork(context)?.let { b.socketFactory(it.socketFactory) }
        return b.build()
    }

    /** Equivalente de `net_hint` del harness, con las causas típicas de Android. */
    fun netHint(e: Throwable): String {
        val m = ((e.message ?: "") + " " + (e.cause?.message ?: "")).lowercase()
        return when {
            "eperm" in m || "operation not permitted" in m ->
                "EPERM: posible bloqueo por permiso de red local (Local Network Protection)"
            e is SocketTimeoutException || "timed out" in m ->
                "timeout: sin respuesta (host apagado, otra red/VLAN o firewall)"
            "econnrefused" in m || "refused" in m -> "rechazado: nada escucha en ese puerto"
            "enetunreach" in m || "network is unreachable" in m -> "red inalcanzable: ¿Wi-Fi distinta o VPN?"
            "ehostunreach" in m || "no route to host" in m -> "sin ruta al host"
            "cleartext" in m -> "tráfico en claro bloqueado por la política de red de la app"
            else -> ""
        }
    }
}
