package com.theveloper.pixelplay.spike.sonos

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SPIKE (fase D). Cola de Sonos con DIDL, reproducción y control, espejo de `p5_queue.py`:
 * RemoveAllTracksFromQueue -> AddURIToQueue (DIDL por pista) -> SetAVTransportURI(x-rincon-queue) -> SetPlayMode -> Play.
 */
object SonosQueue {

    /** Coordinador del grupo donde está [ip]: ahí van la cola y los comandos de transporte. */
    data class Target(val coordinatorIp: String, val coordinatorUuid: String, val grouped: Boolean)

    fun resolveTarget(context: Context, log: SonosRunLog, ip: String): Target? {
        val groups = SonosLab.topology(context, log, ip) ?: return null
        for (g in groups) {
            @Suppress("UNCHECKED_CAST")
            val members = g["members"] as List<Map<String, Any?>>
            if (members.none { it["ip"] == ip }) continue
            val coordUuid = g["coordinator"] as String
            val coord = members.firstOrNull { it["uuid"] == coordUuid } ?: continue
            val coordIp = coord["ip"] as? String ?: continue
            val visible = members.count { it["invisible"] != true }
            val t = Target(coordIp, coordUuid, visible > 1)
            log.event("queue_target", "requested_ip" to ip, "coordinator_ip" to coordIp, "coordinator_uuid" to coordUuid, "grouped" to t.grouped)
            if (coordIp != ip) log.warn("$ip no es el coordinador; uso $coordIp")
            return t
        }
        log.err("No encontré $ip en la topología")
        return null
    }

