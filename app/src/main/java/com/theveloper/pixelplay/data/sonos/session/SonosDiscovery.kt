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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
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
    private val io: CoroutineDispatcher,
    private val topologyTimeoutMs: Long = 2_000,
    private val overallTimeoutMs: Long = 6_000,
) : SpeakerDiscovery {

    private sealed interface Step {
        data class Search(val event: SsdpEvent) : Step
        data object SearchEnded : Step
        data class Topology(val household: String, val outcome: TopologyOutcome) : Step
    }

    private sealed interface TopologyOutcome {
        data class Found(val groups: List<SonosGroup>) : TopologyOutcome
        data class Failed(val failure: SonosFailure) : TopologyOutcome
    }

    private class Household {
        val pendingHosts = ArrayDeque<String>()
        val knownHosts = mutableSetOf<String>()
        var resolved = false
        var lastFailure: SonosFailure? = null
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
                            val outcome = step.outcome
                            if (outcome is TopologyOutcome.Found) {
                                household.resolved = true
                                household.pendingHosts.clear()
                                household.devices = SonosDeviceMapper.fromGroups(outcome.groups)
                                send(DiscoveryUpdate.Devices(SonosDeviceMapper.merge(households.values.flatMap { it.devices })))
                            } else if (outcome is TopologyOutcome.Failed) {
                                household.lastFailure = outcome.failure
                                query(step.household, household)
                            }
                        }
                    }
                }
                // Speakers answered the search but none could be asked: that is a failure, not an empty network.
                val unresolved = households.values.filter { !it.resolved }
                if (households.isNotEmpty() && households.values.none { it.resolved }) {
                    unresolved.firstNotNullOfOrNull { it.lastFailure }?.let { send(DiscoveryUpdate.Blocked(it)) }
                }
            }
        }
    }.flowOn(io)

    private suspend fun topologyOf(host: String): TopologyOutcome {
        val result = transport.call(
            host = host,
            service = SonosService.ZONE_GROUP_TOPOLOGY,
            action = "GetZoneGroupState",
            timeoutMs = topologyTimeoutMs,
        )
        if (result is SonosResult.Err) return TopologyOutcome.Failed(result.failure)
        val xml = (result as SonosResult.Ok).value["ZoneGroupState"]
        val groups = xml?.let(ZoneGroupTopologyParser::parse).orEmpty()
        return if (groups.isEmpty()) TopologyOutcome.Failed(SonosFailure.MalformedResponse) else TopologyOutcome.Found(groups)
    }
}
