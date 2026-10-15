package com.theveloper.pixelplay.data.sonos.session

/** Lets one thing through per [minGapMs], for messages that must not pile up on the user. */
internal class ThrottleGate(private val minGapMs: Long, private val now: () -> Long) {
    private var lastPassAtMs: Long? = null

    @Synchronized
    fun tryPass(): Boolean {
        val at = now()
        val last = lastPassAtMs
        if (last != null && at - last < minGapMs) return false
        lastPassAtMs = at
        return true
    }
}
