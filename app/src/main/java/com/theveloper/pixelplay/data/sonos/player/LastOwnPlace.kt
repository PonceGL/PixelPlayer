package com.theveloper.pixelplay.data.sonos.player

import com.theveloper.pixelplay.data.sonos.model.RepeatMode

/** Where the music of the app was when the speaker stopped being ours: the song of the app's queue and the second. */
internal data class OwnPlace(val index: Int, val positionMs: Long, val repeat: RepeatMode)

/**
 * Remembers the last place of the app's own music on the speaker, so that when somebody else takes the speaker the
 * phone can go back to where the music was and not to wherever the other source is.
 */
internal class LastOwnPlace {
    private var index: Int? = null
    private var positionMs = 0L
    private var advancing = false
    private var seenAtMs = 0L
    private var repeat = RepeatMode.OFF
    private var leftAtMs: Long? = null

    fun seen(index: Int, positionMs: Long, advancing: Boolean, atMs: Long, repeat: RepeatMode) {
        this.index = index
        this.positionMs = positionMs
        this.advancing = advancing
        this.seenAtMs = atMs
        this.repeat = repeat
        leftAtMs = null
    }

    /** The speaker is not playing the app's music (only the first moment counts). */
    fun left(atMs: Long) {
        if (leftAtMs == null) leftAtMs = atMs
    }

    fun place(): OwnPlace? {
        val index = index ?: return null
        val elapsed = if (advancing) ((leftAtMs ?: seenAtMs) - seenAtMs).coerceAtLeast(0L) else 0L
        return OwnPlace(index, positionMs + elapsed, repeat)
    }
}
