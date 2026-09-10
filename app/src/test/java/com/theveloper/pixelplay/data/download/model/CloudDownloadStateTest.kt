package com.theveloper.pixelplay.data.download.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudDownloadStateTest {

    @Test
    fun `there are exactly eleven states`() {
        assertEquals(11, CloudDownloadState.entries.size)
    }

    @Test
    fun `states the engine drives forward on its own are not terminal`() {
        listOf(
            CloudDownloadState.PENDING,
            CloudDownloadState.QUEUED,
            CloudDownloadState.RUNNING,
            CloudDownloadState.VERIFYING,
        ).forEach { state -> assertFalse(state.isTerminal, "$state should not be terminal") }
    }

    @Test
    fun `RETRY_WAIT is not terminal — the engine's own scheduler wakes it up`() {
        // The whole point of next_retry_at is that nothing external has to happen.
        assertFalse(CloudDownloadState.RETRY_WAIT.isTerminal)
    }

    @Test
    fun `BLOCKED is terminal even though it eventually resumes`() {
        // Distinguishes it from RETRY_WAIT: BLOCKED never consumes an attempt and only
        // resumes when an external condition clears, not on a timer.
        assertTrue(CloudDownloadState.BLOCKED.isTerminal)
    }

    @Test
    fun `states that need an external trigger to move are terminal`() {
        listOf(
            CloudDownloadState.COMPLETED,
            CloudDownloadState.FAILED,
            CloudDownloadState.MISSING,
            CloudDownloadState.STALE,
            CloudDownloadState.CANCELLING,
        ).forEach { state -> assertTrue(state.isTerminal, "$state should be terminal") }
    }
}
