package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.model.TransportState

/** Where the phone last knew the playback was, persisted so it survives the process dying. */
internal data class SavedPlayback(
    val trackNumber: Int,
    val positionMs: Long,
    val durationMs: Long,
    val savedAtMs: Long,
)

internal sealed interface ReattachAction {
    data object LeaveAsIs : ReattachAction

    /** Only press play: the speaker kept its position. */
    data object Resume : ReattachAction

    data class SeekAndPlay(val trackNumber: Int, val positionMs: Long) : ReattachAction
}

/** Decides how to pick up a queue that is already on the speaker when the app comes back. */
internal object ReattachPlanner {

    const val FINISHED_MARGIN_MS = 3_000L

    fun plan(
        speakerState: TransportState,
        queueLength: Int,
        saved: SavedPlayback?,
        nowMs: Long,
    ): ReattachAction = when (speakerState) {
        TransportState.PLAYING, TransportState.TRANSITIONING -> ReattachAction.LeaveAsIs
        TransportState.PAUSED_PLAYBACK -> ReattachAction.Resume
        TransportState.STOPPED -> planFromStopped(queueLength, saved, nowMs)
        TransportState.NO_MEDIA_PRESENT, TransportState.UNKNOWN -> ReattachAction.LeaveAsIs
    }

    private fun planFromStopped(queueLength: Int, saved: SavedPlayback?, nowMs: Long): ReattachAction {
        if (saved == null || queueLength <= 0) return ReattachAction.LeaveAsIs
        if (saved.trackNumber !in 1..queueLength) return ReattachAction.SeekAndPlay(trackNumber = 1, positionMs = 0L)
        return if (hasProbablyEnded(saved, nowMs)) {
            val next = if (saved.trackNumber + 1 > queueLength) 1 else saved.trackNumber + 1
            ReattachAction.SeekAndPlay(trackNumber = next, positionMs = 0L)
        } else {
            ReattachAction.SeekAndPlay(trackNumber = saved.trackNumber, positionMs = saved.positionMs)
        }
    }

    private fun hasProbablyEnded(saved: SavedPlayback, nowMs: Long): Boolean {
        if (saved.durationMs <= 0L) return false
        val age = (nowMs - saved.savedAtMs).coerceAtLeast(0L)
        return saved.positionMs + age >= saved.durationMs - FINISHED_MARGIN_MS
    }
}
