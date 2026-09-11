package com.theveloper.pixelplay.data.worker

import androidx.work.NetworkType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudDownloadSchedulerWorkerTest {

    // ─── shouldStartService: the actual decision doWork() makes ───────────────

    @Test
    fun `does not start the service when the feature is disabled, even with real queued work`() {
        assertFalse(CloudDownloadSchedulerWorker.shouldStartService(featureEnabled = false, hasQueuedWork = true))
    }

    @Test
    fun `does not start the service when the queue is empty, even with the feature on`() {
        assertFalse(CloudDownloadSchedulerWorker.shouldStartService(featureEnabled = true, hasQueuedWork = false))
    }

    @Test
    fun `does not start the service when both the feature is off and the queue is empty`() {
        assertFalse(CloudDownloadSchedulerWorker.shouldStartService(featureEnabled = false, hasQueuedWork = false))
    }

    @Test
    fun `starts the service when the feature is on and there is queued work`() {
        assertTrue(CloudDownloadSchedulerWorker.shouldStartService(featureEnabled = true, hasQueuedWork = true))
    }

    // ─── periodicWork(): the request WorkManager actually schedules ───────────

    @Test
    fun `the periodic request is constrained to an unmetered network, not parameterized yet`() {
        val request = CloudDownloadSchedulerWorker.periodicWork()

        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
    }
}
