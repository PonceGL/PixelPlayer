package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.protocol.Didl
import com.theveloper.pixelplay.data.sonos.server.SonosMediaAccess
import com.theveloper.pixelplay.data.sonos.server.SonosServerIdentity
import org.junit.jupiter.api.Test

class SonosTrackMapperTest {

    private val access = SonosMediaAccess { SonosServerIdentity("0123456789abcdef0123456789abcdef", 8080) }
    private val base = "http://192.168.1.20:8080"

    private fun entry(
        songId: String = "42",
        title: String = "Song",
        artist: String? = "Artist",
        album: String? = "Album",
        durationMs: Long = 215_000,
        mimeType: String? = "audio/flac",
        hasArt: Boolean = true,
    ) = QueueEntry(songId, title, artist, album, durationMs, mimeType, hasArt)

    private fun track(entry: QueueEntry) =
        SonosTrackMapper.toTrack(entry, SonosTrackMapper.urlsFor(entry, access, base, revision = "7")!!)

    private fun roundTrip(entry: QueueEntry) = Didl.parse(Didl.build(track(entry)))

    @Test
    fun `a song becomes the track the speaker will show and play`() {
        val result = track(entry())

        assertThat(result.title).isEqualTo("Song")
        assertThat(result.artist).isEqualTo("Artist")
        assertThat(result.album).isEqualTo("Album")
        assertThat(result.durationMs).isEqualTo(215_000)
        assertThat(result.mimeType).isEqualTo("audio/flac")
        assertThat(result.resourceUrl).startsWith("$base/sonos/song/42?")
        assertThat(result.albumArtUrl).startsWith("$base/sonos/art/42?")
    }

    @Test
    fun `ids survive the trip through the url, whatever characters they have`() {
        val ids = listOf(
            "42", "a b", "canción ñ", "x&y=z", "q?r", "h#sh", "50%", "100%25", "a/b", "back\\slash",
            "😀", "content://media/external/audio/media/42", "...", "a;b", "x+y", "%2e%2e",
        )
        for (id in ids) {
            val url = SonosTrackMapper.urlsFor(entry(songId = id), access, base, "1")!!.song

            assertThat(SonosTrackMapper.songIdOf(url)).isEqualTo(id)
        }
    }

    @Test
    fun `an id that a url cannot carry has no address, so it can be skipped instead of breaking the queue`() {
        for (id in listOf(".", "..", "")) {
            assertThat(SonosTrackMapper.urlsFor(entry(songId = id), access, base, "1")).isNull()
        }
    }

    @Test
    fun `an address that is not one of our songs has no id`() {
        val others = listOf(
            "", "   ", "x-sonos-spotify:spotify%3atrack%3a123", "x-rincon-queue:RINCON_1#0", "x-rincon-stream:RINCON_1",
            "x-sonosapi-stream:s1234", "http://example.com/music.mp3", "http://192.168.1.20:8080/sonos/art/42?auth=x",
            "http://192.168.1.20:8080/sonos/song/", "http://192.168.1.20:8080/sonos/song", "http://192.168.1.20:8080/sonos/song/a/b",
            "http://192.168.1.20:8080/other/song/42", "file:///sdcard/a.mp3", "not a url at all",
        )
        for (uri in others) {
            assertThat(SonosTrackMapper.songIdOf(uri)).isNull()
        }
    }

    @Test
    fun `an id comes back from the address a speaker hands out, with its query`() {
        assertThat(SonosTrackMapper.songIdOf("http://10.0.0.5:9000/sonos/song/77?v=3&auth=abc")).isEqualTo("77")
    }

    @Test
    fun `hostile text is escaped and reads back identical`() {
        val hostile = "A & B <i>\"quote\"</i> 'single' 😀 é ]]> <!-- x --> &amp;"
        val result = roundTrip(entry(title = hostile, artist = hostile, album = hostile))!!

        assertThat(result.title).isEqualTo(hostile)
        assertThat(result.artist).isEqualTo(hostile)
        assertThat(result.album).isEqualTo(hostile)
    }

    @Test
    fun `control characters XML forbids are dropped and the document still parses`() {
        val result = roundTrip(entry(title = "Bad\u0000\u0008Title\u001F"))!!

        assertThat(result.title).isEqualTo("BadTitle")
    }

    @Test
    fun `a blank artist, album or no cover leaves those fields out`() {
        val result = track(entry(artist = "  ", album = null, hasArt = false))

        assertThat(result.artist).isNull()
        assertThat(result.album).isNull()
        assertThat(result.albumArtUrl).isNull()
        val didl = Didl.build(result)
        assertThat(didl).doesNotContain("dc:creator")
        assertThat(didl).doesNotContain("upnp:album")
        assertThat(didl).doesNotContain("albumArtURI")
    }

    @Test
    fun `a duration that is zero or negative is left out`() {
        assertThat(track(entry(durationMs = 0)).durationMs).isNull()
        assertThat(track(entry(durationMs = -5)).durationMs).isNull()
        assertThat(Didl.build(track(entry(durationMs = 0)))).doesNotContain("duration=")
    }

    @Test
    fun `an absurd duration does not break the document`() {
        val result = roundTrip(entry(durationMs = Long.MAX_VALUE))

        assertThat(result).isNotNull()
    }

    @Test
    fun `an unknown, blank or parametrized mime type becomes a clean one`() {
        assertThat(track(entry(mimeType = null)).mimeType).isEqualTo("audio/mpeg")
        assertThat(track(entry(mimeType = "  ")).mimeType).isEqualTo("audio/mpeg")
        assertThat(track(entry(mimeType = "Audio/FLAC; codecs=flac")).mimeType).isEqualTo("audio/flac")
    }

    @Test
    fun `a title with nothing in it still shows something`() {
        assertThat(track(entry(title = "   ")).title).isEqualTo("Unknown")
    }

    @Test
    fun `a very long title is cut without splitting a character`() {
        val long = track(entry(title = "x".repeat(10_000))).title
        assertThat(long.codePointCount(0, long.length)).isAtMost(256)
        assertThat(long).endsWith("…")

        val emojis = track(entry(title = "😀".repeat(300))).title
        assertThat(emojis.codePointCount(0, emojis.length)).isAtMost(256)
        assertThat(emojis.codePoints().noneMatch { it in 0xD800..0xDFFF }).isTrue()
        assertThat(roundTrip(entry(title = "😀".repeat(300)))).isNotNull()
    }

    @Test
    fun `a title exactly at the limit is not touched`() {
        val title = "y".repeat(256)

        assertThat(track(entry(title = title)).title).isEqualTo(title)
    }
}
