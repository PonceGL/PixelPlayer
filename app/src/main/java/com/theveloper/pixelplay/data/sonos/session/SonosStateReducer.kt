package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.protocol.AvTransportChange
import com.theveloper.pixelplay.data.sonos.protocol.RenderingChange
import com.theveloper.pixelplay.data.sonos.protocol.TransportErrors

/** Applies speaker events, which are deltas, onto the last known state. */
internal object SonosStateReducer {

    fun reduce(state: SonosPlaybackState, change: AvTransportChange): SonosPlaybackState {
        val uri = change.currentTrackUri?.takeIf { it.isNotBlank() }
        val uriReported = change.currentTrackUri != null
        val uriChanged = uriReported && uri != state.currentTrackUri
        val trackCleared = uriReported && uri == null
        return state.copy(
            transportState = change.transportState ?: state.transportState,
            playMode = change.playMode ?: state.playMode,
            currentTrackNumber = change.currentTrackNumber ?: state.currentTrackNumber,
            numberOfTracks = change.numberOfTracks ?: state.numberOfTracks,
            currentTrackUri = if (uriReported) uri else state.currentTrackUri,
            // The duration belongs to one uri, like the metadata below.
            currentTrackDurationMs = when {
                trackCleared -> null
                uriChanged -> change.currentTrackDurationMs
                else -> change.currentTrackDurationMs ?: state.currentTrackDurationMs
            },
            // Metadata belongs to one uri: a new uri without new metadata must not keep the old one.
            currentTrack = when {
                change.currentTrack != null -> change.currentTrack
                trackCleared || uriChanged -> null
                else -> state.currentTrack
            },
            nextTrackUri = if (change.nextTrackUri != null) change.nextTrackUri.takeIf { it.isNotBlank() } else state.nextTrackUri,
            lastTransportError = if (change.transportStatus != null) {
                TransportErrors.parse(change.transportStatus, change.transportErrorDescription)
            } else {
                state.lastTransportError
            },
        )
    }

    fun reduce(state: SonosPlaybackState, change: RenderingChange): SonosPlaybackState = state.copy(
        volume = change.volume ?: state.volume,
        muted = change.muted ?: state.muted,
    )
}
