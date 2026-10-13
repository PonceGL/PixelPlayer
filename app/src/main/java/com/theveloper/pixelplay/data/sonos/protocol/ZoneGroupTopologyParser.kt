package com.theveloper.pixelplay.data.sonos.protocol

import com.theveloper.pixelplay.data.sonos.model.SonosGroup
import com.theveloper.pixelplay.data.sonos.model.SonosMember
import com.theveloper.pixelplay.data.sonos.model.SonosUdn
import java.net.URI
import java.net.URISyntaxException

internal object ZoneGroupTopologyParser {

    /** Groups of the household, or an empty list when [zoneGroupStateXml] is not a topology. */
    fun parse(zoneGroupStateXml: String): List<SonosGroup> {
        val root = SafeXml.parse(zoneGroupStateXml) ?: return emptyList()
        val groups = root.child("ZoneGroups")?.children.orEmpty().filter { it.name == "ZoneGroup" }
        return groups.mapNotNull { group ->
            val coordinator = group.attributes["Coordinator"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val members = group.children.filter { it.name == "ZoneGroupMember" }.mapNotNull(::memberOf)
            SonosGroup(SonosUdn(coordinator), members)
        }
    }

    private fun memberOf(element: XmlElement): SonosMember? {
        val udn = element.attributes["UUID"]?.takeIf { it.isNotBlank() } ?: return null
        val host = element.attributes["Location"]?.let(::hostOf) ?: return null
        return SonosMember(
            udn = SonosUdn(udn),
            roomName = element.attributes["ZoneName"].orEmpty(),
            host = host,
            isVisible = element.attributes["Invisible"] != "1",
        )
    }

    /** Host of an absolute URL; IPv6 literals keep their brackets so they can be reused in a URL. */
    private fun hostOf(location: String): String? = try {
        URI(location).host
    } catch (_: URISyntaxException) {
        null
    }
}
