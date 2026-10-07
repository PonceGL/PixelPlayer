package com.theveloper.pixelplay.spike.sonos

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class SoapResult(
    val ok: Boolean,
    val status: Int? = null,
    val values: Map<String, String> = emptyMap(),
    val errorCode: Int? = null,
    val errorDesc: String = "",
    val latencyMs: Double? = null,
    val raw: String = "",
    val netError: String = "",
)

/** SPIKE (fase B). Espejo de `soap_call` del harness, con el mismo evento `soap`. */
object SonosSoap {

    /** -> (valores, código UPnP, descripción) */
    fun parseResponse(raw: String): Triple<Map<String, String>, Int?, String> {
        val root = SonosXml.parse(raw) ?: return Triple(emptyMap(), null, "la respuesta no es XML válido")
        val body = root.child("Body")
        val first = body?.children?.firstOrNull() ?: return Triple(emptyMap(), null, "")
        if (first.name == "Fault") {
            val code = first.descendants("errorCode").firstOrNull()?.text?.toString()?.trim()?.toIntOrNull()
            var desc = first.descendants("errorDescription").firstOrNull()?.text?.toString()?.trim().orEmpty()
            if (desc.isEmpty()) desc = first.descendants("faultstring").firstOrNull()?.text?.toString()?.trim().orEmpty()
            return Triple(emptyMap(), code ?: -1, desc.ifEmpty { "SOAP Fault" })
        }
        return Triple(first.children.associate { it.name to it.text.toString() }, null, "")
    }

    fun call(
        context: Context,
        log: SonosRunLog,
        ip: String,
        service: String,
        action: String,
        args: Map<String, Any?> = emptyMap(),
        write: Boolean = false,
        timeoutMs: Long = 5000,
        quiet: Boolean = false,
    ): SoapResult {
        require(write || action.startsWith("Get") || action == "Browse") { "$action no es de solo lectura; pasa write=true de forma explícita" }
        val svc = SonosProtocol.SERVICES.getValue(service)
        val body = SonosProtocol.buildEnvelope(svc.type, action, args)
        val meta = linkedMapOf<String, Any?>(
            "ip" to ip, "service" to service, "action" to action, "args" to args, "write" to write,
        )
        val request = Request.Builder()
            .url("http://$ip:${SonosProtocol.PORT}${svc.path}")
            .header("SOAPACTION", "\"${svc.type}#$action\"")
            .header("User-Agent", SonosProtocol.USER_AGENT)
            .header("Connection", "close")
            .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()

        var status: Int? = null
        var raw = ""
        var netError = ""
        var hint = ""
        val t0 = System.nanoTime()
        try {
            SonosNet.httpClient(context, timeoutMs).newCall(request).execute().use { resp ->
                status = resp.code
                raw = resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            netError = "${e.javaClass.simpleName}: ${e.message}"
            hint = SonosNet.netHint(e)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val latency = Math.round(ms * 10) / 10.0

        if (netError.isNotEmpty()) {
            log.event("soap", meta + mapOf("ok" to false, "net_error" to netError, "hint" to hint,
                "latency_ms" to latency, "request_body" to body))
            log.warn("$service.$action: error de red ($netError)${if (hint.isNotEmpty()) " - $hint" else ""}")
            return SoapResult(ok = false, latencyMs = ms, netError = netError)
        }

        val (values, code, desc) = parseResponse(raw)
        val ok = status == 200 && code == null
        if (!(quiet && ok)) {
            log.event("soap", meta + mapOf("ok" to ok, "status" to status, "error_code" to code, "error_desc" to desc,
                "latency_ms" to latency, "request_body" to body, "response_body" to raw))
        }
        if (ok) {
            if (!quiet) log.debug("$service.$action $args -> OK (${ms.toInt()} ms)")
        } else {
            var msg = "$service.$action falló: HTTP $status"
            if (code != null) msg += ", UPnP $code $desc ${SonosProtocol.UPNP_ERRORS[code]?.let { "($it)" } ?: ""}"
            if (status == 401 || status == 403) msg += " - posible ajuste de autenticación de Sonos para integraciones"
            log.warn(msg)
        }
        return SoapResult(ok, status, values, code, desc, ms, raw)
    }
}
