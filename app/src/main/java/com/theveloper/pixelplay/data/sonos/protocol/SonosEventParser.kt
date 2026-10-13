package com.theveloper.pixelplay.data.sonos.protocol

import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.TrackMetadata
import com.theveloper.pixelplay.data.sonos.model.TransportState

/**
 * Changes carried by one AVTransport NOTIFY. Events are deltas: a null field means the variable
 * was absent, i.e. unchanged. A blank string means the speaker reported it as empty.
 */
internal data class AvTransportChange(
    val transportState: TransportState? = null,
    val playMode: PlayMode? = null,
    val currentTrackNumber: Int? = null,
    val numberOfTracks: Int? = null,
    val currentTrackUri: String? = null,
    val currentTrackDurationMs: Long? = null,
    val currentTrack: TrackMetadata? = null,
    val nextTrackUri: String? = null,
    val transportStatus: String? = null,
    val transportErrorDescription: String? = null,
)

internal data class RenderingChange(
    val volume: Int? = null,
    val muted: Boolean? = null,
)

internal object SonosEventParser {

    private const val MASTER_CHANNEL = "Master"

    fun parseAvTransport(body: String): AvTransportChange? {
        val variables = instanceVariables(body) ?: return null
        fun value(name: String): String? = variables.firstOrNull { it.name == name }?.attributes?.get("val")
        return AvTransportChange(
            transportState = value("TransportState")?.let(TransportState::fromUpnp),
            playMode = PlayMode.fromUpnp(value("CurrentPlayMode")),
            currentTrackNumber = value("CurrentTrack")?.trim()?.toIntOrNull(),
            numberOfTracks = value("NumberOfTracks")?.trim()?.toIntOrNull(),
            currentTrackUri = value("CurrentTrackURI"),
            currentTrackDurationMs = UpnpTime.parseMs(value("CurrentTrackDuration")),
            currentTrack = Didl.parse(value("CurrentTrackMetaData")),
            nextTrackUri = value("NextTrackURI"),
            transportStatus = value("TransportStatus"),
            transportErrorDescription = value("TransportErrorDescription"),
        )
    }

    fun parseRendering(body: String): RenderingChange? {
        val variables = instanceVariables(body) ?: return null
        fun master(name: String): String? = variables
            .firstOrNull { it.name == name && it.attributes["channel"] == MASTER_CHANNEL }
            ?.attributes?.get("val")
        return RenderingChange(
            volume = master("Volume")?.trim()?.toIntOrNull(),
            muted = master("Mute")?.trim()?.let { it == "1" },
        )
    }

    /** The variables inside `<InstanceID>` of the escaped `LastChange` property, or null if absent. */
    private fun instanceVariables(body: String): List<XmlElement>? {
        val propertySet = SafeXml.parse(body) ?: return null
        val lastChange = propertySet.descendants("LastChange").firstOrNull() ?: return null
        val event = SafeXml.parse(lastChange.text) ?: return null
        return event.child("InstanceID")?.children
    }
}
