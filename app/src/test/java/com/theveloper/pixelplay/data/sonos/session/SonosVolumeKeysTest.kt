package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.model.SonosDevice
import com.theveloper.pixelplay.data.sonos.model.SonosUdn
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SonosVolumeKeysTest {

    private val device = SonosDevice(SonosUdn("RINCON_1"), "Move", "192.168.1.50", 1)

    @Test
    fun `the keys go to the speaker only with a session that is on and a volume to change`() {
        assertThat(volumeKeyAction(SonosConnection.Active(device), volumeAttached = true, up = true)).isEqualTo(VolumeKeyAction.Change(+SonosVolumeKeys.STEP))
        assertThat(volumeKeyAction(SonosConnection.Active(device), volumeAttached = true, up = false)).isEqualTo(VolumeKeyAction.Change(-SonosVolumeKeys.STEP))
    }

    @Test
    fun `in every other case the keys stay with the phone`() {
        assertThat(volumeKeyAction(SonosConnection.Idle, volumeAttached = false, up = true)).isEqualTo(VolumeKeyAction.Phone)
        assertThat(volumeKeyAction(SonosConnection.Connecting(device), volumeAttached = false, up = true)).isEqualTo(VolumeKeyAction.Phone)
        assertThat(volumeKeyAction(SonosConnection.Connecting(device), volumeAttached = true, up = false)).isEqualTo(VolumeKeyAction.Phone)
        assertThat(volumeKeyAction(SonosConnection.Failed(device, SonosFailure.Timeout), volumeAttached = true, up = true)).isEqualTo(VolumeKeyAction.Phone)
        assertThat(volumeKeyAction(SonosConnection.Active(device), volumeAttached = false, up = true)).isEqualTo(VolumeKeyAction.Phone)
    }

    @Test
    fun `the indicator shows when a key is used and goes away by itself`() = runTest {
        val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val states = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { indicatorVisible(requests, holdMs = 1_500).collect { states += it } }
        runCurrent()
        assertThat(states).containsExactly(false)

        requests.tryEmit(Unit)
        runCurrent()
        assertThat(states.last()).isTrue()

        advanceTimeBy(1_400)
        runCurrent()
        assertThat(states.last()).isTrue()
        advanceTimeBy(200)
        runCurrent()
        assertThat(states.last()).isFalse()
    }

    @Test
    fun `another key while it shows keeps it showing for the full time again`() = runTest {
        val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val states = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { indicatorVisible(requests, holdMs = 1_500).collect { states += it } }
        runCurrent()

        requests.tryEmit(Unit)
        runCurrent()
        advanceTimeBy(1_000)
        requests.tryEmit(Unit)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(states.last()).isTrue()
        advanceTimeBy(600)
        runCurrent()
        assertThat(states.last()).isFalse()
    }

    private class KeysFixture(val keys: SonosVolumeKeys, val session: SonosSessionHolder, val bridge: SonosVolumeBridge)

    private fun keysFixture(): KeysFixture {
        val bridge = SonosVolumeBridge()
        val session = SonosSessionHolder()
        return KeysFixture(SonosVolumeKeys(bridge, session), session, bridge)
    }

    @Test
    fun `a key with a session on and a volume to change goes to the speaker and shows the indicator`() = runTest {
        val f = keysFixture()
        val control = mockk<SonosVolumeControl>(relaxed = true)
        f.bridge.attach(control)
        f.session.set(SonosConnection.Active(device))
        var shown = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.keys.requests.collect { shown++ } }
        runCurrent()

        assertThat(f.keys.onKey(up = true)).isTrue()
        assertThat(f.keys.onKey(up = false)).isTrue()

        verify(exactly = 1) { control.adjust(+SonosVolumeKeys.STEP) }
        verify(exactly = 1) { control.adjust(-SonosVolumeKeys.STEP) }
        assertThat(shown).isEqualTo(2)
    }

    @Test
    fun `a key with no volume to change stays with the phone and shows nothing`() = runTest {
        val f = keysFixture()
        f.session.set(SonosConnection.Active(device))
        var shown = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.keys.requests.collect { shown++ } }
        runCurrent()

        assertThat(f.keys.onKey(up = true)).isFalse()
        assertThat(shown).isEqualTo(0)
    }

    @Test
    fun `a key while the speaker is still connecting stays with the phone`() = runTest {
        val f = keysFixture()
        val control = mockk<SonosVolumeControl>(relaxed = true)
        f.bridge.attach(control)
        f.session.set(SonosConnection.Connecting(device))

        assertThat(f.keys.onKey(up = true)).isFalse()
        verify(exactly = 0) { control.adjust(any()) }
    }
}
