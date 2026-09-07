package com.theveloper.pixelplay.data.download

import com.theveloper.pixelplay.data.download.model.ServerCapabilities
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The per-server `Range`-support cache described in `F1.md` §4.5 (`C10`, `R-F1-7`): a
 * `DataStore` preference, not a fourth table — this is a cache with no need for transactions,
 * joins, or to survive being wrong (§4.5 rules out `cloud_download_server_caps` on exactly
 * those grounds).
 *
 * Two halves of the same mechanism live on either side of this store, deliberately not both
 * here: `F1.4b` (this class) owns the cache and its invalidation; the actual "probe" is just a
 * live download attempt in `F1.6b` observing whether the server honored `Range` — there is no
 * dedicated probe request here, because a real attempt already answers the question for free.
 */
@Singleton
class DownloadServerCapabilitiesStore @Inject constructor(
    private val preferences: UserPreferencesRepository,
) {

    /**
     * The last known `Range` support for [serverKey], or `null` when it has never been probed
     * **or** the last probe is older than [PROBE_TTL_MILLIS] (30 days) — both cases mean the
     * caller must treat support as unknown and re-probe (i.e. just attempt a `Range` download
     * and call [recordProbeResult] with what actually happened) before trusting it.
     */
    suspend fun cachedSupportsRange(
        serverKey: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean? {
        val capabilities = preferences.downloadServerCapabilitiesFor(serverKey) ?: return null
        if (nowMillis - capabilities.probedAt > PROBE_TTL_MILLIS) return null
        return capabilities.supportsRange
    }

    /**
     * Persists the outcome of a real probe for [serverKey], stamped with [nowMillis]. This is
     * also exactly what a live invalidation is (see [invalidateSupportsRange]) — recording a
     * fresh, negative result is the entire mechanism; there is no separate code path.
     */
    suspend fun recordProbeResult(
        serverKey: String,
        supportsRange: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        preferences.setDownloadServerCapabilities(
            serverKey,
            ServerCapabilities(supportsRange = supportsRange, probedAt = nowMillis),
        )
    }

    /**
     * `F1.6b`'s trigger (§4.5): a live `Range` request that came back `200` instead of `206`
     * proves the cached probe lied — a reverse proxy could have changed the server's behavior
     * at any point since. Marks [serverKey] as **not** supporting `Range`, immediately and with
     * a fresh [ServerCapabilities.probedAt], so the very next download for this server skips
     * `Range` outright instead of repeating the truncate-and-restart loop `C10` describes.
     */
    suspend fun invalidateSupportsRange(serverKey: String, nowMillis: Long = System.currentTimeMillis()) =
        recordProbeResult(serverKey, supportsRange = false, nowMillis = nowMillis)

    companion object {
        internal const val PROBE_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
