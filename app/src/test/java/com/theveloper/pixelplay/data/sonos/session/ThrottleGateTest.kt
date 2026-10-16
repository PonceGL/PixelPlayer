package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ThrottleGateTest {

    @Test
    fun `the first one passes, the ones too close are held back, the one after the gap passes`() {
        var now = 1_000L
        val gate = ThrottleGate(minGapMs = 5_000) { now }

        assertThat(gate.tryPass()).isTrue()
        now += 4_999
        assertThat(gate.tryPass()).isFalse()
        now += 1
        assertThat(gate.tryPass()).isTrue()
    }

    @Test
    fun `a held back one does not push the gap further`() {
        var now = 0L
        val gate = ThrottleGate(minGapMs = 1_000) { now }

        assertThat(gate.tryPass()).isTrue()
        now = 900
        assertThat(gate.tryPass()).isFalse()
        now = 1_000
        assertThat(gate.tryPass()).isTrue()
    }
}
