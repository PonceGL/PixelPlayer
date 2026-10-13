package com.theveloper.pixelplay.spike.sonos

import android.content.Context
import android.os.SystemClock
import okhttp3.Request
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SPIKE (fase E). Suscripciones UPnP (SUBSCRIBE/NOTIFY) al altavoz con un servidor de callback propio.
 * Mide: alcanzabilidad del callback (SEQ 0), renovaciones con la CPU dormida, huecos de SEQ, y detecta
 * cambios externos, saltos silenciosos, errores de transporte y cambios de cola/topología.
 * Todo se escribe al log en cada evento (eventos `sub_*`, `ev_notify`, `ev_detect`).
 */
object SonosEvents {

    private val PATHS = linkedMapOf(
        "AVTransport" to "/MediaRenderer/AVTransport/Event",
        "RenderingControl" to "/MediaRenderer/RenderingControl/Event",
        "Queue" to "/MediaRenderer/Queue/Event",
        "ZoneGroupTopology" to "/ZoneGroupTopology/Event",
    )

    class Sub(val service: String, val host: String) {
        @Volatile var sid: String? = null
        @Volatile var timeoutS = 0
        @Volatile var expiresAt = 0L
        @Volatile var subscribedAt = 0L
        @Volatile var lastSeq = -1L
        @Volatile var notifies = 0
        @Volatile var firstNotifyAt = 0L
        @Volatile var renewals = 0
        @Volatile var failures = 0
        @Volatile var seq0Checked = false
        @Volatile var lastTry = 0L
    }

    val subs = ConcurrentHashMap<String, Sub>()
    @Volatile var lastCmdAt = 0L
    @Volatile var playMode: String? = null
    @Volatile var lastAvChangeAt = 0L
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var pool = Executors.newCachedThreadPool()
    private var startedAt = 0L
    private var requestedTimeout = 120
    private var coordIpSaved = ""

    // estado derivado para detecciones
    private var prevTrack: Int? = null
    private var prevState: String? = null
    private var prevNumTracks: Int? = null
    private var prevVol: String? = null
    private var prevMute: String? = null
    private var prevZgtHash: Int? = null
    private var prevQueueUpdate: String? = null

    fun isRunning() = running.get()

    fun statusLine(): String {
        if (!running.get()) return "eventos: detenidos"
        val now = SystemClock.elapsedRealtime()
        val sb = StringBuilder("eventos: activos ${(now - startedAt) / 1000}s · timeout pedido ${requestedTimeout}s")
        subs.values.sortedBy { it.service }.forEach {
            val exp = if (it.sid == null) "SIN SID" else "caduca en ${(it.expiresAt - now) / 1000}s"
            sb.append("\n ${it.service.take(10)} n=${it.notifies} seq=${it.lastSeq} ren=${it.renewals} fail=${it.failures} $exp")
        }
        return sb.toString()
    }

    @Synchronized
    fun start(context: Context, ip: String, timeoutS: Int) {
        if (running.get()) return
        val app = context.applicationContext
        running.set(true)
        startedAt = SystemClock.elapsedRealtime()
        requestedTimeout = timeoutS
        pool = Executors.newCachedThreadPool()
        prevTrack = null; prevState = null; prevNumTracks = null; prevVol = null; prevMute = null
        prevZgtHash = null; prevQueueUpdate = null
        subs.clear()
        Thread({
            val log = SonosSpike.current(app)
            try {
                val coord = SonosQueue.resolveTarget(app, log, ip)?.coordinatorIp ?: ip
                coordIpSaved = coord
                val local = SonosNet.localIpv4(app, SonosNet.wifiNetwork(app))?.first
                if (local == null) { log.err("ev: no hay IP Wi-Fi local"); running.set(false); return@Thread }
                val ss = try { ServerSocket(3401, 50) } catch (e: Exception) { ServerSocket(0, 50) }
                server = ss
                val base = "http://$local:${ss.localPort}"
                log.event("ev_start", "callback" to base, "timeout_s" to timeoutS, "coordinator" to coord, "rendering_host" to ip)
                Thread({ acceptLoop(app, ss) }, "sonos-ev-accept").apply { isDaemon = true; start() }
                PATHS.keys.forEach { svc ->
                    val host = if (svc == "RenderingControl") ip else coord
                    val s = Sub(svc, host); subs[svc] = s
                    subscribe(app, log, s, base, timeoutS)
                }
                Thread({ maintainLoop(app, base) }, "sonos-ev-maint").apply { isDaemon = false; start() }
            } catch (t: Throwable) {
                log.err("ev start falló: ${t.javaClass.simpleName}: ${t.message}")
                running.set(false)
            }
        }, "sonos-ev-start").start()
    }

