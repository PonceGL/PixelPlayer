package com.theveloper.pixelplay.data.download.engine

import com.theveloper.pixelplay.data.download.DownloadServerCapabilitiesStore
import com.theveloper.pixelplay.data.download.model.DownloadRequestSpec
import com.theveloper.pixelplay.di.DownloadOkHttpClient
import com.theveloper.pixelplay.di.IoDispatcher
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

/** The outcome of one [HttpFileDownloader.download] call — never an uncaught exception. */
sealed interface DownloadOutcome {
    /** [totalBytes] is the staging file's final size — already `fsync`ed by the time this returns. */
    data class Success(val totalBytes: Long) : DownloadOutcome

    /**
     * [reason] is deliberately not a `CloudDownloadState`: assigning one here would be doing
     * F1.6c's (the engine's) and F3.3's (`DownloadErrorClassifier`'s) job early. This is only
     * the raw fact of what happened at the HTTP/file layer — F1.6's own intro line applies:
     * *"en F1 basta con distinguir 'reintentable' de 'no'"*, and even that coarser call is left
     * to the caller, which has the row context this class doesn't.
     */
    data class Failure(
        val reason: DownloadFailureReason,
        val message: String,
        val cause: Throwable? = null,
    ) : DownloadOutcome
}

enum class DownloadFailureReason {
    /** A network-level hiccup (timeout, reset, an unexpected HTTP status) — try again later. */
    TRANSIENT,
    /** `Content-Type` wasn't an `audio/` type or `application/octet-stream` (C15 guard #1). */
    UNEXPECTED_CONTENT_TYPE,
    /** A `Range` request got a `200` back instead of `206` — the server doesn't honor `Range`. */
    RANGE_NOT_HONORED,
    /** A `206`'s `Content-Range` didn't match what was asked for or expected — can't trust it. */
    RANGE_VALIDATOR_MISMATCH,
    /** `416 Requested Range Not Satisfiable`. */
    RANGE_NOT_SATISFIABLE,
    /** The body ended before `Content-Length` said it would. */
    TRUNCATED_BODY,
    /** The finished file's size doesn't match what was expected. */
    SIZE_MISMATCH,
}

/**
 * Bytes to disk, correct or nothing (`F1.6b`, `F1.md` §F1.6). Composes a [DownloadRequestSpec]
 * (F1.4) with a staging [File] (F1.5) — it knows about neither Jellyfin nor Room (I6): every
 * fact it needs travels in through its parameters.
 *
 * **Single attempt per call.** Every condition below that needs "truncate and start over"
 * (a `Range` the server didn't honor, a `206` that doesn't check out, `416`) truncates the
 * staging file and returns a [DownloadOutcome.Failure] — it does **not** silently re-issue a
 * second request internally. The caller (`F1.6c`) decides whether and when to call [download]
 * again with `resumeFromBytes = 0`; this keeps every path here a single, deterministic HTTP
 * exchange, and keeps `416`'s "reintentable la primera vez, `FAILED_CORRUPT` la segunda"
 * escalation (`F1.md` §F1.6 case borde 3) counting across calls, in the caller — the same place
 * that owns the row that count belongs to.
 *
 * **Cancellation (`AND-CONC-04`).** `job.invokeOnCompletion { call.cancel() }` is registered
 * *before* [OkHttpClient.newCall]'s blocking [okhttp3.Call.execute] even starts, so a socket
 * blocked mid-`read()` unblocks immediately instead of waiting out `readTimeout` — this is what
 * the Wear precedent (`PhoneDirectWatchTransferCoordinator`) gets wrong by polling instead.
 * `ensureActive()` also runs every iteration of the copy loop, for the case where cancellation
 * lands between reads rather than during one.
 */
