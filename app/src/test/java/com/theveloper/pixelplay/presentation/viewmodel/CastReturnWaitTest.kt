package com.theveloper.pixelplay.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CastReturnWaitTest {

    @Test
    fun `it waits until the music has come back, not before`() = runTest {
        val returns = MutableStateFlow(3)
        var done: Boolean? = null
        launch { done = awaitCastReturn(returns, before = 3, timeoutMs = 10_000) }
        runCurrent()
        advanceTimeBy(4_000)
        assertThat(done).isNull()

        returns.value = 4
        runCurrent()

        assertThat(done).isTrue()
        assertThat(currentTime).isEqualTo(4_000)
    }

    @Test
    fun `a return that happened before asking does not count`() = runTest {
        val returns = MutableStateFlow(5)

        val result = awaitCastReturn(returns, before = 5, timeoutMs = 2_000)

        assertThat(result).isFalse()
        assertThat(currentTime).isEqualTo(2_000)
    }

    @Test
    fun `a music that never comes back ends the wait with false at the limit`() = runTest {
        val result = awaitCastReturn(MutableStateFlow(0), before = 0, timeoutMs = 10_000)

        assertThat(result).isFalse()
        assertThat(currentTime).isEqualTo(10_000)
    }

    @Test
    fun `it answers at once if the return already happened after before`() = runTest {
        val result = awaitCastReturn(MutableStateFlow(9), before = 8, timeoutMs = 10_000)

        assertThat(result).isTrue()
        assertThat(currentTime).isEqualTo(0)
    }
}
