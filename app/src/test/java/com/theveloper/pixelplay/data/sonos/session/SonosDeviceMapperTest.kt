package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.model.SonosDevice
import com.theveloper.pixelplay.data.sonos.model.SonosGroup
import com.theveloper.pixelplay.data.sonos.model.SonosMember
import com.theveloper.pixelplay.data.sonos.model.SonosUdn
import org.junit.jupiter.api.Test

class SonosDeviceMapperTest {

    private fun member(udn: String, room: String, host: String = "10.0.0.1", visible: Boolean = true) =
        SonosMember(SonosUdn(udn), room, host, visible)

    @Test
    fun `a single speaker becomes one device at the coordinator host`() {
        val group = SonosGroup(SonosUdn("A"), listOf(member("A", "Salón", host = "192.168.1.10")))

        assertThat(SonosDeviceMapper.fromGroups(listOf(group))).containsExactly(
            SonosDevice(SonosUdn("A"), "Salón", "192.168.1.10", roomCount = 1),
        )
    }

    @Test
    fun `a group of rooms is one device named after its rooms in alphabetical order`() {
        val group = SonosGroup(
            SonosUdn("A"),
            listOf(member("A", "Salón", host = "10.0.0.5"), member("B", "Cocina", host = "10.0.0.6")),
        )

        val device = SonosDeviceMapper.fromGroups(listOf(group)).single()

        assertThat(device.name).isEqualTo("Cocina + Salón")
        assertThat(device.roomCount).isEqualTo(2)
        assertThat(device.host).isEqualTo("10.0.0.5")
        assertThat(device.id).isEqualTo(SonosUdn("A"))
    }

    @Test
    fun `invisible members and a room repeated by a stereo pair do not add rooms`() {
        val group = SonosGroup(
            SonosUdn("A"),
            listOf(
                member("A", "TV"),
                member("B", "TV", visible = false),
                member("C", "TV"),
            ),
        )

        val device = SonosDeviceMapper.fromGroups(listOf(group)).single()

        assertThat(device.name).isEqualTo("TV")
        assertThat(device.roomCount).isEqualTo(1)
    }

    @Test
    fun `a group whose coordinator is not listed is dropped`() {
        val group = SonosGroup(SonosUdn("GONE"), listOf(member("B", "Cocina")))

        assertThat(SonosDeviceMapper.fromGroups(listOf(group))).isEmpty()
    }

    @Test
    fun `a coordinator that is not visible still names its group`() {
        val group = SonosGroup(SonosUdn("A"), listOf(member("A", "Bath", visible = false)))

        assertThat(SonosDeviceMapper.fromGroups(listOf(group)).single().name).isEqualTo("Bath")
    }

    @Test
    fun `devices are sorted by name ignoring case and the order of the input does not matter`() {
        val groups = listOf(
            SonosGroup(SonosUdn("1"), listOf(member("1", "salon"))),
            SonosGroup(SonosUdn("2"), listOf(member("2", "Salón"))),
            SonosGroup(SonosUdn("3"), listOf(member("3", "Cocina"))),
            SonosGroup(SonosUdn("4"), listOf(member("4", "baño"))),
        )

        val forward = SonosDeviceMapper.fromGroups(groups)
        val backward = SonosDeviceMapper.fromGroups(groups.reversed())

        assertThat(forward).isEqualTo(backward)
        assertThat(forward.map { it.name }).containsExactly("baño", "Cocina", "salon", "Salón").inOrder()
    }

    @Test
    fun `the same coordinator listed twice appears once`() {
        val group = SonosGroup(SonosUdn("A"), listOf(member("A", "Salón")))

        assertThat(SonosDeviceMapper.fromGroups(listOf(group, group))).hasSize(1)
    }
}
