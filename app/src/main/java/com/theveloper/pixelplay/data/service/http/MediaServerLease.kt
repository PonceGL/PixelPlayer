package com.theveloper.pixelplay.data.service.http

import javax.inject.Inject
import javax.inject.Singleton

internal enum class ServerOwner { CAST, SONOS }

/**
 * Keeps the media server alive while at least one owner needs it. Owners are a set, so acquiring or
 * releasing twice is harmless, and one owner can never stop the server under another.
 */
@Singleton
internal class MediaServerLease @Inject constructor(
    private val controller: MediaServerController,
) {
    private val lock = Any()
    private val owners = mutableSetOf<ServerOwner>()

    fun acquire(owner: ServerOwner, deviceIpHint: String? = null) = synchronized(lock) {
        val wasIdle = owners.isEmpty()
        owners += owner
        if (wasIdle) controller.start(deviceIpHint)
    }

    fun release(owner: ServerOwner) = synchronized(lock) {
        if (owners.remove(owner) && owners.isEmpty()) controller.stop()
    }

    /** Starts the server again, for example on another network, without changing who owns it. */
    fun restart(deviceIpHint: String?) = synchronized(lock) {
        if (owners.isEmpty()) return@synchronized
        controller.stop()
        controller.start(deviceIpHint)
    }

    fun isHeldBy(owner: ServerOwner): Boolean = synchronized(lock) { owner in owners }
}
