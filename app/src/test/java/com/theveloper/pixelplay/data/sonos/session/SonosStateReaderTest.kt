package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.SonosFixtures
import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.TransportState
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.SonosTransport
import com.theveloper.pixelplay.data.sonos.protocol.SoapResponse
import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SonosStateReaderTest {

    private class FakeTransport(val answers: MutableMap<String, SonosResult<Map<String, String>>>) : SonosTransport {
        val calls = CopyOnWriteArrayList<Triple<String, SonosService, List<Pair<String, String>>>>()

        override suspend fun call(
            host: String,
            service: SonosService,
            action: String,
            arguments: List<Pair<String, String>>,
            timeoutMs: Long,
        ): SonosResult<Map<String, String>> {
            calls += Triple(host, service, arguments)
            actions += action
            return answers.getValue(action)
        }

        val actions = CopyOnWriteArrayList<String>()
    }

    private fun fixture(name: String): SonosResult<Map<String, String>> =
        SonosResult.Ok((SoapResponse.parse(SonosFixtures.soapResponseBody("AVTransport.$name.ok")) as SoapResponse.Success).values)

    private fun answers(
        transportInfo: SonosResult<Map<String, String>> = fixture("GetTransportInfo"),
        positionInfo: SonosResult<Map<String, String>> = fixture("GetPositionInfo"),
        settings: SonosResult<Map<String, String>> = fixture("GetTransportSettings"),
        mediaInfo: SonosResult<Map<String, String>> = fixture("GetMediaInfo"),
    ) = mutableMapOf("GetTransportInfo" to transportInfo, "GetPositionInfo" to positionInfo, "GetTransportSettings" to settings, "GetMediaInfo" to mediaInfo)

    private fun ok(vararg values: Pair<String, String>) = SonosResult.Ok(mapOf(*values))

    private fun reader(transport: FakeTransport) = SonosStateReader(transport, "192.168.1.50")

    @Test
    fun `the four answers of a real speaker become one state`() = runTest {
        val transport = FakeTransport(answers())

        val result = reader(transport).read() as SonosResult.Ok

        val state = result.value.playback
        assertThat(state.transportState).isEqualTo(TransportState.TRANSITIONING)
        assertThat(state.playMode).isEqualTo(PlayMode.NORMAL)
        assertThat(state.currentTrackNumber).isEqualTo(1)
        assertThat(state.numberOfTracks).isEqualTo(4)
        assertThat(state.currentTrackUri).isEqualTo("http://192.168.1.20:8765/t/testtok1/formatos/aac_256_44.m4a")
        assertThat(state.currentTrackDurationMs).isEqualTo(30_000)
        assertThat(state.currentTrack?.title).isEqualTo("AAC 256 kbps")
        assertThat(state.nextTrackUri).isNull()
        assertThat(result.value.positionMs).isEqualTo(0)
    }

    @Test
    fun `it asks the coordinator for the four things it needs and nothing else`() = runTest {
        val transport = FakeTransport(answers())

        reader(transport).read()

        assertThat(transport.actions).containsExactly("GetTransportInfo", "GetPositionInfo", "GetTransportSettings", "GetMediaInfo")
        assertThat(transport.calls.all { it.first == "192.168.1.50" && it.second == SonosService.AV_TRANSPORT }).isTrue()
        assertThat(transport.calls.all { it.third == listOf("InstanceID" to "0") }).isTrue()
    }

    @Test
    fun `the position and the song of a playing speaker are read as they are`() = runTest {
        val transport = FakeTransport(answers(
            transportInfo = ok("CurrentTransportState" to "PLAYING", "CurrentTransportStatus" to "OK"),
            positionInfo = ok("Track" to "3", "TrackDuration" to "0:03:25", "TrackURI" to "http://x/sonos/song/9?auth=t", "RelTime" to "0:01:05", "TrackMetaData" to ""),
            settings = ok("PlayMode" to "SHUFFLE"),
            mediaInfo = ok("NrTracks" to "12", "NextURI" to "http://x/sonos/song/10?auth=t"),
        ))

        val result = reader(transport).read() as SonosResult.Ok

        assertThat(result.value.playback.transportState).isEqualTo(TransportState.PLAYING)
        assertThat(result.value.playback.playMode).isEqualTo(PlayMode.SHUFFLE)
        assertThat(result.value.playback.currentTrackNumber).isEqualTo(3)
        assertThat(result.value.playback.currentTrackDurationMs).isEqualTo(205_000)
        assertThat(result.value.playback.nextTrackUri).isEqualTo("http://x/sonos/song/10?auth=t")
        assertThat(result.value.positionMs).isEqualTo(65_000)
    }

    @Test
    fun `an empty speaker is a state with nothing in it and not a failure`() = runTest {
        val transport = FakeTransport(answers(
            transportInfo = ok("CurrentTransportState" to "NO_MEDIA_PRESENT"),
            positionInfo = ok("Track" to "0", "TrackDuration" to "", "TrackURI" to "", "RelTime" to "NOT_IMPLEMENTED", "TrackMetaData" to ""),
            settings = ok("PlayMode" to "NORMAL"),
            mediaInfo = ok("NrTracks" to "0", "NextURI" to ""),
        ))

        val result = reader(transport).read() as SonosResult.Ok

        assertThat(result.value.playback.transportState).isEqualTo(TransportState.NO_MEDIA_PRESENT)
        assertThat(result.value.playback.currentTrackUri).isNull()
        assertThat(result.value.playback.currentTrack).isNull()
        assertThat(result.value.playback.currentTrackDurationMs).isNull()
        assertThat(result.value.playback.numberOfTracks).isEqualTo(0)
        assertThat(result.value.positionMs).isNull()
    }

    @Test
    fun `values the speaker garbles are left unknown instead of breaking the read`() = runTest {
        val transport = FakeTransport(answers(
            transportInfo = ok("CurrentTransportState" to "SOMETHING_NEW"),
            positionInfo = ok("Track" to "abc", "TrackDuration" to "99:99:99", "TrackURI" to "u", "RelTime" to "-1:00:00", "TrackMetaData" to "<<<"),
            settings = ok("PlayMode" to "WEIRD"),
            mediaInfo = ok("NrTracks" to "many"),
        ))

        val result = reader(transport).read() as SonosResult.Ok

        val state = result.value.playback
        assertThat(state.transportState).isEqualTo(TransportState.UNKNOWN)
        assertThat(state.playMode).isNull()
        assertThat(state.currentTrackNumber).isNull()
        assertThat(state.numberOfTracks).isNull()
        assertThat(state.currentTrackDurationMs).isNull()
        assertThat(state.currentTrack).isNull()
        assertThat(result.value.positionMs).isNull()
    }

    @Test
    fun `an answer without the values of the action is a malformed response`() = runTest {
        val transport = FakeTransport(answers(positionInfo = ok()))

        val result = reader(transport).read()

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.MalformedResponse))
    }

    @Test
    fun `any failing call fails the read with its own failure`() = runTest {
        for (failing in listOf("GetTransportInfo", "GetPositionInfo", "GetTransportSettings", "GetMediaInfo")) {
            val broken = answers().also { it[failing] = SonosResult.Err(SonosFailure.Timeout) }

            val result = reader(FakeTransport(broken)).read()

            assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.Timeout))
        }
    }

    @Test
    fun `metadata the speaker leaves behind without a song is not a song`() = runTest {
        val staleMetadata = (fixture("GetPositionInfo") as SonosResult.Ok).value + ("TrackURI" to "")
        val transport = FakeTransport(answers(positionInfo = SonosResult.Ok(staleMetadata)))

        val result = reader(transport).read() as SonosResult.Ok

        assertThat(result.value.playback.currentTrackUri).isNull()
        assertThat(result.value.playback.currentTrack).isNull()
    }
}
