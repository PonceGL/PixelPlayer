package com.theveloper.pixelplay.di

import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DownloadOkHttpClientTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.close()
    }

    // ─── Timeouts (F1.2 · 4.2) ────────────────────────────────────────────────────

    @Test
    fun `callTimeout is explicitly zero — no cap on the whole transfer`() {
        val client = AppModule.provideDownloadOkHttpClient()

        assertEquals(0, client.callTimeoutMillis)
    }

    @Test
    fun `readTimeout and writeTimeout are per-call, not cumulative`() {
        val client = AppModule.provideDownloadOkHttpClient()

        assertEquals(60_000, client.readTimeoutMillis)
        assertEquals(60_000, client.writeTimeoutMillis)
    }

    @Test
    fun `connectTimeout stays short — a slow connect is not the same as a slow transfer`() {
        val client = AppModule.provideDownloadOkHttpClient()

        assertEquals(15_000, client.connectTimeoutMillis)
    }

    // ─── Isolation from the shared client ───────────────────────────────────

    @Test
    fun `the connection pool is a different instance than the shared client's`() {
        val downloadClient = AppModule.provideDownloadOkHttpClient()
        val sharedClient = AppModule.provideOkHttpClient()

        assertNotSame(sharedClient.connectionPool, downloadClient.connectionPool)
    }

    // ─── The test that matters most: no logging, ever ───────────
    // This is the one that fails if provideDownloadOkHttpClient() is ever "simplified"
    // into sharedClient.newBuilder(): newBuilder() carries over every interceptor of its
    // source client, logging included.

    @Test
    fun `the client carries no HttpLoggingInterceptor, unconditionally`() {
        val client = AppModule.provideDownloadOkHttpClient()

        assertTrue(client.interceptors.none { it is HttpLoggingInterceptor })
        assertTrue(client.networkInterceptors.none { it is HttpLoggingInterceptor })
    }

    // ─── Redirects are never auto-followed ────────────────────────

    @Test
    fun `a redirect response is returned as-is, never followed automatically`() {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "https://a-different-host.example/track.flac")
                .build()
        )

        val client = AppModule.provideDownloadOkHttpClient()
        val request = Request.Builder().url(server.url("/track")).build()

        client.newCall(request).execute().use { response ->
            assertEquals(302, response.code)
        }
        // Only the one request was ever made — nothing followed the Location header.
        assertEquals(1, server.requestCount)
    }

    // ─── Authorization survives; nothing adds api_key to the URL ────────────

    @Test
    fun `a request keeps its Authorization header and gains no api_key query param`() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        val client = AppModule.provideDownloadOkHttpClient()
        val authorization = """MediaBrowser Client="PixelPlayer", Token="secret-token""""
        val request = Request.Builder()
            .url(server.url("/Items/abc123/Download"))
            .header("Authorization", authorization)
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }

        val received = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals(authorization, received?.headers?.get("Authorization"))
        assertFalse(received?.url.toString().contains("api_key"))
    }

    @Test
    fun `every request still carries this build's User-Agent`() {
        server.enqueue(MockResponse.Builder().code(200).build())

        val client = AppModule.provideDownloadOkHttpClient()
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { }

        val received = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("PixelPlayer/1.0 (Android; Music Player)", received?.headers?.get("User-Agent"))
    }
}
