package com.theveloper.pixelplay.data.download.engine

import com.theveloper.pixelplay.data.download.DownloadServerCapabilitiesStore
import com.theveloper.pixelplay.data.download.model.DownloadRequestSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `HttpFileDownloader`, against a real `MockWebServer` — the documented edge cases plus the
 * Content-Type guard and the capabilities-store interactions. No fakes for OkHttp itself: the
 * whole point of this class is real HTTP behavior.
 */
class HttpFileDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var staging: File
    private lateinit var capabilities: DownloadServerCapabilitiesStore
    private lateinit var downloader: HttpFileDownloader

    private fun specFor(
        path: String = "/download",
        expectedBytes: Long? = null,
    ): DownloadRequestSpec = DownloadRequestSpec(
        url = server.url(path).toString(),
        headers = mapOf("Authorization" to "MediaBrowser Token=test-token"),
        supportsRange = false,
        expectedBytes = expectedBytes,
        container = "flac",
        mimeType = "audio/flac",
        serverKey = server.url("/").toString().trimEnd('/'),
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        staging = Files.createTempFile("http-file-downloader-test", ".part").toFile()
        capabilities = mockk(relaxed = true) {
            coEvery { cachedSupportsRange(any(), any()) } returns null
        }
        downloader = HttpFileDownloader(OkHttpClient(), capabilities, Dispatchers.IO)
    }

    @AfterEach
    fun tearDown() {
        server.close()
        staging.delete()
    }

    // ─── The happy path ─────────────────────────────────────────────────────────

    @Test
    fun `a plain 200 download writes the exact body to the staging file`() = runTest {
        val body = "the entire audio file, byte for byte"
        server.enqueue(MockResponse.Builder().code(200).body(body).build())

        val outcome = downloader.download(staging, specFor())

        assertTrue(outcome is DownloadOutcome.Success)
        assertEquals(body.toByteArray().size.toLong(), (outcome as DownloadOutcome.Success).totalBytes)
        assertEquals(body, staging.readText())
    }

    @Test
    fun `no Content-Type header at all is accepted — absence proves nothing`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("data").removeHeader("Content-Type").build())

        val outcome = downloader.download(staging, specFor())

        assertTrue(outcome is DownloadOutcome.Success, "expected Success, got $outcome")
    }

    // ─── C15 guard #1: Content-Type ────────────────────────────────────────────

    @Test
    fun `a text_html body is rejected before a single byte is written`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body("<html>please log in</html>")
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .build()
        )

        val outcome = downloader.download(staging, specFor())

        assertTrue(outcome is DownloadOutcome.Failure)
        assertEquals(DownloadFailureReason.UNEXPECTED_CONTENT_TYPE, (outcome as DownloadOutcome.Failure).reason)
        assertEquals(0L, staging.length(), "not a single byte should have been written")
    }

    @Test
    fun `application_octet-stream is accepted, not just audio types`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body("bytes")
                .setHeader("Content-Type", "application/octet-stream")
                .build()
        )

        val outcome = downloader.download(staging, specFor())

        assertTrue(outcome is DownloadOutcome.Success)
    }

    // ─── C15 guard #2: size verification ───────────────────────────────────────

    @Test
    fun `a completed file whose size doesn't match expected_bytes is a size mismatch`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("only nine").build())

        val outcome = downloader.download(staging, specFor(expectedBytes = 99_999L))

        assertTrue(outcome is DownloadOutcome.Failure)
        assertEquals(DownloadFailureReason.SIZE_MISMATCH, (outcome as DownloadOutcome.Failure).reason)
    }

    @Test
    fun `expected_bytes matching the real size succeeds`() = runTest {
        val body = "exactly this many bytes"
        server.enqueue(MockResponse.Builder().code(200).body(body).build())

        val outcome = downloader.download(staging, specFor(expectedBytes = body.toByteArray().size.toLong()))

        assertTrue(outcome is DownloadOutcome.Success)
    }

    @Test
    fun `a body shorter than its own Content-Length is a truncated-body failure`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body("short")
                .setHeader("Content-Length", "500")
                .build()
        )

        val outcome = downloader.download(staging, specFor())

        assertTrue(outcome is DownloadOutcome.Failure, "expected Failure, got $outcome")
        assertEquals(DownloadFailureReason.TRUNCATED_BODY, (outcome as DownloadOutcome.Failure).reason)
    }

    // ─── Case borde 1: Range ignored (200 instead of 206) — C10 ────────────────

    @Test
    fun `a Range request answered with 200 truncates the file and reports RANGE_NOT_HONORED`() = runTest {
        staging.writeBytes(ByteArray(10) { 1 }) // 10 stale bytes from a previous partial attempt
        server.enqueue(MockResponse.Builder().code(200).body("the whole file, from scratch").build())

        val outcome = downloader.download(staging, specFor(), resumeFromBytes = 10L)

        assertTrue(outcome is DownloadOutcome.Failure)
        assertEquals(DownloadFailureReason.RANGE_NOT_HONORED, (outcome as DownloadOutcome.Failure).reason)
        assertEquals(0L, staging.length(), "the stale partial bytes must be discarded")
    }

    @Test
    fun `Range ignored (200) invalidates the server's cached Range support`() = runTest {
        staging.writeBytes(ByteArray(10))
        server.enqueue(MockResponse.Builder().code(200).body("whole file").build())
        val spec = specFor()

        downloader.download(staging, spec, resumeFromBytes = 10L)

        coVerify(exactly = 1) { capabilities.invalidateSupportsRange(spec.serverKey, any()) }
    }

    @Test
    fun `resumeFromBytes of 0 never sends a Range header at all`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("full body").build())

        downloader.download(staging, specFor(), resumeFromBytes = 0L)

        val received = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals(null, received?.headers?.get("Range"))
    }

    @Test
    fun `a server already known not to support Range skips the Range header outright`() = runTest {
        coEvery { capabilities.cachedSupportsRange(any(), any()) } returns false
        staging.writeBytes(ByteArray(10))
        server.enqueue(MockResponse.Builder().code(200).body("full body").build())

        downloader.download(staging, specFor(), resumeFromBytes = 10L)

        val received = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals(null, received?.headers?.get("Range"))
    }

    // ─── Case borde 2: 206 with a mismatched validator ─────────────────────────

    @Test
    fun `a 206 whose Content-Range start doesn't match what was asked is a validator mismatch`() = runTest {
        staging.writeBytes(ByteArray(10))
        server.enqueue(
            MockResponse.Builder().code(206).body("wrong slice")
                .setHeader("Content-Range", "bytes 0-10/100")
                .build()
        )

        val outcome = downloader.download(staging, specFor(), resumeFromBytes = 10L)

        assertTrue(outcome is DownloadOutcome.Failure)
        assertEquals(DownloadFailureReason.RANGE_VALIDATOR_MISMATCH, (outcome as DownloadOutcome.Failure).reason)
        assertEquals(0L, staging.length())
    }

    @Test
    fun `a 206 whose declared total doesn't match expected_bytes is a validator mismatch`() = runTest {
        staging.writeBytes(ByteArray(10))
        server.enqueue(
            MockResponse.Builder().code(206).body("some bytes")
                .setHeader("Content-Range", "bytes 10-19/12345")
                .build()
        )

        val outcome = downloader.download(staging, specFor(expectedBytes = 99_999L), resumeFromBytes = 10L)

        assertTrue(outcome is DownloadOutcome.Failure)
        assertEquals(DownloadFailureReason.RANGE_VALIDATOR_MISMATCH, (outcome as DownloadOutcome.Failure).reason)
    }

    @Test
    fun `a 206 with no Content-Range at all cannot be trusted either`() = runTest {
        staging.writeBytes(ByteArray(10))
        server.enqueue(MockResponse.Builder().code(206).body("bytes").removeHeader("Content-Range").build())

        val outcome = downloader.download(staging, specFor(), resumeFromBytes = 10L)

        assertTrue(outcome is DownloadOutcome.Failure)
        assertEquals(DownloadFailureReason.RANGE_VALIDATOR_MISMATCH, (outcome as DownloadOutcome.Failure).reason)
    }

    @Test
    fun `a matching 206 appends after the existing bytes and reports the combined total`() = runTest {
        val existing = "0123456789"
        staging.writeBytes(existing.toByteArray())
        val appended = "ABCDEFGHIJ"
        server.enqueue(
            MockResponse.Builder().code(206).body(appended)
                .setHeader("Content-Range", "bytes 10-19/20")
                .build()
        )

        val outcome = downloader.download(staging, specFor(expectedBytes = 20L), resumeFromBytes = 10L)

        assertTrue(outcome is DownloadOutcome.Success, "expected Success, got $outcome")
        assertEquals(20L, (outcome as DownloadOutcome.Success).totalBytes)
        assertEquals(existing + appended, staging.readText())
    }

    @Test
    fun `a matching 206 confirms Range support to the store, refreshing its TTL`() = runTest {
        staging.writeBytes(ByteArray(10))
        server.enqueue(
            MockResponse.Builder().code(206).body("ABCDEFGHIJ")
                .setHeader("Content-Range", "bytes 10-19/20")
                .build()
        )
        val spec = specFor(expectedBytes = 20L)

        downloader.download(staging, spec, resumeFromBytes = 10L)

        coVerify(exactly = 1) { capabilities.recordProbeResult(spec.serverKey, true, any()) }
    }

    // ─── Case borde 3: 416 ──────────────────────────────────────────────────────

    @Test
    fun `a 416 truncates the file and reports RANGE_NOT_SATISFIABLE`() = runTest {
        staging.writeBytes(ByteArray(10))
        server.enqueue(MockResponse.Builder().code(416).build())

        val outcome = downloader.download(staging, specFor(), resumeFromBytes = 10L)

        assertTrue(outcome is DownloadOutcome.Failure)
        assertEquals(DownloadFailureReason.RANGE_NOT_SATISFIABLE, (outcome as DownloadOutcome.Failure).reason)
        assertEquals(0L, staging.length())
    }

    // ─── Everything else unexpected ─────────────────────────────────────────────

    @Test
    fun `an unexpected HTTP status is a transient failure, not a crash`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).build())

        val outcome = downloader.download(staging, specFor())

        assertTrue(outcome is DownloadOutcome.Failure)
        assertEquals(DownloadFailureReason.TRANSIENT, (outcome as DownloadOutcome.Failure).reason)
    }

    @Test
    fun `an unreachable server is a transient failure, not an uncaught exception`() = runTest {
        server.close()

        val outcome = downloader.download(staging, specFor())

        assertTrue(outcome is DownloadOutcome.Failure)
        assertEquals(DownloadFailureReason.TRANSIENT, (outcome as DownloadOutcome.Failure).reason)
    }

    // ─── Cancellation in under 1 second ────────────

    @Test
    fun `cancelling mid-transfer interrupts the blocked read in under 1 second`() {
        // Real wall-clock time, deliberately not runTest: runTest's virtual clock would
        // auto-advance delay(200) instantly, racing the IO thread instead of actually letting
        // it block on a read first — this needs the transfer to be genuinely in flight.
        val slowBody = "x".repeat(2_000_000)
        server.enqueue(
            MockResponse.Builder().code(200).body(slowBody)
                // ~2 MB at 1 KB per 100 ms is ~200 s to fully deliver — comfortably slower
                // than the delay+cancel below, so the coroutine is genuinely blocked on read().
                .throttleBody(1_024, 100, TimeUnit.MILLISECONDS)
                .build()
        )

        val elapsed = measureTimeMillis {
            runBlocking {
                val deferred = async(Dispatchers.IO) { downloader.download(staging, specFor()) }
                delay(200) // let the transfer actually start and block on a read
                deferred.cancel()
                try {
                    deferred.await()
                } catch (e: Exception) {
                    // Expected: the cancelled call surfaces as a cancellation/IO exception.
                }
            }
        }

        assertTrue(elapsed < 1_000, "cancellation took ${elapsed}ms, expected well under 1000ms")
    }
}
