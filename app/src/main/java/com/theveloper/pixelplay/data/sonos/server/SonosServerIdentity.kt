package com.theveloper.pixelplay.data.sonos.server

/** The secret in the URLs given to a speaker and the port they point to; both outlive the process. */
internal data class SonosServerIdentity(val token: String, val port: Int?) {
    override fun toString(): String = "SonosServerIdentity(port=$port)"
}

internal class StoredIdentity(val token: String?, val port: Int?)

/** Never throws: an unreadable store answers null and a store that cannot write answers false. */
internal interface SonosServerIdentityStore {
    fun load(): StoredIdentity?
    fun save(token: String, port: Int?): Boolean
}

internal class SonosServerIdentityProvider(
    private val store: SonosServerIdentityStore,
    private val randomBytes: () -> ByteArray,
) {
    private val lock = Any()
    private var identity: SonosServerIdentity? = null

    /** True once the saved port could not be reused: URLs already queued on a speaker no longer work. */
    @Volatile
    var portChanged: Boolean = false
        private set

    fun current(): SonosServerIdentity = synchronized(lock) {
        identity ?: load().also { identity = it }
    }

    fun rememberPort(port: Int) = synchronized(lock) {
        if (port !in VALID_PORTS) return@synchronized
        val previous = current().port
        if (previous != null && previous != port) portChanged = true
        val updated = current().copy(port = port)
        identity = updated
        persist(updated)
    }

    private fun persist(identity: SonosServerIdentity) {
        try {
            store.save(identity.token, identity.port)
        } catch (_: RuntimeException) {
            // The token then lives only in memory for this run.
        }
    }

    private fun load(): SonosServerIdentity {
        // Storage is best effort: a store that fails must never keep the server from starting.
        val stored = try {
            store.load()
        } catch (_: RuntimeException) {
            null
        }
        val token = stored?.token?.takeIf(TOKEN_FORMAT::matches)
        if (token != null) {
            return SonosServerIdentity(token, stored.port?.takeIf { it in VALID_PORTS })
        }
        val created = SonosServerIdentity(newToken(), port = null)
        persist(created)
        return created
    }

    private fun newToken(): String = randomBytes().joinToString("") { "%02x".format(it) }

    private companion object {
        val TOKEN_FORMAT = Regex("[0-9a-f]{32}")
        val VALID_PORTS = 1024..65535
    }
}
