package com.theveloper.pixelplay.data.sonos.server

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SameSubnetTest {

    @Test
    fun `two addresses of the same network are on the same subnet`() {
        assertThat(sameIpv4Subnet("http://192.168.1.20:8080", "192.168.1.50", 24)).isTrue()
        assertThat(sameIpv4Subnet("192.168.1.20", "192.168.1.254", 24)).isTrue()
    }

    @Test
    fun `addresses of different networks are not`() {
        assertThat(sameIpv4Subnet("http://192.168.2.20:8080", "192.168.1.50", 24)).isFalse()
        assertThat(sameIpv4Subnet("10.0.0.5", "192.168.1.50", 24)).isFalse()
    }

    @Test
    fun `the prefix decides how much has to match`() {
        assertThat(sameIpv4Subnet("192.168.1.20", "192.168.2.50", 16)).isTrue()
        assertThat(sameIpv4Subnet("192.168.1.20", "192.168.2.50", 24)).isFalse()
        assertThat(sameIpv4Subnet("192.168.1.20", "10.9.9.9", 0)).isTrue()
        assertThat(sameIpv4Subnet("192.168.1.20", "192.168.1.21", 32)).isFalse()
        assertThat(sameIpv4Subnet("192.168.1.20", "192.168.1.20", 32)).isTrue()
    }

    @Test
    fun `anything that is not an address or a prefix never matches`() {
        assertThat(sameIpv4Subnet(null, "192.168.1.50", 24)).isFalse()
        assertThat(sameIpv4Subnet("192.168.1.20", null, 24)).isFalse()
        assertThat(sameIpv4Subnet("", "192.168.1.50", 24)).isFalse()
        assertThat(sameIpv4Subnet("not an address", "192.168.1.50", 24)).isFalse()
        assertThat(sameIpv4Subnet("192.168.1.256", "192.168.1.50", 24)).isFalse()
        assertThat(sameIpv4Subnet("192.168.1", "192.168.1.50", 24)).isFalse()
        assertThat(sameIpv4Subnet("192.168.1.20", "192.168.1.50", -1)).isFalse()
        assertThat(sameIpv4Subnet("192.168.1.20", "192.168.1.50", 33)).isFalse()
        assertThat(sameIpv4Subnet("fe80::1", "192.168.1.50", 24)).isFalse()
    }
}
