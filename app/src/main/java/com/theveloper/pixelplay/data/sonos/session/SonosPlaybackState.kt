package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.TrackMetadata
import com.theveloper.pixelplay.data.sonos.model.TransportState
import com.theveloper.pixelplay.data.sonos.protocol.TransportError

/** What the speaker reports about its own playback. It is the source of truth for the app. */
internal data class SonosPlaybackState(
    val transportState: TransportState = TransportState.UNKNOWN,
    val playMode: PlayMode? = null,
    val currentTrackNumber: Int? = null,
    val numberOfTracks: Int? = null,
    val currentTrackUri: String? = null,
    val currentTrackDurationMs: Long? = null,
    val currentTrack: TrackMetadata? = null,
    val nextTrackUri: String? = null,
    val volume: Int? = null,
    val muted: Boolean? = null,
    val lastTransportError: TransportError? = null,
)
