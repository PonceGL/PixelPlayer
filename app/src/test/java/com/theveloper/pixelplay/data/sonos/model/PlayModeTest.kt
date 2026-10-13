package com.theveloper.pixelplay.data.sonos.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PlayModeTest {

    @Test
    fun `each speaker value maps to its shuffle and repeat flags`() {
        val expected = listOf(
            Triple("NORMAL", false, RepeatMode.OFF),
            Triple("REPEAT_ALL", false, RepeatMode.ALL),
            Triple("REPEAT_ONE", false, RepeatMode.ONE),
            Triple("SHUFFLE_NOREPEAT", true, RepeatMode.OFF),
            Triple("SHUFFLE", true, RepeatMode.ALL),
            Triple("SHUFFLE_REPEAT_ONE", true, RepeatMode.ONE),
        )

        expected.forEach { (value, shuffle, repeat) ->
            val mode = PlayMode.fromUpnp(value)
            assertThat(mode?.shuffle).isEqualTo(shuffle)
            assertThat(mode?.repeat).isEqualTo(repeat)
            assertThat(mode?.upnpValue).isEqualTo(value)
        }
    }

    @Test
    fun `flags map back to the same mode`() {
        PlayMode.entries.forEach { mode ->
            assertThat(PlayMode.of(mode.shuffle, mode.repeat)).isEqualTo(mode)
        }
    }

    @Test
    fun `no shuffle and no repeat is NORMAL`() {
        assertThat(PlayMode.of(shuffle = false, repeat = RepeatMode.OFF)).isEqualTo(PlayMode.NORMAL)
    }

    @Test
    fun `unknown or missing values are not a mode`() {
        assertThat(PlayMode.fromUpnp("XYZ")).isNull()
        assertThat(PlayMode.fromUpnp("")).isNull()
        assertThat(PlayMode.fromUpnp(null)).isNull()
    }
}
