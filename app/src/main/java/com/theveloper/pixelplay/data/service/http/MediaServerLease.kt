package com.theveloper.pixelplay.data.service.http

import javax.inject.Inject
import javax.inject.Singleton

enum class ServerOwner { CAST, SONOS }

/**
 * Keeps the media server alive while at least one owner needs it. Owners are a set, so releasing
 * twice is harmless and one owner can never stop the server under another.
 */
@Singleton
class MediaServerLease @Inject constructor(
    private val controller: MediaServerController,
) {
    private val lock = Any()
    private val owners = mutableSetOf<ServerOwner>()

    /**
     * Registers [owner] and asks for the server to run. It asks every time, not only for the first
     * owner: the server can stop by itself (no network address, a refused foreground start) and the
     * next caller must bring it back. If the system refuses the start, the owner is not kept.
     */
    fun acquire(owner: ServerOwner, deviceIpHint: String? = null) = synchronized(lock) {
        val added = owners.add(owner)
        try {
            controller.start(deviceIpHint)
        } catch (e: RuntimeException) {
            if (added) owners.remove(owner)
            throw e
        }
    }

    /**
     * Drops [owner] and stops the server when nobody is left. It also stops when nobody held it to begin
     * with: after the process dies the system may restart the service on its own, and without this an
     * orphaned server would stay on with nothing able to turn it off.
     */
    fun release(owner: ServerOwner) = synchronized(lock) {
        owners.remove(owner)
        if (owners.isEmpty()) controller.stop()
    }

    /** Stops the server so it can be started again on another network; every owner keeps its claim. */
    fun stopForRestart() = synchronized(lock) { controller.stop() }

    fun isHeldBy(owner: ServerOwner): Boolean = synchronized(lock) { owner in owners }
}
