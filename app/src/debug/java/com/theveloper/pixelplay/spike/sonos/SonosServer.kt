package com.theveloper.pixelplay.spike.sonos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.theveloper.pixelplay.data.service.http.CastSessionSecurity
import com.theveloper.pixelplay.data.service.http.MediaFileHttpServerService
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * SPIKE (fase C). Reutiliza `MediaFileHttpServerService` (el servidor de Chromecast) con tres añadidos mínimos:
 * `?raw=1` (sin transcodificar), token/puerto persistidos y log de peticiones. Aquí solo hay el pegamento.
 */
object SonosServer {
    private const val PREFS = "sonos_spike"

    data class AudioItem(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val mime: String,
        val size: Long,
        val name: String,
    )

    data class Published(val item: AudioItem, val songUrl: String, val artUrl: String)

    /** Token persistido: si el proceso muere, el siguiente arranque genera las mismas URLs. */
    @Synchronized
    fun token(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("token", null)?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("token", t).apply()
        return t
    }

    fun statusLine(): String =
        "running=${MediaFileHttpServerService.isServerRunning} starting=${MediaFileHttpServerService.isServerStarting} " +
            "addr=${MediaFileHttpServerService.serverAddress} fail=${MediaFileHttpServerService.lastFailureReason}"

    fun start(context: Context, log: SonosRunLog, sonosIp: String?): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedPort = prefs.getInt("server_port", 0)
        MediaFileHttpServerService.preferredPortOverride = savedPort.takeIf { it in 1024..65535 }
        MediaFileHttpServerService.spikeRequestLogger = { m -> SonosSpike.current(app).event(if (m["event"] == "song_transfer") "http_transfer" else "http_request", m) }

