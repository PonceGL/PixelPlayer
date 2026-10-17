package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HandBackTargetTest {

    private fun ids(text: String) = text.map { it.toString() }

    @Test
    fun `the song the player showed is the place, when it is that song`() {
        assertThat(HandBackTarget.of(ids("abca"), songId = "a", shownIndex = 3)).isEqualTo(3)
    }

    @Test
    fun `a shown place with another song is not trusted, the first copy of the song is used`() {
        assertThat(HandBackTarget.of(ids("abca"), songId = "a", shownIndex = 1)).isEqualTo(0)
    }

    @Test
    fun `without a shown place the song is searched for`() {
        assertThat(HandBackTarget.of(ids("abc"), songId = "c", shownIndex = null)).isEqualTo(2)
    }

    @Test
    fun `a song that is not in the queue, or none, has no place`() {
        assertThat(HandBackTarget.of(ids("abc"), songId = "z", shownIndex = 0)).isNull()
        assertThat(HandBackTarget.of(ids("abc"), songId = null, shownIndex = 0)).isNull()
        assertThat(HandBackTarget.of(emptyList(), songId = "a", shownIndex = 0)).isNull()
    }

    @Test
    fun `a shown place outside the queue is ignored`() {
        assertThat(HandBackTarget.of(ids("abc"), songId = "b", shownIndex = 9)).isEqualTo(1)
        assertThat(HandBackTarget.of(ids("abc"), songId = "b", shownIndex = -1)).isEqualTo(1)
    }
}
