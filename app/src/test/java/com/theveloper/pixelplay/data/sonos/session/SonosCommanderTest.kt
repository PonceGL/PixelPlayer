package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.SonosUdn
import com.theveloper.pixelplay.data.sonos.model.TrackMetadata
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.SonosTransport
import com.theveloper.pixelplay.data.sonos.protocol.Didl
import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.jupiter.api.Test

class SonosCommanderTest {

    private val host = "192.168.1.50"
    private val udn = SonosUdn("RINCON_000E58000001001400")

    private class Call(val service: SonosService, val host: String, val action: String, val arguments: List<Pair<String, String>>) {
        fun arg(name: String) = arguments.single { it.first == name }.second
    }

    private class FakeTransport : SonosTransport {
        val calls = CopyOnWriteArrayList<Call>()
        var script: (Call) -> SonosResult<Map<String, String>> = { SonosResult.Ok(emptyMap()) }
        var gate: suspend (Call, Int) -> Unit = { _, _ -> }

        override suspend fun call(
            host: String,
            service: SonosService,
            action: String,
            arguments: List<Pair<String, String>>,
            timeoutMs: Long,
        ): SonosResult<Map<String, String>> {
            val call = Call(service, host, action, arguments)
            calls += call
            gate(call, calls.size)
            return script(call)
        }

        val actions get() = calls.map { it.action }
    }

    private fun track(n: Int) = TrackMetadata(
        title = "Song $n", artist = "Artist", album = "Album", albumArtUrl = null, durationMs = 200_000,
        mimeType = "audio/flac", resourceUrl = "http://192.168.1.20:8080/sonos/song/$n?auth=t",
    )

    private fun tracks(count: Int) = List(count) { track(it) }

    private class Fixture(val scope: TestScope, val host: String, val udn: SonosUdn) {
        val transport = FakeTransport()
        val recorded = mutableListOf<Long>()
        val commander = SonosCommander(transport, host, udn, now = { scope.currentTime }, recordCommand = { recorded += it })
    }

    private fun test(block: suspend TestScope.(Fixture) -> Unit) = runTest { block(Fixture(this, host, udn)) }

    private fun upnp(action: String, kind: UpnpErrorKind) = SonosResult.Err(SonosFailure.Upnp(action, kind))

    private suspend fun Fixture.load(
        tracks: List<TrackMetadata>,
        startIndex: Int = 0,
        positionMs: Long = 0,
        mode: PlayMode = PlayMode.NORMAL,
        play: Boolean = true,
        onStarted: (LoadResult) -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ) = commander.loadAndPlay(tracks, startIndex, positionMs, mode, play, onStarted, onProgress)