        val t0 = SystemClock.elapsedRealtime()
        if (MediaFileHttpServerService.isServerRunning && MediaFileHttpServerService.serverAddress != null) {
            log.info("El servidor ya estaba en marcha: ${MediaFileHttpServerService.serverAddress}")
        } else {
            MediaFileHttpServerService.lastFailureReason = null
            MediaFileHttpServerService.lastFailureMessage = null
            val intent = Intent(app, MediaFileHttpServerService::class.java).apply {
                action = MediaFileHttpServerService.ACTION_START_SERVER
                sonosIp?.takeIf { it.isNotBlank() }?.let { putExtra(MediaFileHttpServerService.EXTRA_CAST_DEVICE_IP, it) }
            }
            app.startForegroundService(intent)
        }
        while (SystemClock.elapsedRealtime() - t0 < 20_000) {
            if (MediaFileHttpServerService.isServerRunning && MediaFileHttpServerService.serverAddress != null) break
            val failure = MediaFileHttpServerService.lastFailureReason
            if (failure != null) {
                log.err("El servidor no arrancó: $failure (${MediaFileHttpServerService.lastFailureMessage})")
                log.event("server_start_failed", "reason" to failure.name, "message" to MediaFileHttpServerService.lastFailureMessage)
                return false
            }
            Thread.sleep(100)
        }
        val address = MediaFileHttpServerService.serverAddress
        if (!MediaFileHttpServerService.isServerRunning || address == null) {
            log.err("El servidor no arrancó en 20 s")
            return false
        }
        MediaFileHttpServerService.adoptAuthToken(token(app))
        val port = address.substringAfterLast(':').toIntOrNull()
        prefs.edit().putInt("server_port", port ?: 0).apply()
        log.event(
            "server_started",
            "address" to address,
            "port" to port,
            "saved_port_before" to savedPort,
            "port_kept" to (savedPort == 0 || savedPort == port),
            "host_selected" to MediaFileHttpServerService.serverHostAddress,
            "prefix" to MediaFileHttpServerService.serverPrefixLength,
            "sonos_ip_hint" to sonosIp,
            "start_ms" to (SystemClock.elapsedRealtime() - t0),
        )
        return true
    }

    fun stop(context: Context, log: SonosRunLog) {
        val app = context.applicationContext
        app.stopService(Intent(app, MediaFileHttpServerService::class.java))
        log.event("server_stopped")
    }

    fun listAudio(context: Context, log: SonosRunLog, limit: Int = 60): List<AudioItem> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log.warn("Falta el permiso de audio (READ_MEDIA_AUDIO): pulsa 'Permiso audio'")
            return emptyList()
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val out = mutableListOf<AudioItem>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Audio.Media.DURATION} > 0", null, "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out.add(
                    AudioItem(
                        id = c.getLong(0), title = c.getString(1).orEmpty(), artist = c.getString(2).orEmpty(),
                        album = c.getString(3).orEmpty(), durationMs = c.getLong(4), mime = c.getString(5).orEmpty(),
                        size = c.getLong(6), name = c.getString(7).orEmpty(),
                    )
                )
            }
        }
        log.event(
            "audio_list",
            "count" to out.size,
            "items" to out.map {
                mapOf("id" to it.id, "name" to it.name, "mime" to it.mime, "size" to it.size, "duration_ms" to it.durationMs)
            },
        )
        return out
    }

    /** Reenganche tras morir el proceso: vuelve a autorizar las mismas pistas de la última publicación (mismo token => mismas URLs). */
    fun republishLast(context: Context, log: SonosRunLog, sonosIp: String?): List<Published> {
        val ids = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("last_ids", "").orEmpty().split(",").mapNotNull { it.trim().toLongOrNull() }
        if (ids.isEmpty()) { log.warn("No hay una publicación anterior guardada"); return emptyList() }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val byId = HashMap<Long, AudioItem>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Audio.Media._ID} IN (${ids.joinToString(",")})", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                byId[c.getLong(0)] = AudioItem(
                    id = c.getLong(0), title = c.getString(1).orEmpty(), artist = c.getString(2).orEmpty(),
                    album = c.getString(3).orEmpty(), durationMs = c.getLong(4), mime = c.getString(5).orEmpty(),
                    size = c.getLong(6), name = c.getString(7).orEmpty(),
                )
            }
        }
        val items = ids.mapNotNull { byId[it] }
        log.event("republish_last", "requested" to ids.size, "found" to items.size)
        return publish(context, log, items, sonosIp)
    }

    fun publish(context: Context, log: SonosRunLog, items: List<AudioItem>, sonosIp: String?): List<Published> {
        val address = MediaFileHttpServerService.serverAddress
        if (address == null) {
            log.err("El servidor no está iniciado")
            return emptyList()
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_ids", items.joinToString(",") { it.id.toString() }).apply()
        val policy = MediaFileHttpServerService.configureCastSessionAccess(items.map { it.id.toString() }, sonosIp)
        val published = items.map {
            val id = it.id.toString()
            Published(
                it,
                CastSessionSecurity.buildSongUrl(address, id, "1", policy.authToken) + "&raw=1",
                CastSessionSecurity.buildArtUrl(address, id, "1", policy.authToken),
            )
        }
        log.event(
            "published",
            "server" to address,
            "token_persisted" to (policy.authToken == token(context)),
            "items" to published.map {
                mapOf(
                    "id" to it.item.id, "name" to it.item.name, "mime" to it.item.mime, "size" to it.item.size,
                    "song_url" to it.songUrl, "art_url" to it.artUrl,
                    "curl" to "curl -s -D- -o /dev/null -r 0-99 '${it.songUrl}'",
                )
            },
        )
        return published
    }

    /** Autoprueba por loopback: no valida la ruta LAN (eso lo hace el curl desde el Mac), sí Range, tipos y modo raw. */
    fun selfTest(context: Context, log: SonosRunLog, published: List<Published>) {
        val address = MediaFileHttpServerService.serverAddress ?: run { log.err("Servidor no iniciado"); return }
        val port = address.substringAfterLast(':')
        val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        fun loop(url: String) = url.replace(Regex("^http://[^/]+"), "http://127.0.0.1:$port")

        fun probe(label: String, id: Long, url: String, method: String, range: String?, maxBytes: Int) {
            val req = Request.Builder().url(loop(url)).method(method, null).apply {
                range?.let { header("Range", it) }
            }.build()
            val t = SystemClock.elapsedRealtime()
            try {
                client.newCall(req).execute().use { r ->
                    var read = 0
                    if (method == "GET") {
                        r.body?.byteStream()?.use { s ->
                            val buf = ByteArray(8192)
                            while (read < maxBytes) {
                                val n = s.read(buf)
                                if (n < 0) break
                                read += n
                            }
                        }
                    }
                    log.event(
                        "selftest", "label" to label, "song_id" to id, "method" to method, "range" to range,
                        "status" to r.code, "content_type" to r.header("Content-Type"),
                        "content_length" to r.header("Content-Length"), "content_range" to r.header("Content-Range"),
                        "accept_ranges" to r.header("Accept-Ranges"), "bytes_read" to read,
                        "ms" to (SystemClock.elapsedRealtime() - t),
                    )
                }
            } catch (e: Exception) {
                log.event("selftest", "label" to label, "song_id" to id, "error" to "${e.javaClass.simpleName}: ${e.message}")
            }
        }
        for (p in published.take(3)) {
            probe("song HEAD", p.item.id, p.songUrl, "HEAD", null, 0)
            probe("song GET 0-99", p.item.id, p.songUrl, "GET", "bytes=0-99", 1000)
            probe("song GET desde la mitad", p.item.id, p.songUrl, "GET", "bytes=${p.item.size / 2}-", 4096)
            probe("art GET", p.item.id, p.artUrl, "GET", null, 8_000_000)
        }
        log.info("Autoprueba terminada")
    }
}
