package com.theveloper.pixelplay.spike.sonos

import android.content.Context
import java.net.URI

/** SPIKE (fase B). Operaciones de alto nivel que usa la pantalla; espejo de get_topology/set_volume. */
object SonosLab {

    fun discover(context: Context, log: SonosRunLog): List<String> {
        val ssdp = SonosSsdp.discover(context, log)
        val ips = ssdp.keys.toList()
        log.event("discover_result", "ssdp" to ips)
        return ips
    }

    fun parseZoneGroupState(xml: String): List<Map<String, Any?>> {
        val root = SonosXml.parse(xml) ?: return emptyList()
        return root.descendants("ZoneGroup").map { zg ->
            val members = zg.children.filter { it.name == "ZoneGroupMember" }.map { m ->
                mapOf(
                    "uuid" to m.attrs["UUID"].orEmpty(),
                    "ip" to runCatching { URI(m.attrs["Location"].orEmpty()).host }.getOrNull(),
                    "name" to m.attrs["ZoneName"].orEmpty(),
                    "software_version" to m.attrs["SoftwareVersion"].orEmpty(),
                    "invisible" to (m.attrs["Invisible"] == "1"),
                    "attrs" to m.attrs,
                )
            }
            mapOf("id" to zg.attrs["ID"].orEmpty(), "coordinator" to zg.attrs["Coordinator"].orEmpty(), "members" to members)
        }
    }

    fun topology(context: Context, log: SonosRunLog, ip: String): List<Map<String, Any?>>? {
        val r = SonosSoap.call(context, log, ip, "ZoneGroupTopology", "GetZoneGroupState")
        if (!r.ok) return null
        val groups = parseZoneGroupState(r.values["ZoneGroupState"].orEmpty())
        log.event("topology", "groups" to groups)
        log.info("Topología: ${groups.size} grupo(s)")
        return groups
    }

    fun getVolume(context: Context, log: SonosRunLog, ip: String): Int? {
        val r = SonosSoap.call(context, log, ip, "RenderingControl", "GetVolume",
            mapOf("InstanceID" to 0, "Channel" to "Master"))
        val v = r.values["CurrentVolume"]?.trim()?.toIntOrNull()
        if (r.ok) log.info("Volumen actual: $v")
        return v
    }

    /** SetVolume con tope (por seguridad al probar en casa): nunca pasa de [cap]. */
    fun setVolume(context: Context, log: SonosRunLog, ip: String, value: Int, cap: Int = SonosProtocol.DEFAULT_MAX_VOLUME): Int {
        val applied = value.coerceIn(0, minOf(cap, 100))
        if (applied != value) {
            log.warn("Volumen $value limitado a $applied (tope $cap)")
            log.event("volume_clamped", "requested" to value, "applied" to applied, "cap" to cap)
        }
        SonosSoap.call(context, log, ip, "RenderingControl", "SetVolume",
            mapOf("InstanceID" to 0, "Channel" to "Master", "DesiredVolume" to applied), write = true)
        return applied
    }

    fun nudgeVolume(context: Context, log: SonosRunLog, ip: String, delta: Int) {
        val current = getVolume(context, log, ip)
        if (current == null) {
            log.warn("No pude leer el volumen; no cambio nada")
            return
        }
        setVolume(context, log, ip, current + delta)
        getVolume(context, log, ip)
    }
}
