package com.theveloper.pixelplay.data.sonos.protocol

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.SonosFixtures
import com.theveloper.pixelplay.data.sonos.model.SonosUdn
import org.junit.jupiter.api.Test

class ZoneGroupTopologyParserTest {

    private fun capturedState(): String {
        val response = SoapResponse.parse(SonosFixtures.soapResponseBody("ZoneGroupTopology.GetZoneGroupState.ok"))
        return (response as SoapResponse.Success).values.getValue("ZoneGroupState")
    }

    @Test
    fun `captured topology has one group coordinated by its only member`() {
        val groups = ZoneGroupTopologyParser.parse(capturedState())

        assertThat(groups).hasSize(1)
        val group = groups.single()
        assertThat(group.coordinator).isEqualTo(SonosUdn("RINCON_000E58000001001400"))
        val member = group.coordinatorMember()
        assertThat(member?.roomName).isEqualTo("Salón")
        assertThat(member?.host).isEqualTo("192.168.1.10")
        assertThat(member?.isVisible).isTrue()
    }

    @Test
    fun `a group lists every member and flags invisible ones`() {
        val xml = """
            <ZoneGroupState><ZoneGroups>
              <ZoneGroup Coordinator="RINCON_A">
                <ZoneGroupMember UUID="RINCON_A" Location="http://10.0.0.5:1400/xml/device_description.xml" ZoneName="Living"/>
                <ZoneGroupMember UUID="RINCON_B" Location="http://10.0.0.6:1400/xml/device_description.xml" ZoneName="Living" Invisible="1"/>
              </ZoneGroup>
              <ZoneGroup Coordinator="RINCON_C">
                <ZoneGroupMember UUID="RINCON_C" Location="http://10.0.0.7:1400/xml/device_description.xml" ZoneName="Kitchen"/>
              </ZoneGroup>
            </ZoneGroups></ZoneGroupState>
        """.trimIndent()

        val groups = ZoneGroupTopologyParser.parse(xml)

        assertThat(groups.map { it.coordinator.value }).containsExactly("RINCON_A", "RINCON_C").inOrder()
        assertThat(groups[0].members.map { it.isVisible }).containsExactly(true, false).inOrder()
        assertThat(groups[1].coordinatorMember()?.roomName).isEqualTo("Kitchen")
    }

    @Test
    fun `bonded satellites inside a member are not members of the group`() {
        val xml = """
            <ZoneGroupState><ZoneGroups><ZoneGroup Coordinator="RINCON_A">
              <ZoneGroupMember UUID="RINCON_A" Location="http://10.0.0.5:1400/xml/d.xml" ZoneName="TV">
                <Satellite UUID="RINCON_S" Location="http://10.0.0.9:1400/xml/d.xml" ZoneName="TV"/>
              </ZoneGroupMember>
            </ZoneGroup></ZoneGroups></ZoneGroupState>
        """.trimIndent()

        assertThat(ZoneGroupTopologyParser.parse(xml).single().members).hasSize(1)
    }

    @Test
    fun `members without identity or address are skipped`() {
        val xml = """
            <ZoneGroupState><ZoneGroups><ZoneGroup Coordinator="RINCON_A">
              <ZoneGroupMember UUID="RINCON_A" ZoneName="No location"/>
              <ZoneGroupMember Location="http://10.0.0.5:1400/x" ZoneName="No uuid"/>
              <ZoneGroupMember UUID="RINCON_B" Location="http://10.0.0.6:1400/x" ZoneName="Ok"/>
            </ZoneGroup></ZoneGroups></ZoneGroupState>
        """.trimIndent()

        val group = ZoneGroupTopologyParser.parse(xml).single()

        assertThat(group.members.map { it.udn.value }).containsExactly("RINCON_B")
        assertThat(group.coordinatorMember()).isNull()
    }

    @Test
    fun `malformed input yields no groups`() {
        assertThat(ZoneGroupTopologyParser.parse("")).isEmpty()
        assertThat(ZoneGroupTopologyParser.parse("garbage")).isEmpty()
        assertThat(ZoneGroupTopologyParser.parse("<ZoneGroupState/>")).isEmpty()
    }
}
