package com.theveloper.pixelplay.data.sonos.protocol

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.SonosFixtures
import org.junit.jupiter.api.Test

class SoapResponseTest {

    @Test
    fun `parse returns the values of a successful response`() {
        val body = SonosFixtures.soapResponseBody("AVTransport.GetPositionInfo.ok")

        val response = SoapResponse.parse(body)

        assertThat(response).isInstanceOf(SoapResponse.Success::class.java)
        val values = (response as SoapResponse.Success).values
        assertThat(values["Track"]).isEqualTo("1")
        assertThat(values["TrackDuration"]).isEqualTo("0:00:30")
        assertThat(values["TrackMetaData"]).startsWith("<DIDL-Lite")
    }

    @Test
    fun `parse returns the UPnP error code of a fault`() {
        val body = SonosFixtures.soapResponseBody("AVTransport.Next.err")

        assertThat(SoapResponse.parse(body)).isEqualTo(SoapResponse.Fault(711))
    }

    @Test
    fun `parse handles a fault without error code`() {
        val body = """<s:Envelope xmlns:s="x"><s:Body><s:Fault><faultstring>boom</faultstring></s:Fault></s:Body></s:Envelope>"""

        assertThat(SoapResponse.parse(body)).isEqualTo(SoapResponse.Fault(null))
    }

    @Test
    fun `parse returns an empty success for an action without output`() {
        val body = SonosFixtures.soapResponseBody("AVTransport.Play.ok")

        assertThat(SoapResponse.parse(body)).isEqualTo(SoapResponse.Success(emptyMap()))
    }

    @Test
    fun `parse returns Malformed for garbage`() {
        assertThat(SoapResponse.parse("")).isEqualTo(SoapResponse.Malformed)
        assertThat(SoapResponse.parse("<html>502</html>")).isEqualTo(SoapResponse.Malformed)
        assertThat(SoapResponse.parse("<s:Envelope xmlns:s=\"x\"><s:Body/></s:Envelope>")).isEqualTo(SoapResponse.Malformed)
    }
}
