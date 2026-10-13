package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.sonos.model.SonosDevice
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.session.DiscoveryUpdate
import com.theveloper.pixelplay.data.sonos.session.SpeakerDiscovery
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Runs the speaker search while the device sheet is open and exposes it as a single screen state. */
@HiltViewModel
internal class SonosDevicesViewModel @Inject constructor(
    private val discovery: SpeakerDiscovery,
) : ViewModel() {

    private val _state = MutableStateFlow<SonosSearchState>(SonosSearchState.Idle)
    val state: StateFlow<SonosSearchState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /**
     * Starts a search, replacing any running one. [permissionJustGranted] tells that the local network
     * permission was granted a moment ago: the system can take a while to apply it, so a denial is
     * retried a few times instead of being reported as a refusal.
     */
    fun search(permissionJustGranted: Boolean = false) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(permissionJustGranted) }
    }

    fun stop() {
        searchJob?.cancel()
        searchJob = null
        _state.value = SonosSearchState.Idle
    }

    private suspend fun runSearch(permissionJustGranted: Boolean) {
        _state.value = SonosSearchState.Searching(emptyList())
        var retries = 0
        while (true) {
            val outcome = searchOnce()
            val failure = outcome.failure
            if (failure == null) {
                _state.value = SonosSearchState.Finished(outcome.devices)
                return
            }
            if (outcome.devices.isNotEmpty()) {
                _state.value = SonosSearchState.Finished(outcome.devices)
                return
            }
            if (failure == SonosFailure.LocalNetworkDenied && permissionJustGranted && retries < PERMISSION_RETRY_DELAYS_MS.size) {
                _state.value = SonosSearchState.Blocked(BlockReason.PERMISSION_PENDING)
                delay(PERMISSION_RETRY_DELAYS_MS[retries++])
                continue
            }
            _state.value = SonosSearchState.Blocked(reasonOf(failure))
            return
        }
    }

    private class Outcome(val devices: List<SonosDevice>, val failure: SonosFailure?)

    private suspend fun searchOnce(): Outcome {
        var devices = emptyList<SonosDevice>()
        var failure: SonosFailure? = null
        discovery.discover().collect { update ->
            when (update) {
                is DiscoveryUpdate.Devices -> {
                    devices = update.devices
                    _state.value = SonosSearchState.Searching(devices)
                }
                is DiscoveryUpdate.Blocked -> failure = update.failure
            }
        }
        return Outcome(devices, failure)
    }

    private fun reasonOf(failure: SonosFailure): BlockReason = when (failure) {
        SonosFailure.NoWifi -> BlockReason.NO_WIFI
        SonosFailure.LocalNetworkDenied -> BlockReason.LOCAL_NETWORK_DENIED
        else -> BlockReason.UNAVAILABLE
    }

    private companion object {
        val PERMISSION_RETRY_DELAYS_MS = longArrayOf(2_000, 4_000, 8_000)
    }
}
