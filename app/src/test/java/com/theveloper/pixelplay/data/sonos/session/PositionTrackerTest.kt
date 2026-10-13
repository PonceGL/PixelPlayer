package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PositionTrackerTest {

    private val anchor = PositionAnchor(positionMs = 10_000L, atElapsedMs = 1_000L, isAdvancing = true, durationMs = 180_000L)

    @Test
    fun `an advancing track moves with the clock`() {
        assertThat(PositionTracker.positionAt(anchor, nowElapsedMs = 3_500L)).isEqualTo(12_500L)
    }

    @Test
    fun `the position at the moment of the read is the read itself`() {
        assertThat(PositionTracker.positionAt(anchor, nowElapsedMs = 1_000L)).isEqualTo(10_000L)
    }

    @Test
    fun `a paused track does not move`() {
        val paused = anchor.copy(isAdvancing = false)

        assertThat(PositionTracker.positionAt(paused, nowElapsedMs = 60_000L)).isEqualTo(10_000L)
    }

    @Test
    fun `the position never passes the duration`() {
        assertThat(PositionTracker.positionAt(anchor, nowElapsedMs = 10_000_000L)).isEqualTo(180_000L)
    }

    @Test
    fun `an unknown duration puts no cap`() {
        val open = anchor.copy(durationMs = null)

        assertThat(PositionTracker.positionAt(open, nowElapsedMs = 501_000L)).isEqualTo(510_000L)
    }

    @Test
    fun `a clock that goes backwards keeps the anchored position`() {
        assertThat(PositionTracker.positionAt(anchor, nowElapsedMs = 500L)).isEqualTo(10_000L)
    }

    @Test
    fun `a zero duration is treated as unknown`() {
        val zero = anchor.copy(durationMs = 0L)

        assertThat(PositionTracker.positionAt(zero, nowElapsedMs = 3_000L)).isEqualTo(12_000L)
    }
}
