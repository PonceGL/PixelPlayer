package com.theveloper.pixelplay.data.sonos.net

import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** UPnP `SUBSCRIBE`/`UNSUBSCRIBE` to port 1400 of a speaker, always through the Wi-Fi network. */
internal class HttpSonosSubscriber(
    private val network: LanNetworkProvider,
    private val baseClient: OkHttpClient,
    private val io: CoroutineDispatcher,
    private val port: Int = SONOS_PORT,
) : SonosSubscriber {

    override suspend fun subscribe(
        host: String,
        service: SonosService,
        callbackUrl: String,
        timeoutSeconds: Int,
    ): SonosResult<Subscription> = send(host, service, SUBSCRIBE, requestedSid = null, requestedSeconds = timeoutSeconds) { builder ->
        builder.header("CALLBACK", "<$callbackUrl>")
        builder.header("NT", "upnp:event")
        builder.header("TIMEOUT", "$SECOND_PREFIX$timeoutSeconds")
    }

    override suspend fun renew(
        host: String,
        service: SonosService,
        sid: String,
        timeoutSeconds: Int,
    ): SonosResult<Subscription> = send(host, service, SUBSCRIBE, requestedSid = sid, requestedSeconds = timeoutSeconds) { builder ->
        builder.header("SID", sid)
        builder.header("TIMEOUT", "$SECOND_PREFIX$timeoutSeconds")
    }

    override suspend fun unsubscribe(host: String, service: SonosService, sid: String): SonosResult<Unit> =
        when (val result = send(host, service, UNSUBSCRIBE, requestedSid = sid, requestedSeconds = SonosSubscriber.DEFAULT_TIMEOUT_SECONDS) { it.header("SID", sid) }) {
            is SonosResult.Ok -> SonosResult.Ok(Unit)
            is SonosResult.Err -> {
                val failure = result.failure
                val forgotten = failure is SonosFailure.Upnp && failure.kind == UpnpErrorKind.PreconditionFailed
                if (forgotten) SonosResult.Ok(Unit) else result
            }
        }

    private suspend fun send(
        host: String,
        service: SonosService,
        method: String,
        requestedSid: String?,
        requestedSeconds: Int,
        headers: (Request.Builder) -> Unit,
    ): SonosResult<Subscription> = withContext(io) {
        val lan = network.wifi() ?: return@withContext SonosResult.Err(SonosFailure.NoWifi)
        val url = "http://$host:$port${service.eventPath}".toHttpUrlOrNull()
            ?: return@withContext SonosResult.Err(SonosFailure.MalformedResponse)
        val client = baseClient.newBuilder()
            .socketFactory(lan.socketFactory)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val request = try {
            val builder = Request.Builder().url(url).header("Connection", "close")
            headers(builder)
            builder.method(method, null).build()
        } catch (_: IllegalArgumentException) {
            // A SID or callback with line breaks or non-ASCII text cannot be sent as a header.
            return@withContext SonosResult.Err(SonosFailure.MalformedResponse)
        }
        try {
            client.newCall(request).await().use { response -> interpret(method, response, requestedSid, requestedSeconds) }
        } catch (e: IOException) {
            SonosResult.Err(NetFailureClassifier.classify(e))
        }
    }

    private fun interpret(
        method: String,
        response: Response,
        requestedSid: String?,
        requestedSeconds: Int,
    ): SonosResult<Subscription> {
        // Only a request that carried a SID can be answered "unknown SID"; a first subscribe rejected with 412
        // has bad CALLBACK/NT headers and retrying it unchanged would loop.
        if (response.code == PRECONDITION_FAILED && requestedSid != null) {
            return SonosResult.Err(SonosFailure.Upnp(method, UpnpErrorKind.PreconditionFailed))
        }
        if (!response.isSuccessful) return SonosResult.Err(SonosFailure.Network("HTTP ${response.code}"))
        val sid = response.header("SID")?.takeIf { it.isNotBlank() } ?: requestedSid
            ?: return SonosResult.Err(SonosFailure.MalformedResponse)
        return SonosResult.Ok(Subscription(sid, grantedSeconds(response.header("TIMEOUT"), requestedSeconds)))
    }

    /** `Second-N`; `infinite`, a missing value, zero, a negative or an unreadable one fall back to the timeout that was asked for. */
    private fun grantedSeconds(header: String?, requestedSeconds: Int): Int {
        val value = header?.trim()
        val seconds = value?.takeIf { it.startsWith(SECOND_PREFIX, ignoreCase = true) }
            ?.substring(SECOND_PREFIX.length)?.toIntOrNull()
        return if (seconds != null && seconds > 0) seconds else requestedSeconds
    }

    private companion object {
        const val SONOS_PORT = 1400
        const val SUBSCRIBE = "SUBSCRIBE"
        const val UNSUBSCRIBE = "UNSUBSCRIBE"
        const val SECOND_PREFIX = "Second-"
        const val PRECONDITION_FAILED = 412
        const val REQUEST_TIMEOUT_MS = 4_000L
    }
}
