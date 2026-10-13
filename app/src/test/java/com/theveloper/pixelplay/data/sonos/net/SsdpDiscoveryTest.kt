package com.theveloper.pixelplay.data.sonos.net

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.SonosFixtures
import java.io.IOException
import java.net.DatagramSocket
import javax.net.SocketFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SsdpDiscoveryTest {

    private class FakeLan : LanNetwork {
        override val socketFactory: SocketFactory = SocketFactory.getDefault()
        override fun bind(socket: DatagramSocket) = Unit
    }

    private class FakeProvider(private val lan: LanNetwork?) : LanNetworkProvider {
        override fun wifi(): LanNetwork? = lan
    }

    /** Delivers packets at fixed virtual times and records what was sent and when. */
    private class FakeSocket(
        private val scope: TestScope,
        private val packets: List<Pair<Long, SsdpPacket>> = emptyList(),
        private val sendError: IOException? = null,
        private val receiveError: IOException? = null,
    ) : SsdpSocket {
        val sentAt = mutableListOf<Long>()
        var closed = false
        private val pending = packets.toMutableList()

        override suspend fun send(payload: ByteArray) {
            sendError?.let { throw it }
            sentAt += scope.currentTime
        }

        override suspend fun receive(timeoutMs: Long): SsdpPacket? {
            receiveError?.let { throw it }
            val next = pending.firstOrNull()
            val now = scope.currentTime
            if (next != null && next.first <= now) return pending.removeAt(0).second
            val wait = if (next == null) timeoutMs else minOf(timeoutMs, next.first - now)
            delay(wait)
            val after = scope.currentTime
            return if (next != null && next.first <= after) pending.removeAt(0).second else null
        }

        override fun close() {
            closed = true
        }
    }

    private fun speaker(host: String = "192.168.1.10", payload: String = SonosFixtures.ssdpResponse()) =
        SsdpPacket(host, payload.toByteArray())

    private fun TestScope.discovery(socket: FakeSocket, lan: LanNetwork? = FakeLan(), opened: MutableList<LanNetwork>? = null) =
        SsdpDiscovery(
            openSocket = { net -> opened?.add(net); socket },
            network = FakeProvider(lan),
            elapsedRealtimeMs = { currentTime },
        )

    @Test
    fun `emits the speaker that answers and closes the socket when the search ends`() = runTest {
        val socket = FakeSocket(this, listOf(650L to speaker()))

        val events = discovery(socket).search().toList()

        val response = (events.single() as SsdpEvent.Response).response
        assertThat(response.host).isEqualTo("192.168.1.10")
        assertThat(socket.closed).isTrue()
    }

    @Test
    fun `the same host answering to every send is reported once`() = runTest {
        val socket = FakeSocket(this, listOf(600L to speaker(), 1_600L to speaker(), 2_100L to speaker()))

        val events = discovery(socket).search().toList()

        assertThat(events).hasSize(1)
    }

    @Test
    fun `two different speakers are both reported`() = runTest {
        val socket = FakeSocket(this, listOf(600L to speaker("192.168.1.10"), 700L to speaker("192.168.1.11")))

        val hosts = discovery(socket).search().toList().map { (it as SsdpEvent.Response).response.host }

        assertThat(hosts).containsExactly("192.168.1.10", "192.168.1.11")
    }

    @Test
    fun `sends three searches one second apart and stops listening after 3500 ms`() = runTest {
        val socket = FakeSocket(this)

        discovery(socket).search().toList()

        assertThat(socket.sentAt).containsExactly(0L, 1_000L, 2_000L).inOrder()
        assertThat(currentTime).isEqualTo(3_500L)
    }

    @Test
    fun `garbage and replies from other devices are ignored`() = runTest {
        val socket = FakeSocket(
            this,
            listOf(
                100L to speaker(payload = ""),
                200L to speaker(payload = "\u0000\u0001garbage"),
                300L to speaker(payload = SonosFixtures.ssdpResponse().replace("ZonePlayer", "MediaRenderer")),
                400L to speaker(payload = "x".repeat(200_000)),
            ),
        )

        val events = discovery(socket).search().toList()

        assertThat(events).isEmpty()
    }

    @Test
    fun `without wifi it reports NoWifi and opens no socket`() = runTest {
        val opened = mutableListOf<LanNetwork>()
        val socket = FakeSocket(this)

        val events = discovery(socket, lan = null, opened = opened).search().toList()

        assertThat(events).containsExactly(SsdpEvent.Failed(SonosFailure.NoWifi))
        assertThat(opened).isEmpty()
        assertThat(socket.sentAt).isEmpty()
    }

    @Test
    fun `a permission failure when sending ends the search with LocalNetworkDenied`() = runTest {
        val socket = FakeSocket(this, sendError = IOException("sendto failed: EPERM (Operation not permitted)"))

        val events = discovery(socket).search().toList()

        assertThat(events).containsExactly(SsdpEvent.Failed(SonosFailure.LocalNetworkDenied))
        assertThat(socket.closed).isTrue()
        assertThat(currentTime).isEqualTo(0L)
    }

    @Test
    fun `a failure while receiving ends the search with a failure`() = runTest {
        val socket = FakeSocket(this, receiveError = IOException("socket closed"))

        val events = discovery(socket).search().toList()

        assertThat(events).containsExactly(SsdpEvent.Failed(SonosFailure.Network("socket closed")))
        assertThat(socket.closed).isTrue()
    }

    @Test
    fun `a socket that cannot be opened is a failure and not a crash`() = runTest {
        val discovery = SsdpDiscovery(
            openSocket = { throw IOException("EPERM (Operation not permitted)") },
            network = FakeProvider(FakeLan()),
            elapsedRealtimeMs = { currentTime },
        )

        assertThat(discovery.search().toList()).containsExactly(SsdpEvent.Failed(SonosFailure.LocalNetworkDenied))
    }

    @Test
    fun `cancelling the collector halfway closes the socket`() = runTest {
        val socket = FakeSocket(this)
        val job = launch { discovery(socket).search().toList() }

        advanceTimeBy(1_200)
        runCurrent()
        assertThat(socket.closed).isFalse()
        job.cancel()
        runCurrent()

        assertThat(socket.closed).isTrue()
        assertThat(socket.sentAt).containsExactly(0L, 1_000L).inOrder()
    }

    @Test
    fun `stopping after the first speaker closes the socket without waiting for the rest`() = runTest {
        val socket = FakeSocket(this, listOf(650L to speaker()))

        discovery(socket).search().first()

        assertThat(socket.closed).isTrue()
        assertThat(currentTime).isEqualTo(650L)
    }
}
