package com.theveloper.pixelplay.data.download.jellyfin

import com.theveloper.pixelplay.data.database.SourceType
import com.theveloper.pixelplay.data.download.CloudDownloadSourceRegistry
import com.theveloper.pixelplay.data.download.DownloadServerCapabilitiesStore
import com.theveloper.pixelplay.data.download.model.CloudDownloadQuality
import com.theveloper.pixelplay.data.jellyfin.model.JellyfinCredentials
import com.theveloper.pixelplay.data.network.jellyfin.JellyfinApiService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JellyfinCloudDownloadSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var apiService: JellyfinApiService
    private lateinit var serverCapabilities: DownloadServerCapabilitiesStore
    private lateinit var source: JellyfinCloudDownloadSource

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        apiService = JellyfinApiService(OkHttpClient())
        apiService.setCredentials(
            JellyfinCredentials(
                serverUrl = server.url("/").toString().trimEnd('/'),
                username = "user",
                password = "pw",
                accessToken = "test-token",
                userId = "user-1",
            )
        )
        // Never probed, by default: the same "unknown" a fresh install would see.
        serverCapabilities = mockk {
            coEvery { cachedSupportsRange(any(), any()) } returns null
        }
        source = JellyfinCloudDownloadSource(apiService, serverCapabilities)
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    // ─── Identity and quality ────────────────────────────────────────

    @Test
    fun `reports its own SourceType constant`() {
        assertEquals(SourceType.JELLYFIN, source.sourceType)
    }

    @Test
    fun `supports only MAX quality`() {
        assertTrue(source.supports(CloudDownloadQuality.MAX))
        assertFalse(source.supports(CloudDownloadQuality(code = 99, null, null, null, null)))
    }

    @Test
    fun `every SourceType constant either has a registered source or explicitly doesn't yet`() {
        // The five other cloud sources are "coming soon" — not registered yet. This
        // documents the current, honest state rather than pretending they exist (SourceType
        // isn't an enum, so there's no compiler exhaustiveness — this test is the only net
        // over a constant silently missing from the registry once it *is* implemented).
        val registry = CloudDownloadSourceRegistry(mapOf(SourceType.JELLYFIN to source))
        val allSourceTypes = listOf(
            SourceType.LOCAL, SourceType.TELEGRAM, SourceType.NETEASE, SourceType.GDRIVE,
            SourceType.QQMUSIC, SourceType.NAVIDROME, SourceType.JELLYFIN,
        )

        assertEquals(7, allSourceTypes.distinct().size)
        assertNotNull(registry.sourceFor(SourceType.JELLYFIN))
        (allSourceTypes - SourceType.JELLYFIN).forEach { sourceType ->
            assertNull(registry.sourceFor(sourceType), "unexpectedly registered: $sourceType")
        }
    }

    // ─── fetchItemInfo ─────────────────────────────────────────────────────────────

    @Test
    fun `fetchItemInfo with an empty list makes no request`() = runTest {
        val result = source.fetchItemInfo(emptyList())

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `fetchItemInfo maps size and container from the first MediaSource`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """{"Items":[{"Id":"song-1","MediaSources":[
                        {"Size":123456,"Container":"flac"}
                    ]}]}"""
                )
                .build()
        )

        val result = source.fetchItemInfo(listOf("song-1"))

        assertTrue(result.isSuccess)
        val info = result.getOrThrow().getValue("song-1")
        assertEquals(123456L, info.expectedBytes)
        assertEquals("flac", info.container)
        assertEquals("audio/flac", info.mimeType)
    }

    @Test
    fun `fetchItemInfo — a missing size is null, not zero or a guess`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"Items":[{"Id":"song-1","MediaSources":[{"Container":"mp3"}]}]}""")
                .build()
        )

        val result = source.fetchItemInfo(listOf("song-1"))

        assertNull(result.getOrThrow().getValue("song-1").expectedBytes)
    }

    @Test
    fun `fetchItemInfo splits a large id list across multiple batches`() = runTest {
        val ids = List(200) { "11111111-1111-1111-1111-11111111111$it" }
        // Two batches expected at this id count/length; enqueue enough responses regardless.
        repeat(5) {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"Items":[{"Id":"placeholder-$it","MediaSources":[]}]}""")
                    .build()
            )
        }

        val result = source.fetchItemInfo(ids)

        assertTrue(result.isSuccess)
        assertTrue(server.requestCount > 1, "expected more than one batch, got ${server.requestCount}")
    }

    @Test
    fun `fetchItemInfo — one failed batch does not invalidate the others`() = runTest {
        val ids = List(200) { "22222222-2222-2222-2222-22222222222$it" }
        server.enqueue(MockResponse.Builder().code(500).build()) // first batch fails
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"Items":[{"Id":"survivor","MediaSources":[]}]}""")
                .build()
        ) // second batch succeeds

        val result = source.fetchItemInfo(ids)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().containsKey("survivor"))
    }

    @Test
    fun `fetchItemInfo fails only when every batch fails`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())

        val result = source.fetchItemInfo(listOf("song-1"))

        assertTrue(result.isFailure)
    }

    // ─── buildDownloadRequest (403 degrades, never throws) ──────────

    @Test
    fun `buildDownloadRequest rejects an unsupported quality without a network call`() = runTest {
        val unsupported = CloudDownloadQuality(code = 99, null, null, null, null)

        val result = source.buildDownloadRequest("item-1", unsupported)

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `buildDownloadRequest uses the original download url when permitted`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())

        val result = source.buildDownloadRequest("item-1", CloudDownloadQuality.MAX)

        assertTrue(result.isSuccess)
        val spec = result.getOrThrow()
        assertTrue(spec.url.endsWith("/Items/item-1/Download"))
        assertTrue(spec.headers.containsKey("Authorization"))
    }

    @Test
    fun `buildDownloadRequest degrades to direct-play on a 403, without throwing`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).build())

        val result = source.buildDownloadRequest("item-1", CloudDownloadQuality.MAX)

        assertTrue(result.isSuccess)
        val spec = result.getOrThrow()
        assertTrue(spec.url.contains("/Audio/item-1/stream"))
        assertTrue(spec.url.contains("static=true"))
    }

    @Test
    fun `buildDownloadRequest never assumes Range support when it was never probed`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())

        val result = source.buildDownloadRequest("item-1", CloudDownloadQuality.MAX)

        assertFalse(result.getOrThrow().supportsRange)
    }

    @Test
    fun `buildDownloadRequest reports supportsRange true only on a confirmed probe`() = runTest {
        serverCapabilities = mockk {
            coEvery { cachedSupportsRange(any(), any()) } returns true
        }
        source = JellyfinCloudDownloadSource(apiService, serverCapabilities)
        server.enqueue(MockResponse.Builder().code(200).build())

        val result = source.buildDownloadRequest("item-1", CloudDownloadQuality.MAX)

        assertTrue(result.getOrThrow().supportsRange)
    }

    @Test
    fun `buildDownloadRequest reports supportsRange false when the cache explicitly says so`() = runTest {
        serverCapabilities = mockk {
            coEvery { cachedSupportsRange(any(), any()) } returns false
        }
        source = JellyfinCloudDownloadSource(apiService, serverCapabilities)
        server.enqueue(MockResponse.Builder().code(200).build())

        val result = source.buildDownloadRequest("item-1", CloudDownloadQuality.MAX)

        assertFalse(result.getOrThrow().supportsRange)
    }

    @Test
    fun `buildDownloadRequest sets serverKey to the server's own normalized URL`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())

        val result = source.buildDownloadRequest("item-1", CloudDownloadQuality.MAX)

        assertEquals(apiService.getServerUrl(), result.getOrThrow().serverKey)
    }

    @Test
    fun `buildDownloadRequest never puts api_key in the url`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())

        val result = source.buildDownloadRequest("item-1", CloudDownloadQuality.MAX)

        assertFalse(result.getOrThrow().url.contains("api_key"))
    }
}
