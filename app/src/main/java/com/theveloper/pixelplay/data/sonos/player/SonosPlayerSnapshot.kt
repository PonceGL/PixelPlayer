package com.theveloper.pixelplay.data.sonos.player

import com.theveloper.pixelplay.data.sonos.model.RepeatMode
import com.theveloper.pixelplay.data.sonos.model.TransportState
import com.theveloper.pixelplay.data.sonos.server.SonosMediaAccess
import com.theveloper.pixelplay.data.sonos.session.PlaybackError
import com.theveloper.pixelplay.data.sonos.session.PositionAnchor
import com.theveloper.pixelplay.data.sonos.session.SonosSessionState
import com.theveloper.pixelplay.data.sonos.session.SonosTrackMapper

/**
 * The app's queue as it was given to the speaker.
 *
 * @property songIds every song of the app's queue, in the app's order
 * @property window the part of it that is on the speaker; its first song is the speaker's track 1
 */
internal data class PlayerQueue(val songIds: List<String>, val window: IntRange)

/** Everything a media3 player has to say about the session, with no Android types so it can be tested. */
internal data class SonosPlayerSnapshot(
    val phase: Phase,
    val playWhenReady: Boolean,
    val currentIndex: Int?,
    val positionMs: Long,
    val positionAdvancing: Boolean,
    val durationMs: Long?,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val canControl: Boolean,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val canSeek: Boolean,
    val error: PlaybackError?,
) {
    enum class Phase { IDLE, BUFFERING, READY, ENDED }

    companion object {
        private const val RESTART_THRESHOLD_MS = 3_000L

        /**
         * @param shuffleByApp the queue was sent in a shuffled order the app chose, which the speaker does not know
         * @param previousIndex the song that was current before, to tell repeated songs apart when the number does not
         */
        fun from(
            state: SonosSessionState,
            nowElapsedMs: Long,
            queue: PlayerQueue,
            access: SonosMediaAccess,
            shuffleByApp: Boolean,
            previousIndex: Int?,
        ): SonosPlayerSnapshot {
            val playback = state.playback
            val mode = playback.playMode
            val phase = when (playback.transportState) {
                TransportState.PLAYING, TransportState.PAUSED_PLAYBACK -> Phase.READY
                TransportState.TRANSITIONING -> Phase.BUFFERING
                TransportState.STOPPED -> if (state.queueEnded) Phase.ENDED else Phase.READY
                TransportState.NO_MEDIA_PRESENT, TransportState.UNKNOWN -> Phase.IDLE
            }
            val playing = playback.transportState == TransportState.PLAYING
            val position = state.positionAt(nowElapsedMs)
            val duration = playback.currentTrackDurationMs?.takeIf { it > 0 }
            val number = playback.currentTrackNumber
            val total = playback.numberOfTracks
            val wraps = mode != null && (mode.shuffle || mode.repeat != RepeatMode.OFF)
            val controllable = phase != Phase.IDLE

            return SonosPlayerSnapshot(
                phase = phase,
                playWhenReady = playing || playback.transportState == TransportState.TRANSITIONING,
                currentIndex = if (controllable) indexOf(playback.currentTrackUri, number, queue, access, previousIndex) else null,
                positionMs = position,
                positionAdvancing = phase == Phase.READY && playing && state.anchor.isAdvancing(),
                durationMs = duration,
                shuffle = (mode?.shuffle ?: false) || shuffleByApp,
                repeat = mode?.repeat ?: RepeatMode.OFF,
                canControl = controllable,
                canPrevious = controllable && (wraps || (number != null && number > 1) || position > RESTART_THRESHOLD_MS),
                canNext = controllable && (wraps || (number != null && total != null && number < total)),
                canSeek = controllable && duration != null,
                error = state.lastError,
            )
        }

        private fun PositionAnchor?.isAdvancing() = this?.isAdvancing == true

        /**
         * The speaker says which track plays (a number inside the window) and its address. The number settles
         * repeated songs; the address proves it still means our song, otherwise it is searched for by address.
         */
        private fun indexOf(uri: String?, number: Int?, queue: PlayerQueue, access: SonosMediaAccess, previousIndex: Int?): Int? {
            val songId = uri?.let { SonosTrackMapper.songIdOf(it, access) } ?: return null
            val window = queue.window.filter { it in queue.songIds.indices }
            val byNumber = number?.takeIf { it in 1..window.size }?.let { window[it - 1] }
            if (byNumber != null && queue.songIds[byNumber] == songId) return byNumber

            val matches = window.filter { queue.songIds[it] == songId }
            if (matches.isEmpty()) return null
            return matches.firstOrNull { previousIndex != null && it > previousIndex } ?: matches.first()
        }
    }
}
