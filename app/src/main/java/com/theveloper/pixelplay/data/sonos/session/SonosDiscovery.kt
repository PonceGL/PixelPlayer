package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.model.SonosDevice
import com.theveloper.pixelplay.data.sonos.model.SonosGroup
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.SonosTransport
import com.theveloper.pixelplay.data.sonos.net.SsdpEvent
import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import com.theveloper.pixelplay.data.sonos.protocol.ZoneGroupTopologyParser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface DiscoveryUpdate {
    data class Devices(val devices: List<SonosDevice>) : DiscoveryUpdate
    data class Blocked(val failure: SonosFailure) : DiscoveryUpdate
}

internal fun interface SpeakerDiscovery {
    fun discover(): Flow<DiscoveryUpdate>
}

/**
 * Finds the speakers on the network and lists them as groups. Speakers answering the search are
 * asked for the household topology once per household; the flow completes when the search ends,
 * every query has finished, or [overallTimeoutMs] has passed.
 */
internal class SonosDiscovery(
    private val searchSpeakers: () -> Flow<SsdpEvent>,
    private val transport: SonosTransport,
    private val topologyTimeoutMs: Long = 2_000,
    private val overallTimeoutMs: Long = 6_000,
) : SpeakerDiscovery {

    private sealed interface Step {
        data class Search(val event: SsdpEvent) : Step
        data object SearchEnded : Step
        data class Topology(val household: String, val groups: List<SonosGroup>?) : Step
    }

    private class Household {
        val pendingHosts = ArrayDeque<String>()
        val knownHosts = mutableSetOf<String>()
        var resolved = false
        var querying = false
        var devices: List<SonosDevice> = emptyList()
    }

    override fun discover(): Flow<DiscoveryUpdate> = channelFlow {
        withTimeoutOrNull(overallTimeoutMs) {
            coroutineScope {
                val steps = Channel<Step>(Channel.UNLIMITED)
                val households = mutableMapOf<String, Household>()
                var searching = true
                var running = 0

                fun query(key: String, household: Household) {
                    val host = household.pendingHosts.removeFirstOrNull() ?: return
                    household.querying = true
                    running++
                    launch { steps.send(Step.Topology(key, topologyOf(host))) }
                }

                launch {
                    searchSpeakers().collect { steps.send(Step.Search(it)) }
                    steps.send(Step.SearchEnded)
                }

                while (searching || running > 0) {
                    when (val step = steps.receive()) {
                        Step.SearchEnded -> searching = false
                        is Step.Search -> when (val event = step.event) {
                            is SsdpEvent.Failed -> {
                                send(DiscoveryUpdate.Blocked(event.failure))
                                coroutineContext.cancelChildren()
                                return@coroutineScope
                            }
                            is SsdpEvent.Response -> {
                                val key = event.response.household ?: event.response.host
                                val household = households.getOrPut(key) { Household() }
                                if (!household.resolved && household.knownHosts.add(event.response.host)) {
                                    household.pendingHosts.addLast(event.response.host)
                                    if (!household.querying) query(key, household)
                                }
                            }
                        }
                        is Step.Topology -> {
                            running--
                            val household = households.getValue(step.household)
                            household.querying = false
                            if (step.groups != null) {
                                household.resolved = true
                                household.pendingHosts.clear()
                                household.devices = SonosDeviceMapper.fromGroups(step.groups)
                                send(DiscoveryUpdate.Devices(SonosDeviceMapper.merge(households.values.flatMap { it.devices })))
                            } else {
                                query(step.household, household)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun topologyOf(host: String): List<SonosGroup>? {
        val result = transport.call(
            host = host,
            service = SonosService.ZONE_GROUP_TOPOLOGY,
            action = "GetZoneGroupState",
            timeoutMs = topologyTimeoutMs,
        )
        val xml = (result as? SonosResult.Ok)?.value?.get("ZoneGroupState") ?: return null
        return ZoneGroupTopologyParser.parse(xml).takeIf { it.isNotEmpty() }
    }
}
