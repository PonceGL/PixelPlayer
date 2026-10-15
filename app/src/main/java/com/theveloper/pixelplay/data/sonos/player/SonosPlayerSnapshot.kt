package com.theveloper.pixelplay.data.sonos.player

import com.theveloper.pixelplay.data.sonos.model.RepeatMode
import com.theveloper.pixelplay.data.sonos.model.TransportState
import com.theveloper.pixelplay.data.sonos.server.SonosMediaAccess
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
    /** "Previous" can always at least restart the song. */
    val canPrevious: Boolean,
    /** There is an earlier song to go back to. */
    val canPreviousItem: Boolean,
    val canNext: Boolean,
    val canSeek: Boolean,
) {
    enum class Phase { IDLE, BUFFERING, READY, ENDED }

    companion object {
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
            val number = playback.currentTrackNumber
            val total = playback.numberOfTracks
            // Playing something that is not in our queue is not playing a song of the player: no song to show.
            val index = indexOf(playback.currentTrackUri, number, queue, access, previousIndex)
            val phase = when {
                index == null -> Phase.IDLE
                else -> when (playback.transportState) {
                    TransportState.PLAYING, TransportState.PAUSED_PLAYBACK -> Phase.READY
                    TransportState.TRANSITIONING -> Phase.BUFFERING
                    TransportState.STOPPED -> if (state.queueEnded) Phase.ENDED else Phase.READY
                    TransportState.NO_MEDIA_PRESENT, TransportState.UNKNOWN -> Phase.IDLE
                }
            }
            val playing = playback.transportState == TransportState.PLAYING
            val duration = playback.currentTrackDurationMs?.takeIf { it > 0 }
            val wraps = mode != null && (mode.shuffle || mode.repeat != RepeatMode.OFF)
            val controllable = phase != Phase.IDLE

            return SonosPlayerSnapshot(
                phase = phase,
                playWhenReady = controllable && (playing || playback.transportState == TransportState.TRANSITIONING),
                currentIndex = if (controllable) index else null,
                positionMs = state.positionAt(nowElapsedMs),
                positionAdvancing = phase == Phase.READY && playing && state.anchor.isAdvancing(),
                durationMs = duration,
                shuffle = (mode?.shuffle ?: false) || shuffleByApp,
                repeat = mode?.repeat ?: RepeatMode.OFF,
                canControl = controllable,
                canPrevious = controllable,
                canPreviousItem = controllable && (wraps || (number != null && number > 1)),
                canNext = controllable && (wraps || (number != null && total != null && number < total)),
                canSeek = controllable && duration != null,
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
            // At or after the song that was current: the same state always gives the same song.
            return matches.firstOrNull { previousIndex != null && it >= previousIndex } ?: matches.first()
        }
    }
}
