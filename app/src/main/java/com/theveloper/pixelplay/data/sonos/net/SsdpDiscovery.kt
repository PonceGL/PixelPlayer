package com.theveloper.pixelplay.data.sonos.net

import com.theveloper.pixelplay.data.sonos.protocol.Ssdp
import com.theveloper.pixelplay.data.sonos.protocol.SsdpResponse
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

internal sealed interface SsdpEvent {
    data class Response(val response: SsdpResponse) : SsdpEvent
    data class Failed(val failure: SonosFailure) : SsdpEvent
}

/**
 * Looks for Sonos players with SSDP over the Wi-Fi network. Collecting the flow runs one search;
 * cancelling the collector closes the socket.
 */
internal class SsdpDiscovery(
    private val openSocket: (LanNetwork) -> SsdpSocket,
    private val network: LanNetworkProvider,
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {

    fun search(
        listenMs: Long = DEFAULT_LISTEN_MS,
        sendAtMs: List<Long> = DEFAULT_SEND_AT_MS,
    ): Flow<SsdpEvent> = flow {
        val lan = network.wifi()
        if (lan == null) {
            emit(SsdpEvent.Failed(SonosFailure.NoWifi))
            return@flow
        }
        val socket = try {
            openSocket(lan)
        } catch (e: IOException) {
            emit(SsdpEvent.Failed(NetFailureClassifier.classify(e)))
            return@flow
        }
        socket.use { listen(it, listenMs, sendAtMs) }
    }

    private suspend fun FlowCollector<SsdpEvent>.listen(
        socket: SsdpSocket,
        listenMs: Long,
        sendAtMs: List<Long>,
    ) {
        val request = Ssdp.searchRequest().toByteArray()
        val seen = mutableSetOf<String>()
        val start = elapsedRealtimeMs()
        var sent = 0
        try {
            while (true) {
                val elapsed = elapsedRealtimeMs() - start
                if (elapsed >= listenMs) return
                val nextSendAt = sendAtMs.getOrNull(sent)
                if (nextSendAt != null && elapsed >= nextSendAt) {
                    socket.send(request)
                    sent++
                    continue
                }
                val untilNextSend = nextSendAt?.let { it - elapsed } ?: Long.MAX_VALUE
                val packet = socket.receive(minOf(RECEIVE_SLICE_MS, untilNextSend, listenMs - elapsed)) ?: continue
                val response = Ssdp.parseResponse(packet.payload.toString(Charsets.UTF_8), packet.sourceHost)
                if (response != null && seen.add(response.host)) emit(SsdpEvent.Response(response))
            }
        } catch (e: IOException) {
            emit(SsdpEvent.Failed(NetFailureClassifier.classify(e)))
        }
    }

    private companion object {
        const val DEFAULT_LISTEN_MS = 3_500L
        val DEFAULT_SEND_AT_MS = listOf(0L, 1_000L, 2_000L)

        /** Upper bound of a blocking read, so cancellation never waits long for a quiet socket. */
        const val RECEIVE_SLICE_MS = 400L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
