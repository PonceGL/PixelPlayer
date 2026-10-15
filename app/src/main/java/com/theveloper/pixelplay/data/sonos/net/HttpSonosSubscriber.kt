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
    ): SonosResult<Subscription> = send(host, service, SUBSCRIBE, requestedSid = null) { builder ->
        builder.header("CALLBACK", "<$callbackUrl>")
        builder.header("NT", "upnp:event")
        builder.header("TIMEOUT", "Second-$timeoutSeconds")
    }

    override suspend fun renew(
        host: String,
        service: SonosService,
        sid: String,
        timeoutSeconds: Int,
    ): SonosResult<Subscription> = send(host, service, SUBSCRIBE, requestedSid = sid) { builder ->
        builder.header("SID", sid)
        builder.header("TIMEOUT", "Second-$timeoutSeconds")
    }

    override suspend fun unsubscribe(host: String, service: SonosService, sid: String): SonosResult<Unit> =
        when (val result = send(host, service, UNSUBSCRIBE, requestedSid = sid) { it.header("SID", sid) }) {
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
        headers: (Request.Builder) -> Unit,
    ): SonosResult<Subscription> = withContext(io) {
        val lan = network.wifi() ?: return@withContext SonosResult.Err(SonosFailure.NoWifi)
        val url = "http://$host:$port${service.eventPath}".toHttpUrlOrNull()
            ?: return@withContext SonosResult.Err(SonosFailure.MalformedResponse)
        val client = baseClient.newBuilder()
            .socketFactory(lan.socketFactory)
            .retryOnConnectionFailure(false)
            .connectTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val builder = Request.Builder().url(url).header("Connection", "close")
        headers(builder)
        val request = builder.method(method, null).build()
        try {
            client.newCall(request).await().use { response -> interpret(method, response, requestedSid) }
        } catch (e: IOException) {
            SonosResult.Err(NetFailureClassifier.classify(e))
        }
    }

    private fun interpret(method: String, response: Response, requestedSid: String?): SonosResult<Subscription> {
        if (response.code == PRECONDITION_FAILED) {
            return SonosResult.Err(SonosFailure.Upnp(method, UpnpErrorKind.PreconditionFailed))
        }
        if (!response.isSuccessful) return SonosResult.Err(SonosFailure.Network("HTTP ${response.code}"))
        val sid = response.header("SID")?.takeIf { it.isNotBlank() } ?: requestedSid
            ?: return SonosResult.Err(SonosFailure.MalformedResponse)
        return SonosResult.Ok(Subscription(sid, grantedSeconds(response.header("TIMEOUT"))))
    }

    /** `Second-N`; `infinite`, a missing or an unreadable value fall back to the default. */
    private fun grantedSeconds(header: String?): Int {
        val seconds = header?.trim()?.removePrefix("Second-")?.toIntOrNull()
        return if (seconds != null && seconds > 0) seconds else SonosSubscriber.DEFAULT_TIMEOUT_SECONDS
    }

    private companion object {
        const val SONOS_PORT = 1400
        const val SUBSCRIBE = "SUBSCRIBE"
        const val UNSUBSCRIBE = "UNSUBSCRIBE"
        const val PRECONDITION_FAILED = 412
        const val REQUEST_TIMEOUT_MS = 4_000L
    }
}
