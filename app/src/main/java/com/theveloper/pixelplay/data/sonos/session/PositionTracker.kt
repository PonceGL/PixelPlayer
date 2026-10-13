package com.theveloper.pixelplay.data.sonos.session

/** The last position read from the speaker and the moment (monotonic clock) it was read. */
internal data class PositionAnchor(
    val positionMs: Long,
    val atElapsedMs: Long,
    val isAdvancing: Boolean,
    val durationMs: Long?,
)

/** Speaker events carry no position, so it is interpolated between reads. */
internal object PositionTracker {

    fun positionAt(anchor: PositionAnchor, nowElapsedMs: Long): Long {
        if (!anchor.isAdvancing) return anchor.positionMs
        val elapsed = (nowElapsedMs - anchor.atElapsedMs).coerceAtLeast(0L)
        val position = anchor.positionMs + elapsed
        val duration = anchor.durationMs?.takeIf { it > 0L } ?: return position
        return position.coerceAtMost(duration)
    }
}
