package com.theveloper.pixelplay.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.MainCoroutineExtension
import com.theveloper.pixelplay.data.sonos.model.SonosDevice
import com.theveloper.pixelplay.data.sonos.model.SonosUdn
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.session.DiscoveryUpdate
import com.theveloper.pixelplay.data.sonos.session.SpeakerDiscovery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExperimentalCoroutinesApi
@ExtendWith(MainCoroutineExtension::class)
class SonosDevicesViewModelTest {

    private val salon = SonosDevice(SonosUdn("A"), "Salón", "10.0.0.5", 1)
    private val cocina = SonosDevice(SonosUdn("B"), "Cocina", "10.0.0.6", 1)

    /** Each call to discover() runs the next script; the last one repeats. */
    private class ScriptedDiscovery(private val scripts: List<() -> Flow<DiscoveryUpdate>>) : SpeakerDiscovery {
        var started = 0
        var active = 0
        var maxActive = 0
        var cancelled = 0

        override fun discover(): Flow<DiscoveryUpdate> {
            val script = scripts[minOf(started, scripts.size - 1)]
            started++
            return flow {
                active++
                maxActive = maxOf(maxActive, active)
                try {
                    script().collect { emit(it) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled++
                    throw e
                } finally {
                    active--
                }
            }
        }
    }

    private fun script(vararg updates: DiscoveryUpdate, gapMs: Long = 500, thenHang: Boolean = false): () -> Flow<DiscoveryUpdate> = {
        flow {
            for (update in updates) {
                delay(gapMs)
                emit(update)
            }
            if (thenHang) awaitCancellation()
        }
    }

    private fun viewModel(discovery: SpeakerDiscovery) = SonosDevicesViewModel(discovery)

    @Test
    fun `it starts idle`() = runTest {
        assertThat(viewModel(ScriptedDiscovery(listOf(script()))).state.value).isEqualTo(SonosSearchState.Idle)
    }

    @Test
    fun `searching shows a growing list and finishes with the final one`() = runTest {
        val vm = viewModel(
            ScriptedDiscovery(
                listOf(script(DiscoveryUpdate.Devices(listOf(salon)), DiscoveryUpdate.Devices(listOf(cocina, salon)))),
            ),
        )

        vm.search()
        runCurrent()
        assertThat(vm.state.value).isEqualTo(SonosSearchState.Searching(emptyList()))
        advanceTimeBy(600)
        assertThat(vm.state.value).isEqualTo(SonosSearchState.Searching(listOf(salon)))
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(SonosSearchState.Finished(listOf(cocina, salon)))
    }

    @Test
    fun `finding nothing is a finished empty search and not an endless one`() = runTest {
        val vm = viewModel(ScriptedDiscovery(listOf(script())))

        vm.search()
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(SonosSearchState.Finished(emptyList()))
    }

    @Test
    fun `searching again replaces the running search`() = runTest {
        val discovery = ScriptedDiscovery(listOf(script(thenHang = true)))
        val vm = viewModel(discovery)

        vm.search()
        advanceTimeBy(100)
        vm.search()
        advanceTimeBy(100)

        assertThat(discovery.started).isEqualTo(2)
        assertThat(discovery.maxActive).isEqualTo(1)
        assertThat(discovery.cancelled).isEqualTo(1)
        assertThat(vm.state.value).isEqualTo(SonosSearchState.Searching(emptyList()))
    }

    @Test
    fun `stopping cancels the search and goes back to idle`() = runTest {
        val discovery = ScriptedDiscovery(listOf(script(thenHang = true)))
        val vm = viewModel(discovery)
        vm.search()
        advanceTimeBy(100)

        vm.stop()
        runCurrent()

        assertThat(discovery.cancelled).isEqualTo(1)
        assertThat(vm.state.value).isEqualTo(SonosSearchState.Idle)
    }

    @Test
    fun `no wifi is reported as such`() = runTest {
        val vm = viewModel(ScriptedDiscovery(listOf(script(DiscoveryUpdate.Blocked(SonosFailure.NoWifi)))))

        vm.search()
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(SonosSearchState.Blocked(BlockReason.NO_WIFI))
    }

    @Test
    fun `a denied permission is reported at once when it was not just granted`() = runTest {
        val discovery = ScriptedDiscovery(listOf(script(DiscoveryUpdate.Blocked(SonosFailure.LocalNetworkDenied))))
        val vm = viewModel(discovery)

        vm.search()
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(SonosSearchState.Blocked(BlockReason.LOCAL_NETWORK_DENIED))
        assertThat(discovery.started).isEqualTo(1)
    }

    @Test
    fun `a just granted permission is retried three times before giving up`() = runTest {
        val discovery = ScriptedDiscovery(listOf(script(DiscoveryUpdate.Blocked(SonosFailure.LocalNetworkDenied), gapMs = 0)))
        val vm = viewModel(discovery)

        val start = currentTime
        vm.search(permissionJustGranted = true)
        runCurrent()
        assertThat(vm.state.value).isEqualTo(SonosSearchState.Blocked(BlockReason.PERMISSION_PENDING))
        advanceTimeBy(2_001)
        assertThat(discovery.started).isEqualTo(2)
        assertThat(vm.state.value).isEqualTo(SonosSearchState.Blocked(BlockReason.PERMISSION_PENDING))
        advanceUntilIdle()

        assertThat(discovery.started).isEqualTo(4)
        assertThat(currentTime - start).isEqualTo(14_000L)
        assertThat(vm.state.value).isEqualTo(SonosSearchState.Blocked(BlockReason.LOCAL_NETWORK_DENIED))
    }

    @Test
    fun `a retry that works after the permission takes effect shows the speakers`() = runTest {
        val discovery = ScriptedDiscovery(
            listOf(
                script(DiscoveryUpdate.Blocked(SonosFailure.LocalNetworkDenied), gapMs = 0),
                script(DiscoveryUpdate.Devices(listOf(salon)), gapMs = 100),
            ),
        )
        val vm = viewModel(discovery)

        vm.search(permissionJustGranted = true)
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(SonosSearchState.Finished(listOf(salon)))
        assertThat(discovery.started).isEqualTo(2)
    }

    @Test
    fun `stopping during the permission wait cancels the retries`() = runTest {
        val discovery = ScriptedDiscovery(listOf(script(DiscoveryUpdate.Blocked(SonosFailure.LocalNetworkDenied), gapMs = 0)))
        val vm = viewModel(discovery)
        vm.search(permissionJustGranted = true)
        advanceTimeBy(1_000)

        vm.stop()
        advanceUntilIdle()

        assertThat(discovery.started).isEqualTo(1)
        assertThat(vm.state.value).isEqualTo(SonosSearchState.Idle)
    }

    @Test
    fun `network and timeout failures are unavailable`() = runTest {
        for (failure in listOf(SonosFailure.Network("x"), SonosFailure.Timeout, SonosFailure.NetworkUnreachable)) {
            val vm = viewModel(ScriptedDiscovery(listOf(script(DiscoveryUpdate.Blocked(failure)))))

            vm.search()
            advanceUntilIdle()

            assertThat(vm.state.value).isEqualTo(SonosSearchState.Blocked(BlockReason.UNAVAILABLE))
        }
    }

    @Test
    fun `a failure after some speakers were found keeps them`() = runTest {
        val vm = viewModel(
            ScriptedDiscovery(
                listOf(script(DiscoveryUpdate.Devices(listOf(salon)), DiscoveryUpdate.Blocked(SonosFailure.Network("x")))),
            ),
        )

        vm.search()
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(SonosSearchState.Finished(listOf(salon)))
    }
}
