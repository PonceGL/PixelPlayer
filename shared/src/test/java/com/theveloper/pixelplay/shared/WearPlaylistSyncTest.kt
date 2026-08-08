package com.theveloper.pixelplay.shared

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class WearPlaylistSyncTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `round-trips through JSON preserving song order`() {
        val original = WearPlaylistSync(
            playlistId = "playlist-1",
            name = "Running mix",
            songIds = listOf("3", "1", "2"),
        )

        val decoded = json.decodeFromString<WearPlaylistSync>(json.encodeToString(original))

        assertThat(decoded).isEqualTo(original)
        assertThat(decoded.songIds).containsExactly("3", "1", "2").inOrder()
    }

    @Test
    fun `decodes an empty song list`() {
        val original = WearPlaylistSync(playlistId = "playlist-1", name = "Empty", songIds = emptyList())

        val decoded = json.decodeFromString<WearPlaylistSync>(json.encodeToString(original))

        assertThat(decoded.songIds).isEmpty()
    }

    @Test
    fun `ignores unknown fields from a newer sender`() {
        // The receiving side (watch) may run an older app version than the phone that sent this
        // payload — unknown fields must not break decoding, only newly-added optional ones should.
        val payloadWithExtraField =
            """{"playlistId":"playlist-1","name":"Running mix","songIds":["1"],"futureField":true}"""

        val decoded = json.decodeFromString<WearPlaylistSync>(payloadWithExtraField)

        assertThat(decoded).isEqualTo(
            WearPlaylistSync(playlistId = "playlist-1", name = "Running mix", songIds = listOf("1")),
        )
    }
}
