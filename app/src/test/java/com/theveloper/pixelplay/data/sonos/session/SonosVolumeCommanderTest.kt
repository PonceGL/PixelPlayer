package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.SonosTransport
import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SonosVolumeCommanderTest {

    private class Call(val service: SonosService, val action: String, val arguments: List<Pair<String, String>>) {
        fun arg(name: String) = arguments.single { it.first == name }.second
        fun has(name: String) = arguments.any { it.first == name }
    }

    private class FakeTransport : SonosTransport {
        val calls = mutableListOf<Call>()
        var script: (Call) -> SonosResult<Map<String, String>> = { SonosResult.Ok(emptyMap()) }

        override suspend fun call(
            host: String,
            service: SonosService,
            action: String,
            arguments: List<Pair<String, String>>,
            timeoutMs: Long,
        ): SonosResult<Map<String, String>> {
            val call = Call(service, action, arguments)
            calls += call
            return script(call)
        }
    }

    private fun single(transport: FakeTransport) = SonosVolumeCommander(transport, "192.168.1.50", group = false)
    private fun group(transport: FakeTransport) = SonosVolumeCommander(transport, "192.168.1.50", group = true)

    @Test
    fun `a single speaker is set through its own volume, the master channel, as a whole number`() = runTest {
        val transport = FakeTransport()

        assertThat(single(transport).setVolume(37)).isEqualTo(SonosResult.Ok(37))

        val call = transport.calls.single()
        assertThat(call.service).isEqualTo(SonosService.RENDERING_CONTROL)
        assertThat(call.action).isEqualTo("SetVolume")
        assertThat(call.arg("InstanceID")).isEqualTo("0")
        assertThat(call.arg("Channel")).isEqualTo("Master")
        assertThat(call.arg("DesiredVolume")).isEqualTo("37")
    }

    @Test
    fun `a level outside zero to a hundred is brought inside before it is sent`() = runTest {
        val transport = FakeTransport()
        val commander = single(transport)

        assertThat(commander.setVolume(-5)).isEqualTo(SonosResult.Ok(0))
        assertThat(commander.setVolume(250)).isEqualTo(SonosResult.Ok(100))

        assertThat(transport.calls.map { it.arg("DesiredVolume") }).containsExactly("0", "100").inOrder()
    }

    @Test
    fun `a relative change gives the new level the speaker reports`() = runTest {
        val transport = FakeTransport()
        transport.script = { SonosResult.Ok(mapOf("NewVolume" to "42")) }

        assertThat(single(transport).adjust(5)).isEqualTo(SonosResult.Ok(42))

        val call = transport.calls.single()
        assertThat(call.action).isEqualTo("SetRelativeVolume")
        assertThat(call.arg("Channel")).isEqualTo("Master")
        assertThat(call.arg("Adjustment")).isEqualTo("5")
    }

    @Test
    fun `a relative change with no new level in the answer is not understood`() = runTest {
        val transport = FakeTransport()
        transport.script = { SonosResult.Ok(emptyMap()) }

        assertThat(single(transport).adjust(-5)).isEqualTo(SonosResult.Err(SonosFailure.MalformedResponse))
    }

    @Test
    fun `muting is sent as one or zero`() = runTest {
        val transport = FakeTransport()
        val commander = single(transport)

        commander.setMuted(true)
        commander.setMuted(false)

        assertThat(transport.calls.map { it.action }).containsExactly("SetMute", "SetMute")
        assertThat(transport.calls.map { it.arg("DesiredMute") }).containsExactly("1", "0").inOrder()
        assertThat(transport.calls.first().arg("Channel")).isEqualTo("Master")
    }

    @Test
    fun `reading asks for the volume and the mute and gives both`() = runTest {
        val transport = FakeTransport()
        transport.script = { call ->
            when (call.action) {
                "GetVolume" -> SonosResult.Ok(mapOf("CurrentVolume" to "12"))
                "GetMute" -> SonosResult.Ok(mapOf("CurrentMute" to "1"))
                else -> SonosResult.Ok(emptyMap())
            }
        }

        assertThat(single(transport).read()).isEqualTo(SonosResult.Ok(SonosVolume(level = 12, muted = true)))
    }

    @Test
    fun `a reading that cannot be understood is not a volume`() = runTest {
        val transport = FakeTransport()
        transport.script = { SonosResult.Ok(mapOf("CurrentVolume" to "loud", "CurrentMute" to "0")) }

        assertThat(single(transport).read()).isEqualTo(SonosResult.Err(SonosFailure.MalformedResponse))
    }

    @Test
    fun `a failure of the speaker is passed on`() = runTest {
        val transport = FakeTransport()
        transport.script = { SonosResult.Err(SonosFailure.Timeout) }

        assertThat(single(transport).setVolume(10)).isEqualTo(SonosResult.Err(SonosFailure.Timeout))
        assertThat(single(transport).read()).isEqualTo(SonosResult.Err(SonosFailure.Timeout))
    }

    @Test
    fun `a group is set through the group volume, with no channel`() = runTest {
        val transport = FakeTransport()
        val commander = group(transport)

        commander.setVolume(20)
        commander.setMuted(true)

        val (volume, mute) = transport.calls
        assertThat(volume.service).isEqualTo(SonosService.GROUP_RENDERING_CONTROL)
        assertThat(volume.action).isEqualTo("SetGroupVolume")
        assertThat(volume.has("Channel")).isFalse()
        assertThat(volume.arg("DesiredVolume")).isEqualTo("20")
        assertThat(mute.action).isEqualTo("SetGroupMute")
        assertThat(mute.arg("DesiredMute")).isEqualTo("1")
    }

    @Test
    fun `a relative change of a group takes a snapshot of the group first`() = runTest {
        val transport = FakeTransport()
        transport.script = { call -> if (call.action == "SetRelativeGroupVolume") SonosResult.Ok(mapOf("NewVolume" to "30")) else SonosResult.Ok(emptyMap()) }

        assertThat(group(transport).adjust(-5)).isEqualTo(SonosResult.Ok(30))

        assertThat(transport.calls.map { it.action }).containsExactly("SnapshotGroupVolume", "SetRelativeGroupVolume").inOrder()
        assertThat(transport.calls.last().arg("Adjustment")).isEqualTo("-5")
    }

    @Test
    fun `reading a group asks the group`() = runTest {
        val transport = FakeTransport()
        transport.script = { call ->
            when (call.action) {
                "GetGroupVolume" -> SonosResult.Ok(mapOf("CurrentVolume" to "8"))
                "GetGroupMute" -> SonosResult.Ok(mapOf("CurrentMute" to "0"))
                else -> SonosResult.Ok(emptyMap())
            }
        }

        assertThat(group(transport).read()).isEqualTo(SonosResult.Ok(SonosVolume(level = 8, muted = false)))
        assertThat(transport.calls.map { it.service }.toSet()).containsExactly(SonosService.GROUP_RENDERING_CONTROL)
    }
}
