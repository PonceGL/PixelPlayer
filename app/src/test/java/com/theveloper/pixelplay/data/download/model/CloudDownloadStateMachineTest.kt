package com.theveloper.pixelplay.data.download.model

import com.theveloper.pixelplay.data.download.model.CloudDownloadState.BLOCKED
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.CANCELLING
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.COMPLETED
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.FAILED
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.MISSING
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.PENDING
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.QUEUED
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.RETRY_WAIT
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.RUNNING
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.STALE
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.VERIFYING
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The transition table, tested against an independently written-out expectation (not by
 * re-deriving [CloudDownloadStateMachine]'s own map) so a missing or superfluous edge in the
 * implementation actually fails a test instead of trivially agreeing with itself.
 */
class CloudDownloadStateMachineTest {

    /** Every legal edge of the state diagram, transcribed independently of the production map. */
    private val expectedEdges: Map<CloudDownloadState, Set<CloudDownloadState>> = mapOf(
        PENDING to setOf(QUEUED, CANCELLING),
        QUEUED to setOf(RUNNING, CANCELLING),
        RUNNING to setOf(VERIFYING, RETRY_WAIT, BLOCKED, FAILED, CANCELLING),
        VERIFYING to setOf(COMPLETED, RETRY_WAIT, BLOCKED, FAILED, CANCELLING),
        // BLOCKED added alongside MISSING/STALE once the download engine needed to tell "the
        // file is actually gone" (MISSING, self-heals on its own) apart from "the file is fine
        // but its volume isn't reachable right now" (BLOCKED, must not self-heal — retrying
        // would leave two copies once the volume comes back).
        COMPLETED to setOf(MISSING, STALE, BLOCKED, CANCELLING),
        RETRY_WAIT to setOf(QUEUED, CANCELLING),
        BLOCKED to setOf(QUEUED, CANCELLING),
        FAILED to setOf(QUEUED, CANCELLING),
        MISSING to setOf(QUEUED, CANCELLING),
        STALE to setOf(QUEUED, CANCELLING),
        CANCELLING to emptySet(),
    )

    @Test
    fun `isLegal matches the state diagram for every pair of states`() {
        CloudDownloadState.entries.forEach { from ->
            CloudDownloadState.entries.forEach { to ->
                val shouldBeLegal = to in expectedEdges.getValue(from)
                assertEquals(
                    shouldBeLegal,
                    CloudDownloadStateMachine.isLegal(from, to),
                    "$from -> $to should be ${if (shouldBeLegal) "legal" else "illegal"}",
                )
            }
        }
    }

    @Test
    fun `every state can be cancelled, except CANCELLING itself`() {
        (CloudDownloadState.entries - CANCELLING).forEach { from ->
            assertTrue(CloudDownloadStateMachine.isLegal(from, CANCELLING), "$from -> CANCELLING")
        }
        assertFalse(CloudDownloadStateMachine.isLegal(CANCELLING, CANCELLING))
    }

    @Test
    fun `CANCELLING has no legal outgoing transition — the row is gone`() {
        CloudDownloadState.entries.forEach { to ->
            assertFalse(CloudDownloadStateMachine.isLegal(CANCELLING, to), "CANCELLING -> $to")
        }
    }

    @Test
    fun `no state legally transitions to itself`() {
        CloudDownloadState.entries.forEach { state ->
            assertFalse(CloudDownloadStateMachine.isLegal(state, state), "$state -> $state")
        }
    }

    @Test
    fun `RUNNING and VERIFYING share the same failure fork — I4's two distinct outcomes`() {
        assertTrue(CloudDownloadStateMachine.isLegal(RUNNING, RETRY_WAIT))
        assertTrue(CloudDownloadStateMachine.isLegal(RUNNING, BLOCKED))
        assertTrue(CloudDownloadStateMachine.isLegal(RUNNING, FAILED))
        assertTrue(CloudDownloadStateMachine.isLegal(VERIFYING, RETRY_WAIT))
        assertTrue(CloudDownloadStateMachine.isLegal(VERIFYING, BLOCKED))
        assertTrue(CloudDownloadStateMachine.isLegal(VERIFYING, FAILED))
    }

    @Test
    fun `RETRY_WAIT, BLOCKED, FAILED, MISSING and STALE all resolve back to QUEUED, never straight to RUNNING`() {
        listOf(RETRY_WAIT, BLOCKED, FAILED, MISSING, STALE).forEach { recoverable ->
            assertTrue(CloudDownloadStateMachine.isLegal(recoverable, QUEUED), "$recoverable -> QUEUED")
            assertFalse(CloudDownloadStateMachine.isLegal(recoverable, RUNNING), "$recoverable -> RUNNING")
        }
    }

    // ─── transition(): the legal path ──────────────────────────────────────────────

    @Test
    fun `transition returns the target state when the move is legal`() {
        assertEquals(QUEUED, CloudDownloadStateMachine.transition(PENDING, QUEUED))
        assertEquals(RUNNING, CloudDownloadStateMachine.transition(QUEUED, RUNNING))
        assertEquals(COMPLETED, CloudDownloadStateMachine.transition(VERIFYING, COMPLETED))
    }

    // ─── transition(): the illegal path ───────────────────────────────

    @Test
    fun `an illegal transition throws when throwOnIllegal is true`() {
        assertThrows(IllegalStateException::class.java) {
            CloudDownloadStateMachine.transition(PENDING, COMPLETED, throwOnIllegal = true)
        }
    }

    @Test
    fun `an illegal transition degrades to the original state when throwOnIllegal is false`() {
        val result = CloudDownloadStateMachine.transition(PENDING, COMPLETED, throwOnIllegal = false)

        assertEquals(PENDING, result)
    }

    @Test
    fun `degrading never silently accepts the requested target`() {
        val result = CloudDownloadStateMachine.transition(CANCELLING, RUNNING, throwOnIllegal = false)

        assertEquals(CANCELLING, result)
    }

    @Test
    fun `the default throwOnIllegal follows this build variant's BuildConfig-DEBUG (true for unit tests)`() {
        // testDebugUnitTest compiles against the debug variant's generated BuildConfig, so
        // BuildConfig.DEBUG is true here — exercising the default wiring, not just the parameter.
        assertThrows(IllegalStateException::class.java) {
            CloudDownloadStateMachine.transition(PENDING, COMPLETED)
        }
    }
}