    @Test
    fun `loading queues up to the current song, selects it, sets the mode, seeks, plays, then queues the rest`() = test { f ->
        val songs = tracks(3)
        val started = mutableListOf<Int>()

        val result = f.load(songs, startIndex = 1, positionMs = 95_000, mode = PlayMode.REPEAT_ALL, onStarted = { started += f.transport.calls.size })

        assertThat(result).isEqualTo(SonosResult.Ok(LoadResult(0..2, positionApplied = true)))
        assertThat(f.transport.actions).containsExactly(
            "RemoveAllTracksFromQueue", "AddURIToQueue", "AddURIToQueue", "SetAVTransportURI", "Seek", "SetPlayMode", "Seek", "Play",
            "AddURIToQueue",
        ).inOrder()
        assertThat(started).containsExactly(8) // everything up to Play was done, the rest not yet queued
        assertThat(f.transport.calls.all { it.host == host && it.service == SonosService.AV_TRANSPORT }).isTrue()
        val calls = f.transport.calls
        assertThat(calls[0].arguments).containsExactly("InstanceID" to "0")
        for ((i, n) in listOf(1 to 0, 2 to 1, 8 to 2)) {
            assertThat(calls[i].arguments).containsExactly(
                "InstanceID" to "0",
                "EnqueuedURI" to songs[n].resourceUrl,
                "EnqueuedURIMetaData" to Didl.build(songs[n]),
                "DesiredFirstTrackNumberEnqueued" to "0",
                "EnqueueAsNext" to "0",
            ).inOrder()
        }
        assertThat(calls[3].arguments).containsExactly(
            "InstanceID" to "0", "CurrentURI" to "x-rincon-queue:RINCON_000E58000001001400#0", "CurrentURIMetaData" to "",
        ).inOrder()
        assertThat(calls[4].arguments).containsExactly("InstanceID" to "0", "Unit" to "TRACK_NR", "Target" to "2").inOrder()
        assertThat(calls[5].arguments).containsExactly("InstanceID" to "0", "NewPlayMode" to "REPEAT_ALL").inOrder()
        assertThat(calls[6].arguments).containsExactly("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to "0:01:35").inOrder()
        assertThat(calls[7].arguments).containsExactly("InstanceID" to "0", "Speed" to "1").inOrder()
    }

    @Test
    fun `starting at the first song queues one before playing, at the last song queues all and has no rest`() = test { f ->
        f.load(tracks(3), startIndex = 0)
        assertThat(f.transport.actions.indexOf("Play")).isEqualTo(f.transport.actions.indexOf("SetAVTransportURI") + 3)
        assertThat(f.transport.actions.count { it == "AddURIToQueue" }).isEqualTo(3)
        assertThat(f.transport.actions.take(3)).containsExactly("RemoveAllTracksFromQueue", "AddURIToQueue", "SetAVTransportURI").inOrder()

        val g = Fixture(this, host, udn)
        g.load(tracks(3), startIndex = 2)
        assertThat(g.transport.actions.takeLast(1)).containsExactly("Play")
        assertThat(g.transport.calls.first { it.action == "Seek" }.arg("Target")).isEqualTo("3")
    }

    @Test
    fun `paused start does not play, and position zero does not seek in time`() = test { f ->
        f.load(tracks(2), startIndex = 0, positionMs = 0, play = false)

        assertThat(f.transport.actions).doesNotContain("Play")
        assertThat(f.transport.calls.filter { it.action == "Seek" }.map { it.arg("Unit") }).containsExactly("TRACK_NR")
    }

    @Test
    fun `progress reports the size of the queue after each song queued while playing`() = test { f ->
        val progress = mutableListOf<Int>()

        f.load(tracks(4), startIndex = 1, onProgress = { progress += it })

        assertThat(progress).containsExactly(3, 4).inOrder() // songs 3 and 4 of the queue, after the first two were queued
    }

    @Test
    fun `a queue of 1500 is cut to 1000 around the first song`() = test { f ->
        val result = f.load(tracks(1500), startIndex = 0) as SonosResult.Ok

        assertThat(result.value.window).isEqualTo(0..999)
        assertThat(f.transport.actions.count { it == "AddURIToQueue" }).isEqualTo(1000)
    }

    @Test
    fun `a window around a song far into the queue keeps that song and tells which one is first`() = test { f ->
        val result = f.load(tracks(1500), startIndex = 1400) as SonosResult.Ok

        assertThat(result.value.window).isEqualTo(500..1499)
        assertThat(f.transport.calls.first { it.action == "Seek" }.arg("Target")).isEqualTo("901")
        assertThat(f.transport.calls.first { it.action == "AddURIToQueue" }.arg("EnqueuedURI")).isEqualTo(tracks(1500)[500].resourceUrl)
    }

    @Test
    fun `a queue of exactly 1000 is not cut and an empty one or a bad index is refused without touching the speaker`() = test { f ->
        assertThat((f.load(tracks(1000), startIndex = 999) as SonosResult.Ok).value.window).isEqualTo(0..999)

        val g = Fixture(this, host, udn)
        assertThat(g.load(emptyList(), startIndex = 0)).isEqualTo(SonosResult.Err(SonosFailure.MalformedResponse))
        assertThat(g.load(tracks(2), startIndex = 2)).isEqualTo(SonosResult.Err(SonosFailure.MalformedResponse))
        assertThat(g.load(tracks(2), startIndex = -1)).isEqualTo(SonosResult.Err(SonosFailure.MalformedResponse))
        assertThat(g.transport.calls).isEmpty()
    }

    @Test
    fun `cancelling while the first songs are being queued stops everything and never plays`() = test { f ->
        f.transport.gate = { call, n -> if (call.action == "AddURIToQueue" && n == 3) awaitCancellation() }
        val job = launch { f.load(tracks(5), startIndex = 3) }
        runCurrent()

        job.cancel()
        runCurrent()

        assertThat(f.transport.actions).containsExactly("RemoveAllTracksFromQueue", "AddURIToQueue", "AddURIToQueue").inOrder()
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun `cancelling while the rest is being queued keeps what plays and stops adding`() = test { f ->
        f.transport.gate = { call, _ -> if (call.action == "AddURIToQueue" && f.transport.calls.count { it.action == "AddURIToQueue" } == 4) awaitCancellation() }
        val job = launch { f.load(tracks(10), startIndex = 0) }
        runCurrent()
        val before = f.transport.calls.size

        job.cancel()
        runCurrent()

        assertThat(f.transport.calls.size).isEqualTo(before)
        assertThat(f.transport.actions).contains("Play")
        assertThat(f.transport.actions.count { it == "AddURIToQueue" }).isEqualTo(4)
    }

    @Test
    fun `a failure while queueing is typed, ends the load and never plays`() = test { f ->
        for (kind in listOf(UpnpErrorKind.ResourceNotFound, UpnpErrorKind.NotCoordinator, UpnpErrorKind.TransitionNotAvailable)) {
            val g = Fixture(this, host, udn)
            g.transport.script = { if (it.action == "AddURIToQueue") upnp("AddURIToQueue", kind) else SonosResult.Ok(emptyMap()) }

            val result = g.load(tracks(3), startIndex = 1)

            assertThat(result).isEqualTo(upnp("AddURIToQueue", kind))
            assertThat(g.transport.actions).containsExactly("RemoveAllTracksFromQueue", "AddURIToQueue").inOrder()
        }
    }

    @Test
    fun `a failure in any early step ends the load there`() = test { f ->
        for (failing in listOf("RemoveAllTracksFromQueue", "SetAVTransportURI", "SetPlayMode", "Play")) {
            val g = Fixture(this, host, udn)
            g.transport.script = { if (it.action == failing) SonosResult.Err(SonosFailure.Timeout) else SonosResult.Ok(emptyMap()) }

            val result = g.load(tracks(3), startIndex = 0)

            assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.Timeout))
            assertThat(g.transport.actions.last()).isEqualTo(failing)
        }
    }

