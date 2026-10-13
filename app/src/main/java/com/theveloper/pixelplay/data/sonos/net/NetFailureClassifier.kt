package com.theveloper.pixelplay.data.sonos.net

import java.io.IOException
import java.io.InterruptedIOException
import java.net.NoRouteToHostException
import java.net.UnknownServiceException

internal object NetFailureClassifier {

    /** HTTP clients wrap the socket error that matters, so the whole cause chain is inspected. */
    fun classify(e: IOException): SonosFailure {
        val chain = generateSequence<Throwable>(e) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        val message = chain.joinToString(" | ") { it.message.orEmpty() }
        return when {
            chain.any { it is InterruptedIOException } -> SonosFailure.Timeout
            chain.any { it is UnknownServiceException } && message.contains("CLEARTEXT", ignoreCase = true) ->
                SonosFailure.CleartextBlocked
            message.contains("EPERM") -> SonosFailure.LocalNetworkDenied
            message.contains("ECONNREFUSED") || message.contains("Connection refused", ignoreCase = true) ->
                SonosFailure.ConnectionRefused
            chain.any { it is NoRouteToHostException } ||
                UNREACHABLE_MARKERS.any { message.contains(it, ignoreCase = true) } ->
                SonosFailure.NetworkUnreachable
            else -> SonosFailure.Network(e.message.orEmpty())
        }
    }

    private const val MAX_CAUSE_DEPTH = 5
    private val UNREACHABLE_MARKERS = listOf("ENETUNREACH", "EHOSTUNREACH", "Network is unreachable", "No route to host")
}
