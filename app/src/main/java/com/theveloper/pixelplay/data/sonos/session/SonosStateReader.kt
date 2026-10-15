package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.TransportState
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.SonosTransport
import com.theveloper.pixelplay.data.sonos.protocol.Didl
import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import com.theveloper.pixelplay.data.sonos.protocol.UpnpTime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** The speaker's playback state and how far into the current song it is. */
internal data class SpeakerSnapshot(val playback: SonosPlaybackState, val positionMs: Long?)

/** Reads the full playback state of the coordinator of a speaker, which events only change in parts. */
internal class SonosStateReader(
    private val transport: SonosTransport,
    private val host: String,
) {

    suspend fun read(): SonosResult<SpeakerSnapshot> = coroutineScope {
        val transportInfo = async { ask("GetTransportInfo", "CurrentTransportState") }
        val positionInfo = async { ask("GetPositionInfo", "Track") }
        val settings = async { ask("GetTransportSettings", "PlayMode") }
        val mediaInfo = async { ask("GetMediaInfo", "NrTracks") }
        val answers = listOf(transportInfo, positionInfo, settings, mediaInfo).map { it.await() }
        answers.firstOrNull { it is SonosResult.Err }?.let { return@coroutineScope it as SonosResult.Err }
        val (transportValues, positionValues, settingsValues, mediaValues) = answers.map { (it as SonosResult.Ok).value }

        val uri = positionValues["TrackURI"]?.trim()?.takeIf { it.isNotEmpty() }
        SonosResult.Ok(
            SpeakerSnapshot(
                playback = SonosPlaybackState(
                    transportState = TransportState.fromUpnp(transportValues["CurrentTransportState"]),
                    playMode = PlayMode.fromUpnp(settingsValues["PlayMode"]),
                    currentTrackNumber = positionValues["Track"]?.trim()?.toIntOrNull(),
                    numberOfTracks = mediaValues["NrTracks"]?.trim()?.toIntOrNull(),
                    currentTrackUri = uri,
                    currentTrackDurationMs = UpnpTime.parseMs(positionValues["TrackDuration"]),
                    currentTrack = if (uri == null) null else Didl.parse(positionValues["TrackMetaData"]),
                    nextTrackUri = mediaValues["NextURI"]?.trim()?.takeIf { it.isNotEmpty() },
                ),
                positionMs = UpnpTime.parseMs(positionValues["RelTime"]),
            ),
        )
    }

    /** The answer, or a malformed response if the speaker leaves out [required], which every real answer has. */
    private suspend fun ask(action: String, required: String): SonosResult<Map<String, String>> {
        val result = transport.call(host, SonosService.AV_TRANSPORT, action, listOf("InstanceID" to "0"))
        return if (result is SonosResult.Ok && required !in result.value) SonosResult.Err(SonosFailure.MalformedResponse) else result
    }
}
