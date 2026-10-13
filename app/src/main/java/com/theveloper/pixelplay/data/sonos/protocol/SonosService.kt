package com.theveloper.pixelplay.data.sonos.protocol

/** UPnP services of a Sonos player that this app talks to. */
internal enum class SonosService(
    val controlPath: String,
    val eventPath: String,
    val serviceType: String,
) {
    AV_TRANSPORT(
        controlPath = "/MediaRenderer/AVTransport/Control",
        eventPath = "/MediaRenderer/AVTransport/Event",
        serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
    ),
    RENDERING_CONTROL(
        controlPath = "/MediaRenderer/RenderingControl/Control",
        eventPath = "/MediaRenderer/RenderingControl/Event",
        serviceType = "urn:schemas-upnp-org:service:RenderingControl:1",
    ),
    GROUP_RENDERING_CONTROL(
        controlPath = "/MediaRenderer/GroupRenderingControl/Control",
        eventPath = "/MediaRenderer/GroupRenderingControl/Event",
        serviceType = "urn:schemas-upnp-org:service:GroupRenderingControl:1",
    ),
    ZONE_GROUP_TOPOLOGY(
        controlPath = "/ZoneGroupTopology/Control",
        eventPath = "/ZoneGroupTopology/Event",
        serviceType = "urn:schemas-upnp-org:service:ZoneGroupTopology:1",
    ),
    CONTENT_DIRECTORY(
        controlPath = "/MediaServer/ContentDirectory/Control",
        eventPath = "/MediaServer/ContentDirectory/Event",
        serviceType = "urn:schemas-upnp-org:service:ContentDirectory:1",
    ),
}
