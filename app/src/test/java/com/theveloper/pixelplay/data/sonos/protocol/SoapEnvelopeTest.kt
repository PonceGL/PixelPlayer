package com.theveloper.pixelplay.data.sonos.protocol

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.SonosFixtures
import org.junit.jupiter.api.Test

class SoapEnvelopeTest {

    @Test
    fun `build produces the exact envelope the speaker accepted`() {
        val expected = SonosFixtures.soapRequestBody("AVTransport.GetPositionInfo.ok")

        val actual = SoapEnvelope.build(SonosService.AV_TRANSPORT, "GetPositionInfo", listOf("InstanceID" to "0"))

        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `build keeps the order of arguments`() {
        val xml = SoapEnvelope.build(
            SonosService.AV_TRANSPORT,
            "Seek",
            listOf("InstanceID" to "0", "Unit" to "TRACK_NR", "Target" to "3"),
        )

        assertThat(xml).contains("<InstanceID>0</InstanceID><Unit>TRACK_NR</Unit><Target>3</Target>")
    }

    @Test
    fun `build escapes argument values`() {
        val xml = SoapEnvelope.build(SonosService.AV_TRANSPORT, "SetAVTransportURI", listOf("CurrentURIMetaData" to """a<b&"c""""))

        assertThat(xml).contains("<CurrentURIMetaData>a&lt;b&amp;&quot;c&quot;</CurrentURIMetaData>")
    }

    @Test
    fun `soap action header names the service type and the action`() {
        assertThat(SoapEnvelope.soapActionHeader(SonosService.RENDERING_CONTROL, "SetVolume"))
            .isEqualTo("\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\"")
    }

    @Test
    fun `control and event paths match what the speaker serves`() {
        assertThat(SonosService.AV_TRANSPORT.controlPath).isEqualTo("/MediaRenderer/AVTransport/Control")
        assertThat(SonosService.AV_TRANSPORT.eventPath).isEqualTo("/MediaRenderer/AVTransport/Event")
        assertThat(SonosService.RENDERING_CONTROL.eventPath).isEqualTo("/MediaRenderer/RenderingControl/Event")
        assertThat(SonosService.ZONE_GROUP_TOPOLOGY.controlPath).isEqualTo("/ZoneGroupTopology/Control")
        assertThat(SonosService.ZONE_GROUP_TOPOLOGY.eventPath).isEqualTo("/ZoneGroupTopology/Event")
    }
}