    @Test
    fun `selecting the song by number failing ends the load`() = test { f ->
        f.transport.script = { if (it.action == "Seek") SonosResult.Err(SonosFailure.Timeout) else SonosResult.Ok(emptyMap()) }

        val result = f.load(tracks(3), startIndex = 1, positionMs = 5_000)

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.Timeout))
        assertThat(f.transport.actions).doesNotContain("Play")
    }

    @Test
    fun `a position that cannot be applied does not stop the song from starting`() = test { f ->
        f.transport.script = { call ->
            if (call.action == "Seek" && call.arg("Unit") == "REL_TIME") upnp("Seek", UpnpErrorKind.TransitionNotAvailable) else SonosResult.Ok(emptyMap())
        }

        val result = f.load(tracks(2), startIndex = 0, positionMs = 30_000)

        assertThat(result).isEqualTo(SonosResult.Ok(LoadResult(0..1, positionApplied = false)))
        assertThat(f.transport.actions).contains("Play")
    }

    @Test
    fun `next moves, and at the end of a normal queue the 711 is not a failure`() = test { f ->
        assertThat(f.commander.next()).isEqualTo(SonosResult.Ok(Navigation.MOVED))

        f.transport.script = { upnp("Next", UpnpErrorKind.NoMoreTracks) }
        assertThat(f.commander.next()).isEqualTo(SonosResult.Ok(Navigation.NO_MORE))

        f.transport.script = { upnp("Next", UpnpErrorKind.NotCoordinator) }
        assertThat(f.commander.next()).isEqualTo(upnp("Next", UpnpErrorKind.NotCoordinator))
    }

    @Test
    fun `previous goes back after three seconds or less and restarts the song after more`() = test { f ->
        f.commander.previousSmart(positionMs = 5_000)
        assertThat(f.transport.calls.single().let { it.action to it.arguments })
            .isEqualTo("Seek" to listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to "0:00:00"))

        val g = Fixture(this, host, udn)
        g.commander.previousSmart(positionMs = 1_000)
        assertThat(g.transport.actions).containsExactly("Previous")

        val h = Fixture(this, host, udn)
        h.commander.previousSmart(positionMs = 3_000)
        h.commander.previousSmart(positionMs = 3_001)
        assertThat(h.transport.actions).containsExactly("Previous", "Seek").inOrder()
    }

    @Test
    fun `previous at the start of a normal queue is not a failure`() = test { f ->
        f.transport.script = { upnp("Previous", UpnpErrorKind.NoMoreTracks) }

        assertThat(f.commander.previousSmart(positionMs = 500)).isEqualTo(SonosResult.Ok(Navigation.NO_MORE))
    }

    @Test
    fun `seek formats hours, minutes and seconds and never goes below zero`() = test { f ->
        for ((ms, expected) in listOf(0L to "0:00:00", -5L to "0:00:00", 61_000L to "0:01:01", 3_723_000L to "1:02:03", 1_500L to "0:00:02")) {
            val g = Fixture(this, host, udn)
            g.commander.seekTo(ms)
            assertThat(g.transport.calls.single().arg("Target")).isEqualTo(expected)
        }
    }

    @Test
    fun `seeking to a song selects it by number and then moves in time`() = test { f ->
        f.commander.seekToTrack(trackNumber = 4, positionMs = 12_000)

        assertThat(f.transport.calls.map { it.arguments }).containsExactly(
            listOf("InstanceID" to "0", "Unit" to "TRACK_NR", "Target" to "4"),
            listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to "0:00:12"),
        ).inOrder()

        val g = Fixture(this, host, udn)
        g.commander.seekToTrack(trackNumber = 0, positionMs = 0)
        assertThat(g.transport.calls.single().arg("Target")).isEqualTo("1")
    }

    @Test
    fun `transport commands send what the speaker expects`() = test { f ->
        f.commander.play()
        f.commander.pause()
        f.commander.stop()
        f.commander.setPlayMode(PlayMode.SHUFFLE_NOREPEAT)

        assertThat(f.transport.calls.map { it.action to it.arguments }).containsExactly(
            "Play" to listOf("InstanceID" to "0", "Speed" to "1"),
            "Pause" to listOf("InstanceID" to "0"),
            "Stop" to listOf("InstanceID" to "0"),
            "SetPlayMode" to listOf("InstanceID" to "0", "NewPlayMode" to "SHUFFLE_NOREPEAT"),
        ).inOrder()
    }

    @Test
    fun `a speaker that is not the coordinator is reported as such`() = test { f ->
        f.transport.script = { upnp(it.action, UpnpErrorKind.NotCoordinator) }

        assertThat(f.commander.play()).isEqualTo(upnp("Play", UpnpErrorKind.NotCoordinator))
        assertThat(f.commander.seekTo(1_000)).isEqualTo(upnp("Seek", UpnpErrorKind.NotCoordinator))
    }

    @Test
    fun `every command marks the own change window at the moment it is sent`() = test { f ->
        f.commander.play()
        advanceTimeBy(1_000)
        f.commander.pause()
        advanceTimeBy(500)
        f.commander.next()
        advanceTimeBy(500)
        f.commander.seekTo(0)
        f.commander.setPlayMode(PlayMode.NORMAL)
        f.commander.stop()

        assertThat(f.recorded).containsExactly(0L, 1_000L, 1_500L, 2_000L, 2_000L, 2_000L).inOrder()
    }

    @Test
    fun `loading marks the window for every step, including the songs queued later`() = test { f ->
        f.load(tracks(3), startIndex = 0)

        assertThat(f.recorded).hasSize(f.transport.calls.size)
    }

    @Test
    fun `commands launched together reach the speaker in the order they were given`() = test { f ->
        val release = CompletableDeferred<Unit>()
        f.transport.gate = { call, _ -> if (call.action == "Pause") release.await() }
        val order = mutableListOf<String>()

        launch { f.commander.pause(); order += "pause done" }
        runCurrent()
        launch { f.commander.play(); order += "play done" }
        runCurrent()
        assertThat(f.transport.actions).containsExactly("Pause") // play waits for the pause to finish
        release.complete(Unit)
        runCurrent()

        assertThat(f.transport.actions).containsExactly("Pause", "Play").inOrder()
        assertThat(order).containsExactly("pause done", "play done").inOrder()
    }

    @Test
    fun `a command given while the rest of a queue is being loaded goes before the next song is added`() = test { f ->
        val release = CompletableDeferred<Unit>()
        f.transport.gate = { call, _ ->
            if (call.action == "AddURIToQueue" && f.transport.calls.count { it.action == "AddURIToQueue" } == 3) release.await()
        }
        launch { f.load(tracks(6), startIndex = 0) }
        runCurrent()

        launch { f.commander.pause() }
        runCurrent()
        release.complete(Unit)
        runCurrent()

        val actions = f.transport.actions
        val pause = actions.indexOf("Pause")
        val thirdAdd = actions.withIndex().filter { it.value == "AddURIToQueue" }[2].index
        assertThat(pause).isEqualTo(thirdAdd + 1)
    }
}
