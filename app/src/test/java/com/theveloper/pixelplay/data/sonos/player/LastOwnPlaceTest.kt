package com.theveloper.pixelplay.data.sonos.player

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.model.RepeatMode
import org.junit.jupiter.api.Test

class LastOwnPlaceTest {

    @Test
    fun `nothing seen is no place`() {
        assertThat(LastOwnPlace().place()).isNull()
    }

    @Test
    fun `while the speaker is ours the place is the last thing seen`() {
        val place = LastOwnPlace()
        place.seen(index = 3, positionMs = 40_000, advancing = true, atMs = 1_000, repeat = RepeatMode.ALL)

        assertThat(place.place()).isEqualTo(OwnPlace(3, 40_000, RepeatMode.ALL))
    }

    @Test
    fun `a song that was advancing counts up to the moment the speaker was lost`() {
        val place = LastOwnPlace()
        place.seen(index = 3, positionMs = 40_000, advancing = true, atMs = 1_000, repeat = RepeatMode.OFF)
        place.left(atMs = 11_000)
        place.left(atMs = 99_000)

        assertThat(place.place()?.positionMs).isEqualTo(50_000)
    }

    @Test
    fun `a song that was paused does not move`() {
        val place = LastOwnPlace()
        place.seen(index = 1, positionMs = 5_000, advancing = false, atMs = 1_000, repeat = RepeatMode.OFF)
        place.left(atMs = 11_000)

        assertThat(place.place()?.positionMs).isEqualTo(5_000)
    }

    @Test
    fun `coming back to our own music starts the count again`() {
        val place = LastOwnPlace()
        place.seen(index = 1, positionMs = 5_000, advancing = true, atMs = 1_000, repeat = RepeatMode.OFF)
        place.left(atMs = 3_000)
        place.seen(index = 2, positionMs = 0, advancing = true, atMs = 20_000, repeat = RepeatMode.OFF)
        place.left(atMs = 25_000)

        assertThat(place.place()).isEqualTo(OwnPlace(2, 5_000, RepeatMode.OFF))
    }
}