@Singleton
class HttpFileDownloader @Inject constructor(
    @DownloadOkHttpClient private val okHttpClient: OkHttpClient,
    private val serverCapabilities: DownloadServerCapabilitiesStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * @param resumeFromBytes How many bytes of [staging] are already on disk and should be
     * kept. `0` means a fresh download. A `Range` request is only attempted when this is `> 0`
     * **and** [spec]'s server isn't cached as known-not-to-support it — `spec.supportsRange`
     * itself already reflects that cache (`JellyfinCloudDownloadSource`, F1.6b), this is a
     * second, request-time check because that cache can change between when the spec was built
     * and when this actually runs.
     */
    suspend fun download(
        staging: File,
        spec: DownloadRequestSpec,
        resumeFromBytes: Long = 0L,
    ): DownloadOutcome = withContext(ioDispatcher) {
        val attemptRange = resumeFromBytes > 0L &&
            serverCapabilities.cachedSupportsRange(spec.serverKey) != false

        val requestBuilder = Request.Builder().url(spec.url)
        spec.headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        if (attemptRange) {
            requestBuilder.header("Range", "bytes=$resumeFromBytes-")
        }
        val call = okHttpClient.newCall(requestBuilder.build())

        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                handleResponse(response, staging, spec, attemptRange, resumeFromBytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            coroutineContext.ensureActive() // a cancellation-triggered call.cancel() surfaces as
            // an IOException too — ensureActive() rethrows CancellationException first if so.
            DownloadOutcome.Failure(DownloadFailureReason.TRANSIENT, e.message ?: "IO error", e)
        } finally {
            cancelHandle?.dispose()
        }
    }

    private suspend fun handleResponse(
        response: Response,
        staging: File,
        spec: DownloadRequestSpec,
        rangeRequested: Boolean,
        resumeFromBytes: Long,
    ): DownloadOutcome {
        if (response.code == 416) {
            truncate(staging)
            return DownloadOutcome.Failure(
                DownloadFailureReason.RANGE_NOT_SATISFIABLE,
                "416 Requested Range Not Satisfiable",
            )
        }

        if (rangeRequested && response.code == 200) {
            // The server ignored Range entirely (case borde 1, C10): what we already had on
            // disk is worthless now, and so is trusting Range for this server going forward.
            truncate(staging)
            serverCapabilities.invalidateSupportsRange(spec.serverKey)
            return DownloadOutcome.Failure(
                DownloadFailureReason.RANGE_NOT_HONORED,
                "Server returned 200 for a Range request",
            )
        }

        if (rangeRequested && response.code == 206) {
            val contentRange = parseContentRange(response.header("Content-Range"))
            val mismatched = contentRange == null ||
                contentRange.start != resumeFromBytes ||
                (spec.expectedBytes != null && contentRange.total != null && contentRange.total != spec.expectedBytes)
            if (mismatched) {
                truncate(staging)
                return DownloadOutcome.Failure(
                    DownloadFailureReason.RANGE_VALIDATOR_MISMATCH,
                    "Content-Range didn't match what was requested/expected: ${response.header("Content-Range")}",
                )
            }
        }

        // 416, and a Range request answered with 200 or a mismatched 206, are already handled
        // above. Anything else that isn't the plain success code for what was actually asked
        // (5xx, a stray redirect, ...) is F3.3's finer classification to make — this is only
        // the coarse "not what we asked for" bucket.
        val expectedSuccessCode = if (rangeRequested) 206 else 200
        if (response.code != expectedSuccessCode) {
            return DownloadOutcome.Failure(
                DownloadFailureReason.TRANSIENT,
                "Unexpected HTTP ${response.code}",
            )
        }

        val contentType = response.header("Content-Type")
        if (!isAcceptableContentType(contentType)) {
            // C15 guard #1: rejected before a single byte is written.
            return DownloadOutcome.Failure(
                DownloadFailureReason.UNEXPECTED_CONTENT_TYPE,
                "Unexpected Content-Type: $contentType",
            )
        }

        val body = response.body // non-null: this OkHttp always supplies a body for a real response
        val writeFromPosition = if (response.code == 206) resumeFromBytes else 0L
        val declaredContentLength = body.contentLength().takeIf { it >= 0 }

        val totalBytes = try {
            writeBody(staging, body, writeFromPosition)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            return DownloadOutcome.Failure(
                DownloadFailureReason.TRUNCATED_BODY,
                e.message ?: "Body ended unexpectedly",
                e,
            )
        }

        if (declaredContentLength != null && totalBytes != writeFromPosition + declaredContentLength) {
            return DownloadOutcome.Failure(
                DownloadFailureReason.TRUNCATED_BODY,
                "Content-Length promised ${writeFromPosition + declaredContentLength} bytes total, got $totalBytes",
            )
        }

        // C15 guard #2: expected_bytes is authoritative when the caller has it; Content-Length
        // is only the last-resort fallback (it "se valida a sí mismo" — PLAN.md C15).
        val expectedFinalSize = spec.expectedBytes ?: declaredContentLength?.let { writeFromPosition + it }
        if (expectedFinalSize != null && totalBytes != expectedFinalSize) {
            return DownloadOutcome.Failure(
                DownloadFailureReason.SIZE_MISMATCH,
                "Expected $expectedFinalSize bytes, got $totalBytes",
            )
        }

        if (response.code == 206) {
            // A Range request that actually worked end to end confirms — and refreshes — the
            // capability, resetting the 30-day TTL (F1.4b, §4.5).
            serverCapabilities.recordProbeResult(spec.serverKey, supportsRange = true)
        }

        return DownloadOutcome.Success(totalBytes)
    }

    /**
     * Writes [body] into [staging] starting at [fromPosition], `fsync`ing before returning —
     * *always*, on every path out of this function, per F1.6's own lesson: a size check that
     * passes on an un-synced file after a power cut is exactly the silent corruption C15/this
     * task exist to prevent.
     */
    private suspend fun writeBody(staging: File, body: ResponseBody, fromPosition: Long): Long {
        RandomAccessFile(staging, "rw").use { raf ->
            raf.setLength(fromPosition) // discards any stale tail from a previous, different attempt
            raf.seek(fromPosition)
            body.byteStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    raf.write(buffer, 0, read)
                }
            }
            raf.fd.sync()
            return raf.length()
        }
    }

    private fun truncate(staging: File) {
        RandomAccessFile(staging, "rw").use { it.setLength(0L) }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

private fun isAcceptableContentType(rawContentType: String?): Boolean {
    if (rawContentType == null) return true // absence proves nothing either way
    val mediaType = rawContentType.substringBefore(';').trim().lowercase()
    return mediaType.startsWith("audio/") || mediaType == "application/octet-stream"
}

private data class ContentRange(val start: Long, val total: Long?)

/** Parses a `Content-Range` header (`bytes start-end/total`, total possibly `*`); `null` if malformed. */
private fun parseContentRange(header: String?): ContentRange? {
    if (header == null) return null
    val match = Regex("""^bytes (\d+)-\d+/(\d+|\*)$""").find(header.trim()) ?: return null
    val (start, totalRaw) = match.destructured
    return ContentRange(start = start.toLong(), total = totalRaw.toLongOrNull())
}
