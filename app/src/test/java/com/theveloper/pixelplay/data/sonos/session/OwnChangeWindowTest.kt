package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OwnChangeWindowTest {

    private val afterCommand = OwnChangeWindow().recordCommand(atMs = 10_000L)

    @Test
    fun `a change right after our command is ours`() {
        assertThat(afterCommand.classify(changeAtMs = 10_050L)).isEqualTo(ChangeOrigin.OWN)
        assertThat(afterCommand.classify(changeAtMs = 13_999L)).isEqualTo(ChangeOrigin.OWN)
    }

    @Test
    fun `the window includes its last millisecond and ends after it`() {
        assertThat(afterCommand.classify(changeAtMs = 14_000L)).isEqualTo(ChangeOrigin.OWN)
        assertThat(afterCommand.classify(changeAtMs = 14_001L)).isEqualTo(ChangeOrigin.EXTERNAL)
    }

    @Test
    fun `without any command every change is external`() {
        assertThat(OwnChangeWindow().classify(changeAtMs = 5L)).isEqualTo(ChangeOrigin.EXTERNAL)
    }

    @Test
    fun `a change that happened before our command is external`() {
        assertThat(afterCommand.classify(changeAtMs = 9_999L)).isEqualTo(ChangeOrigin.EXTERNAL)
    }

    @Test
    fun `a newer command moves the window`() {
        val later = afterCommand.recordCommand(atMs = 20_000L)

        assertThat(later.classify(changeAtMs = 22_000L)).isEqualTo(ChangeOrigin.OWN)
        assertThat(later.classify(changeAtMs = 15_000L)).isEqualTo(ChangeOrigin.EXTERNAL)
    }

    @Test
    fun `the window length can be customised`() {
        val short = OwnChangeWindow(windowMs = 500L).recordCommand(atMs = 0L)

        assertThat(short.classify(changeAtMs = 500L)).isEqualTo(ChangeOrigin.OWN)
        assertThat(short.classify(changeAtMs = 501L)).isEqualTo(ChangeOrigin.EXTERNAL)
    }
}
