package com.theveloper.pixelplay.data.sonos.net

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.theveloper.pixelplay.data.sonos.SonosFixtures
import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HttpSonosTransportTest {

    private lateinit var server: HttpServer
    private val baseClient = OkHttpClient()
    private val hits = AtomicInteger()

    private class FakeLan : LanNetwork {
        override val socketFactory: SocketFactory = SocketFactory.getDefault()
        override fun bind(socket: DatagramSocket) = Unit
    }

    private fun transport(wifi: Boolean = true, port: Int = server.address.port) = HttpSonosTransport(
        network = object : LanNetworkProvider {
            override fun wifi(): LanNetwork? = if (wifi) FakeLan() else null
        },
        baseClient = baseClient,
        port = port,
    )

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
        baseClient.dispatcher.executorService.shutdown()
    }

    private fun answer(path: String, status: Int, body: String) {
        server.createContext(path) { exchange ->
            hits.incrementAndGet()
            reply(exchange, status, body)
        }
    }

    @Test
    fun `a successful answer is returned as values`() = runBlocking {
        answer(SonosService.AV_TRANSPORT.controlPath, 200, SonosFixtures.soapResponseBody("AVTransport.GetPositionInfo.ok"))

        val result = transport().call("127.0.0.1", SonosService.AV_TRANSPORT, "GetPositionInfo", listOf("InstanceID" to "0"))

        val values = (result as SonosResult.Ok<*>).value as Map<*, *>
        assertThat(values["Track"]).isEqualTo("1")
    }

    @Test
    fun `the request carries the SOAP action header and the exact envelope`() = runBlocking {
        var soapAction: String? = null
        var body: String? = null
        server.createContext(SonosService.AV_TRANSPORT.controlPath) { exchange ->
            soapAction = exchange.requestHeaders.getFirst("SOAPACTION")
            body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            reply(exchange, 200, SonosFixtures.soapResponseBody("AVTransport.Next.ok"))
        }

        transport().call("127.0.0.1", SonosService.AV_TRANSPORT, "Next", listOf("InstanceID" to "0"))

        assertThat(soapAction).isEqualTo("\"urn:schemas-upnp-org:service:AVTransport:1#Next\"")
        assertThat(body).isEqualTo(SonosFixtures.soapRequestBody("AVTransport.Next.ok"))
    }

    @Test
    fun `a fault is returned as a typed UPnP failure`() = runBlocking {
        answer(SonosService.AV_TRANSPORT.controlPath, 500, SonosFixtures.soapResponseBody("AVTransport.Next.err"))

        val result = transport().call("127.0.0.1", SonosService.AV_TRANSPORT, "Next", listOf("InstanceID" to "0"))

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.Upnp("Next", UpnpErrorKind.NoMoreTracks)))
    }

    @Test
    fun `an HTML page instead of SOAP is a malformed response`() = runBlocking {
        answer(SonosService.AV_TRANSPORT.controlPath, 200, "<html><body>captive portal</body></html>")

        val result = transport().call("127.0.0.1", SonosService.AV_TRANSPORT, "Play")

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.MalformedResponse))
    }

    @Test
    fun `an empty error body is a malformed response`() = runBlocking {
        answer(SonosService.AV_TRANSPORT.controlPath, 503, "")

        val result = transport().call("127.0.0.1", SonosService.AV_TRANSPORT, "Play")

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.MalformedResponse))
    }

    @Test
    fun `a speaker that never answers times out close to the requested timeout`() = runBlocking {
        server.createContext(SonosService.AV_TRANSPORT.controlPath) { exchange ->
            hits.incrementAndGet()
            Thread.sleep(3_000)
            exchange.close()
        }

        val started = System.nanoTime()
        val result = transport().call("127.0.0.1", SonosService.AV_TRANSPORT, "Play", timeoutMs = 300)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.Timeout))
        assertThat(elapsedMs).isLessThan(2_000)
    }

    @Test
    fun `a closed port is a refused connection`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }

        val result = transport(port = closedPort).call("127.0.0.1", SonosService.AV_TRANSPORT, "Play")

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.ConnectionRefused))
    }

    @Test
    fun `without wifi nothing is sent`() = runBlocking {
        answer(SonosService.AV_TRANSPORT.controlPath, 200, SonosFixtures.soapResponseBody("AVTransport.Next.ok"))

        val result = transport(wifi = false).call("127.0.0.1", SonosService.AV_TRANSPORT, "Next")

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.NoWifi))
        assertThat(hits.get()).isEqualTo(0)
    }

    @Test
    fun `cancelling a hung call aborts it promptly and frees the connection`() = runBlocking {
        val arrived = CountDownLatch(1)
        server.createContext(SonosService.AV_TRANSPORT.controlPath) { exchange ->
            arrived.countDown()
            Thread.sleep(5_000)
            exchange.close()
        }
        val call = async(Dispatchers.Default) {
            transport().call("127.0.0.1", SonosService.AV_TRANSPORT, "Play", timeoutMs = 10_000)
        }
        assertThat(arrived.await(2, TimeUnit.SECONDS)).isTrue()

        val started = System.nanoTime()
        call.cancel()
        call.join()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertThat(elapsedMs).isLessThan(1_000)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (baseClient.dispatcher.runningCallsCount() > 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertThat(baseClient.dispatcher.runningCallsCount()).isEqualTo(0)
    }
}
