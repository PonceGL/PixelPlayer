package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SonosVolumeControlTest {

    /** A speaker that takes [rttMs] to answer and remembers what it was told. */
    private class FakeSink(val scope: TestScope, var rttMs: Long = 25) : VolumeSink {
        val log = mutableListOf<String>()
        val sentAt = mutableListOf<Long>()
        var level = 30
        var muted = false
        /** What the speaker would tell by event once it has done something. */
        var onState: (SonosVolume) -> Unit = {}
        var failNext: SonosFailure? = null
        private var inFlight = 0
        var maxInFlight = 0

        private suspend fun <T> turn(label: String, work: () -> T): SonosResult<T> {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            log += label
            sentAt += scope.currentTime
            delay(rttMs)
            inFlight--
            failNext?.let { failNext = null; return SonosResult.Err(it) }
            return SonosResult.Ok(work()).also { onState(SonosVolume(level, muted)) }
        }

        override suspend fun setVolume(level: Int) = turn("set $level") { this.level = level; level }
        override suspend fun adjust(delta: Int) = turn("adjust $delta") { level = (level + delta).coerceIn(0, 100); level }
        override suspend fun setMuted(muted: Boolean) = turn("mute $muted") { this.muted = muted }
    }

    private class Fixture(val scope: TestScope) {
        val sink = FakeSink(scope)
        val reported = MutableStateFlow<SonosVolume?>(SonosVolume(30, false))
        val failures = mutableListOf<SonosFailure>()
        init { sink.onState = { reported.value = it } }
        val control = SonosVolumeControl(sink, reported, scope.backgroundScope, { scope.currentTime }, { failures += it })
        val shown get() = control.shown.value
    }

    private fun test(block: suspend TestScope.(Fixture) -> Unit) = runTest { block(Fixture(this)) }

    /** Lets the time go by: the control works in a background scope, which `advanceUntilIdle` does not wait for. */
    private suspend fun TestScope.settle() {
        advanceTimeBy(3_000)
        runCurrent()
    }

    @Test
    fun `when nothing is going on it shows what the speaker says`() = test { f ->
        runCurrent()
        assertThat(f.shown).isEqualTo(SonosVolume(30, false))

        f.reported.value = SonosVolume(12, true)
        runCurrent()
        assertThat(f.shown).isEqualTo(SonosVolume(12, true))

        f.reported.value = null
        runCurrent()
        assertThat(f.shown).isNull()
    }

    @Test
    fun `a level asked for is shown at once and sent`() = test { f ->
        runCurrent()
        f.control.setLevel(55)

        assertThat(f.shown?.level).isEqualTo(55)
        settle()
        assertThat(f.sink.log).containsExactly("set 55")
    }

    @Test
    fun `a level outside zero to a hundred is brought inside`() = test { f ->
        runCurrent()
        f.control.setLevel(-3)
        settle()
        f.control.setLevel(140)
        settle()

        assertThat(f.sink.log).containsExactly("set 0", "set 100").inOrder()
    }

    @Test
    fun `dragging sends few levels, never an older one after a newer one, and ends on the last`() = test { f ->
        runCurrent()
        for (level in 31..60) {
            f.control.setLevel(level)
            advanceTimeBy(10)
        }
        settle()

        val sent = f.sink.log.map { it.removePrefix("set ").toInt() }
        assertThat(sent).isInOrder()
        assertThat(sent.last()).isEqualTo(60)
        assertThat(sent.size).isLessThan(8)
        assertThat(f.sink.maxInFlight).isEqualTo(1)
        // between two sends of an absolute level at least 80 ms
        assertThat(f.sink.sentAt.zipWithNext { a, b -> b - a }.all { it >= 80 }).isTrue()
    }

    @Test
    fun `while the user is setting a level, a different level reported on the way does not move it`() = test { f ->
        runCurrent()
        f.control.setLevel(40)
        advanceTimeBy(10) // sent, not yet confirmed by the speaker

        f.reported.value = SonosVolume(33, false)
        runCurrent()
        assertThat(f.shown?.level).isEqualTo(40)

        settle() // the speaker confirms 40
        assertThat(f.shown?.level).isEqualTo(40)
    }

    @Test
    fun `once the speaker has confirmed the level, another one reported is somebody else's change`() = test { f ->
        runCurrent()
        f.control.setLevel(40)
        advanceTimeBy(100)

        f.reported.value = SonosVolume(33, false)
        runCurrent()
        assertThat(f.shown?.level).isEqualTo(33)
    }

    @Test
    fun `after the margin, what the speaker says wins again`() = test { f ->
        runCurrent()
        f.control.setLevel(40)
        settle()
        advanceTimeBy(800)

        f.reported.value = SonosVolume(20, false)
        runCurrent()
        assertThat(f.shown?.level).isEqualTo(20)
    }

    @Test
    fun `keys pressed while one is on its way are added up and sent as one change`() = test { f ->
        runCurrent()
        f.control.adjust(5)
        advanceTimeBy(5) // the first one is on its way now
        f.control.adjust(5)
        f.control.adjust(5)
        settle()

        assertThat(f.sink.log).containsExactly("adjust 5", "adjust 10").inOrder()
        assertThat(f.sink.maxInFlight).isEqualTo(1)
        assertThat(f.shown?.level).isEqualTo(45)
    }

    @Test
    fun `keys pressed before the first goes out are one change`() = test { f ->
        runCurrent()
        f.control.adjust(5)
        f.control.adjust(5)
        f.control.adjust(-5)
        settle()

        assertThat(f.sink.log).containsExactly("adjust 5")
        assertThat(f.shown?.level).isEqualTo(35)
    }

    @Test
    fun `a key changes what is shown at once`() = test { f ->
        runCurrent()
        f.control.adjust(5)

        assertThat(f.shown?.level).isEqualTo(35)
    }

    @Test
    fun `the level the speaker gives back after a key is the level shown`() = test { f ->
        runCurrent()
        f.sink.level = 98
        f.control.adjust(5)
        settle()

        assertThat(f.shown?.level).isEqualTo(100)
    }

    @Test
    fun `turning up while muted takes the mute off first`() = test { f ->
        f.sink.muted = true
        f.reported.value = SonosVolume(30, true)
        runCurrent()
        f.control.adjust(5)
        settle()

        assertThat(f.sink.log).containsExactly("mute false", "adjust 5").inOrder()
        assertThat(f.shown?.muted).isFalse()
    }

    @Test
    fun `turning down while muted leaves the mute alone`() = test { f ->
        f.sink.muted = true
        f.reported.value = SonosVolume(30, true)
        runCurrent()
        f.control.adjust(-5)
        settle()

        assertThat(f.sink.log).containsExactly("adjust -5")
        assertThat(f.shown?.muted).isTrue()
    }

    @Test
    fun `muting flips what is shown at once and sends the new state`() = test { f ->
        runCurrent()
        f.control.toggleMute()
        assertThat(f.shown?.muted).isTrue()
        settle()
        f.control.toggleMute()
        settle()

        assertThat(f.sink.log).containsExactly("mute true", "mute false").inOrder()
        assertThat(f.shown?.muted).isFalse()
    }

    @Test
    fun `a key after a level that has not gone out yet moves that level`() = test { f ->
        runCurrent()
        f.control.setLevel(50)
        f.control.adjust(5)
        settle()

        assertThat(f.sink.log).containsExactly("set 55")
        assertThat(f.shown?.level).isEqualTo(55)
    }

    @Test
    fun `a refusal is told and what is shown goes back to what the speaker says`() = test { f ->
        runCurrent()
        f.sink.failNext = SonosFailure.Timeout
        f.control.setLevel(80)
        settle()

        assertThat(f.failures).containsExactly(SonosFailure.Timeout)
        assertThat(f.shown?.level).isEqualTo(30)
    }

    @Test
    fun `a failed key does not leave a change waiting to be sent`() = test { f ->
        runCurrent()
        f.sink.failNext = SonosFailure.Timeout
        f.control.adjust(5)
        settle()
        val sentBefore = f.sink.log.size
        advanceTimeBy(5_000)

        assertThat(f.sink.log).hasSize(sentBefore)
        assertThat(f.shown?.level).isEqualTo(30)
    }

    @Test
    fun `with no volume known a key still goes to the speaker`() = test { f ->
        f.reported.value = null
        runCurrent()
        f.control.adjust(5)
        settle()

        assertThat(f.sink.log).containsExactly("adjust 5")
        assertThat(f.shown?.level).isEqualTo(35)
    }

    @Test
    fun `raising the level with a slider while muted takes the mute off first`() = test { f ->
        f.sink.muted = true
        f.reported.value = SonosVolume(30, true)
        runCurrent()
        f.control.setLevel(60)
        settle()

        assertThat(f.sink.log).containsExactly("mute false", "set 60").inOrder()
        assertThat(f.shown).isEqualTo(SonosVolume(60, false))
    }

    @Test
    fun `lowering the level with a slider while muted leaves the mute alone`() = test { f ->
        f.sink.muted = true
        f.reported.value = SonosVolume(30, true)
        runCurrent()
        f.control.setLevel(10)
        settle()

        assertThat(f.sink.log).containsExactly("set 10")
        assertThat(f.shown?.muted).isTrue()
    }

    @Test
    fun `a speaker that cannot call back keeps the level it answered to a key, the old observation does not come back`() = test { f ->
        f.sink.onState = {} // no events: what the speaker reported stays at 30
        runCurrent()
        f.control.adjust(5)
        settle()

        assertThat(f.shown?.level).isEqualTo(35)
    }

    @Test
    fun `an event after the answer is the speaker's word again`() = test { f ->
        f.sink.onState = {}
        runCurrent()
        f.control.adjust(5)
        settle()

        f.reported.value = SonosVolume(50, false) // somebody else turned it up
        runCurrent()

        assertThat(f.shown?.level).isEqualTo(50)
    }

    @Test
    fun `a level chosen while a key is on its way is not replaced by the answer to the key`() = test { f ->
        f.sink.rttMs = 200
        runCurrent()
        f.control.adjust(5)
        advanceTimeBy(50)
        f.control.setLevel(80)
        advanceTimeBy(300) // the answer to the key arrives (35)
        runCurrent()

        assertThat(f.shown?.level).isEqualTo(80)
        settle()
        assertThat(f.sink.log).containsExactly("adjust 5", "set 80").inOrder()
        assertThat(f.shown?.level).isEqualTo(80)
    }
}
