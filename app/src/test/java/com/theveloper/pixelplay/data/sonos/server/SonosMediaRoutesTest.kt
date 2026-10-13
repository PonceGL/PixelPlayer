package com.theveloper.pixelplay.data.sonos.server

import com.google.common.truth.Truth.assertThat
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SonosMediaRoutesTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val access = SonosMediaAccess { SonosServerIdentity(token, null) }
    private val logLines = CopyOnWriteArrayList<String>()
    private val client = OkHttpClient()
    private lateinit var server: EmbeddedServer<*, *>
    private var port = 0

    private val songBytes = ByteArray(300_000) { (it * 31 + 7).toByte() }
    private val artBytes = ByteArray(2_048) { (it % 251).toByte() }
    private val openedOffsets = CopyOnWriteArrayList<Long>()
    private val closedStreams = CountDownLatch(1)

    private val source = object : SonosMediaSource {
        override suspend fun audio(songId: String): AudioPayload? = audioFor(songId)

        private fun audioFor(id: String): AudioPayload? = when (id) {
            "42", "Café (live)", "a b" -> AudioPayload(songBytes.size.toLong(), "audio/flac", 1_700_000_000_000L) { offset ->
                openedOffsets += offset
                ByteArrayInputStream(songBytes, offset.toInt(), songBytes.size - offset.toInt())
            }
            "huge" -> AudioPayload(4_000_000_000L, "audio/mp4", null) { offset ->
                openedOffsets += offset
                CountingZeros(onClose = { closedStreams.countDown() })
            }
            "endless" -> AudioPayload(10_000_000_000L, "audio/flac", null) { _ ->
                CountingZeros(onClose = { closedStreams.countDown() })
            }
            "unknown" -> AudioPayload(-1, "audio/mpeg", null) { _ -> ByteArrayInputStream(songBytes) }
            "broken" -> AudioPayload(1_000, "audio/flac", null) { _ -> throw java.io.IOException("file vanished") }
            "missing-file" -> null
            else -> if (id.startsWith("mime:")) {
                AudioPayload(10, id.removePrefix("mime:"), null) { _ -> ByteArrayInputStream(ByteArray(10)) }
            } else null
        }

        override suspend fun art(songId: String): ArtPayload? = when (songId) {
            "42" -> ArtPayload(artBytes.size.toLong(), "image/jpeg", null) { ByteArrayInputStream(artBytes) }
            else -> null
        }
    }

    private class CountingZeros(private val onClose: () -> Unit) : InputStream() {
        override fun read(): Int = 0
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            java.util.Arrays.fill(b, off, off + len, 0)
            return len
        }
        override fun close() = onClose()
    }

    @BeforeEach
    fun start() {
        access.publish(listOf("42", "Café (live)", "a b", "huge", "endless", "unknown", "missing-file", "broken"))
        server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing { sonosMediaRoutes(access, source, Dispatchers.IO) { logLines += it } }
        }.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterEach
    fun stop() {
        server.stop(0, 0)
    }

    private fun songUrl(id: String = "42", withToken: String? = token) =
        access.songUrl("http://127.0.0.1:$port", id, "1").toHttpUrl().newBuilder().apply {
            removeAllQueryParameters("auth")
            withToken?.let { addQueryParameter("auth", it) }
        }.build()

    private fun artUrl(id: String = "42") = access.artUrl("http://127.0.0.1:$port", id, "1").toHttpUrl()

    private fun get(url: okhttp3.HttpUrl, range: String? = null) =
        client.newCall(Request.Builder().url(url).apply { range?.let { header("Range", it) } }.build()).execute()

    private fun head(url: okhttp3.HttpUrl) = client.newCall(Request.Builder().url(url).head().build()).execute()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()

    @Test
    fun `GET returns the original bytes untouched`() {
        get(songUrl()).use { response ->
            assertThat(response.code).isEqualTo(200)
            assertThat(response.header("Content-Type")).startsWith("audio/flac")
            assertThat(response.header("Accept-Ranges")).isEqualTo("bytes")
            assertThat(response.header("Content-Length")).isEqualTo("300000")
            assertThat(response.header("Last-Modified")).isNotNull()
            assertThat(sha256(response.body.bytes())).isEqualTo(sha256(songBytes))
        }
    }

    @Test
    fun `a range returns 206 with the exact bytes and content range`() {
        get(songUrl(), "bytes=1000-1999").use { response ->
            assertThat(response.code).isEqualTo(206)
            assertThat(response.header("Content-Range")).isEqualTo("bytes 1000-1999/300000")
            assertThat(response.header("Content-Length")).isEqualTo("1000")
            assertThat(response.body.bytes().toList()).isEqualTo(songBytes.copyOfRange(1000, 2000).toList())
        }
    }

    @Test
    fun `open ended and suffix ranges work`() {
        get(songUrl(), "bytes=299000-").use { response ->
            assertThat(response.code).isEqualTo(206)
            assertThat(response.body.bytes().toList()).isEqualTo(songBytes.copyOfRange(299_000, 300_000).toList())
        }
        get(songUrl(), "bytes=-500").use { response ->
            assertThat(response.header("Content-Range")).isEqualTo("bytes 299500-299999/300000")
            assertThat(response.body.bytes().toList()).isEqualTo(songBytes.copyOfRange(299_500, 300_000).toList())
        }
    }

    @Test
    fun `a range past the end is 416 with the size`() {
        get(songUrl(), "bytes=300000-").use { response ->
            assertThat(response.code).isEqualTo(416)
            assertThat(response.header("Content-Range")).isEqualTo("bytes */300000")
        }
    }

    @Test
    fun `a garbage range is ignored and the whole file is served`() {
        get(songUrl(), "bytes=banana").use { response ->
            assertThat(response.code).isEqualTo(200)
            assertThat(response.body.bytes().size).isEqualTo(300_000)
        }
    }

    @Test
    fun `HEAD has the same headers as GET, no body and a single Content-Length`() {
        val url = songUrl()
        val raw = rawHead(url.encodedPath + "?" + url.encodedQuery)

        assertThat(raw.status).isEqualTo(200)
        assertThat(raw.headers.count { it.startsWith("content-length:", ignoreCase = true) }).isEqualTo(1)
        assertThat(raw.headers.first { it.startsWith("content-length:", ignoreCase = true) }.substringAfter(":").trim())
            .isEqualTo("300000")
        assertThat(raw.headers.any { it.startsWith("accept-ranges: bytes", ignoreCase = true) }).isTrue()
        assertThat(raw.headers.any { it.startsWith("content-type: audio/flac", ignoreCase = true) }).isTrue()
        assertThat(raw.bodyBytes).isEqualTo(0)
    }

    @Test
    fun `HEAD with a range answers 206 headers without a body`() {
        val response = client.newCall(Request.Builder().url(songUrl()).head().header("Range", "bytes=0-9").build()).execute()

        response.use {
            assertThat(it.code).isEqualTo(206)
            assertThat(it.header("Content-Range")).isEqualTo("bytes 0-9/300000")
            assertThat(it.header("Content-Length")).isEqualTo("10")
        }
    }

    @Test
    fun `a missing or wrong token is 401 and a song that was not published is 404`() {
        get(songUrl(withToken = null)).use { assertThat(it.code).isEqualTo(401) }
        get(songUrl(withToken = "0".repeat(32))).use { assertThat(it.code).isEqualTo(401) }
        get(songUrl(id = "99")).use { assertThat(it.code).isEqualTo(404) }
        head(songUrl(id = "99")).use { assertThat(it.code).isEqualTo(404) }
    }

    @Test
    fun `paths that try to leave what is published are 404`() {
        val attempts = listOf("/sonos/song/..%2F42", "/sonos/song/%2e%2e%2f42", "/sonos/song/42%2F..%2F43", "/sonos/song/..", "/sonos/song/")
        for (path in attempts) {
            val url = "http://127.0.0.1:$port$path?auth=$token".toHttpUrl()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                assertThat(response.code).isIn(listOf(400, 404))
            }
        }
    }

    @Test
    fun `other routes are not served`() {
        for (path in listOf("/", "/sonos", "/sonos/song", "/sonos/other/42", "/song/42")) {
            client.newCall(Request.Builder().url("http://127.0.0.1:$port$path?auth=$token".toHttpUrl()).build()).execute().use {
                assertThat(it.code).isEqualTo(404)
            }
        }
    }

    @Test
    fun `ids with spaces accents and parentheses are served`() {
        for (id in listOf("a b", "Café (live)")) {
            get(songUrl(id)).use { response ->
                assertThat(response.code).isEqualTo(200)
                assertThat(response.body.bytes().size).isEqualTo(300_000)
            }
        }
    }

    @Test
    fun `a published song whose file cannot be found is 404`() {
        get(songUrl("missing-file")).use { assertThat(it.code).isEqualTo(404) }
    }

    @Test
    fun `a file that cannot be opened after the answer started does not break the server`() {
        runCatching { get(songUrl("broken")).use { it.body.bytes() } }

        assertThat(logLines.any { it.contains("unavailable") && it.contains("IOException") }).isTrue()
        assertThat(logLines.joinToString()).doesNotContain("file vanished")

        get(songUrl()).use { assertThat(it.code).isEqualTo(200) }
    }

    @Test
    fun `an odd mime type from the source never breaks the answer`() {
        val odd = listOf("", " ", "audio", "audio/", "/flac", "a/b/c", "audio/flac;;;", "audio/flac; =", "@@@", "audio/fl ac", "audio/flac; charset")
        for (mime in odd) {
            val id = "mime:$mime"
            access.publish(listOf(id))

            get(songUrl(id)).use { response ->
                assertThat(response.code).isEqualTo(200)
                assertThat(response.header("Content-Type")).isNotNull()
            }
        }
    }

    @Test
    fun `an unknown size is served without length or range support`() {
        get(songUrl("unknown")).use { response ->
            assertThat(response.code).isEqualTo(200)
            assertThat(response.header("Accept-Ranges")).isNull()
            assertThat(response.header("Content-Length")).isNull()
            assertThat(response.body.bytes().size).isEqualTo(300_000)
        }
        get(songUrl("unknown"), "bytes=0-9").use { response ->
            assertThat(response.code).isEqualTo(200)
        }
    }

    @Test
    fun `a huge offset is opened at that position without reading what comes before`() {
        get(songUrl("huge"), "bytes=3000000000-3000000009").use { response ->
            assertThat(response.code).isEqualTo(206)
            assertThat(response.header("Content-Range")).isEqualTo("bytes 3000000000-3000000009/4000000000")
            assertThat(response.body.bytes().size).isEqualTo(10)
        }
        assertThat(openedOffsets).contains(3_000_000_000L)
    }

    @Test
    fun `the client dropping the connection closes the file`() {
        val call = client.newCall(Request.Builder().url(songUrl("endless")).build())
        val response = call.execute()
        response.body.byteStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            stream.read(buffer)
        }
        call.cancel()

        assertThat(closedStreams.await(5, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `the three download patterns of the speaker are all served`() {
        // 1. one burst without Range
        get(songUrl()).use { assertThat(it.body.bytes().size).isEqualTo(300_000) }
        // 2. a first 128 KB block with Range, then the rest
        get(songUrl(), "bytes=0-131071").use {
            assertThat(it.code).isEqualTo(206)
            assertThat(it.body.bytes().size).isEqualTo(131_072)
        }
        get(songUrl(), "bytes=131072-").use { assertThat(it.body.bytes().size).isEqualTo(300_000 - 131_072) }
        // 3. a slow reader with Range
        get(songUrl(), "bytes=1000-").use { response ->
            val stream = response.body.byteStream()
            var total = 0
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                Thread.sleep(5)
            }
            assertThat(total).isEqualTo(299_000)
        }
    }

    @Test
    fun `art is served with its type and rejected without the token`() {
        get(artUrl()).use { response ->
            assertThat(response.code).isEqualTo(200)
            assertThat(response.header("Content-Type")).startsWith("image/jpeg")
            assertThat(response.header("Content-Length")).isEqualTo("2048")
            assertThat(response.body.bytes().toList()).isEqualTo(artBytes.toList())
        }
        get(artUrl().newBuilder().removeAllQueryParameters("auth").build()).use { assertThat(it.code).isEqualTo(401) }
        get(artUrl().newBuilder().setPathSegment(2, "99").build()).use { assertThat(it.code).isEqualTo(404) }
        head(artUrl()).use {
            assertThat(it.code).isEqualTo(200)
            assertThat(it.header("Content-Length")).isEqualTo("2048")
        }
    }

    @Test
    fun `the log never contains the token or the query`() {
        get(songUrl()).close()
        get(songUrl(withToken = "bad")).close()
        get(songUrl(id = "99")).close()
        head(songUrl()).close()
        get(artUrl()).close()

        assertThat(logLines).isNotEmpty()
        for (line in logLines) {
            assertThat(line).doesNotContain(token)
            assertThat(line).doesNotContain("auth")
            assertThat(line).doesNotContain("?")
        }
    }

    private class RawResponse(val status: Int, val headers: List<String>, val bodyBytes: Int)

    private fun rawHead(pathAndQuery: String): RawResponse {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().write("HEAD $pathAndQuery HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            val bytes = socket.getInputStream().readBytes()
            val text = bytes.toString(Charsets.ISO_8859_1)
            val head = text.substringBefore("\r\n\r\n")
            val lines = head.split("\r\n")
            return RawResponse(
                status = lines.first().split(" ")[1].toInt(),
                headers = lines.drop(1),
                bodyBytes = text.length - head.length - 4,
            )
        }
    }
}
