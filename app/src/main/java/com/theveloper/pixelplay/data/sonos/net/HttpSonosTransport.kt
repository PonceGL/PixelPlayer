package com.theveloper.pixelplay.data.sonos.net

import com.theveloper.pixelplay.data.sonos.protocol.SoapEnvelope
import com.theveloper.pixelplay.data.sonos.protocol.SoapResponse
import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import com.theveloper.pixelplay.data.sonos.protocol.UpnpErrors
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber

/** SOAP over HTTP to port 1400 of a speaker, always through the Wi-Fi network. */
internal class HttpSonosTransport(
    private val network: LanNetworkProvider,
    private val baseClient: OkHttpClient,
    private val io: CoroutineDispatcher,
    private val port: Int = SONOS_PORT,
) : SonosTransport {

    /** Every conversation leaves one line (action, outcome, time): without it a session that goes wrong cannot be followed. */
    override suspend fun call(
        host: String,
        service: SonosService,
        action: String,
        arguments: List<Pair<String, String>>,
        timeoutMs: Long,
    ): SonosResult<Map<String, String>> {
        val startedNs = System.nanoTime()
        val result = send(host, service, action, arguments, timeoutMs)
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
        val outcome = if (result is SonosResult.Err) result.failure.safeName() else "ok"
        Timber.tag(LOG_TAG).d("%s %s in %d ms", action, outcome, tookMs)
        return result
    }

    private suspend fun send(
        host: String,
        service: SonosService,
        action: String,
        arguments: List<Pair<String, String>>,
        timeoutMs: Long,
    ): SonosResult<Map<String, String>> = withContext(io) {
        val lan = network.wifi() ?: return@withContext SonosResult.Err(SonosFailure.NoWifi)
        val client = baseClient.newBuilder()
            .socketFactory(lan.socketFactory)
            .retryOnConnectionFailure(false)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val url = "http://$host:$port${service.controlPath}".toHttpUrlOrNull()
            ?: return@withContext SonosResult.Err(SonosFailure.MalformedResponse)
        val request = Request.Builder()
            .url(url)
            .header("SOAPACTION", SoapEnvelope.soapActionHeader(service, action))
            .header("Connection", "close")
            .post(SoapEnvelope.build(service, action, arguments).toRequestBody(XML_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).await().use { response -> interpret(action, response) }
        } catch (e: IOException) {
            SonosResult.Err(NetFailureClassifier.classify(e))
        }
    }

    private fun interpret(action: String, response: Response): SonosResult<Map<String, String>> {
        val body = response.peekBody(MAX_RESPONSE_BYTES).string()
        return when (val parsed = SoapResponse.parse(body)) {
            is SoapResponse.Success -> SonosResult.Ok(parsed.values)
            is SoapResponse.Fault -> SonosResult.Err(SonosFailure.Upnp(action, UpnpErrors.classify(action, parsed.upnpErrorCode)))
            SoapResponse.Malformed -> SonosResult.Err(SonosFailure.MalformedResponse)
        }
    }

    private companion object {
        const val LOG_TAG = "SonosSession"
        const val SONOS_PORT = 1400
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
        val XML_MEDIA_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()
    }
}
