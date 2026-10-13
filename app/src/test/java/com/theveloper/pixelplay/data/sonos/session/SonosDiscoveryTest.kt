package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.SonosFixtures
import com.theveloper.pixelplay.data.sonos.model.SonosDevice
import com.theveloper.pixelplay.data.sonos.model.SonosUdn
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.SonosTransport
import com.theveloper.pixelplay.data.sonos.net.SsdpEvent
import com.theveloper.pixelplay.data.sonos.protocol.SoapResponse
import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import com.theveloper.pixelplay.data.sonos.protocol.SsdpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SonosDiscoveryTest {

    private fun ssdp(host: String, household: String? = "HH1", udn: String = "RINCON_$host") =
        SsdpEvent.Response(SsdpResponse(host, SonosUdn(udn), household, null))

    private fun topology(vararg groups: Pair<String, List<Triple<String, String, String>>>): String =
        groups.joinToString(prefix = "<ZoneGroupState><ZoneGroups>", postfix = "</ZoneGroups></ZoneGroupState>", separator = "") { (coordinator, members) ->
            val inner = members.joinToString("") { (udn, room, host) ->
                """<ZoneGroupMember UUID="$udn" Location="http://$host:1400/xml/device_description.xml" ZoneName="$room"/>"""
            }
            """<ZoneGroup Coordinator="$coordinator">$inner</ZoneGroup>"""
        }

    private class FakeTransport(private val behavior: suspend (host: String) -> SonosResult<Map<String, String>>) : SonosTransport {
        val queried = mutableListOf<String>()
        var cancelled = 0

        override suspend fun call(
            host: String,
            service: SonosService,
            action: String,
            arguments: List<Pair<String, String>>,
            timeoutMs: Long,
        ): SonosResult<Map<String, String>> {
            assertThat(service).isEqualTo(SonosService.ZONE_GROUP_TOPOLOGY)
            assertThat(action).isEqualTo("GetZoneGroupState")
            queried += host
            try {
                return behavior(host)
            } catch (e: CancellationException) {
                cancelled++
                throw e
            }
        }
    }

    private fun ok(xml: String) = SonosResult.Ok(mapOf("ZoneGroupState" to xml))

    private fun searchOf(vararg events: Pair<Long, SsdpEvent>, endAfterMs: Long = 3_500): () -> Flow<SsdpEvent> = {
        flow {
            var now = 0L
            for ((at, event) in events) {
                delay(at - now)
                now = at
                emit(event)
            }
            delay(endAfterMs - now)
        }
    }

    private fun discoveryOf(search: () -> Flow<SsdpEvent>, transport: FakeTransport) =
        SonosDiscovery(search, transport, topologyTimeoutMs = 2_000, overallTimeoutMs = 6_000)

    private suspend fun Flow<DiscoveryUpdate>.lastDevices(): List<SonosDevice> =
        toList().filterIsInstance<DiscoveryUpdate.Devices>().lastOrNull()?.devices.orEmpty()

    @Test
    fun `the captured topology yields the Salón speaker`() = runTest {
        val captured = (SoapResponse.parse(SonosFixtures.soapResponseBody("ZoneGroupTopology.GetZoneGroupState.ok")) as SoapResponse.Success)
            .values.getValue("ZoneGroupState")
        val transport = FakeTransport { ok(captured) }

        val devices = discoveryOf(searchOf(650L to ssdp("192.168.1.10")), transport).discover().lastDevices()

        assertThat(devices).containsExactly(
            SonosDevice(SonosUdn("RINCON_000E58000001001400"), "Salón", "192.168.1.10", roomCount = 1),
        )
    }

    @Test
    fun `two grouped rooms are a single row`() = runTest {
        val xml = topology("RINCON_A" to listOf(Triple("RINCON_A", "Salón", "10.0.0.5"), Triple("RINCON_B", "Cocina", "10.0.0.6")))
        val transport = FakeTransport { ok(xml) }

        val devices = discoveryOf(searchOf(100L to ssdp("10.0.0.5"), 200L to ssdp("10.0.0.6")), transport).discover().lastDevices()

        assertThat(devices.map { it.name to it.roomCount }).containsExactly("Cocina + Salón" to 2)
    }

    @Test
    fun `speakers of one household cost a single topology query`() = runTest {
        val xml = topology("RINCON_A" to listOf(Triple("RINCON_A", "Salón", "10.0.0.5")), "RINCON_B" to listOf(Triple("RINCON_B", "Cocina", "10.0.0.6")))
        val transport = FakeTransport { ok(xml) }

        val devices = discoveryOf(
            searchOf(100L to ssdp("10.0.0.5"), 150L to ssdp("10.0.0.6"), 300L to ssdp("10.0.0.5")),
            transport,
        ).discover().lastDevices()

        assertThat(transport.queried).containsExactly("10.0.0.5")
        assertThat(devices.map { it.name }).containsExactly("Cocina", "Salón").inOrder()
    }

    @Test
    fun `two households on the same network both appear`() = runTest {
        val transport = FakeTransport { host ->
            when (host) {
                "10.0.0.5" -> ok(topology("RINCON_A" to listOf(Triple("RINCON_A", "Salón", "10.0.0.5"))))
                else -> ok(topology("RINCON_Z" to listOf(Triple("RINCON_Z", "Oficina", "10.0.0.9"))))
            }
        }

        val devices = discoveryOf(
            searchOf(100L to ssdp("10.0.0.5", household = "HH1"), 200L to ssdp("10.0.0.9", household = "HH2")),
            transport,
        ).discover().lastDevices()

        assertThat(devices.map { it.name }).containsExactly("Oficina", "Salón").inOrder()
    }

    @Test
    fun `the list is the same whatever order the speakers answer in`() = runTest {
        val behavior: suspend (String) -> SonosResult<Map<String, String>> = { host ->
            when (host) {
                "10.0.0.5" -> ok(topology("RINCON_A" to listOf(Triple("RINCON_A", "Salón", "10.0.0.5"))))
                else -> ok(topology("RINCON_Z" to listOf(Triple("RINCON_Z", "Oficina", "10.0.0.9"))))
            }
        }
        val first = discoveryOf(
            searchOf(100L to ssdp("10.0.0.5", household = "HH1"), 200L to ssdp("10.0.0.9", household = "HH2")),
            FakeTransport(behavior),
        ).discover().lastDevices()
        val second = discoveryOf(
            searchOf(100L to ssdp("10.0.0.9", household = "HH2"), 200L to ssdp("10.0.0.5", household = "HH1")),
            FakeTransport(behavior),
        ).discover().lastDevices()

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `when the first responder does not answer another speaker of the household is tried`() = runTest {
        val xml = topology("RINCON_A" to listOf(Triple("RINCON_A", "Salón", "10.0.0.5")))
        val transport = FakeTransport { host ->
            if (host == "10.0.0.6") {
                delay(2_000)
                SonosResult.Err(SonosFailure.Timeout)
            } else {
                ok(xml)
            }
        }

        val devices = discoveryOf(searchOf(100L to ssdp("10.0.0.6"), 500L to ssdp("10.0.0.5")), transport).discover().lastDevices()

        assertThat(transport.queried).containsExactly("10.0.0.6", "10.0.0.5").inOrder()
        assertThat(devices.map { it.name }).containsExactly("Salón")
    }

    @Test
    fun `a response that is not a topology is skipped`() = runTest {
        val transport = FakeTransport { host ->
            if (host == "10.0.0.6") ok("garbage") else ok(topology("RINCON_A" to listOf(Triple("RINCON_A", "Salón", "10.0.0.5"))))
        }

        val devices = discoveryOf(searchOf(100L to ssdp("10.0.0.6"), 200L to ssdp("10.0.0.5")), transport).discover().lastDevices()

        assertThat(devices.map { it.name }).containsExactly("Salón")
    }

    @Test
    fun `nobody answering the topology ends the search without devices inside the overall ceiling`() = runTest {
        val transport = FakeTransport { awaitCancellation() }
        val search: () -> Flow<SsdpEvent> = { flow { emit(ssdp("10.0.0.5")); awaitCancellation() } }

        val updates = discoveryOf(search, transport).discover().toList()

        assertThat(updates.filterIsInstance<DiscoveryUpdate.Devices>()).isEmpty()
        assertThat(currentTime).isEqualTo(6_000L)
        assertThat(transport.cancelled).isEqualTo(1)
    }

    @Test
    fun `a permission failure of the search is reported as blocked`() = runTest {
        val search: () -> Flow<SsdpEvent> = { flow { emit(SsdpEvent.Failed(SonosFailure.LocalNetworkDenied)) } }

        val updates = discoveryOf(search, FakeTransport { error("not queried") }).discover().toList()

        assertThat(updates).containsExactly(DiscoveryUpdate.Blocked(SonosFailure.LocalNetworkDenied))
    }

    @Test
    fun `devices found before a later search failure are kept and the failure is reported`() = runTest {
        val xml = topology("RINCON_A" to listOf(Triple("RINCON_A", "Salón", "10.0.0.5")))
        val search: () -> Flow<SsdpEvent> = {
            flow {
                emit(ssdp("10.0.0.5"))
                delay(500)
                emit(SsdpEvent.Failed(SonosFailure.Network("socket closed")))
            }
        }

        val updates = discoveryOf(search, FakeTransport { ok(xml) }).discover().toList()

        assertThat(updates.first()).isInstanceOf(DiscoveryUpdate.Devices::class.java)
        assertThat(updates.last()).isEqualTo(DiscoveryUpdate.Blocked(SonosFailure.Network("socket closed")))
    }

    @Test
    fun `devices are emitted as soon as they are known and not at the end`() = runTest {
        val xml = topology("RINCON_A" to listOf(Triple("RINCON_A", "Salón", "10.0.0.5")))
        val emittedAt = mutableListOf<Long>()
        val job = launch {
            discoveryOf(searchOf(650L to ssdp("10.0.0.5")), FakeTransport { ok(xml) }).discover().collect { emittedAt += currentTime }
        }

        job.join()

        assertThat(emittedAt).containsExactly(650L)
    }

    @Test
    fun `cancelling the collector cancels pending queries`() = runTest {
        val transport = FakeTransport { awaitCancellation() }
        val search: () -> Flow<SsdpEvent> = { flow { emit(ssdp("10.0.0.5")); awaitCancellation() } }
        val job: Job = launch { discoveryOf(search, transport).discover().toList() }

        advanceTimeBy(1_000)
        runCurrent()
        assertThat(transport.queried).containsExactly("10.0.0.5")
        job.cancel()
        runCurrent()

        assertThat(transport.cancelled).isEqualTo(1)
    }
}
