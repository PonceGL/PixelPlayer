package com.theveloper.pixelplay.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.Song
import org.junit.Test

private fun song(id: String, title: String, albumId: Long) = Song(
    id = id,
    title = title,
    artist = "Artist",
    artistId = 1L,
    album = "Album $albumId",
    albumId = albumId,
    path = "",
    contentUriString = "",
    albumArtUriString = null,
    duration = 1000L,
    mimeType = null,
    bitrate = null,
    sampleRate = null
)

private fun section(albumId: Long, year: Int?, songs: List<Song>) = ArtistAlbumSection(
    albumId = albumId,
    title = "Album $albumId",
    year = year,
    albumArtUriString = null,
    songs = songs
)

class ArtistDetailUiStateTest {

    @Test
    fun songsInAlbumOrder_concatenatesSectionsInSectionOrder() {
        val albumOneSongs = listOf(song("1", "A1S1", 1L), song("2", "A1S2", 1L))
        val albumTwoSongs = listOf(song("3", "A2S1", 2L), song("4", "A2S2", 2L))
        val state = ArtistDetailUiState(
            albumSections = listOf(
                section(albumId = 2L, year = 2020, songs = albumTwoSongs),
                section(albumId = 1L, year = 2010, songs = albumOneSongs)
            )
        )

        assertThat(state.songsInAlbumOrder.map { it.id })
            .containsExactly("3", "4", "1", "2")
            .inOrder()
    }

    @Test
    fun songsInAlbumOrder_preservesWithinAlbumOrderFromEachSection() {
        val albumSongsInDisplayOrder = listOf(
            song("10", "Track 1", 5L),
            song("11", "Track 2", 5L),
            song("12", "Track 3", 5L)
        )
        val state = ArtistDetailUiState(
            albumSections = listOf(section(albumId = 5L, year = 2015, songs = albumSongsInDisplayOrder))
        )

        assertThat(state.songsInAlbumOrder).isEqualTo(albumSongsInDisplayOrder)
    }

    @Test
    fun songsInAlbumOrder_emptyAlbumSections_returnsEmptyList() {
        val state = ArtistDetailUiState()

        assertThat(state.songsInAlbumOrder).isEmpty()
    }

    @Test
    fun songsInAlbumOrder_singleAlbum_returnsJustThatAlbumsSongs() {
        val songs = listOf(song("1", "Only Song", 1L))
        val state = ArtistDetailUiState(
            albumSections = listOf(section(albumId = 1L, year = null, songs = songs))
        )

        assertThat(state.songsInAlbumOrder).isEqualTo(songs)
    }
}
