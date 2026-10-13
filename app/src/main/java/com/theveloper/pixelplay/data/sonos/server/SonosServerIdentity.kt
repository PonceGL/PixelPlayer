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

    fun current(): SonosServerIdentity = synchronized(lock) {
        identity ?: load().also { identity = it }
    }

    fun rememberPort(port: Int) = synchronized(lock) {
        if (port !in VALID_PORTS) return@synchronized
        val updated = current().copy(port = port)
        identity = updated
        store.save(updated.token, updated.port)
    }

    private fun load(): SonosServerIdentity {
        val stored = store.load()
        val token = stored?.token?.takeIf(TOKEN_FORMAT::matches)
        if (token != null) {
            return SonosServerIdentity(token, stored.port?.takeIf { it in VALID_PORTS })
        }
        val created = SonosServerIdentity(newToken(), port = null)
        store.save(created.token, created.port)
        return created
    }

    private fun newToken(): String = randomBytes().joinToString("") { "%02x".format(it) }

    private companion object {
        val TOKEN_FORMAT = Regex("[0-9a-f]{32}")
        val VALID_PORTS = 1024..65535
    }
}