    @Synchronized
    fun stop(context: Context) {
        if (!running.get()) return
        val app = context.applicationContext
        running.set(false)
        Thread({
            val log = SonosSpike.current(app)
            subs.values.forEach { if (it.sid != null) unsubscribe(app, log, it) }
            runCatching { server?.close() }
            server = null
            runCatching { pool.shutdownNow() }
            log.event("ev_stop", "uptime_s" to (SystemClock.elapsedRealtime() - startedAt) / 1000)
        }, "sonos-ev-stop").start()
    }

    // ---------- SUBSCRIBE / renew / UNSUBSCRIBE ----------

    private fun subscribe(app: Context, log: SonosRunLog, sub: Sub, base: String, timeoutS: Int): Boolean {
        sub.lastTry = SystemClock.elapsedRealtime()
        val req = Request.Builder().url("http://${sub.host}:1400${PATHS.getValue(sub.service)}")
            .method("SUBSCRIBE", null)
            .header("CALLBACK", "<$base/ev/${sub.service}>")
            .header("NT", "upnp:event")
            .header("TIMEOUT", "Second-$timeoutS")
            .header("User-Agent", SonosProtocol.USER_AGENT)
            .header("Connection", "close")
            .build()
        return doSub(app, log, sub, req, "subscribe", fresh = true)
    }

    private fun renew(app: Context, log: SonosRunLog, sub: Sub, lateS: Double): Boolean {
        sub.lastTry = SystemClock.elapsedRealtime()
        val req = Request.Builder().url("http://${sub.host}:1400${PATHS.getValue(sub.service)}")
            .method("SUBSCRIBE", null)
            .header("SID", sub.sid ?: "")
            .header("TIMEOUT", "Second-$requestedTimeout")
            .header("User-Agent", SonosProtocol.USER_AGENT)
            .header("Connection", "close")
            .build()
        return doSub(app, log, sub, req, "renew", fresh = false, lateS = lateS)
    }

    private fun doSub(app: Context, log: SonosRunLog, sub: Sub, req: Request, op: String, fresh: Boolean, lateS: Double? = null): Boolean {
        val t0 = System.nanoTime()
        var status: Int? = null
        var sid: String? = null
        var granted: String? = null
        var err = ""
        try {
            SonosNet.httpClient(app, 5000).newCall(req).execute().use { r ->
                status = r.code; sid = r.header("SID"); granted = r.header("TIMEOUT")
            }
        } catch (e: Exception) { err = "${e.javaClass.simpleName}: ${e.message}" }
        val ms = Math.round((System.nanoTime() - t0) / 100_000.0) / 10.0
        val ok = status == 200
        val gs = granted?.substringAfter("Second-")?.toIntOrNull()
        val now = SystemClock.elapsedRealtime()
        if (ok) {
            if (fresh) { sub.sid = sid; sub.lastSeq = -1; sub.notifies = 0; sub.firstNotifyAt = 0; sub.subscribedAt = now; sub.seq0Checked = false }
            else sub.renewals++
            sub.timeoutS = gs ?: requestedTimeout
            sub.expiresAt = now + sub.timeoutS * 1000L
            sub.failures = 0
        } else {
            sub.failures++
            if (!fresh && status == 412) sub.sid = null   // suscripción desconocida para el altavoz
        }
        log.event(
            "sub_cmd", "service" to sub.service, "op" to op, "ok" to ok, "status" to status, "sid" to (sid ?: sub.sid),
            "timeout_granted" to granted, "latency_ms" to ms, "late_s" to lateS, "net_error" to err.ifEmpty { null },
            "net_hint" to if (err.isNotEmpty()) SonosNet.netHint(RuntimeException(err)) else null,
        )
        return ok
    }

    private fun unsubscribe(app: Context, log: SonosRunLog, sub: Sub) {
        val req = Request.Builder().url("http://${sub.host}:1400${PATHS.getValue(sub.service)}")
            .method("UNSUBSCRIBE", null).header("SID", sub.sid ?: "").header("Connection", "close").build()
        var status: Int? = null; var err = ""
        try { SonosNet.httpClient(app, 3000).newCall(req).execute().use { status = it.code } }
        catch (e: Exception) { err = e.javaClass.simpleName }
        log.event("sub_cmd", "service" to sub.service, "op" to "unsubscribe", "ok" to (status == 200), "status" to status, "net_error" to err.ifEmpty { null })
        sub.sid = null
    }

