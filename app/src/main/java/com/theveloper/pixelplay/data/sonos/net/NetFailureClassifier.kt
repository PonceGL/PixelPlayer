package com.theveloper.pixelplay.data.sonos.net

import java.io.IOException
import java.io.InterruptedIOException
import java.net.NoRouteToHostException
import java.net.UnknownServiceException

internal object NetFailureClassifier {

    fun classify(e: IOException): SonosFailure {
        val message = e.message.orEmpty()
        return when {
            e is InterruptedIOException -> SonosFailure.Timeout
            e is UnknownServiceException && message.contains("CLEARTEXT", ignoreCase = true) ->
                SonosFailure.CleartextBlocked
            message.contains("EPERM") -> SonosFailure.LocalNetworkDenied
            message.contains("ECONNREFUSED") || message.contains("Connection refused", ignoreCase = true) -> SonosFailure.ConnectionRefused
            e is NoRouteToHostException || UNREACHABLE_MARKERS.any { message.contains(it, ignoreCase = true) } ->
                SonosFailure.NetworkUnreachable
            else -> SonosFailure.Network(message)
        }
    }

    private val UNREACHABLE_MARKERS = listOf("ENETUNREACH", "EHOSTUNREACH", "Network is unreachable", "No route to host")
}
