package com.theveloper.pixelplay.data.sonos.protocol

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.SonosFixtures
import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.TransportState
import org.junit.jupiter.api.Test

class SonosEventParserTest {

    /** Wraps a LastChange payload the way the speaker does: escaped inside a propertyset. */
    private fun notify(lastChange: String): String =
        """<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0"><e:property>""" +
            "<LastChange>${XmlText.escape(lastChange)}</LastChange></e:property></e:propertyset>"

    private fun avt(vararg variables: String): String =
        notify("""<Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/"><InstanceID val="0">${variables.joinToString("")}</InstanceID></Event>""")

    private fun rcs(vararg variables: String): String =
        notify("""<Event xmlns="urn:schemas-upnp-org:metadata-1-0/RCS/"><InstanceID val="0">${variables.joinToString("")}</InstanceID></Event>""")

    @Test
    fun `initial AVTransport event carries the full transport state`() {
        val change = SonosEventParser.parseAvTransport(SonosFixtures.notify("AVTransport_0"))

        assertThat(change?.transportState).isEqualTo(TransportState.STOPPED)
        assertThat(change?.playMode).isEqualTo(PlayMode.NORMAL)
        assertThat(change?.currentTrackNumber).isEqualTo(1)
        assertThat(change?.numberOfTracks).isEqualTo(1)
        assertThat(change?.currentTrackUri).isEqualTo("http://192.168.1.20:8765/t/testtok2/wma_192.wma")
        assertThat(change?.currentTrackDurationMs).isEqualTo(30_000L)
        assertThat(change?.currentTrack?.title).isEqualTo("WMA 192 kbps")
        assertThat(change?.transportStatus).isEqualTo("OK")
    }

    @Test
    fun `event for an empty queue reports zero tracks and empty uris`() {
        val change = SonosEventParser.parseAvTransport(SonosFixtures.notify("AVTransport_1"))

        assertThat(change?.numberOfTracks).isEqualTo(0)
        assertThat(change?.currentTrackNumber).isEqualTo(0)
        assertThat(change?.currentTrackUri).isEmpty()
        assertThat(change?.currentTrack).isNull()
    }

    @Test
    fun `event during a track change reports the next track`() {
        val change = SonosEventParser.parseAvTransport(SonosFixtures.notify("AVTransport_2"))

        assertThat(change?.transportState).isEqualTo(TransportState.TRANSITIONING)
        assertThat(change?.numberOfTracks).isEqualTo(4)
        assertThat(change?.currentTrack?.title).isEqualTo("AAC 256 kbps")
        assertThat(change?.nextTrackUri).isEqualTo("http://192.168.1.20:8765/t/testtok1/formatos/aiff_16_44.aiff")
    }

    @Test
    fun `absent variables stay null so a delta means no change`() {
        val change = SonosEventParser.parseAvTransport(avt("""<TransportState val="PLAYING"/>"""))

        assertThat(change?.transportState).isEqualTo(TransportState.PLAYING)
        assertThat(change?.playMode).isNull()
        assertThat(change?.currentTrackNumber).isNull()
        assertThat(change?.currentTrackUri).isNull()
        assertThat(change?.transportStatus).isNull()
    }

    @Test
    fun `playback error variables are read with their description`() {
        val change = SonosEventParser.parseAvTransport(
            avt(
                """<TransportStatus val="ERROR_UNSUPPORTED_FORMAT"/>""",
                """<r:TransportErrorDescription xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" val="8,0,aiff_24_48.aiff,192.168.1.20:8765,h"/>""",
            ),
        )

        assertThat(change?.transportStatus).isEqualTo("ERROR_UNSUPPORTED_FORMAT")
        assertThat(change?.transportErrorDescription).isEqualTo("8,0,aiff_24_48.aiff,192.168.1.20:8765,h")
    }

    @Test
    fun `unknown variables are ignored`() {
        val change = SonosEventParser.parseAvTransport(avt("""<Foo val="x"/>""", """<TransportState val="STOPPED"/>"""))

        assertThat(change?.transportState).isEqualTo(TransportState.STOPPED)
    }

    @Test
    fun `non numeric track numbers are ignored`() {
        val change = SonosEventParser.parseAvTransport(avt("""<CurrentTrack val="abc"/>""", """<NumberOfTracks val=""/>"""))

        assertThat(change?.currentTrackNumber).isNull()
        assertThat(change?.numberOfTracks).isNull()
    }

    @Test
    fun `rendering event reads master volume and ignores balance channels`() {
        val change = SonosEventParser.parseRendering(SonosFixtures.notify("RenderingControl_1"))

        assertThat(change?.volume).isEqualTo(6)
        assertThat(change?.muted).isFalse()
    }

    @Test
    fun `initial rendering event reads master volume even though LF and RF are 100`() {
        assertThat(SonosEventParser.parseRendering(SonosFixtures.notify("RenderingControl_0"))?.volume).isEqualTo(8)
    }

    @Test
    fun `volume outside the usual range is reported as is`() {
        val change = SonosEventParser.parseRendering(
            rcs("""<Volume channel="Master" val="30"/>""", """<Mute channel="Master" val="1"/>"""),
        )

        assertThat(change?.volume).isEqualTo(30)
        assertThat(change?.muted).isTrue()
    }

    @Test
    fun `rendering event without master channel changes nothing`() {
        val change = SonosEventParser.parseRendering(rcs("""<Volume channel="LF" val="100"/>"""))

        assertThat(change?.volume).isNull()
        assertThat(change?.muted).isNull()
    }

    @Test
    fun `malformed or unrelated bodies are null`() {
        assertThat(SonosEventParser.parseAvTransport("")).isNull()
        assertThat(SonosEventParser.parseAvTransport("<e:propertyset xmlns:e=\"x\"/>")).isNull()
        assertThat(SonosEventParser.parseAvTransport(notify("not xml"))).isNull()
        assertThat(SonosEventParser.parseRendering("<html/>")).isNull()
    }
}
