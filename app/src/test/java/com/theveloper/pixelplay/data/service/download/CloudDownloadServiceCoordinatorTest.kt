package com.theveloper.pixelplay.data.service.download

import com.theveloper.pixelplay.data.download.engine.CloudDownloadEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudDownloadServiceCoordinatorTest {

    private lateinit var engine: CloudDownloadEngine
    private lateinit var coordinator: CloudDownloadServiceCoordinator

    @BeforeEach
    fun setUp() {
        engine = mockk(relaxed = true)
        coordinator = CloudDownloadServiceCoordinator(engine)
    }

    @Test
    fun `triggerPass runs the engine and reports idle when nothing is left`() = runTest {
        coEvery { engine.hasActiveWork() } returns false
        var idleCalled = false

        coordinator.triggerPass(this) { idleCalled = true }
        advanceUntilIdle()

        coVerify(exactly = 1) { engine.start(any()) }
        assertTrue(idleCalled)
    }

    @Test
    fun `triggerPass does not report idle while there's still active work`() = runTest {
        coEvery { engine.hasActiveWork() } returns true
        var idleCalled = false

        coordinator.triggerPass(this) { idleCalled = true }
        advanceUntilIdle()

        assertFalse(idleCalled)
    }

    /**
     * Two `onStartCommand` calls close together must not run two overlapping engine passes —
     * see [CloudDownloadServiceCoordinator.triggerPass]'s own doc for why that's unsafe. The
     * gate below holds the first pass mid-flight so the second `triggerPass` call genuinely
     * lands while one is still running, rather than merely happening "first" in source order.
     */
    @Test
    fun `a second trigger while a pass is in flight does not start a second one`() = runTest {
        val startGate = CompletableDeferred<Unit>()
        coEvery { engine.start(any()) } coAnswers { startGate.await() }
        coEvery { engine.hasActiveWork() } returns false

        coordinator.triggerPass(this) {}
        coordinator.triggerPass(this) {} // lands while the first is suspended on startGate

        startGate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { engine.start(any()) }
    }

    @Test
    fun `a trigger after the previous pass finished starts a fresh one`() = runTest {
        coEvery { engine.hasActiveWork() } returns false

        coordinator.triggerPass(this) {}
        advanceUntilIdle()
        coordinator.triggerPass(this) {}
        advanceUntilIdle()

        coVerify(exactly = 2) { engine.start(any()) }
    }
}