    /** H:MM:SS como lo usa UPnP en <res duration>. */
    fun fmtDuration(ms: Long): String? {
        if (ms <= 0) return null
        val s = Math.round(ms / 1000.0)
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    fun buildDidl(p: SonosServer.Published): String {
        val e: (String) -> String = { x -> SonosProtocol.escapeXml(x) }
        val song = p.item
        val parts = StringBuilder()
        parts.append(
            "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
                "xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\" " +
                "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">"
        )
        parts.append("<item id=\"-1\" parentID=\"-1\" restricted=\"true\">")
        parts.append("<dc:title>${e(song.title.ifBlank { song.name })}</dc:title>")
        parts.append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        if (song.artist.isNotBlank() && song.artist != "<unknown>") parts.append("<dc:creator>${e(song.artist)}</dc:creator>")
        if (song.album.isNotBlank()) parts.append("<upnp:album>${e(song.album)}</upnp:album>")
        parts.append("<upnp:albumArtURI>${e(p.artUrl)}</upnp:albumArtURI>")
        val dur = fmtDuration(song.durationMs)?.let { d -> " duration=\"$d\"" }.orEmpty()
        parts.append("<res protocolInfo=\"http-get:*:${e(song.mime)}:*\"$dur>${e(p.songUrl)}</res>")
        parts.append("</item></DIDL-Lite>")
        return parts.toString()
    }

    /** Devuelve true si Play fue aceptado. Cada paso deja su evento `soap`; aquí solo `queue_item`/`enqueued`/`queue_ready`. */
    fun enqueueAndPlay(
        context: Context, log: SonosRunLog, ip: String, items: List<SonosServer.Published>, mode: String, startTrack: Int = 1,
    ): Boolean {
        val target = resolveTarget(context, log, ip) ?: return false
        val coord = target.coordinatorIp
        items.forEachIndexed { i, p ->
            log.event(
                "queue_item", "n" to i + 1, "id" to p.item.id, "name" to p.item.name, "mime" to p.item.mime,
                "duration" to fmtDuration(p.item.durationMs), "didl" to buildDidl(p),
            )
        }
        var r = SonosSoap.call(context, log, coord, "AVTransport", "RemoveAllTracksFromQueue", mapOf("InstanceID" to 0), write = true)
        if (!r.ok) { log.err("RemoveAllTracksFromQueue falló (${r.errorCode} ${r.errorDesc})"); return false }
        val t0 = SystemClock.elapsedRealtime()
        items.forEachIndexed { i, p ->
            r = SonosSoap.call(
                context, log, coord, "AVTransport", "AddURIToQueue",
                mapOf(
                    "InstanceID" to 0, "EnqueuedURI" to p.songUrl, "EnqueuedURIMetaData" to buildDidl(p),
                    "DesiredFirstTrackNumberEnqueued" to 0, "EnqueueAsNext" to 0,
                ),
                write = true,
            )
            if (!r.ok) { log.err("AddURIToQueue falló en la pista ${i + 1} (${r.errorCode} ${r.errorDesc})"); return false }
            log.event("enqueued", "n" to i + 1, "values" to r.values, "latency_ms" to r.latencyMs)
        }
        val enqueueMs = SystemClock.elapsedRealtime() - t0
        r = SonosSoap.call(
            context, log, coord, "AVTransport", "SetAVTransportURI",
            mapOf("InstanceID" to 0, "CurrentURI" to "x-rincon-queue:${target.coordinatorUuid}#0", "CurrentURIMetaData" to ""),
            write = true,
        )
        if (!r.ok) { log.err("SetAVTransportURI(cola) falló (${r.errorCode} ${r.errorDesc})"); return false }
        if (startTrack > 1) {
            SonosSoap.call(context, log, coord, "AVTransport", "Seek",
                mapOf("InstanceID" to 0, "Unit" to "TRACK_NR", "Target" to startTrack), write = true)
        }
        if (mode != "keep") {
            SonosSoap.call(context, log, coord, "AVTransport", "SetPlayMode",
                mapOf("InstanceID" to 0, "NewPlayMode" to mode), write = true)
        }
        log.event("queue_ready", "tracks" to items.size, "enqueue_ms" to enqueueMs, "mode" to mode, "coordinator" to coord)
        r = SonosSoap.call(context, log, coord, "AVTransport", "Play", mapOf("InstanceID" to 0, "Speed" to 1), write = true)
        if (!r.ok) { log.err("Play falló (${r.errorCode} ${r.errorDesc})"); return false }
        log.info("Cola de ${items.size} pistas en marcha ($mode)")
        return true
    }

    /** Aplica el modo (NORMAL, SHUFFLE, REPEAT_ALL, REPEAT_ONE...) sobre la cola actual, sin reconstruirla. */
    fun setPlayMode(context: Context, log: SonosRunLog, ip: String, mode: String): Boolean {
        val coord = resolveTarget(context, log, ip)?.coordinatorIp ?: ip
        val r = SonosSoap.call(context, log, coord, "AVTransport", "SetPlayMode",
            mapOf("InstanceID" to 0, "NewPlayMode" to mode), write = true)
        log.event("play_mode_cmd", "mode" to mode, "ok" to r.ok, "error_code" to r.errorCode)
        val settings = SonosSoap.call(context, log, coord, "AVTransport", "GetTransportSettings", mapOf("InstanceID" to 0))
        log.event("play_mode_now", "play_mode" to settings.values["PlayMode"], "rec_quality" to settings.values["RecQualityMode"])
        return r.ok
    }

    /** Anterior como el botón físico: con más de [thresholdS] s de pista, reinicia; si no, va a la pista anterior. */
    fun previousSmart(context: Context, log: SonosRunLog, ip: String, thresholdS: Double = 3.0): Boolean {
        val coord = resolveTarget(context, log, ip)?.coordinatorIp ?: ip
        val snap = snapshot(context, log, coord, quiet = true)
        val rel = snap?.rel
        if (rel != null && rel > thresholdS) {
            val r = SonosSoap.call(context, log, coord, "AVTransport", "Seek",
                mapOf("InstanceID" to 0, "Unit" to "REL_TIME", "Target" to "0:00:00"), write = true)
            log.event("previous_decision", "rel" to rel, "threshold" to thresholdS, "did" to "seek_0", "ok" to r.ok, "error_code" to r.errorCode)
            return r.ok
        }
        log.event("previous_decision", "rel" to rel, "threshold" to thresholdS, "did" to "previous")
        return transport(context, log, ip, "Previous")
    }

    /** Play, Pause, Next, Previous, Stop. */
    fun transport(context: Context, log: SonosRunLog, ip: String, action: String): Boolean {
        val coord = resolveTarget(context, log, ip)?.coordinatorIp ?: ip
        val args = if (action == "Play") mapOf("InstanceID" to 0, "Speed" to 1) else mapOf("InstanceID" to 0)
        val r = SonosSoap.call(context, log, coord, "AVTransport", action, args, write = true)
        log.event("transport_cmd", "action" to action, "ok" to r.ok, "error_code" to r.errorCode)
        return r.ok
    }

    private fun parseUpnpTime(s: String?): Double? {
        val p = s?.trim()?.split(":") ?: return null
        if (p.size != 3) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val sec = p[2].toDoubleOrNull() ?: return null
        return h * 3600.0 + m * 60 + sec
    }

    data class Snapshot(val state: String?, val track: Int?, val rel: Double?, val dur: Double?, val title: String?,
                        val creator: String?, val album: String?, val art: String?, val uri: String?)

    fun snapshot(context: Context, log: SonosRunLog, coordIp: String, quiet: Boolean): Snapshot? {
        val ti = SonosSoap.call(context, log, coordIp, "AVTransport", "GetTransportInfo", mapOf("InstanceID" to 0), quiet = quiet)
        val pi = SonosSoap.call(context, log, coordIp, "AVTransport", "GetPositionInfo", mapOf("InstanceID" to 0), quiet = quiet)
        if (!ti.ok || !pi.ok) return null
        val didl = pi.values["TrackMetaData"]?.let { SonosXml.parse(it) }?.child("item")
        fun field(n: String) = didl?.child(n)?.text?.toString()?.trim()
        return Snapshot(
            state = ti.values["CurrentTransportState"]?.trim(),
            track = pi.values["Track"]?.trim()?.toIntOrNull(),
            rel = parseUpnpTime(pi.values["RelTime"]),
            dur = parseUpnpTime(pi.values["TrackDuration"]),
            title = field("title"), creator = field("creator"), album = field("album"), art = field("albumArtURI"),
            uri = pi.values["TrackURI"]?.replace(Regex("auth=[^&]+"), "auth=<redacted>"),
        )
    }

    private fun powerInfo(context: Context): Map<String, Any?> {
        val pm = context.getSystemService(PowerManager::class.java) ?: return emptyMap()
        return mapOf("interactive" to pm.isInteractive, "doze" to pm.isDeviceIdleMode, "power_save" to pm.isPowerSaveMode)
    }

    fun logSnapshot(log: SonosRunLog, s: Snapshot, reason: String, power: Map<String, Any?> = emptyMap()) {
        log.event(
            "state", "reason" to reason, "power" to power, "transport" to s.state, "track" to s.track, "rel" to s.rel, "dur" to s.dur,
            "title" to s.title, "creator" to s.creator, "album" to s.album, "art" to s.art, "uri" to s.uri,
        )
    }

    /** Una lectura puntual con las llamadas SOAP completas en el log. */
    fun status(context: Context, log: SonosRunLog, ip: String) {
        val coord = resolveTarget(context, log, ip)?.coordinatorIp ?: ip
        SonosSoap.call(context, log, coord, "AVTransport", "GetMediaInfo", mapOf("InstanceID" to 0))
        SonosSoap.call(context, log, coord, "AVTransport", "GetTransportSettings", mapOf("InstanceID" to 0))
        snapshot(context, log, coord, quiet = false)?.let { logSnapshot(log, it, "manual") }
        // Para investigar diferencias de nivel de volumen entre pruebas.
        val rc = mapOf("InstanceID" to 0, "Channel" to "Master")
        SonosSoap.call(context, log, coord, "RenderingControl", "GetVolume", rc)
        SonosSoap.call(context, log, coord, "RenderingControl", "GetMute", rc)
        SonosSoap.call(context, log, coord, "RenderingControl", "GetLoudness", rc)
        for (eq in listOf("NightMode", "DialogLevel", "SubGain", "SurroundEnable")) {
            SonosSoap.call(context, log, coord, "RenderingControl", "GetEQ", mapOf("InstanceID" to 0, "EQType" to eq))
        }
        SonosSoap.call(context, log, coord, "RenderingControl", "GetBass", mapOf("InstanceID" to 0))
        SonosSoap.call(context, log, coord, "RenderingControl", "GetTreble", mapOf("InstanceID" to 0))
    }

    /** Lee la cola tal como la guarda el altavoz (ContentDirectory Browse Q:0) y registra el texto/carátula de cada ítem. */
    fun browseQueue(context: Context, log: SonosRunLog, ip: String) {
        val coord = resolveTarget(context, log, ip)?.coordinatorIp ?: ip
        val r = SonosSoap.call(context, log, coord, "ContentDirectory", "Browse", mapOf(
            "ObjectID" to "Q:0", "BrowseFlag" to "BrowseDirectChildren", "Filter" to "*",
            "StartingIndex" to 0, "RequestedCount" to 100, "SortCriteria" to ""))
        if (!r.ok) return
        val root = SonosXml.parse(r.values["Result"].orEmpty())
        val items = root?.children?.filter { it.name == "item" }.orEmpty().mapIndexed { i, n ->
            fun f(x: String) = n.child(x)?.text?.toString()?.trim()
            mapOf(
                "n" to i + 1, "id" to n.attrs["id"], "title" to f("title"), "creator" to f("creator"), "album" to f("album"),
                "art" to f("albumArtURI")?.replace(Regex("auth=[^&]+"), "auth=<redacted>"),
                "res" to f("res")?.replace(Regex("auth=[^&]+"), "auth=<redacted>"),
                "res_attrs" to n.child("res")?.attrs, "class" to f("class"),
            )
        }
        log.event("queue_browse", "number_returned" to r.values["NumberReturned"], "total" to r.values["TotalMatches"], "items" to items)
        log.info("Cola en el altavoz: ${items.size} ítems")
    }

    /**
     * Reenganche: lee la cola del altavoz, comprueba que las URLs son nuestras (mismo servidor:puerto y mismo token)
     * y vuelve a autorizar esos ids en el servidor. No reconstruye la cola. Así no hace falta persistir la lista.
     */
    fun reattach(context: Context, log: SonosRunLog, ip: String): Boolean {
        val address = com.theveloper.pixelplay.data.service.http.MediaFileHttpServerService.serverAddress
        if (address == null) { log.err("Reenganche: el servidor no está iniciado (pestaña Servidor -> Iniciar servidor)"); return false }
        val coord = resolveTarget(context, log, ip)?.coordinatorIp ?: ip
        val r = SonosSoap.call(context, log, coord, "ContentDirectory", "Browse", mapOf(
            "ObjectID" to "Q:0", "BrowseFlag" to "BrowseDirectChildren", "Filter" to "*",
            "StartingIndex" to 0, "RequestedCount" to 500, "SortCriteria" to ""))
        if (!r.ok) return false
        val items = SonosXml.parse(r.values["Result"].orEmpty())?.children?.filter { it.name == "item" }.orEmpty()
        val myToken = SonosServer.token(context)
        val ids = mutableListOf<String>()
        var foreign = 0
        var wrongAddress = 0
        var wrongToken = 0
        for (n in items) {
            val url = n.child("res")?.text?.toString()?.trim().orEmpty()
            val u = runCatching { java.net.URI(url) }.getOrNull()
            val seg = u?.path?.split("/")?.filter { it.isNotEmpty() }
            if (u == null || seg == null || seg.size != 2 || seg[0] != "song") { foreign++; continue }
            val base = "${u.scheme}://${u.host}:${u.port}"
            val tok = u.query?.split("&")?.firstOrNull { it.startsWith("auth=") }?.removePrefix("auth=")
            if (base != address) { wrongAddress++; continue }
            if (tok != myToken) { wrongToken++; continue }
            ids.add(seg[1])
        }
        log.event(
            "reattach", "queue_items" to items.size, "ours" to ids.size, "foreign" to foreign,
            "wrong_address" to wrongAddress, "wrong_token" to wrongToken, "server" to address, "ids" to ids,
        )
        if (ids.isEmpty()) {
            log.warn("Reenganche: ninguna pista de la cola es reutilizable (dirección/token distintos). Hay que reconstruir la cola.")
            return false
        }
        com.theveloper.pixelplay.data.service.http.MediaFileHttpServerService.configureCastSessionAccess(ids, ip)
        log.info("Reenganche: ${ids.size} de ${items.size} pistas autorizadas de nuevo")
        return true
    }

    /** Sondea cada [intervalMs] hasta [stop] o [maxSeconds]; escribe `state` al cambiar estado/pista y cada 5 s. */
    fun monitor(context: Context, ip: String, stop: AtomicBoolean, maxSeconds: Int = 1800, intervalMs: Long = 1000) {
        val first = SonosSpike.current(context)
        val coord = resolveTarget(context, first, ip)?.coordinatorIp ?: ip
        first.info("Monitor iniciado (cada ${intervalMs} ms, máx ${maxSeconds}s)")
        val t0 = SystemClock.elapsedRealtime()
        var last: Snapshot? = null
        var lastTick = 0L
        var failures = 0
        while (!stop.get() && SystemClock.elapsedRealtime() - t0 < maxSeconds * 1000L) {
            val log = SonosSpike.current(context)
            val s = snapshot(context, log, coord, quiet = true)
            if (s == null) {
                failures++
                if (failures == 1 || failures % 10 == 0) log.warn("Monitor: lectura fallida ($failures)")
            } else {
                failures = 0
                val changed = last == null || last.state != s.state || last.track != s.track || last.title != s.title
                val now = SystemClock.elapsedRealtime()
                val power = powerInfo(context)
                if (changed) { logSnapshot(log, s, "change", power); lastTick = now }
                else if (now - lastTick >= 5000) { logSnapshot(log, s, "tick", power); lastTick = now }
                last = s
            }
            Thread.sleep(intervalMs)
        }
        SonosSpike.current(context).info("Monitor detenido")
    }
}
