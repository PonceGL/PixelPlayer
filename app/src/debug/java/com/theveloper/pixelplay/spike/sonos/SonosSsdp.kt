package com.theveloper.pixelplay.spike.sonos

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** SPIKE (fase B). SSDP y escaneo de subred; mismos eventos que `ssdp_discover`/`subnet_scan` del harness. */
object SonosSsdp {

    fun parseSsdp(data: String): Map<String, String> {
        val lines = data.split("\r\n")
        val headers = LinkedHashMap<String, String>()
        headers["_status"] = lines.firstOrNull() ?: ""
        for (ln in lines.drop(1)) {
            val i = ln.indexOf(':')
            if (i > 0) headers[ln.substring(0, i).trim().lowercase()] = ln.substring(i + 1).trim()
        }
        return headers
    }

    /** -> {ip: headers}. Necesita MulticastLock: sin él muchos teléfonos descartan el multicast con la pantalla apagada. */
    fun discover(context: Context, log: SonosRunLog, listenMs: Long = 4000, repeats: Int = 3): Map<String, Map<String, String>> {
        val found = LinkedHashMap<String, Map<String, String>>()
        val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: ${SonosProtocol.SSDP_HOST}:${SonosProtocol.SSDP_PORT}\r\n" +
            "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ${SonosProtocol.SSDP_ST}\r\n\r\n").toByteArray()
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("sonos-spike-ssdp")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val net = SonosNet.wifiNetwork(context)
        val socket: MulticastSocket = try {
            MulticastSocket(0).also { sock ->
                try {
                    sock.timeToLive = 2
                    net?.bindSocket(sock)
                } catch (e: IOException) {
                    sock.close()
                    throw e
                }
            }
        } catch (e: IOException) {
            log.err("No se pudo preparar el socket SSDP: ${e.message}")
            log.event("ssdp_socket_error", "error" to e.toString(), "hint" to SonosNet.netHint(e))
            lock?.release()
            return found
        }
        log.event(
            "ssdp_network",
            "bound_to_wifi" to (net != null),
            "multicast_lock" to (lock != null),
            "local_port" to socket.localPort,
            "local_ip" to SonosNet.localIpv4(context, net)?.first,
        )

        log.info("SSDP: escuchando ${listenMs / 1000.0} s (ST=${SonosProtocol.SSDP_ST})")
        val target = InetAddress.getByName(SonosProtocol.SSDP_HOST)
        val deadline = SystemClock.elapsedRealtime() + listenMs
        var sent = 0
        var nextSend = 0L
        val buf = ByteArray(8192)
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                val now = SystemClock.elapsedRealtime()
                if (sent < repeats && now >= nextSend) {
                    try {
                        socket.send(DatagramPacket(msg, msg.size, target, SonosProtocol.SSDP_PORT))
                        sent++
                        nextSend = now + 1000
                        log.event("ssdp_sent", "n" to sent)
                    } catch (e: IOException) {
                        log.err("SSDP: no se pudo enviar el M-SEARCH: ${e.message}")
                        log.event("ssdp_send_error", "error" to e.toString(), "hint" to SonosNet.netHint(e))
                        break
                    }
                }
                socket.soTimeout = 400
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    log.event("ssdp_recv_error", "error" to e.toString())
                    break
                }
                val from = pkt.address.hostAddress ?: continue
                val headers = parseSsdp(String(pkt.data, 0, pkt.length, Charsets.UTF_8))
                log.event("ssdp_response", "from_ip" to from, "headers" to headers)
                if (from !in found) {
                    log.debug("SSDP respuesta de $from (${headers["server"] ?: ""})")
                    found[from] = headers
                }
            }
        } finally {
            socket.close()
            lock?.release()
        }
        log.info("SSDP: ${found.size} dispositivo(s) respondieron")
        return found
    }

    /** Plan B sin multicast: prueba el puerto 1400 en la /24 de la IP local. */
    fun scan(context: Context, log: SonosRunLog, timeoutMs: Int = 500): List<String> {
        val net = SonosNet.wifiNetwork(context)
        val local = SonosNet.localIpv4(context, net)
        if (local == null) {
            log.warn("No pude determinar la IP local; ¿hay Wi-Fi?")
            return emptyList()
        }
        val prefix = local.first.substringBeforeLast('.')
        log.info("Escaneo: $prefix.1-254 puerto ${SonosProtocol.PORT} (asume máscara /24; la real es /${local.second})")
        val pool = Executors.newFixedThreadPool(64)
        val hosts = try {
            val tasks = (1..254).map { i ->
                Callable<String?> {
                    val host = "$prefix.$i"
                    try {
                        val s = net?.socketFactory?.createSocket() ?: Socket()
                        s.use { it.connect(InetSocketAddress(host, SonosProtocol.PORT), timeoutMs) }
                        host
                    } catch (_: IOException) {
                        null
                    }
                }
            }
            pool.invokeAll(tasks).mapNotNull { runCatching { it.get() }.getOrNull() }
        } finally {
            pool.shutdown()
        }
        log.event("scan_result", "subnet" to prefix, "hosts" to hosts)
        log.info("Escaneo: ${hosts.size} host(s) con el puerto ${SonosProtocol.PORT} abierto")
        return hosts
    }
}
