package com.theveloper.pixelplay.data.sonos.server

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

internal data class SonosNotification(val sid: String?, val seq: Long?, val body: String)

internal fun interface SonosEventSink {
    fun onNotification(notification: SonosNotification)
}

/**
 * Hands the events a speaker sends to whoever subscribed. Each subscription gets an unguessable key
 * that becomes part of its callback URL, and only the speaker it was made for may use it.
 */
@Singleton
internal class SonosEventRouter @Inject constructor() {

    internal class Registration(val expectedHost: String, val sink: SonosEventSink)

    private val random = SecureRandom()
    private val registrations = ConcurrentHashMap<String, Registration>()

    fun register(expectedHost: String, sink: SonosEventSink): String {
        val key = ByteArray(KEY_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        registrations[key] = Registration(expectedHost, sink)
        return key
    }

    fun unregister(key: String) {
        registrations.remove(key)
    }

    fun find(key: String?): Registration? = key?.let(registrations::get)

    private companion object {
        const val KEY_BYTES = 16
    }
}
