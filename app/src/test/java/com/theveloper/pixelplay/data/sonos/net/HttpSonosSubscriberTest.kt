package com.theveloper.pixelplay.data.sonos.net

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HttpSonosSubscriberTest {

    private class Seen(val method: String, val path: String, val headers: Map<String, String>)

    private lateinit var server: HttpServer
    private val baseClient = OkHttpClient()
    private val seen = CopyOnWriteArrayList<Seen>()
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sonos-io-test") }
    private val wifiLookupThreads = CopyOnWriteArrayList<String>()

    private class FakeLan : LanNetwork {
        override val socketFactory: SocketFactory = SocketFactory.getDefault()
        override fun bind(socket: DatagramSocket) = Unit
    }

    private fun subscriber(wifi: Boolean = true) = HttpSonosSubscriber(
        network = object : LanNetworkProvider {
            override fun wifi(): LanNetwork? {
                wifiLookupThreads += Thread.currentThread().name
                return if (wifi) FakeLan() else null
            }
        },
        baseClient = baseClient,
        io = ioExecutor.asCoroutineDispatcher(),
        port = server.address.port,
    )

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
        baseClient.dispatcher.executorService.shutdown()
        ioExecutor.shutdownNow()
    }

    @Volatile private var answer: () -> Triple<Int, Map<String, String>, String> = { Triple(200, emptyMap(), "") }

    private fun respond(status: Int, headers: Map<String, String> = emptyMap(), body: String = "") {
        answer = { Triple(status, headers, body) }
        if (!contextInstalled) {
            contextInstalled = true
            server.createContext("/") { exchange ->
                seen += Seen(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    headers = exchange.requestHeaders.mapValues { it.value.first() },
                )
                val (code, responseHeaders, responseBody) = answer()
                reply(exchange, code, responseHeaders, responseBody)
            }
        }
    }

    private var contextInstalled = false

    private fun reply(exchange: HttpExchange, status: Int, headers: Map<String, String>, body: String) {
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private suspend fun subscribe(callback: String = "http://192.168.1.20:8080/sonos/events/key") =
        subscriber().subscribe("127.0.0.1", SonosService.AV_TRANSPORT, callback)

    @Test
    fun `a 200 with SID and TIMEOUT is a subscription`() = runBlocking {
        respond(200, mapOf("SID" to "uuid:RINCON_1-sub42", "TIMEOUT" to "Second-120"))

        val result = subscribe()

        assertThat(result).isEqualTo(SonosResult.Ok(Subscription("uuid:RINCON_1-sub42", 120)))
    }

    @Test
    fun `the granted timeout is the one the speaker answers and not the requested one`() = runBlocking {
        respond(200, mapOf("SID" to "uuid:x", "TIMEOUT" to "Second-1800"))

        val result = subscribe()

        assertThat(result).isEqualTo(SonosResult.Ok(Subscription("uuid:x", 1800)))
    }

    @Test
    fun `an infinite, missing or unreadable TIMEOUT falls back to 120 seconds`() = runBlocking {
        for (timeout in listOf("infinite", null, "Second-abc", "Second--5", "Second-0", "garbage")) {
            seen.clear()
            respond(200, listOfNotNull("SID" to "uuid:x", timeout?.let { "TIMEOUT" to it }).toMap())

            val result = subscribe()

            assertThat(result).isEqualTo(SonosResult.Ok(Subscription("uuid:x", 120)))
        }
    }

    @Test
    fun `a 200 without SID is a malformed response`() = runBlocking {
        respond(200, mapOf("TIMEOUT" to "Second-120"))

        assertThat(subscribe()).isEqualTo(SonosResult.Err(SonosFailure.MalformedResponse))
    }

    @Test
    fun `subscribe sends exactly CALLBACK, NT and TIMEOUT to the event path`() = runBlocking {
        respond(200, mapOf("SID" to "uuid:x", "TIMEOUT" to "Second-120"))

        subscribe(callback = "http://192.168.1.20:8080/sonos/events/key")

        val request = seen.single()
        assertThat(request.method).isEqualTo("SUBSCRIBE")
        assertThat(request.path).isEqualTo(SonosService.AV_TRANSPORT.eventPath)
        assertThat(request.headers["Callback"]).isEqualTo("<http://192.168.1.20:8080/sonos/events/key>")
        assertThat(request.headers["Nt"]).isEqualTo("upnp:event")
        assertThat(request.headers["Timeout"]).isEqualTo("Second-120")
        assertThat(request.headers).doesNotContainKey("Sid")
    }

    @Test
    fun `renew sends SID and TIMEOUT and neither CALLBACK nor NT`() = runBlocking {
        respond(200, mapOf("SID" to "uuid:x", "TIMEOUT" to "Second-120"))

        val result = subscriber().renew("127.0.0.1", SonosService.AV_TRANSPORT, "uuid:x")

        assertThat(result).isEqualTo(SonosResult.Ok(Subscription("uuid:x", 120)))
        val request = seen.single()
        assertThat(request.method).isEqualTo("SUBSCRIBE")
        assertThat(request.headers["Sid"]).isEqualTo("uuid:x")
        assertThat(request.headers["Timeout"]).isEqualTo("Second-120")
        assertThat(request.headers).doesNotContainKey("Callback")
        assertThat(request.headers).doesNotContainKey("Nt")
    }

    @Test
    fun `a renew answered without SID keeps the SID that was renewed`() = runBlocking {
        respond(200, mapOf("TIMEOUT" to "Second-60"))

        val result = subscriber().renew("127.0.0.1", SonosService.AV_TRANSPORT, "uuid:x")

        assertThat(result).isEqualTo(SonosResult.Ok(Subscription("uuid:x", 60)))
    }

    @Test
    fun `a 412 on renew means the subscription is gone`() = runBlocking {
        respond(412)

        val result = subscriber().renew("127.0.0.1", SonosService.AV_TRANSPORT, "uuid:x")

        assertThat(result).isEqualTo(
            SonosResult.Err(SonosFailure.Upnp("SUBSCRIBE", UpnpErrorKind.PreconditionFailed)),
        )
    }

    @Test
    fun `a server error or an empty answer is a failure and not a subscription`() = runBlocking {
        respond(503)
        assertThat(subscribe()).isEqualTo(SonosResult.Err(SonosFailure.Network("HTTP 503")))

        respond(200)
        assertThat(subscribe()).isEqualTo(SonosResult.Err(SonosFailure.MalformedResponse))
    }

    @Test
    fun `unsubscribe sends only the SID`() = runBlocking {
        respond(200)

        val result = subscriber().unsubscribe("127.0.0.1", SonosService.AV_TRANSPORT, "uuid:x")

        assertThat(result).isEqualTo(SonosResult.Ok(Unit))
        val request = seen.single()
        assertThat(request.method).isEqualTo("UNSUBSCRIBE")
        assertThat(request.path).isEqualTo(SonosService.AV_TRANSPORT.eventPath)
        assertThat(request.headers["Sid"]).isEqualTo("uuid:x")
        assertThat(request.headers).doesNotContainKey("Callback")
        assertThat(request.headers).doesNotContainKey("Nt")
        assertThat(request.headers).doesNotContainKey("Timeout")
    }

    @Test
    fun `unsubscribing a subscription the speaker already forgot is not an error`() = runBlocking {
        respond(412)

        val result = subscriber().unsubscribe("127.0.0.1", SonosService.AV_TRANSPORT, "uuid:x")

        assertThat(result).isEqualTo(SonosResult.Ok(Unit))
    }

    @Test
    fun `cancelling a hung subscribe aborts it promptly`() = runBlocking {
        val arrived = CountDownLatch(1)
        server.createContext("/") { exchange ->
            arrived.countDown()
            Thread.sleep(5_000)
            exchange.close()
        }
        val call = async(Dispatchers.Default) { subscribe() }
        assertThat(arrived.await(2, TimeUnit.SECONDS)).isTrue()

        val started = System.nanoTime()
        call.cancel()
        call.join()

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_000)
    }

    @Test
    fun `without wifi nothing is sent`() = runBlocking {
        respond(200, mapOf("SID" to "uuid:x"))

        val result = subscriber(wifi = false).subscribe("127.0.0.1", SonosService.AV_TRANSPORT, "http://a/b")

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.NoWifi))
        assertThat(seen).isEmpty()
    }

    @Test
    fun `a host that cannot be part of a URL is a malformed response`() = runBlocking {
        val result = subscriber().subscribe("fe80::1%wlan0 bad host", SonosService.AV_TRANSPORT, "http://a/b")

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.MalformedResponse))
    }

    @Test
    fun `the whole call runs on the io dispatcher`() = runBlocking {
        respond(200, mapOf("SID" to "uuid:x"))

        subscribe()

        assertThat(wifiLookupThreads).containsExactly("sonos-io-test")
    }
}
