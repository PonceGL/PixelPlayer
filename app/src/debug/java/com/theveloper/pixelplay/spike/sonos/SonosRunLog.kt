package com.theveloper.pixelplay.spike.sonos

import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * SPIKE (fase A). Log JSONL con el MISMO formato que `harness/sonos_lab.py::RunLog`:
 * una línea por evento con `ts` (ISO-8601 con offset y milisegundos), `t_ms` (ms
 * monotónicos desde el inicio de la corrida), `kind` y los campos propios del evento.
 * Así los logs del teléfono se pueden comparar con los del script.
 *
 * Diferencias esperables en `run_start`: en vez de argv/python/platform lleva datos del
 * dispositivo Android (model, manufacturer, sdk_int, app_version).
 */
class SonosRunLog private constructor(
    val name: String,
    val dir: File,
) {
    val runId: String = dir.name
    val file: File = File(dir, "log.jsonl")

    private val t0 = SystemClock.elapsedRealtime()
    private val lock = Any()
    private val writer: BufferedWriter =
        BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** Últimas líneas (texto legible) para mostrarlas en la pantalla de depuración. */
    val lines: StateFlow<List<String>> = _lines

    fun event(kind: String, vararg fields: Pair<String, Any?>) = event(kind, fields.toMap())

    fun event(kind: String, fields: Map<String, Any?>) {
        val now = OffsetDateTime.now()
        val rec = JSONObject()
        rec.put("ts", TS_FORMAT.format(now))
        rec.put("t_ms", SystemClock.elapsedRealtime() - t0)
        rec.put("kind", kind)
        for ((k, v) in fields) {
            // Los nombres reservados no se pisan (en el harness `kind` ya lo tenía reservado).
            val key = if (k == "ts" || k == "t_ms" || k == "kind") "field_$k" else k
            rec.put(key, toJson(v))
        }
        val line = rec.toString()
        synchronized(lock) {
            runCatching {
                writer.write(line)
                writer.write("\n")
                writer.flush()
            }
            val shortTs = TS_SHORT.format(now)
            val summary = fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
            val text = "$shortTs $kind $summary".trim()
            _lines.value = (_lines.value + text).takeLast(MAX_UI_LINES)
        }
    }

    fun info(msg: String) = event("info", "msg" to msg)
    fun warn(msg: String) = event("warn", "msg" to msg)
    fun err(msg: String) = event("error", "msg" to msg)
    fun debug(msg: String) = event("debug", "msg" to msg)

    fun close() {
        synchronized(lock) { runCatching { writer.close() } }
    }

    private fun toJson(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJson(x)) } }
        is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
        is Array<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
        is Number, is Boolean, is String -> v
        else -> v.toString()
    }

    companion object {
        private const val MAX_UI_LINES = 400
        private val TS_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx", Locale.US)
        private val TS_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
        private val RUN_ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)

        /** Raíz de los logs: `Android/data/<pkg>/files/sonos-spike/` (se puede hacer `adb pull`). */
        fun root(context: Context): File {
            val base = context.getExternalFilesDir("sonos-spike") ?: File(context.filesDir, "sonos-spike")
            return base.also { it.mkdirs() }
        }

        fun create(context: Context, name: String): SonosRunLog {
            val id = RUN_ID_FORMAT.format(OffsetDateTime.now()) + "_" + name
            val dir = File(root(context), id).also { it.mkdirs() }
            val log = SonosRunLog(name, dir)
            log.event(
                "run_start",
                "platform" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "sdk_int" to Build.VERSION.SDK_INT,
                "app_id" to context.packageName,
                "app_version" to appVersion(context),
            )
            return log
        }

        private fun appVersion(context: Context): String = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "?"
        }.getOrDefault("?")
    }
}

/** Corrida activa compartida por la pantalla y, más adelante, por el cliente SOAP/servidor. */
object SonosSpike {
    @Volatile
    private var current: SonosRunLog? = null

    @Synchronized
    fun current(context: Context): SonosRunLog =
        current ?: SonosRunLog.create(context.applicationContext, "spike").also { current = it }

    @Synchronized
    fun newRun(context: Context, name: String = "spike"): SonosRunLog {
        current?.let {
            it.event("run_end")
            it.close()
        }
        return SonosRunLog.create(context.applicationContext, name).also { current = it }
    }
}
