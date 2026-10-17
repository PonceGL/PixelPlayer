package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SonosConnectPlanTest {

    @Test
    fun `a new queue never goes back to the place of the old one`() {
        assertThat(SonosConnectPlan.restoresPosition(freshQueue = true, hadSession = true)).isFalse()
    }

    @Test
    fun `changing speaker with the same queue brings the position back first`() {
        assertThat(SonosConnectPlan.restoresPosition(freshQueue = false, hadSession = true)).isTrue()
    }

    @Test
    fun `a first connection has nothing to bring back`() {
        assertThat(SonosConnectPlan.restoresPosition(freshQueue = false, hadSession = false)).isFalse()
    }

    @Test
    fun `a new queue always plays, even if the speaker was paused`() {
        assertThat(SonosConnectPlan.plays(freshQueue = true, pending = null, speakerWasPlaying = false, phoneWasPlaying = false)).isTrue()
    }

    @Test
    fun `without a new queue the earlier intent wins, then what the speaker did, then the phone`() {
        assertThat(SonosConnectPlan.plays(freshQueue = false, pending = false, speakerWasPlaying = true, phoneWasPlaying = true)).isFalse()
        assertThat(SonosConnectPlan.plays(freshQueue = false, pending = null, speakerWasPlaying = true, phoneWasPlaying = false)).isTrue()
        assertThat(SonosConnectPlan.plays(freshQueue = false, pending = null, speakerWasPlaying = null, phoneWasPlaying = true)).isTrue()
        assertThat(SonosConnectPlan.plays(freshQueue = false, pending = null, speakerWasPlaying = null, phoneWasPlaying = false)).isFalse()
    }
}
