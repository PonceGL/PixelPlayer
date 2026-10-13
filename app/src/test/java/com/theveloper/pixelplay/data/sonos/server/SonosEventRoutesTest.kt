package com.theveloper.pixelplay.data.sonos.server

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.SonosFixtures
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SonosEventRoutesTest {

    private companion object {
        const val LIMIT = 64 * 1024
    }

    private val router = SonosEventRouter()
    private val client = OkHttpClient()
    private val received = CopyOnWriteArrayList<SonosNotification>()
    private val logLines = CopyOnWriteArrayList<String>()
    private lateinit var server: EmbeddedServer<*, *>
    private var port = 0

    @BeforeEach
    fun start() {
        server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing { sonosEventRoutes(router, maxBodyBytes = LIMIT, bodyTimeoutMs = 400) { logLines += it } }
        }.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterEach
    fun stop() {
        server.stop(0, 0)
    }

    private fun register(host: String = "127.0.0.1", sink: SonosEventSink = SonosEventSink { received += it }) =
        router.register(host, sink)

    private fun notify(
        key: String,
        body: String = "<e:propertyset/>",
        sid: String? = "uuid:RINCON_A_sub0000000001",
        seq: String? = "0",
        method: String = "NOTIFY",
    ) = client.newCall(
        Request.Builder()
            .url("http://127.0.0.1:$port/sonos/events/$key")
            .method(method, if (method == "GET") null else body.toRequestBody("text/xml".toMediaType()))
            .apply {
                sid?.let { header("SID", it) }
                seq?.let { header("SEQ", it) }
                header("NT", "upnp:event")
            }
            .build(),
    ).execute()

    @Test
    fun `a real initial notification reaches the sink whole with its subscription id and sequence`() {
        val key = register()
        val body = SonosFixtures.notify("AVTransport_0")

        notify(key, body).use { assertThat(it.code).isEqualTo(200) }

        assertThat(received).hasSize(1)
        assertThat(received[0].body).isEqualTo(body)
        assertThat(received[0].sid).isEqualTo("uuid:RINCON_A_sub0000000001")
        assertThat(received[0].seq).isEqualTo(0L)
    }

    @Test
    fun `an unknown key is not found`() {
        notify("nope").use { assertThat(it.code).isEqualTo(404) }
        assertThat(received).isEmpty()
    }

    @Test
    fun `after unregistering the key is not found`() {
        val key = register()
        router.unregister(key)

        notify(key).use { assertThat(it.code).isEqualTo(404) }
        assertThat(received).isEmpty()
    }

    @Test
    fun `methods other than NOTIFY are not allowed`() {
        val key = register()

        for (method in listOf("GET", "POST", "PUT", "DELETE")) {
            notify(key, method = method).use { assertThat(it.code).isEqualTo(405) }
        }
        assertThat(received).isEmpty()
    }

    @Test
    fun `a body over the limit is refused and never delivered`() {
        val key = register()

        notify(key, body = "x".repeat(LIMIT + 1)).use { assertThat(it.code).isEqualTo(413) }
        assertThat(received).isEmpty()
    }

    @Test
    fun `a body over the limit sent without a length is also refused`() {
        val key = register()
        val unknownLength = object : RequestBody() {
            override fun contentType() = "text/xml".toMediaType()
            override fun contentLength() = -1L
            override fun writeTo(sink: BufferedSink) {
                repeat(LIMIT / 1_000 + 2) { sink.writeUtf8("y".repeat(1_000)); sink.flush() }
            }
        }

        client.newCall(
            Request.Builder().url("http://127.0.0.1:$port/sonos/events/$key").method("NOTIFY", unknownLength).build(),
        ).execute().use { assertThat(it.code).isEqualTo(413) }
        assertThat(received).isEmpty()
    }

    @Test
    fun `a body at exactly the limit is accepted`() {
        val key = register()

        notify(key, body = "z".repeat(LIMIT)).use { assertThat(it.code).isEqualTo(200) }
        assertThat(received.single().body.length).isEqualTo(LIMIT)
    }

    @Test
    fun `a sender that is not the registered speaker is refused`() {
        val key = register(host = "10.9.9.9")

        notify(key).use { assertThat(it.code).isEqualTo(403) }
        assertThat(received).isEmpty()
    }

    @Test
    fun `missing or malformed headers become null instead of failing`() {
        val key = register()

        notify(key, sid = null, seq = null).use { assertThat(it.code).isEqualTo(200) }
        notify(key, seq = "abc").use { assertThat(it.code).isEqualTo(200) }
        notify(key, seq = "-5x").use { assertThat(it.code).isEqualTo(200) }

        assertThat(received.map { it.seq }).containsExactly(null, null, null)
        assertThat(received[0].sid).isNull()
    }

    @Test
    fun `two subscriptions each receive only their own notifications`() {
        val a = CopyOnWriteArrayList<String>()
        val b = CopyOnWriteArrayList<String>()
        val keyA = register(sink = { a += it.body })
        val keyB = register(sink = { b += it.body })

        notify(keyA, body = "for-a").close()
        notify(keyB, body = "for-b").close()

        assertThat(a).containsExactly("for-a")
        assertThat(b).containsExactly("for-b")
    }

    @Test
    fun `a sink that throws does not take the server down`() {
        val bad = register(sink = { error("boom") })
        val good = register()

        notify(bad).use { assertThat(it.code).isEqualTo(500) }
        notify(good).use { assertThat(it.code).isEqualTo(200) }
        assertThat(received).hasSize(1)
        assertThat(logLines.joinToString()).doesNotContain("boom")
    }

    @Test
    fun `a body that never finishes arriving times out`() {
        val key = register()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().write(
                "NOTIFY /sonos/events/$key HTTP/1.1\r\nHost: x\r\nContent-Length: 100\r\n\r\nonly ten b".toByteArray(),
            )
            socket.getOutputStream().flush()
            val answer = socket.getInputStream().bufferedReader().readLine()

            assertThat(answer).contains("408")
        }
        assertThat(received).isEmpty()
    }

    @Test
    fun `keys are long unique and unguessable looking`() {
        val keys = List(500) { register() }

        assertThat(keys.toSet()).hasSize(500)
        for (key in keys) assertThat(key).matches("[0-9a-f]{32}")
    }

    @Test
    fun `the log never contains the key or the body`() {
        val key = register()
        notify(key, body = "secret-body").close()
        notify("unknown-key-123").close()

        assertThat(logLines).isNotEmpty()
        for (line in logLines) {
            assertThat(line).doesNotContain(key)
            assertThat(line).doesNotContain("secret-body")
            assertThat(line).doesNotContain("unknown-key-123")
        }
    }
}
