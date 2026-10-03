package com.theveloper.pixelplay.data.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers the last artwork URI granted to each external controller so the same cover is not
 * granted again on every metadata callback. Only the latest URI per package is kept, so it
 * stays bounded no matter how long the session runs.
 */
internal class ArtworkGrantLedger {
    private val lastGrantedUriByPackage = ConcurrentHashMap<String, String>()

    /** Returns true when [uri] still has to be granted to [packageName], and records it. */
    fun shouldGrant(packageName: String, uri: String): Boolean =
        lastGrantedUriByPackage.put(packageName, uri) != uri

    fun forget(packageName: String) {
        lastGrantedUriByPackage.remove(packageName)
    }
}
