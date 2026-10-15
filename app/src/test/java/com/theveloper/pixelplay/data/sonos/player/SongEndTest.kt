package com.theveloper.pixelplay.data.sonos.player

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SongEndTest {

    @Test
    fun `a local player reports the end of a song as an automatic transition`() {
        assertThat(endedByItself(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, speakerSession = false)).isTrue()
    }

    @Test
    fun `a speaker that moves on by itself changes the list and that counts as the end of the song`() {
        assertThat(endedByItself(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, speakerSession = true)).isTrue()
    }

    @Test
    fun `a change of list on the phone is not the end of a song`() {
        assertThat(endedByItself(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, speakerSession = false)).isFalse()
    }

    @Test
    fun `a seek or a repeat is not the end of a song anywhere`() {
        for (speaker in listOf(false, true)) {
            assertThat(endedByItself(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, speaker)).isFalse()
            assertThat(endedByItself(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT, speaker)).isFalse()
        }
    }
}
