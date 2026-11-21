package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SonosVolumeBridgeTest {

    private class RecordingSink : VolumeSink {
        val log = mutableListOf<String>()
        override suspend fun setVolume(level: Int): SonosResult<Int> { log += "set $level"; return SonosResult.Ok(level) }
        override suspend fun adjust(delta: Int): SonosResult<Int> { log += "adjust $delta"; return SonosResult.Ok(30 + delta) }
        override suspend fun setMuted(muted: Boolean): SonosResult<Unit> { log += "mute $muted"; return SonosResult.Ok(Unit) }
    }

    @Test
    fun `with no session nothing is handled and no volume is shown`() = runTest {
        val bridge = SonosVolumeBridge()

        assertThat(bridge.isAttached).isFalse()
        assertThat(bridge.setLevel(40)).isFalse()
        assertThat(bridge.adjust(5)).isFalse()
        assertThat(bridge.toggleMute()).isFalse()
    }

    @Test
    fun `with a session its volume is shown and the orders reach it`() = runTest {
        val bridge = SonosVolumeBridge()
        val sink = RecordingSink()
        val reported = MutableStateFlow<SonosVolume?>(SonosVolume(30, false))
        val control = SonosVolumeControl(sink, reported, backgroundScope, { testScheduler.currentTime }, {})
        bridge.attach(control)
        val seen = mutableListOf<SonosVolume?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { bridge.volume.collect { seen += it } }
        runCurrent()

        assertThat(bridge.isAttached).isTrue()
        assertThat(bridge.adjust(5)).isTrue()
        advanceTimeBy(100)
        runCurrent()

        assertThat(sink.log).containsExactly("adjust 5")
        assertThat(seen.last()?.level).isEqualTo(35)
    }

    @Test
    fun `letting go of a session that is no longer the current one leaves the current one`() = runTest {
        val bridge = SonosVolumeBridge()
        val sink = RecordingSink()
        val reported = MutableStateFlow<SonosVolume?>(SonosVolume(30, false))
        val first = SonosVolumeControl(sink, reported, backgroundScope, { testScheduler.currentTime }, {})
        val second = SonosVolumeControl(sink, reported, backgroundScope, { testScheduler.currentTime }, {})

        bridge.attach(first)
        bridge.attach(second)
        bridge.detach(first)

        assertThat(bridge.isAttached).isTrue()
        bridge.detach(second)
        assertThat(bridge.isAttached).isFalse()
    }
}
