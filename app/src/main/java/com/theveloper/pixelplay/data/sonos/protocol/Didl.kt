package com.theveloper.pixelplay.data.sonos.protocol

import com.theveloper.pixelplay.data.sonos.model.TrackMetadata

/** DIDL-Lite: the metadata document Sonos stores for every queue item. */
internal object Didl {

    private const val MUSIC_TRACK_CLASS = "object.item.audioItem.musicTrack"
    private const val PROTOCOL_INFO_MIME_INDEX = 2

    fun build(track: TrackMetadata): String = buildString {
        append("<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
        append("xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\" ")
        append("xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">")
        append("<item id=\"-1\" parentID=\"-1\" restricted=\"true\">")
        append("<dc:title>${XmlText.escape(track.title)}</dc:title>")
        append("<upnp:class>$MUSIC_TRACK_CLASS</upnp:class>")
        track.artist?.takeIf { it.isNotBlank() }?.let { append("<dc:creator>${XmlText.escape(it)}</dc:creator>") }
        track.album?.takeIf { it.isNotBlank() }?.let { append("<upnp:album>${XmlText.escape(it)}</upnp:album>") }
        track.albumArtUrl?.takeIf { it.isNotBlank() }
            ?.let { append("<upnp:albumArtURI>${XmlText.escape(it)}</upnp:albumArtURI>") }
        val duration = track.durationMs?.let(UpnpTime::format)?.let { " duration=\"$it\"" }.orEmpty()
        append("<res protocolInfo=\"http-get:*:${XmlText.escape(track.mimeType)}:*\"$duration>")
        append("${XmlText.escape(track.resourceUrl)}</res>")
        append("</item></DIDL-Lite>")
    }

    /** Null when [didl] is missing, malformed or describes an item without a playable resource. */
    fun parse(didl: String?): TrackMetadata? {
        val item = didl?.let(SafeXml::parse)?.child("item") ?: return null
        val resource = item.child("res") ?: return null
        val url = resource.text.trim().takeIf { it.isNotEmpty() } ?: return null
        val mime = resource.attributes["protocolInfo"]
            ?.split(':')?.getOrNull(PROTOCOL_INFO_MIME_INDEX)
            ?.takeIf { it.isNotBlank() && it != "*" }
            ?: ""
        return TrackMetadata(
            title = item.child("title")?.text?.trim().orEmpty(),
            artist = item.child("creator")?.text?.trim()?.takeIf { it.isNotEmpty() },
            album = item.child("album")?.text?.trim()?.takeIf { it.isNotEmpty() },
            albumArtUrl = item.child("albumArtURI")?.text?.trim()?.takeIf { it.isNotEmpty() },
            durationMs = UpnpTime.parseMs(resource.attributes["duration"]),
            mimeType = mime,
            resourceUrl = url,
        )
    }
}
