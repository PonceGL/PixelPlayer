package com.theveloper.pixelplay.data.sonos.protocol

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.SonosFixtures
import com.theveloper.pixelplay.data.sonos.model.SonosUdn
import org.junit.jupiter.api.Test

class SsdpTest {

    private val sourceHost = "192.168.1.10"

    @Test
    fun `search request is a ZonePlayer M-SEARCH with CRLF line endings`() {
        assertThat(Ssdp.searchRequest()).isEqualTo(
            "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n",
        )
    }

    @Test
    fun `search request carries the requested maximum wait`() {
        assertThat(Ssdp.searchRequest(maxWaitSeconds = 1)).contains("MX: 1\r\n")
    }

    @Test
    fun `captured reply yields host, udn and household`() {
        val response = Ssdp.parseResponse(SonosFixtures.ssdpResponse(), sourceHost)

        assertThat(response).isEqualTo(
            SsdpResponse(
                host = "192.168.1.10",
                udn = SonosUdn("RINCON_000E58000001001400"),
                household = "Sonos_TestHousehold0000000000000",
                serverBanner = "Linux UPnP/1.0 Sonos/93.1-73290 (ZPS44)",
            ),
        )
    }

    @Test
    fun `header names are matched regardless of case`() {
        val raw = SonosFixtures.ssdpResponse().lines().joinToString("\r\n") { line ->
            val name = line.substringBefore(':', missingDelimiterValue = "")
            if (name.isEmpty()) line else name.lowercase() + line.substring(name.length)
        }

        assertThat(Ssdp.parseResponse(raw, sourceHost)?.udn).isEqualTo(SonosUdn("RINCON_000E58000001001400"))
    }

    @Test
    fun `the host comes from the packet source and not from the LOCATION header`() {
        val response = Ssdp.parseResponse(SonosFixtures.ssdpResponse(), "10.9.9.9")

        assertThat(response?.host).isEqualTo("10.9.9.9")
    }

    @Test
    fun `a reply without household still identifies the speaker`() {
        val raw = SonosFixtures.ssdpResponse().lines().filterNot { it.startsWith("X-RINCON-HOUSEHOLD") }
            .joinToString("\r\n")

        val response = Ssdp.parseResponse(raw, sourceHost)

        assertThat(response?.udn).isEqualTo(SonosUdn("RINCON_000E58000001001400"))
        assertThat(response?.household).isNull()
    }

    @Test
    fun `replies for other device types are ignored`() {
        val raw = SonosFixtures.ssdpResponse().replace(
            "ST: urn:schemas-upnp-org:device:ZonePlayer:1",
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1",
        )

        assertThat(Ssdp.parseResponse(raw, sourceHost)).isNull()
    }

    @Test
    fun `replies that are not 200 OK are ignored`() {
        val raw = SonosFixtures.ssdpResponse().replace("HTTP/1.1 200 OK", "HTTP/1.1 500 Internal Server Error")

        assertThat(Ssdp.parseResponse(raw, sourceHost)).isNull()
    }

    @Test
    fun `a USN that is not a Sonos player is ignored`() {
        val raw = SonosFixtures.ssdpResponse().replace("uuid:RINCON_000E58000001001400", "uuid:1234-abcd")

        assertThat(Ssdp.parseResponse(raw, sourceHost)).isNull()
    }

    @Test
    fun `an advertisement sent by the speaker is not a search reply`() {
        val raw = SonosFixtures.ssdpResponse().replace("HTTP/1.1 200 OK", "NOTIFY * HTTP/1.1")

        assertThat(Ssdp.parseResponse(raw, sourceHost)).isNull()
    }

    @Test
    fun `empty, truncated, binary and oversized input never throws`() {
        val fixture = SonosFixtures.ssdpResponse()

        assertThat(Ssdp.parseResponse("", sourceHost)).isNull()
        assertThat(Ssdp.parseResponse(fixture.take(20), sourceHost)).isNull()
        assertThat(Ssdp.parseResponse("\u0000\u0001￿\r\n\r\n", sourceHost)).isNull()
        assertThat(Ssdp.parseResponse(fixture + "X-JUNK: " + "a".repeat(100_000), sourceHost)).isNull()
    }
}
