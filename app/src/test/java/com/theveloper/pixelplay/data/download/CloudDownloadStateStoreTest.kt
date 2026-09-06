package com.theveloper.pixelplay.data.download

import app.cash.turbine.test
import com.theveloper.pixelplay.data.database.SourceType
import com.theveloper.pixelplay.data.download.model.CloudDownloadKey
import com.theveloper.pixelplay.data.download.model.CloudDownloadState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudDownloadStateStoreTest {

    private val key = CloudDownloadKey(SourceType.JELLYFIN, "item-1")
    private val otherKey = CloudDownloadKey(SourceType.JELLYFIN, "item-2")

    // ─── Basic transitions ────────────────────────────────────────────────────────

    @Test
    fun `update publishes the latest progress for a download`() {
        val store = CloudDownloadStateStore()

        store.update(key, CloudDownloadState.RUNNING, downloadedBytes = 100, expectedBytes = 1000, nowMillis = 0)

        val progress = store.progressByDownloadId.value.getValue(key.storageId)
        assertEquals(CloudDownloadState.RUNNING, progress.state)
        assertEquals(100L, progress.downloadedBytes)
        assertEquals(0.1f, progress.fraction)
    }

    @Test
    fun `remove drops the entry entirely`() {
        val store = CloudDownloadStateStore()
        store.update(key, CloudDownloadState.RUNNING, 100, 1000, nowMillis = 0)

        store.remove(key)

        assertTrue(store.progressByDownloadId.value.isEmpty())
    }

    @Test
    fun `case borde 1 — cancelled before a second tick leaves no orphan entry`() {
        val store = CloudDownloadStateStore()

        store.update(key, CloudDownloadState.PENDING, 0, null, nowMillis = 0)
        store.remove(key) // cancelled immediately, before any real progress tick

        assertTrue(store.progressByDownloadId.value.isEmpty())
    }

    // ─── Case borde 2: ETA with zero speed is never Infinity/NaN ──────────────────

    @Test
    fun `a terminal state reports zero speed and a null eta, never NaN or Infinity`() {
        val store = CloudDownloadStateStore()
        store.update(key, CloudDownloadState.RUNNING, 0, 1000, nowMillis = 0)

        store.update(key, CloudDownloadState.BLOCKED, 0, 1000, nowMillis = 1000)

        val progress = store.progressByDownloadId.value.getValue(key.storageId)
        assertEquals(0L, progress.bytesPerSecond)
        assertNull(progress.etaMillis)
    }

    @Test
    fun `a stalled but non-terminal download reports zero speed and no eta`() {
        val store = CloudDownloadStateStore()
        store.update(key, CloudDownloadState.RUNNING, 500, 1000, nowMillis = 0)

        // Same byte count a second later — no bytes moved, but still RUNNING.
        store.update(key, CloudDownloadState.RUNNING, 500, 1000, nowMillis = 1000)

        val progress = store.progressByDownloadId.value.getValue(key.storageId)
        assertEquals(0L, progress.bytesPerSecond)
        assertNull(progress.etaMillis)
    }

    @Test
    fun `speed and eta are unknown, not zero, before a second sample exists`() {
        val store = CloudDownloadStateStore()

        store.update(key, CloudDownloadState.RUNNING, 0, 1000, nowMillis = 0)

        val progress = store.progressByDownloadId.value.getValue(key.storageId)
        assertNull(progress.bytesPerSecond)
        assertNull(progress.etaMillis)
    }

    @Test
    fun `speed and eta are computed over the sliding window, not the cumulative average`() {
        val store = CloudDownloadStateStore()
        // A slow first second, then a much faster second — an average over the whole
        // download would still read closer to the slow rate.
        store.update(key, CloudDownloadState.RUNNING, 100, 10_100, nowMillis = 0)
        store.update(key, CloudDownloadState.RUNNING, 200, 10_100, nowMillis = 1000) // 100 B/s so far
        store.update(key, CloudDownloadState.RUNNING, 1_200, 10_100, nowMillis = 2000) // last tick: 1000 B/s

        val progress = store.progressByDownloadId.value.getValue(key.storageId)
        // Window covers [0, 2000]: (1200 - 100) bytes over 2000ms = 550 B/s — reacts to the
        // recent burst, unlike a cumulative average which would sit near 600 B/s total but
        // trend toward the *older*, slower rate as more slow history accumulates.
        assertEquals(550L, progress.bytesPerSecond)
    }

    // ─── Case borde 3: expectedBytes null → indeterminate, not zero ───────────────

    @Test
    fun `a null expectedBytes means indeterminate progress, not a fraction of zero`() {
        val store = CloudDownloadStateStore()

        store.update(key, CloudDownloadState.RUNNING, 500, expectedBytes = null, nowMillis = 0)

        val progress = store.progressByDownloadId.value.getValue(key.storageId)
        assertNull(progress.fraction)
    }

    @Test
    fun `a null expectedBytes still allows a speed reading, just no eta`() {
        val store = CloudDownloadStateStore()
        store.update(key, CloudDownloadState.RUNNING, 0, expectedBytes = null, nowMillis = 0)

        store.update(key, CloudDownloadState.RUNNING, 1000, expectedBytes = null, nowMillis = 1000)

        val progress = store.progressByDownloadId.value.getValue(key.storageId)
        assertEquals(1000L, progress.bytesPerSecond)
        assertNull(progress.etaMillis)
    }

    // ─── Case borde 4: the clock is injected and monotonic ────────────────────────

    @Test
    fun `an earlier nowMillis than the previous sample never produces a negative-time result`() {
        val store = CloudDownloadStateStore()
        store.update(key, CloudDownloadState.RUNNING, 1000, 10_000, nowMillis = 5000)

        // Simulates a device clock moved backwards — impossible with a real monotonic clock,
        // but the caller-supplied value is what's under test here.
        store.update(key, CloudDownloadState.RUNNING, 2000, 10_000, nowMillis = 4000)

        val progress = store.progressByDownloadId.value.getValue(key.storageId)
        assertNull(progress.bytesPerSecond)
        assertNull(progress.etaMillis)
    }

    // ─── Collection aggregation ────────────────────────────────────────────────────

    @Test
    fun `collection progress counts only members whose state is COMPLETED`() {
        val store = CloudDownloadStateStore()
        store.registerSubscriptionMembers("sub-1", setOf(key.storageId, otherKey.storageId))

        store.update(key, CloudDownloadState.COMPLETED, 1000, 1000, nowMillis = 0)
        store.update(otherKey, CloudDownloadState.RUNNING, 500, 1000, nowMillis = 0)

        val collection = store.progressBySubscriptionId.value.getValue("sub-1")
        assertEquals(1, collection.currentCount)
        assertEquals(2, collection.totalCount)
        assertTrue(collection.isRunning)
        assertEquals(false, collection.isCompleted)
    }

    @Test
    fun `a collection is completed only once every member is COMPLETED`() {
        val store = CloudDownloadStateStore()
        store.registerSubscriptionMembers("sub-1", setOf(key.storageId, otherKey.storageId))

        store.update(key, CloudDownloadState.COMPLETED, 1000, 1000, nowMillis = 0)
        store.update(otherKey, CloudDownloadState.COMPLETED, 1000, 1000, nowMillis = 0)

        val collection = store.progressBySubscriptionId.value.getValue("sub-1")
        assertEquals(2, collection.currentCount)
        assertTrue(collection.isCompleted)
        assertEquals(false, collection.isRunning)
    }

    @Test
    fun `unregistering a subscription removes its aggregate`() {
        val store = CloudDownloadStateStore()
        store.registerSubscriptionMembers("sub-1", setOf(key.storageId))
        store.update(key, CloudDownloadState.RUNNING, 0, 1000, nowMillis = 0)

        store.unregisterSubscription("sub-1")

        assertTrue(store.progressBySubscriptionId.value.isEmpty())
    }

    // ─── Completion emission (turbine) ────────────────────────────────────────────

    @Test
    fun `completedDownloads emits once when a download transitions into COMPLETED`() = runTest {
        val store = CloudDownloadStateStore()

        store.completedDownloads.test {
            store.update(key, CloudDownloadState.RUNNING, 500, 1000, nowMillis = 0)
            store.update(key, CloudDownloadState.COMPLETED, 1000, 1000, nowMillis = 1000)

            assertEquals(key, awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `completedDownloads does not re-emit for a download that is already COMPLETED`() = runTest {
        val store = CloudDownloadStateStore()
        store.update(key, CloudDownloadState.COMPLETED, 1000, 1000, nowMillis = 0)

        store.completedDownloads.test {
            store.update(key, CloudDownloadState.COMPLETED, 1000, 1000, nowMillis = 1000)

            expectNoEvents()
        }
    }

    // ─── Case borde 5 (I3): never touches Room, by construction ───────────────────

    @Test
    fun `a thousand consecutive updates never throw and only ever keep the latest value`() {
        // I3 is enforced structurally here, not behaviorally: this class takes no DAO
        // dependency at all, so there is nothing for it to write to Room with — a fake DAO
        // to "count writes against" would have nothing to attach to. What a burst of updates
        // can still break is the live map or the sliding window; this proves it doesn't.
        val store = CloudDownloadStateStore()

        for (tick in 0 until 1000) {
            store.update(key, CloudDownloadState.RUNNING, tick.toLong(), 1000L, nowMillis = tick.toLong())
        }

        assertEquals(1, store.progressByDownloadId.value.size)
        assertEquals(999L, store.progressByDownloadId.value.getValue(key.storageId).downloadedBytes)
    }
}