    private fun maintainLoop(app: Context, base: String) {
        while (running.get()) {
            try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
            val log = SonosSpike.current(app)
            val now = SystemClock.elapsedRealtime()
            for (sub in subs.values) {
                if (sub.sid == null) {
                    if (now - sub.lastTry > 10_000) subscribe(app, log, sub, base, requestedTimeout)
                    continue
                }
                if (!sub.seq0Checked && now - sub.subscribedAt > 3000) {
                    sub.seq0Checked = true
                    log.event("sub_seq0", "service" to sub.service, "received" to (sub.notifies > 0),
                        "ms" to if (sub.firstNotifyAt > 0) sub.firstNotifyAt - sub.subscribedAt else null)
                }
                val due = sub.expiresAt - sub.timeoutS * 500L
                if (now >= due) {
                    val late = (now - due) / 1000.0
                    if (now > sub.expiresAt) log.event("sub_expired_before_renew", "service" to sub.service, "over_s" to (now - sub.expiresAt) / 1000.0)
                    val ok = renew(app, log, sub, late)
                    if (!ok && sub.sid == null) subscribe(app, log, sub, base, requestedTimeout)
                    else if (!ok && sub.failures >= 3) { sub.sid = null }
                }
            }
        }
    }

    // ---------- servidor de callback ----------

    private fun acceptLoop(app: Context, ss: ServerSocket) {
        while (running.get() && !ss.isClosed) {
            val s = try { ss.accept() } catch (_: Exception) { break }
            pool.execute { try { handle(app, s) } catch (t: Throwable) {
                SonosSpike.current(app).event("ev_handler_error", "error" to "${t.javaClass.simpleName}: ${t.message}")
            } }
        }
    }

