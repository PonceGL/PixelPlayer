package com.theveloper.pixelplay.spike.sonos

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** SPIKE (fase B). Constantes y XML mínimo; espejo de `harness/sonos_lab.py`. */
object SonosProtocol {
    const val PORT = 1400
    const val SSDP_HOST = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val SSDP_ST = "urn:schemas-upnp-org:device:ZonePlayer:1"
    const val DEFAULT_MAX_VOLUME = 20
    const val USER_AGENT = "sonos-spike/0.1-android"

    data class Service(val path: String, val type: String)

    val SERVICES: Map<String, Service> = mapOf(
        "AVTransport" to Service("/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1"),
        "RenderingControl" to Service("/MediaRenderer/RenderingControl/Control", "urn:schemas-upnp-org:service:RenderingControl:1"),
        "GroupRenderingControl" to Service("/MediaRenderer/GroupRenderingControl/Control", "urn:schemas-upnp-org:service:GroupRenderingControl:1"),
        "ZoneGroupTopology" to Service("/ZoneGroupTopology/Control", "urn:schemas-upnp-org:service:ZoneGroupTopology:1"),
        "ContentDirectory" to Service("/MediaServer/ContentDirectory/Control", "urn:schemas-upnp-org:service:ContentDirectory:1"),
        "DeviceProperties" to Service("/DeviceProperties/Control", "urn:schemas-upnp-org:service:DeviceProperties:1"),
    )

    val UPNP_ERRORS: Map<Int, String> = mapOf(
        401 to "Invalid action", 402 to "Invalid args", 501 to "Action failed",
        701 to "Transition not available (o no soportado / no es coordinador)",
        702 to "No content", 703 to "Read error", 704 to "Format not supported for playback",
        710 to "Seek mode not supported", 711 to "Illegal seek target", 712 to "Play mode not supported",
        714 to "Illegal MIME-Type", 716 to "Resource not found", 718 to "Invalid InstanceID",
        737 to "No dns configured", 738 to "Bad domain", 739 to "Server error",
        800 to "Command not supported or not a coordinator",
    )

    fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    fun buildEnvelope(type: String, action: String, args: Map<String, Any?>): String {
        val inner = args.entries.joinToString("") { (k, v) -> "<$k>${escapeXml(v.toString())}</$k>" }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$type\">$inner</u:$action></s:Body></s:Envelope>"
    }
}

/** Nodo XML mínimo (sin prefijos de namespace) para SOAP, ZoneGroupState y, más adelante, DIDL/NOTIFY. */
class XmlNode(val name: String, val attrs: Map<String, String>) {
    val children = mutableListOf<XmlNode>()
    val text = StringBuilder()

    fun child(n: String): XmlNode? = children.firstOrNull { it.name == n }

    fun descendants(n: String): List<XmlNode> {
        val out = mutableListOf<XmlNode>()
        fun walk(x: XmlNode) {
            if (x.name == n) out.add(x)
            x.children.forEach { walk(it) }
        }
        walk(this)
        return out
    }
}

object SonosXml {
    /** Devuelve la raíz o null si no es XML válido. Sin DTD/entidades externas (XmlPullParser). */
    fun parse(xml: String): XmlNode? = runCatching {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))
        val stack = ArrayDeque<XmlNode>()
        var root: XmlNode? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val attrs = LinkedHashMap<String, String>()
                    for (i in 0 until p.attributeCount) {
                        attrs[p.getAttributeName(i).substringAfter(':')] = p.getAttributeValue(i)
                    }
                    val n = XmlNode(p.name.substringAfter(':'), attrs)
                    stack.lastOrNull()?.children?.add(n)
                    if (root == null) root = n
                    stack.addLast(n)
                }
                XmlPullParser.TEXT -> stack.lastOrNull()?.text?.append(p.text)
                XmlPullParser.END_TAG -> stack.removeLastOrNull()
            }
            ev = p.next()
        }
        root
    }.getOrNull()
}
