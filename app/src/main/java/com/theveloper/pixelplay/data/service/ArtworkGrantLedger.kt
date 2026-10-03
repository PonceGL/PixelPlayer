package com.theveloper.pixelplay.data.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers the last artwork URI successfully granted to each external controller so the same
 * cover is not granted again on every metadata callback. Only the latest URI per package is
 * kept, so it stays bounded no matter how long the session runs. A grant is recorded only once
 * it is confirmed, so a failed one is retried on the next callback.
 */
internal class ArtworkGrantLedger {
    private val lastGrantedUriByPackage = ConcurrentHashMap<String, String>()

    fun needsGrant(packageName: String, uri: String): Boolean =
        lastGrantedUriByPackage[packageName] != uri

    fun markGranted(packageName: String, uri: String) {
        lastGrantedUriByPackage[packageName] = uri
    }

    fun forget(packageName: String) {
        lastGrantedUriByPackage.remove(packageName)
    }
}
