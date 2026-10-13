package com.theveloper.pixelplay.data.sonos.session

internal enum class ChangeOrigin { OWN, EXTERNAL }

/**
 * Tells our own commands apart from changes made on the speaker or in the Sonos app, so the app
 * never fights what the user just did elsewhere.
 */
internal data class OwnChangeWindow(
    val lastCommandAtMs: Long? = null,
    val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    fun recordCommand(atMs: Long): OwnChangeWindow = copy(lastCommandAtMs = atMs)

    fun classify(changeAtMs: Long): ChangeOrigin {
        val commandAt = lastCommandAtMs ?: return ChangeOrigin.EXTERNAL
        val sinceCommand = changeAtMs - commandAt
        return if (sinceCommand in 0..windowMs) ChangeOrigin.OWN else ChangeOrigin.EXTERNAL
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 4_000L
    }
}
