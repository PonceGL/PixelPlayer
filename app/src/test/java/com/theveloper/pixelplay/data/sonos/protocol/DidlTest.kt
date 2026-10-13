package com.theveloper.pixelplay.data.sonos.protocol

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.SonosFixtures
import com.theveloper.pixelplay.data.sonos.model.TrackMetadata
import org.junit.jupiter.api.Test

class DidlTest {

    private val aacTrack = TrackMetadata(
        title = "AAC 256 kbps",
        artist = "Prueba formatos",
        album = "Matriz de formatos",
        albumArtUrl = "http://192.168.1.20:8765/t/testtok1/_art/8b50d0bb846f4fae.jpg",
        durationMs = 30_000L,
        mimeType = "audio/mp4",
        resourceUrl = "http://192.168.1.20:8765/t/testtok1/formatos/aac_256_44.m4a",
    )

    @Test
    fun `build matches the DIDL stored by the speaker`() {
        val response = SoapResponse.parse(SonosFixtures.soapResponseBody("AVTransport.GetPositionInfo.ok"))
        val stored = (response as SoapResponse.Success).values.getValue("TrackMetaData")

        assertThat(Didl.build(aacTrack)).isEqualTo(stored)
    }

    @Test
    fun `build omits empty artist and album`() {
        val didl = Didl.build(aacTrack.copy(artist = "", album = null))

        assertThat(didl).doesNotContain("dc:creator")
        assertThat(didl).doesNotContain("upnp:album>")
    }

    @Test
    fun `build omits the album art element when there is none`() {
        assertThat(Didl.build(aacTrack.copy(albumArtUrl = null))).doesNotContain("albumArtURI")
    }

    @Test
    fun `build includes the duration only when known`() {
        assertThat(Didl.build(aacTrack)).contains("duration=\"0:00:30\"")
        assertThat(Didl.build(aacTrack.copy(durationMs = null))).doesNotContain("duration=")
    }

    @Test
    fun `build escapes XML special characters`() {
        val didl = Didl.build(aacTrack.copy(title = "Rock & Roll <Live> \"2024\""))

        assertThat(didl).contains("<dc:title>Rock &amp; Roll &lt;Live&gt; &quot;2024&quot;</dc:title>")
    }

    @Test
    fun `build keeps emojis and accents`() {
        assertThat(Didl.build(aacTrack.copy(title = "Canción 🎵 ñ"))).contains("<dc:title>Canción 🎵 ñ</dc:title>")
    }

    @Test
    fun `build removes control characters XML forbids`() {
        assertThat(Didl.build(aacTrack.copy(title = "a\u0001b\u001F"))).contains("<dc:title>ab</dc:title>")
    }

    @Test
    fun `parse reads back what build wrote`() {
        val tricky = aacTrack.copy(title = "Rock & Roll <Live> \"2024\" 🎵", artist = "Ñandú & Co")

        assertThat(Didl.parse(Didl.build(tricky))).isEqualTo(tricky)
    }

    @Test
    fun `parse reads metadata the speaker reports for a track`() {
        val response = SoapResponse.parse(SonosFixtures.soapResponseBody("AVTransport.GetPositionInfo.ok"))
        val stored = (response as SoapResponse.Success).values.getValue("TrackMetaData")

        assertThat(Didl.parse(stored)).isEqualTo(aacTrack)
    }

    @Test
    fun `parse returns null for empty or non DIDL input`() {
        assertThat(Didl.parse(null)).isNull()
        assertThat(Didl.parse("")).isNull()
        assertThat(Didl.parse("<foo/>")).isNull()
        assertThat(Didl.parse("not xml")).isNull()
    }

    @Test
    fun `parse returns null when the item has no resource`() {
        val didl = "<DIDL-Lite><item><dc:title>x</dc:title></item></DIDL-Lite>"

        assertThat(Didl.parse(didl)).isNull()
    }
}
