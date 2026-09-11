package com.theveloper.pixelplay.data.github

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Properties
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GitHubAnnouncementPropertiesServiceTest {

    private lateinit var server: HttpServer

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun url(path: String) = "http://localhost:${server.address.port}$path"

    private fun respond(path: String, code: Int, body: String) {
        server.createContext(path) { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    // ─── buildRawContentUrl: pure, no I/O ───────────────────────────────────────

    @Test
    fun `buildRawContentUrl points at raw githubusercontent com with the given path`() {
        val url = buildRawContentUrl("owner", "repo", "main", "config/flags.properties")

        assertEquals("https://raw.githubusercontent.com/owner/repo/main/config/flags.properties", url)
    }

    // ─── fetchRawProperties: real HTTP behavior against a local server ──────────

    @Test
    fun `a 200 response is parsed into Properties`() = runTest {
        respond("/ok", 200, "key1=value1\nkey2 = value2\n")

        val result = fetchRawProperties(url("/ok"))

        assertTrue(result.isSuccess)
        val props = result.getOrThrow()
        assertEquals("value1", props.getProperty("key1"))
        assertEquals("value2", props.getProperty("key2"))
    }

    @Test
    fun `a 404 is success with empty Properties, not a failure`() = runTest {
        respond("/missing", 404, "")

        val result = fetchRawProperties(url("/missing"))

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty)
    }

    @Test
    fun `a server error is a failure`() = runTest {
        respond("/broken", 500, "internal error")

        val result = fetchRawProperties(url("/broken"))

        assertTrue(result.isFailure)
    }

    @Test
    fun `an unreachable host is a failure, not a thrown exception`() = runTest {
        // Nothing is listening on this port.
        val result = fetchRawProperties("http://localhost:1/nothing-here")

        assertTrue(result.isFailure)
    }

    // ─── Cancellation before the fetch starts ───────────────────────────────────
    //
    // `HttpURLConnection` is a classic blocking-socket API and does not react to
    // `Thread.interrupt()`: cancelling the caller while it's genuinely blocked inside
    // `getResponseCode()` does not interrupt that call (verified empirically — attempts at
    // forcing it, including closing the connection from a completion handler, could not make
    // it happen reliably; documented as a known limitation on `fetchRawProperties`'s own KDoc).
    //
    // This test cancels *before* `fetchRawProperties` is even entered, so — unlike the OkHttp
    // case in `JellyfinApiServiceErrorHandlingTest`, which can inject an exception straight from
    // an interceptor — it does not reach the `catch (e: CancellationException) { throw e }`
    // inside this function either: `withContext`'s own pre-flight `ensureActive()` throws first.
    // `HttpURLConnection` has no equivalent seam to throw from mid-call, so that catch currently
    // has no test that can reach it at all — it stays in place as a correct-by-construction
    // guard (and the one thing that *would* exercise it, a suspending call appearing inside the
    // try body, doesn't exist today). What this test verifies instead — a real and separate
    // guarantee — is that cancelling the caller before this suspend function is even entered
    // still propagates as cancellation, not as a silently-returned Result.

    @Test
    fun `a coroutine cancelled before the fetch starts propagates cancellation, not a Result`() = runTest {
        var sawCancellation = false

        val job = launch {
            cancel()
            try {
                fetchRawProperties(url("/never-reached"))
            } catch (e: CancellationException) {
                sawCancellation = true
                throw e
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(sawCancellation)
    }

    // ─── toPlayStoreAnnouncementRemoteConfig: the mapping, with no network at all ─

    @Test
    fun `empty Properties maps to the same defaults as PlayStoreAnnouncementRemoteConfig()`() {
        val config = Properties().toPlayStoreAnnouncementRemoteConfig()

        assertEquals(PlayStoreAnnouncementRemoteConfig(), config)
    }

    @Test
    fun `a fully populated properties file maps every field`() {
        val props = Properties().apply {
            setProperty("play_store_announcement_enabled", "true")
            setProperty("play_store_url", "https://play.google.com/store/apps/details?id=x")
            setProperty("play_store_announcement_title", "New version!")
            setProperty("play_store_announcement_body", "Check it out")
            setProperty("play_store_primary_action", "Update")
            setProperty("play_store_dismiss_action", "Later")
            setProperty("play_store_link_pending_message", "Opening store…")
        }

        val config = props.toPlayStoreAnnouncementRemoteConfig()

        assertTrue(config.enabled)
        assertEquals("https://play.google.com/store/apps/details?id=x", config.playStoreUrl)
        assertEquals("New version!", config.title)
        assertEquals("Check it out", config.body)
        assertEquals("Update", config.primaryActionLabel)
        assertEquals("Later", config.dismissActionLabel)
        assertEquals("Opening store…", config.linkPendingMessage)
    }

    @Test
    fun `booleanFlag accepts the documented truthy spellings and defaults everything else to false`() {
        listOf("true", "1", "yes", "on", "TRUE", " true ").forEach { value ->
            val props = Properties().apply { setProperty("play_store_announcement_enabled", value) }
            assertTrue(props.toPlayStoreAnnouncementRemoteConfig().enabled, "expected '$value' to be truthy")
        }
        listOf("false", "0", "no", "off", "garbage", "").forEach { value ->
            val props = Properties().apply { setProperty("play_store_announcement_enabled", value) }
            assertFalse(props.toPlayStoreAnnouncementRemoteConfig().enabled, "expected '$value' to be falsy")
        }
    }

    @Test
    fun `a blank string value is treated the same as an absent one`() {
        val props = Properties().apply { setProperty("play_store_url", "   ") }

        assertNull(props.toPlayStoreAnnouncementRemoteConfig().playStoreUrl)
    }

    // fetchProperties itself is a one-line composition of buildRawContentUrl (tested above,
    // pure) and fetchRawProperties (tested above, against a local server): it always builds a
    // real GitHub URL by design, so there's no seam to verify it end to end without a live
    // network call — which would make this suite flaky and offline-hostile for no real
    // coverage gain. Not tested separately on purpose.
}
