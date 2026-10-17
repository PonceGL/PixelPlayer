package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
        val edits = mutableListOf<Long>()
        val commander = SonosCommander(
            transport, host, udn, now = { scope.currentTime },
            recordCommand = { recorded += it },
            recordQueueEdit = { edits += it },
        )
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
    fun `loading queues up to the current song, selects the mode and the song, seeks, plays, then queues the rest`() = test { f ->
        val result = f.load(tracks(3), startIndex = 1, positionMs = 95_000, mode = PlayMode.REPEAT_ALL)

        assertThat(result).isEqualTo(SonosResult.Ok(LoadResult(0..2, positionApplied = true)))
        assertThat(f.transport.actions).containsExactly(
            "RemoveAllTracksFromQueue", "AddURIToQueue", "AddURIToQueue", "SetAVTransportURI", "SetPlayMode", "Seek", "Seek", "Play",
            "AddURIToQueue",
        ).inOrder()
        assertThat(f.transport.calls.all { it.host == host && it.service == SonosService.AV_TRANSPORT }).isTrue()
    }

    @Test
    fun `each loading step sends the arguments the speaker expects`() = test { f ->
        val songs = tracks(3)
        f.load(songs, startIndex = 1, positionMs = 95_000, mode = PlayMode.REPEAT_ALL)
        val calls = f.transport.calls

        assertThat(calls.first { it.action == "RemoveAllTracksFromQueue" }.arguments).containsExactly("InstanceID" to "0")
        val adds = calls.filter { it.action == "AddURIToQueue" }
        assertThat(adds).hasSize(3)
        for ((add, song) in adds.zip(songs)) {
            assertThat(add.arguments).containsExactly(
                "InstanceID" to "0",
                "EnqueuedURI" to song.resourceUrl,
                "EnqueuedURIMetaData" to Didl.build(song),
                "DesiredFirstTrackNumberEnqueued" to "0",
                "EnqueueAsNext" to "0",
            ).inOrder()
        }
        assertThat(calls.first { it.action == "SetAVTransportURI" }.arguments).containsExactly(
            "InstanceID" to "0", "CurrentURI" to "x-rincon-queue:RINCON_000E58000001001400#0", "CurrentURIMetaData" to "",
        ).inOrder()
        assertThat(calls.first { it.action == "SetPlayMode" }.arguments)
            .containsExactly("InstanceID" to "0", "NewPlayMode" to "REPEAT_ALL").inOrder()
        val seeks = calls.filter { it.action == "Seek" }
        assertThat(seeks[0].arguments).containsExactly("InstanceID" to "0", "Unit" to "TRACK_NR", "Target" to "2").inOrder()
        assertThat(seeks[1].arguments).containsExactly("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to "0:01:35").inOrder()
        assertThat(calls.first { it.action == "Play" }.arguments).containsExactly("InstanceID" to "0", "Speed" to "1").inOrder()
    }

    @Test
    fun `the mode is set before the song is selected so the speaker cannot move to another song afterwards`() = test { f ->
        f.load(tracks(4), startIndex = 2, mode = PlayMode.NORMAL)

        val actions = f.transport.actions
        assertThat(actions.indexOf("SetPlayMode")).isLessThan(actions.indexOf("Seek"))
    }

    @Test
    fun `started is told once with the result that is returned, after Play and before the rest is queued`() = test { f ->
        val told = mutableListOf<Pair<LoadResult, Int>>()
        f.transport.script = { call ->
            if (call.action == "Seek" && call.arg("Unit") == "REL_TIME") upnp("Seek", UpnpErrorKind.TransitionNotAvailable) else SonosResult.Ok(emptyMap())
        }

        val result = f.load(tracks(3), startIndex = 0, positionMs = 9_000, onStarted = { told += it to f.transport.calls.size })

        assertThat(told).hasSize(1)
        assertThat(told.single().first).isEqualTo(LoadResult(0..2, positionApplied = false))
        assertThat(SonosResult.Ok(told.single().first)).isEqualTo(result)
        assertThat(f.transport.actions.take(told.single().second).last()).isEqualTo("Play")
    }

    @Test
    fun `started is told even when the load does not play, and never when a step before it fails`() = test { f ->
        var told = 0
        f.load(tracks(2), startIndex = 0, play = false, onStarted = { told++ })
        assertThat(told).isEqualTo(1)
        assertThat(f.transport.actions).doesNotContain("Play")

        for (failing in listOf("RemoveAllTracksFromQueue", "AddURIToQueue", "SetAVTransportURI", "SetPlayMode", "Seek", "Play")) {
            val g = Fixture(this, host, udn)
            g.transport.script = { if (it.action == failing) SonosResult.Err(SonosFailure.Timeout) else SonosResult.Ok(emptyMap()) }
            var count = 0

            g.load(tracks(3), startIndex = 1, onStarted = { count++ })

            assertWithMessage(failing).that(count).isEqualTo(0)
        }
    }

    @Test
    fun `starting at the first song queues one before playing`() = test { f ->
        f.load(tracks(3), startIndex = 0)

        assertThat(f.transport.actions).containsExactly(
            "RemoveAllTracksFromQueue", "AddURIToQueue", "SetAVTransportURI", "SetPlayMode", "Seek", "Play", "AddURIToQueue", "AddURIToQueue",
        ).inOrder()
    }

    @Test
    fun `starting at the last song queues all before playing and has no rest`() = test { f ->
        f.load(tracks(3), startIndex = 2)

        assertThat(f.transport.actions).containsExactly(
            "RemoveAllTracksFromQueue", "AddURIToQueue", "AddURIToQueue", "AddURIToQueue", "SetAVTransportURI", "SetPlayMode", "Seek", "Play",
        ).inOrder()
        assertThat(f.transport.calls.first { it.action == "Seek" }.arg("Target")).isEqualTo("3")
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
    fun `progress counts from the real position in the queue when the window starts later`() = test { f ->
        val progress = mutableListOf<Int>()

        f.load(tracks(1500), startIndex = 600, onProgress = { progress += it })

        assertThat(progress.take(3)).containsExactly(102, 103, 104).inOrder()
        assertThat(progress.last()).isEqualTo(1000)
    }

    @Test
    fun `a queue of 1500 is cut to 1000 around the first song`() = test { f ->
        val result = f.load(tracks(1500), startIndex = 0) as SonosResult.Ok

        assertThat(result.value.window).isEqualTo(0..999)
        assertThat(f.transport.actions.count { it == "AddURIToQueue" }).isEqualTo(1000)
    }

    @Test
    fun `a window keeps at most 100 songs before the current one so playback starts quickly`() = test { f ->
        val result = f.load(tracks(1500), startIndex = 1400) as SonosResult.Ok

        assertThat(result.value.window).isEqualTo(1300..1499)
        assertThat(f.transport.calls.first { it.action == "Seek" }.arg("Target")).isEqualTo("101")
        assertThat(f.transport.calls.first { it.action == "AddURIToQueue" }.arg("EnqueuedURI")).isEqualTo(tracks(1500)[1300].resourceUrl)
        assertThat(f.transport.actions.takeWhile { it != "SetAVTransportURI" }.count { it == "AddURIToQueue" }).isEqualTo(101)
    }

    @Test
    fun `the window around any song keeps it, keeps the order and never exceeds 1000`() {
        val cases = listOf(
            Triple(10, 0, 0..9), Triple(10, 9, 0..9), Triple(250, 249, 149..249), Triple(1000, 0, 0..999), Triple(1000, 999, 899..999),
            Triple(1001, 0, 0..999), Triple(1001, 100, 0..999), Triple(1001, 101, 1..1000), Triple(1001, 1000, 900..1000),
            Triple(1500, 300, 200..1199), Triple(1500, 1499, 1399..1499),
        )
        for ((size, start, expected) in cases) {
            assertWithMessage("size $size start $start").that(SonosCommander.queueWindow(size, start)).isEqualTo(expected)
        }
    }

    @Test
    fun `an empty queue or an index outside it is a programming error`() = test { f ->
        for ((songs, index) in listOf(emptyList<TrackMetadata>() to 0, tracks(2) to 2, tracks(2) to -1)) {
            val failure = runCatching { f.load(songs, startIndex = index) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(f.transport.calls).isEmpty()
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
    fun `a failure while queueing the first songs is typed, ends the load and never plays`() = test { f ->
        for (kind in listOf(UpnpErrorKind.ResourceNotFound, UpnpErrorKind.NotCoordinator, UpnpErrorKind.TransitionNotAvailable)) {
            val g = Fixture(this, host, udn)
            g.transport.script = { if (it.action == "AddURIToQueue") upnp("AddURIToQueue", kind) else SonosResult.Ok(emptyMap()) }

            val result = g.load(tracks(3), startIndex = 1)

            assertThat(result).isEqualTo(upnp("AddURIToQueue", kind))
            assertThat(g.transport.actions).containsExactly("RemoveAllTracksFromQueue", "AddURIToQueue").inOrder()
        }
    }

    @Test
    fun `a failure on the second of the first songs also ends the load there`() = test { f ->
        f.transport.script = { call ->
            if (call.action == "AddURIToQueue" && f.transport.calls.count { it.action == "AddURIToQueue" } == 2) upnp("AddURIToQueue", UpnpErrorKind.NotCoordinator)
            else SonosResult.Ok(emptyMap())
        }

        val result = f.load(tracks(5), startIndex = 3)

        assertThat(result).isEqualTo(upnp("AddURIToQueue", UpnpErrorKind.NotCoordinator))
        assertThat(f.transport.actions).containsExactly("RemoveAllTracksFromQueue", "AddURIToQueue", "AddURIToQueue").inOrder()
    }

    @Test
    fun `a failure while queueing the rest is typed, stops adding and leaves what already plays`() = test { f ->
        val progress = mutableListOf<Int>()
        f.transport.script = { call ->
            if (call.action == "AddURIToQueue" && f.transport.calls.count { it.action == "AddURIToQueue" } == 4) upnp("AddURIToQueue", UpnpErrorKind.ResourceNotFound)
            else SonosResult.Ok(emptyMap())
        }

        val result = f.load(tracks(8), startIndex = 0, onProgress = { progress += it })

        assertThat(result).isEqualTo(upnp("AddURIToQueue", UpnpErrorKind.ResourceNotFound))
        assertThat(f.transport.actions).contains("Play")
        assertThat(f.transport.actions.count { it == "AddURIToQueue" }).isEqualTo(4)
        assertThat(progress).containsExactly(2, 3).inOrder()
    }

    @Test
    fun `a failure in any early step ends the load there`() = test { f ->
        for (failing in listOf("RemoveAllTracksFromQueue", "SetAVTransportURI", "SetPlayMode", "Seek", "Play")) {
            for (start in listOf(0, 1)) {
                val g = Fixture(this, host, udn)
                g.transport.script = { if (it.action == failing) SonosResult.Err(SonosFailure.Timeout) else SonosResult.Ok(emptyMap()) }

                val result = g.load(tracks(3), startIndex = start)

                assertWithMessage("$failing at $start").that(result).isEqualTo(SonosResult.Err(SonosFailure.Timeout))
                assertWithMessage("$failing at $start").that(g.transport.actions.last()).isEqualTo(failing)
            }
        }
    }

    @Test
    fun `a position that the speaker refuses does not stop the song from starting`() = test { f ->
        f.transport.script = { call ->
            if (call.action == "Seek" && call.arg("Unit") == "REL_TIME") upnp("Seek", UpnpErrorKind.TransitionNotAvailable) else SonosResult.Ok(emptyMap())
        }

        val result = f.load(tracks(2), startIndex = 0, positionMs = 30_000)

        assertThat(result).isEqualTo(SonosResult.Ok(LoadResult(0..1, positionApplied = false)))
        assertThat(f.transport.actions).contains("Play")
    }

    @Test
    fun `a position that cannot be sent because the network failed does fail the load`() = test { f ->
        f.transport.script = { call ->
            if (call.action == "Seek" && call.arg("Unit") == "REL_TIME") SonosResult.Err(SonosFailure.Timeout) else SonosResult.Ok(emptyMap())
        }

        val result = f.load(tracks(2), startIndex = 0, positionMs = 30_000)

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.Timeout))
        assertThat(f.transport.actions).doesNotContain("Play")
    }

    @Test
    fun `a negative or zero position does not seek in time and counts as applied`() = test { f ->
        for (position in listOf(0L, -1L)) {
            val g = Fixture(this, host, udn)
            val result = g.load(tracks(2), startIndex = 0, positionMs = position)

            assertThat(result).isEqualTo(SonosResult.Ok(LoadResult(0..1, positionApplied = true)))
            assertThat(g.transport.calls.filter { it.action == "Seek" }.map { it.arg("Unit") }).containsExactly("TRACK_NR")
        }
    }

    @Test
    fun `a newer load takes over and the older one stops adding songs and says it was superseded`() = test { f ->
        val release = CompletableDeferred<Unit>()
        f.transport.gate = { call, _ ->
            if (call.action == "AddURIToQueue" && f.transport.calls.count { it.action == "AddURIToQueue" } == 3) release.await()
        }
        var older: SonosResult<LoadResult>? = null
        launch { older = f.load(tracks(10), startIndex = 0) }
        runCurrent()

        val newer = launch { f.load(tracks(2), startIndex = 1) }
        runCurrent()
        release.complete(Unit)
        runCurrent()

        assertThat(older).isEqualTo(SonosResult.Err(SonosFailure.Superseded))
        assertThat(newer.isCompleted).isTrue()
        val actions = f.transport.actions
        val newerStart = actions.withIndex().filter { it.value == "RemoveAllTracksFromQueue" }[1].index
        assertThat(actions.drop(newerStart)).containsExactly(
            "RemoveAllTracksFromQueue", "AddURIToQueue", "AddURIToQueue", "SetAVTransportURI", "SetPlayMode", "Seek", "Play",
        ).inOrder()
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
    fun `previous restarts the song after more than three seconds`() = test { f ->
        val result = f.commander.previousSmart(positionMs = 5_000)

        assertThat(result).isEqualTo(SonosResult.Ok(Navigation.MOVED))
        assertThat(f.transport.calls.single().let { it.action to it.arguments })
            .isEqualTo("Seek" to listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to "0:00:00"))
    }

    @Test
    fun `previous goes to the one before at three seconds or less`() = test { f ->
        assertThat(f.commander.previousSmart(positionMs = 1_000)).isEqualTo(SonosResult.Ok(Navigation.MOVED))
        assertThat(f.commander.previousSmart(positionMs = 3_000)).isEqualTo(SonosResult.Ok(Navigation.MOVED))
        assertThat(f.commander.previousSmart(positionMs = 3_001)).isEqualTo(SonosResult.Ok(Navigation.MOVED))

        assertThat(f.transport.actions).containsExactly("Previous", "Previous", "Seek").inOrder()
    }

    @Test
    fun `previous reports real failures on both branches`() = test { f ->
        f.transport.script = { upnp(it.action, UpnpErrorKind.NotCoordinator) }
        assertThat(f.commander.previousSmart(positionMs = 5_000)).isEqualTo(upnp("Seek", UpnpErrorKind.NotCoordinator))
        assertThat(f.commander.previousSmart(positionMs = 500)).isEqualTo(upnp("Previous", UpnpErrorKind.NotCoordinator))

        f.transport.script = { upnp(it.action, UpnpErrorKind.NoMoreTracks) }
        assertThat(f.commander.previousSmart(positionMs = 5_000)).isEqualTo(upnp("Seek", UpnpErrorKind.NoMoreTracks))
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
        val result = f.commander.seekToTrack(trackNumber = 4, positionMs = 12_000)

        assertThat(result).isEqualTo(SonosResult.Ok(true))
        assertThat(f.transport.calls.map { it.arguments }).containsExactly(
            listOf("InstanceID" to "0", "Unit" to "TRACK_NR", "Target" to "4"),
            listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to "0:00:12"),
        ).inOrder()
    }

    @Test
    fun `seeking to a song never selects a number below one nor moves in time for zero or less`() = test { f ->
        f.commander.seekToTrack(trackNumber = 0, positionMs = -3)

        assertThat(f.transport.calls.single().arg("Target")).isEqualTo("1")
    }

    @Test
    fun `when the song cannot be selected nothing else is sent`() = test { f ->
        f.transport.script = { SonosResult.Err(SonosFailure.Timeout) }

        val result = f.commander.seekToTrack(trackNumber = 2, positionMs = 5_000)

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.Timeout))
        assertThat(f.transport.actions).containsExactly("Seek")
    }

    @Test
    fun `when the song changed but the second was refused the caller is told the position was lost`() = test { f ->
        f.transport.script = { call ->
            if (call.arg("Unit") == "REL_TIME") upnp("Seek", UpnpErrorKind.TransitionNotAvailable) else SonosResult.Ok(emptyMap())
        }

        assertThat(f.commander.seekToTrack(trackNumber = 2, positionMs = 5_000)).isEqualTo(SonosResult.Ok(false))

        f.transport.script = { call -> if (call.arg("Unit") == "REL_TIME") SonosResult.Err(SonosFailure.Timeout) else SonosResult.Ok(emptyMap()) }
        assertThat(f.commander.seekToTrack(trackNumber = 2, positionMs = 5_000)).isEqualTo(SonosResult.Err(SonosFailure.Timeout))
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
    fun `every command marks the own change window when it is sent and again when it is done`() = test { f ->
        f.commander.play()
        advanceTimeBy(1_000)
        f.commander.pause()
        advanceTimeBy(500)
        f.commander.next()
        advanceTimeBy(500)
        f.commander.seekTo(0)
        f.commander.setPlayMode(PlayMode.NORMAL)
        f.commander.stop()
        f.commander.previousSmart(10_000)
        f.commander.seekToTrack(2, 0)

        assertThat(f.recorded).containsExactly(0L, 0L, 1_000L, 1_000L, 1_500L, 1_500L, 2_000L, 2_000L, 2_000L, 2_000L, 2_000L, 2_000L, 2_000L, 2_000L, 2_000L, 2_000L).inOrder()
    }

    @Test
    fun `the window is marked before the command reaches the speaker`() = test { f ->
        val seen = mutableListOf<Int>()
        f.transport.gate = { _, _ -> seen += f.recorded.size }

        f.commander.pause()

        assertThat(seen).containsExactly(1)
    }

    @Test
    fun `a slow command keeps the window open until it is done`() = test { f ->
        f.transport.gate = { _, _ -> kotlinx.coroutines.delay(3_900) }

        f.commander.pause()

        assertThat(f.recorded).containsExactly(0L, 3_900L).inOrder()
    }

    @Test
    fun `a command that waits for its turn is marked when it is really sent`() = test { f ->
        val release = CompletableDeferred<Unit>()
        f.transport.gate = { call, _ -> if (call.action == "Pause") release.await() }
        launch { f.commander.pause() }
        runCurrent()
        launch { f.commander.play() }
        runCurrent()
        advanceTimeBy(1_000)

        release.complete(Unit)
        runCurrent()

        assertThat(f.recorded).containsExactly(0L, 1_000L, 1_000L, 1_000L).inOrder()
    }

    @Test
    fun `queueing the rest of the songs while it plays does not keep the window open`() = test { f ->
        f.load(tracks(3), startIndex = 0)

        val calls = f.transport.calls.size
        assertThat(calls).isEqualTo(8) // Remove, Add, SetAVTransportURI, SetPlayMode, Seek, Play + 2 songs of the rest
        assertThat(f.recorded).hasSize(2 * (calls - 2))
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

    @Test
    fun `inserting more than sixteen songs goes in batches of sixteen, each after the one before`() = test { f ->
        val progress = mutableListOf<Int>()

        val result = f.commander.insertTracks(tracks(20), position = 3, onProgress = { progress += it })

        assertThat(result).isEqualTo(SonosResult.Ok(Unit))
        assertThat(f.transport.actions).containsExactly("AddMultipleURIsToQueue", "AddMultipleURIsToQueue").inOrder()
        val (first, second) = f.transport.calls
        assertThat(first.arg("NumberOfURIs")).isEqualTo("16")
        assertThat(first.arg("DesiredFirstTrackNumberEnqueued")).isEqualTo("3")
        assertThat(second.arg("NumberOfURIs")).isEqualTo("4")
        assertThat(second.arg("DesiredFirstTrackNumberEnqueued")).isEqualTo("19")
        assertThat(first.arg("EnqueueAsNext")).isEqualTo("0")
        assertThat(first.arg("UpdateID")).isEqualTo("0")
        assertThat(progress).containsExactly(16, 20).inOrder()
    }

    @Test
    fun `the addresses go separated by a space and each song brings its own metadata`() = test { f ->
        f.commander.insertTracks(tracks(2), position = 0)

        val call = f.transport.calls.single()
        assertThat(call.arg("EnqueuedURIs")).isEqualTo("${track(0).resourceUrl} ${track(1).resourceUrl}")
        assertThat(call.arg("EnqueuedURIsMetaData")).isEqualTo("${Didl.build(track(0))} ${Didl.build(track(1))}")
    }

    @Test
    fun `appending keeps asking for the end on every batch`() = test { f ->
        f.commander.insertTracks(tracks(20), position = 0)

        assertThat(f.transport.calls.map { it.arg("DesiredFirstTrackNumberEnqueued") }).containsExactly("0", "0")
    }

    @Test
    fun `a speaker that refuses a batch gets smaller ones, and the next batches stay small`() = test { f ->
        f.transport.script = { call ->
            if (call.arg("NumberOfURIs").toInt() > 4) upnp("AddMultipleURIsToQueue", UpnpErrorKind.InvalidArguments) else SonosResult.Ok(emptyMap())
        }
        val progress = mutableListOf<Int>()

        val result = f.commander.insertTracks(tracks(12), position = 1, onProgress = { progress += it })

        assertThat(result).isEqualTo(SonosResult.Ok(Unit))
        // 16 would not fit in 12, so the first try is 12, then 6, then 3 from there on.
        assertThat(f.transport.calls.map { it.arg("NumberOfURIs").toInt() }).containsExactly(12, 6, 3, 3, 3, 3).inOrder()
        assertThat(progress).containsExactly(3, 6, 9, 12).inOrder()
    }

    @Test
    fun `a failure in the middle says how many songs went in`() = test { f ->
        f.transport.script = { call ->
            if (call.arg("DesiredFirstTrackNumberEnqueued") == "17") SonosResult.Err(SonosFailure.Timeout) else SonosResult.Ok(emptyMap())
        }
        val progress = mutableListOf<Int>()

        val result = f.commander.insertTracks(tracks(40), position = 1, onProgress = { progress += it })

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.Timeout))
        assertThat(progress).containsExactly(16)
        assertThat(f.transport.calls).hasSize(2)
    }

    @Test
    fun `nothing to insert sends nothing`() = test { f ->
        assertThat(f.commander.insertTracks(emptyList(), position = 1)).isEqualTo(SonosResult.Ok(Unit))
        assertThat(f.transport.calls).isEmpty()
    }

    @Test
    fun `a load that starts while songs are being inserted ends the insertion`() = test { f ->
        val release = CompletableDeferred<Unit>()
        f.transport.gate = { _, n -> if (n == 1) release.await() }
        val result = CompletableDeferred<SonosResult<Unit>>()
        launch { result.complete(f.commander.insertTracks(tracks(40), position = 1)) }
        runCurrent()

        launch { f.load(tracks(2)) }
        runCurrent()
        release.complete(Unit)
        runCurrent()

        assertThat(result.await()).isEqualTo(SonosResult.Err(SonosFailure.Superseded))
        assertThat(f.transport.actions.count { it == "AddMultipleURIsToQueue" }).isEqualTo(1)
    }

    @Test
    fun `removing songs sends the first track and how many`() = test { f ->
        assertThat(f.commander.removeTracks(first = 4, count = 2)).isEqualTo(SonosResult.Ok(Unit))

        val call = f.transport.calls.single()
        assertThat(call.action).isEqualTo("RemoveTrackRangeFromQueue")
        assertThat(call.arg("StartingIndex")).isEqualTo("4")
        assertThat(call.arg("NumberOfTracks")).isEqualTo("2")
        assertThat(call.arg("UpdateID")).isEqualTo("0")
        assertThat(f.commander.removeTracks(first = 4, count = 0)).isEqualTo(SonosResult.Ok(Unit))
        assertThat(f.transport.calls).hasSize(1)
    }

    @Test
    fun `moving songs says before which original track they go`() = test { f ->
        assertThat(f.commander.moveTracks(first = 1, count = 2, before = 6)).isEqualTo(SonosResult.Ok(Unit))

        val call = f.transport.calls.single()
        assertThat(call.action).isEqualTo("ReorderTracksInQueue")
        assertThat(call.arg("StartingIndex")).isEqualTo("1")
        assertThat(call.arg("NumberOfTracks")).isEqualTo("2")
        assertThat(call.arg("InsertBefore")).isEqualTo("6")
        assertThat(call.arg("UpdateID")).isEqualTo("0")
    }

    @Test
    fun `every edit of the queue is marked as ours, before and after, and other commands are not edits`() = test { f ->
        f.commander.insertTracks(tracks(1), position = 1)
        f.commander.removeTracks(first = 1, count = 1)
        f.commander.moveTracks(first = 1, count = 1, before = 3)
        assertThat(f.edits).hasSize(6)

        f.commander.pause()
        assertThat(f.edits).hasSize(6)
        assertThat(f.recorded).hasSize(2)
    }
}
