package com.theveloper.pixelplay.data.download

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val THIRTY_DAYS_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val SERVER_A = "https://music.example.com"
private const val SERVER_B = "https://other.example.org"

/**
 * F1.4b: the per-server `Range`-support cache (§4.5, `C10`). No network here at all — the
 * "probe" itself is F1.6b's job; this only covers the store's read/write/staleness/invalidation
 * contract, against a real (temp-file) `DataStore`, exactly like [DownloadsFeatureGateTest] does
 * for `UserPreferencesRepository`.
 */
class DownloadServerCapabilitiesStoreTest {

    private fun repositoryWithTempDataStore(scope: CoroutineScope) =
        UserPreferencesRepository(
            dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = {
                    Files.createTempDirectory("download-server-capabilities-store-test")
                        .resolve("settings.preferences_pb")
                        .toFile()
                }
            ),
            json = Json
        )

    @Test
    fun `a server that was never probed reports unknown, not a guess`() = runTest {
        val store = DownloadServerCapabilitiesStore(repositoryWithTempDataStore(backgroundScope))

        assertNull(store.cachedSupportsRange(SERVER_A))
    }

    @Test
    fun `recordProbeResult is read back exactly by cachedSupportsRange`() = runTest {
        val store = DownloadServerCapabilitiesStore(repositoryWithTempDataStore(backgroundScope))
        val probedAt = 1_000_000L

        store.recordProbeResult(SERVER_A, supportsRange = true, nowMillis = probedAt)

        assertEquals(true, store.cachedSupportsRange(SERVER_A, nowMillis = probedAt))
    }

    @Test
    fun `a false result is read back just as faithfully as a true one`() = runTest {
        val store = DownloadServerCapabilitiesStore(repositoryWithTempDataStore(backgroundScope))
        val probedAt = 1_000_000L

        store.recordProbeResult(SERVER_A, supportsRange = false, nowMillis = probedAt)

        assertEquals(false, store.cachedSupportsRange(SERVER_A, nowMillis = probedAt))
    }

    @Test
    fun `each server gets its own independent entry`() = runTest {
        val store = DownloadServerCapabilitiesStore(repositoryWithTempDataStore(backgroundScope))

        store.recordProbeResult(SERVER_A, supportsRange = true, nowMillis = 0L)
        store.recordProbeResult(SERVER_B, supportsRange = false, nowMillis = 0L)

        assertEquals(true, store.cachedSupportsRange(SERVER_A, nowMillis = 0L))
        assertEquals(false, store.cachedSupportsRange(SERVER_B, nowMillis = 0L))
    }

    @Test
    fun `a probe just under 30 days old is still trusted`() = runTest {
        val store = DownloadServerCapabilitiesStore(repositoryWithTempDataStore(backgroundScope))
        val probedAt = 1_000_000L

        store.recordProbeResult(SERVER_A, supportsRange = true, nowMillis = probedAt)

        assertEquals(
            true,
            store.cachedSupportsRange(SERVER_A, nowMillis = probedAt + THIRTY_DAYS_MILLIS - 1),
        )
    }

    @Test
    fun `a probe older than 30 days is stale — unknown, not a stale guess`() = runTest {
        val store = DownloadServerCapabilitiesStore(repositoryWithTempDataStore(backgroundScope))
        val probedAt = 1_000_000L

        store.recordProbeResult(SERVER_A, supportsRange = true, nowMillis = probedAt)

        assertNull(store.cachedSupportsRange(SERVER_A, nowMillis = probedAt + THIRTY_DAYS_MILLIS + 1))
    }

    @Test
    fun `invalidateSupportsRange overrides a true result to false immediately`() = runTest {
        val store = DownloadServerCapabilitiesStore(repositoryWithTempDataStore(backgroundScope))
        store.recordProbeResult(SERVER_A, supportsRange = true, nowMillis = 0L)

        store.invalidateSupportsRange(SERVER_A, nowMillis = 1L)

        assertEquals(false, store.cachedSupportsRange(SERVER_A, nowMillis = 1L))
    }

    @Test
    fun `invalidateSupportsRange resets the TTL clock too, not just the value`() = runTest {
        val store = DownloadServerCapabilitiesStore(repositoryWithTempDataStore(backgroundScope))
        store.recordProbeResult(SERVER_A, supportsRange = true, nowMillis = 0L)

        store.invalidateSupportsRange(SERVER_A, nowMillis = 500L)

        // Read right at the fresh probedAt, not the original nowMillis=0 one: still fresh.
        assertEquals(
            false,
            store.cachedSupportsRange(SERVER_A, nowMillis = 500L + THIRTY_DAYS_MILLIS - 1),
        )
    }

    @Test
    fun `re-probing after a stale entry overwrites it instead of merging`() = runTest {
        val store = DownloadServerCapabilitiesStore(repositoryWithTempDataStore(backgroundScope))
        store.recordProbeResult(SERVER_A, supportsRange = false, nowMillis = 0L)

        store.recordProbeResult(SERVER_A, supportsRange = true, nowMillis = THIRTY_DAYS_MILLIS + 1)

        assertTrue(
            store.cachedSupportsRange(SERVER_A, nowMillis = THIRTY_DAYS_MILLIS + 1) == true
        )
    }

    @Test
    fun `an unrelated server key is never touched by another server's writes`() = runTest {
        val store = DownloadServerCapabilitiesStore(repositoryWithTempDataStore(backgroundScope))

        store.recordProbeResult(SERVER_A, supportsRange = true, nowMillis = 0L)

        assertNull(store.cachedSupportsRange(SERVER_B, nowMillis = 0L))
    }
}