    private fun readHead(inp: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 16384) {
            val b = inp.read(); if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.toString()
        }
        return null
    }

    private fun handle(app: Context, s: Socket) {
        s.use {
            s.soTimeout = 10_000
            val inp = BufferedInputStream(s.getInputStream())
            val head = readHead(inp) ?: return
            val lines = head.split("\r\n")
            val reqLine = lines[0]
            val headers = HashMap<String, String>()
            lines.drop(1).forEach { l -> val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val buf = ByteArray(len)
            var got = 0
            while (got < len) { val n = inp.read(buf, got, len - got); if (n < 0) break; got += n }
            s.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            process(app, reqLine, headers, String(buf, 0, got, Charsets.UTF_8), s.inetAddress?.hostAddress)
        }
    }

    // ---------- NOTIFY ----------

    private fun short(v: String?, n: Int = 160) = v?.let { if (it.length > n) it.take(n) + "…" else it }

    private fun process(app: Context, reqLine: String, h: Map<String, String>, body: String, from: String?) {
        val log = SonosSpike.current(app)
        val now = SystemClock.elapsedRealtime()
        val path = reqLine.split(" ").getOrNull(1) ?: ""
        val service = path.removePrefix("/ev/")
        val sid = h["sid"]
        val seq = h["seq"]?.toLongOrNull()
        val sub = subs[service]
        var gap = false
        if (sub == null) {
            log.event("ev_unknown", "request" to reqLine, "sid" to sid, "from" to from)
            return
        }
        if (sub.sid != null && sid != null && sid != sub.sid) log.event("ev_sid_mismatch", "service" to service, "got" to sid, "have" to sub.sid)
        sub.notifies++
        if (sub.firstNotifyAt == 0L) sub.firstNotifyAt = now
        if (seq != null) {
            if (seq != 0L && sub.lastSeq >= 0 && seq != sub.lastSeq + 1) {
                gap = true
                log.event("ev_seq_gap", "service" to service, "expected" to sub.lastSeq + 1, "got" to seq)
            }
            sub.lastSeq = seq
        }
        val root = SonosXml.parse(body)
        val props = LinkedHashMap<String, String>()
        root?.children?.forEach { p -> p.children.firstOrNull()?.let { props[it.name] = it.text.toString() } }
        val vars = LinkedHashMap<String, String>()
        props["LastChange"]?.let { lc ->
            SonosXml.parse(lc)?.child("InstanceID")?.children?.forEach { v ->
                val key = if (v.attrs["channel"] != null) "${v.name}_${v.attrs["channel"]}" else v.name
                vars[key] = v.attrs["val"] ?: v.text.toString()
            }
        }
        val summary = LinkedHashMap<String, Any?>()
        vars.forEach { (k, v) ->
            when {
                k == "AVTransportURIMetaData" || k == "EnqueuedTransportURIMetaData" -> {}
                k.endsWith("MetaData") -> summary["${k}_title"] = SonosXml.parse(v)?.descendants("title")?.firstOrNull()?.text?.toString()
                else -> summary[k] = short(v)
            }
        }
        if (service == "ZoneGroupTopology" || service == "Queue") {
            props.forEach { (k, v) -> if (k != "LastChange") summary["p_$k"] = if (k == "ZoneGroupState") "len=${v.length}" else short(v, 120) }
        }
        val cmdRecent = now - lastCmdAt < 4000
        log.event("ev_notify", "service" to service, "seq" to seq, "sid" to short(sid, 12), "bytes" to body.length,
            "vars" to summary, "cmd_recent" to cmdRecent, "from" to from,
            "raw" to if (service == "Queue") short(body, 600) else null)

        when (service) {
            "AVTransport" -> detectAv(log, vars, cmdRecent)
            "RenderingControl" -> {
                val vol = vars["Volume_Master"]; val mute = vars["Mute_Master"]
                if (vol != null && vol != prevVol && prevVol != null)
                    log.event("ev_detect", "what" to "volume_changed", "from" to prevVol, "to" to vol, "external" to !cmdRecent)
                if (mute != null && mute != prevMute && prevMute != null)
                    log.event("ev_detect", "what" to "mute_changed", "from" to prevMute, "to" to mute, "external" to !cmdRecent)
                vol?.let { prevVol = it }; mute?.let { prevMute = it }
            }
            "ZoneGroupTopology" -> props["ZoneGroupState"]?.let {
                val hsh = it.hashCode()
                if (prevZgtHash != null && prevZgtHash != hsh) log.event("ev_detect", "what" to "topology_changed")
                prevZgtHash = hsh
            }
            "Queue" -> {
                val up = props["UpdateID"] ?: vars["UpdateID"]
                if (up != null && up != prevQueueUpdate && prevQueueUpdate != null)
                    log.event("ev_detect", "what" to "queue_changed", "update_id" to up, "external" to !cmdRecent)
                up?.let { prevQueueUpdate = it }
            }
        }
        if (gap) Thread({
            val coord = coordIpSaved
            SonosQueue.snapshot(app, SonosSpike.current(app), coord, quiet = true)?.let {
                SonosQueue.logSnapshot(SonosSpike.current(app), it, "resync_after_gap")
            }
        }, "sonos-ev-resync").start()
    }

    private fun detectAv(log: SonosRunLog, v: Map<String, String>, cmdRecent: Boolean) {
        if (v.keys.any { it == "TransportState" || it == "CurrentTrack" || it == "CurrentPlayMode" }) lastAvChangeAt = SystemClock.elapsedRealtime()
        val st = v["TransportState"]
        val trk = v["CurrentTrack"]?.toIntOrNull()
        val status = v["TransportStatus"]
        val pm = v["CurrentPlayMode"]
        v["NumberOfTracks"]?.toIntOrNull()?.let { prevNumTracks = it }
        if (status != null && status != "OK")
            log.event("ev_detect", "what" to "transport_status_error", "status" to status, "desc" to v["TransportErrorDescription"])
        if (v["TransportErrorDescription"]?.isNotBlank() == true && status == null)
            log.event("ev_detect", "what" to "transport_error_desc", "desc" to v["TransportErrorDescription"])
        if (st != null && st != prevState) {
            log.event("ev_detect", "what" to "state_changed", "from" to prevState, "to" to st, "external" to !cmdRecent)
            prevState = st
        }
        if (pm != null && pm != playMode) {
            log.event("ev_detect", "what" to "play_mode_changed", "from" to playMode, "to" to pm, "external" to !cmdRecent)
            playMode = pm
        }
        if (trk != null && trk != prevTrack) {
            val p = prevTrack
            if (p != null) {
                val n = prevNumTracks
                val natural = trk == p + 1 || (trk == 1 && n != null && p == n)
                log.event("ev_detect", "what" to "track_changed", "from" to p, "to" to trk, "natural" to natural, "external" to !cmdRecent)
                if (!cmdRecent && trk - p >= 2 && (pm ?: playMode)?.startsWith("SHUFFLE") != true)
                    log.event("ev_detect", "what" to "skip_suspected", "from" to p, "to" to trk, "skipped" to trk - p - 1)
            }
            prevTrack = trk
        }
    }
}
