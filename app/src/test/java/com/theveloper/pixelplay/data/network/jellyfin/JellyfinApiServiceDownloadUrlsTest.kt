package com.theveloper.pixelplay.data.network.jellyfin

import com.theveloper.pixelplay.data.jellyfin.model.JellyfinCredentials
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * F1.4a's additions to [JellyfinApiService]: the two download URL builders and the download
 * permission probe. Everything else on this service predates F1 and is out of scope here.
 */
class JellyfinApiServiceDownloadUrlsTest {

    private lateinit var server: MockWebServer
    private lateinit var service: JellyfinApiService

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = JellyfinApiService(OkHttpClient())
        service.setCredentials(
            JellyfinCredentials(
                serverUrl = server.url("/").toString().trimEnd('/'),
                username = "user",
                password = "pw",
                accessToken = "test-token",
                userId = "user-1",
            )
        )
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    // ─── URL construction (no network) ───────────────────────────────────────────

    @Test
    fun `getOriginalDownloadUrl points at Items id Download with no api_key in the query`() {
        val url = service.getOriginalDownloadUrl("item-123")

        assertTrue(url.endsWith("/Items/item-123/Download"))
        assertFalse(url.contains("api_key"))
    }

    @Test
    fun `getDirectPlayUrl points at the static audio stream with no api_key in the query`() {
        val url = service.getDirectPlayUrl("item-123")

        assertTrue(url.contains("/Audio/item-123/stream"))
        assertTrue(url.contains("static=true"))
        assertFalse(url.contains("api_key"))
    }

    // ─── checkDownloadPermission (real HTTP, against a local server) ──────────────

    @Test
    fun `checkDownloadPermission is true for anything but a 403`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())

        val result = service.checkDownloadPermission("item-1")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
    }

    @Test
    fun `checkDownloadPermission is false for a 403 — download denied by policy`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).build())

        val result = service.checkDownloadPermission("item-1")

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }

    @Test
    fun `checkDownloadPermission sends the Authorization header, not api_key`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())

        service.checkDownloadPermission("item-1")

        val received = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("HEAD", received?.method)
        assertTrue(received?.headers?.get("Authorization")?.contains("test-token") == true)
        assertFalse(received?.url.toString().contains("api_key"))
    }

    @Test
    fun `checkDownloadPermission on an unreachable server is a failure`() = runTest {
        server.close() // nothing is listening anymore

        val result = service.checkDownloadPermission("item-1")

        assertTrue(result.isFailure)
    }

    // ─── getItemsByIds (real HTTP) ────────────────────────────────────────────────

    @Test
    fun `getItemsByIds joins ids with a comma and requests MediaSources`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"Items":[{"Id":"a"},{"Id":"b"}],"TotalRecordCount":2}""")
                .build()
        )

        val result = service.getItemsByIds(listOf("a", "b"))

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().size)
        val received = server.takeRequest(1, TimeUnit.SECONDS)
        assertTrue(received?.url.toString().contains("Ids=a%2Cb") || received?.url.toString().contains("Ids=a,b"))
        assertTrue(received?.url.toString().contains("Fields=MediaSources"))
    }

    @Test
    fun `getItemsByIds with an empty list makes no request`() = runTest {
        val result = service.getItemsByIds(emptyList())

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
        assertEquals(0, server.requestCount)
    }
}
