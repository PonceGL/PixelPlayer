package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.RepeatMode
import com.theveloper.pixelplay.data.sonos.model.SonosDevice
import com.theveloper.pixelplay.data.sonos.model.SonosUdn
import com.theveloper.pixelplay.data.sonos.model.TransportState
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.SonosSubscriber
import com.theveloper.pixelplay.data.sonos.net.SonosTransport
import com.theveloper.pixelplay.data.sonos.net.Subscription
import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind
import com.theveloper.pixelplay.data.sonos.protocol.UpnpTime
import com.theveloper.pixelplay.data.sonos.server.AccessDecision
import com.theveloper.pixelplay.data.sonos.server.SonosEventRouter
import com.theveloper.pixelplay.data.sonos.server.SonosMediaAccess
import com.theveloper.pixelplay.data.sonos.server.SonosServerIdentity
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SonosSessionControllerTest {

    private val device = SonosDevice(SonosUdn("RINCON_1"), "Cocina", "192.168.1.50", 1)
    private val token = "0123456789abcdef0123456789abcdef"

    /** Keeps what a Sonos keeps: a queue, which song, the state, the mode and how far into the song it is. */
    private class FakeSpeaker(private val now: () -> Long) : SonosTransport {
        val calls = CopyOnWriteArrayList<Pair<String, List<Pair<String, String>>>>()
        val queue = mutableListOf<String>()
        var current = 1
        var transport = "STOPPED"
        var mode = "NORMAL"
        private var positionBase = 0L
        private var playingSince = 0L
        var failOn: (action: String, nth: Int) -> SonosResult<Map<String, String>>? = { _, _ -> null }
        var hangOn: String? = null
        private val counts = mutableMapOf<String, Int>()

        suspend fun call(action: String, arguments: List<Pair<String, String>>) { call("x", SonosService.AV_TRANSPORT, action, arguments, 0) }
        val actions get() = calls.map { it.first }
        val commands get() = actions.filterNot { it.startsWith("Get") }
        fun positionMs() = if (transport == "PLAYING") positionBase + (now() - playingSince) else positionBase

        override suspend fun call(host: String, service: SonosService, action: String, arguments: List<Pair<String, String>>, timeoutMs: Long): SonosResult<Map<String, String>> {
            calls += action to arguments
            val nth = counts.merge(action, 1, Int::plus)!!
            if (hangOn == action) awaitCancellation()
            failOn(action, nth)?.let { return it }
            fun arg(name: String) = arguments.single { it.first == name }.second
            return SonosResult.Ok(
                when (action) {
                    "RemoveAllTracksFromQueue" -> { queue.clear(); emptyMap() }
                    "AddURIToQueue" -> { queue += arg("EnqueuedURI"); emptyMap() }
                    "SetAVTransportURI" -> { current = 1; transport = "STOPPED"; emptyMap() }
                    "Seek" -> {
                        if (arg("Unit") == "TRACK_NR") { current = arg("Target").toInt(); positionBase = 0 } else { positionBase = UpnpTime.parseMs(arg("Target")) ?: 0 }
                        if (transport == "PLAYING") playingSince = now()
                        emptyMap()
                    }
                    "SetPlayMode" -> { mode = arg("NewPlayMode"); emptyMap() }
                    "Play" -> { if (transport != "PLAYING") { transport = "PLAYING"; playingSince = now() }; emptyMap() }
                    "Pause" -> { positionBase = positionMs(); transport = "PAUSED_PLAYBACK"; emptyMap() }
                    "Stop" -> { positionBase = 0; transport = "STOPPED"; emptyMap() }
                    "GetTransportInfo" -> mapOf("CurrentTransportState" to transport, "CurrentTransportStatus" to "OK")
                    "GetPositionInfo" -> mapOf(
                        "Track" to current.toString(), "TrackDuration" to "0:03:20", "TrackMetaData" to "",
                        "TrackURI" to queue.getOrElse(current - 1) { "" }, "RelTime" to (UpnpTime.format(positionMs()) ?: "0:00:00"),
                    )
                    "GetTransportSettings" -> mapOf("PlayMode" to mode)
                    "GetMediaInfo" -> mapOf("NrTracks" to queue.size.toString())
                    else -> emptyMap()
                },
            )
        }
    }

    private class FakeSubscriber : SonosSubscriber {
        val subscribed = CopyOnWriteArrayList<SonosService>()
        val unsubscribed = CopyOnWriteArrayList<SonosService>()
        override suspend fun subscribe(host: String, service: SonosService, callbackUrl: String, timeoutSeconds: Int): SonosResult<Subscription> {
            subscribed += service
            return SonosResult.Ok(Subscription("sid-${service.name}", 120))
        }
        override suspend fun renew(host: String, service: SonosService, sid: String, timeoutSeconds: Int) = SonosResult.Ok(Subscription(sid, 120))
        override suspend fun unsubscribe(host: String, service: SonosService, sid: String): SonosResult<Unit> {
            unsubscribed += service
            return SonosResult.Ok(Unit)
        }
    }

    private class FakeServer : SonosMediaServer {
        val log = CopyOnWriteArrayList<String>()
        var answer: suspend (String) -> SonosResult<String> = { SonosResult.Ok("http://192.168.1.20:8080") }
        override suspend fun acquire(speakerHost: String): SonosResult<String> {
            log += "acquire:$speakerHost"
            return answer(speakerHost)
        }
        override fun release() { log += "release" }
    }

    private class FakeLocks : SonosPowerLocks {
        val log = CopyOnWriteArrayList<String>()
        var held = false
        override fun acquire() { held = true; log += "acquire" }
        override fun release() { held = false; log += "release" }
    }

    private class Fixture(val test: TestScope, val device: SonosDevice, token: String) {
        val scopeJob = Job(test.backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(test.backgroundScope.coroutineContext + scopeJob)
        val speaker = FakeSpeaker { test.currentTime }
        val subscriber = FakeSubscriber()
        val server = FakeServer()
        val locks = FakeLocks()
        val holder = SonosSessionHolder()
        val router = SonosEventRouter()
        val access = SonosMediaAccess { SonosServerIdentity(token, 8080) }
        val controller = SonosSessionController(speaker, subscriber, router, access, server, locks, holder, { test.currentTime }, scope)
        val connections = mutableListOf<SonosConnection>()
    }

    private fun test(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        val fixture = Fixture(this, device, token)
        backgroundScope.launch { fixture.holder.state.collect { fixture.connections += it } }
        runCurrent()
        block(fixture)
    }

    private fun entries(count: Int = 5) = List(count) { QueueEntry("s$it", "Song $it", "Artist", "Album", 200_000, "audio/flac", hasArt = true) }

    private fun queue(
        entries: List<QueueEntry> = entries(),
        current: Int = 2,
        positionMs: Long = 95_000,
        play: Boolean = true,
        repeat: RepeatMode = RepeatMode.OFF,
        shuffledByApp: Boolean = false,
    ) = LocalQueue(entries, current, positionMs, play, repeat, shuffledByApp)

    @Test
    fun `connecting takes the locks and the server, queues the songs, subscribes and starts at the same second`() = test { f ->
        val result = f.controller.connect(device, queue())

        assertThat(result).isInstanceOf(SonosResult.Ok::class.java)
        assertThat(f.locks.log).containsExactly("acquire")
        assertThat(f.server.log).containsExactly("acquire:192.168.1.50")
        val actions = f.speaker.commands
        assertThat(actions.take(8)).containsExactly(
            "RemoveAllTracksFromQueue", "AddURIToQueue", "AddURIToQueue", "AddURIToQueue", "SetAVTransportURI", "SetPlayMode", "Seek", "Seek",
        ).inOrder()
        assertThat(f.speaker.transport).isEqualTo("PLAYING")
        assertThat(f.speaker.current).isEqualTo(3)
        assertThat(f.speaker.queue).hasSize(5)
        assertThat(f.speaker.queue.first()).startsWith("http://192.168.1.20:8080/sonos/song/s0?")
        assertThat(f.subscriber.subscribed).containsExactly(SonosService.AV_TRANSPORT, SonosService.RENDERING_CONTROL)
        runCurrent()
        assertThat(f.connections.map { it::class }).containsExactly(SonosConnection.Idle::class, SonosConnection.Connecting::class, SonosConnection.Active::class).inOrder()
        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Active(device))
    }

    @Test
    fun `the session says which songs went to the speaker and the engine follows the speaker`() = test { f ->
        val session = (f.controller.connect(device, queue()) as SonosResult.Ok).value
        advanceTimeBy(1_000)
        runCurrent()

        assertThat(session.included).containsExactly(0, 1, 2, 3, 4).inOrder()
        assertThat(session.window).isEqualTo(0..4)
        assertThat(session.engine.state.value.playback.transportState).isEqualTo(TransportState.PLAYING)
        assertThat(session.engine.state.value.playback.currentTrackNumber).isEqualTo(3)
    }

    @Test
    fun `only the songs sent to the speaker can be fetched by it`() = test { f ->
        f.controller.connect(device, queue(entries(3), current = 0, positionMs = 0))

        assertThat(f.access.authorize(token, "s1")).isEqualTo(AccessDecision.Allowed)
        assertThat(f.access.authorize(token, "other")).isEqualTo(AccessDecision.NotPublished)
    }

    @Test
    fun `the repeat of the user is the mode of the speaker and the shuffle of the app is not`() = test { f ->
        f.controller.connect(device, queue(repeat = RepeatMode.ALL, shuffledByApp = true))

        assertThat(f.speaker.mode).isEqualTo(PlayMode.REPEAT_ALL.upnpValue)
    }

    @Test
    fun `a start that is paused leaves the speaker paused`() = test { f ->
        f.controller.connect(device, queue(play = false))

        assertThat(f.speaker.actions).doesNotContain("Play")
        assertThat(f.speaker.transport).isEqualTo("STOPPED")
    }

    @Test
    fun `without the server nothing is queued, nothing is subscribed and everything is let go`() = test { f ->
        f.server.answer = { SonosResult.Err(SonosFailure.ServerUnavailable) }

        val result = f.controller.connect(device, queue())

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.ServerUnavailable))
        assertThat(f.speaker.calls).isEmpty()
        assertThat(f.subscriber.subscribed).isEmpty()
        assertThat(f.server.log).containsExactly("acquire:192.168.1.50", "release").inOrder()
        assertThat(f.locks.log).containsExactly("acquire", "release").inOrder()
        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Failed(device, SonosFailure.ServerUnavailable))
    }

    @Test
    fun `a server that never answers ends in a timeout and lets everything go`() = test { f ->
        f.server.answer = { awaitCancellation() }

        val result = f.controller.connect(device, queue())

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.Timeout))
        assertThat(f.locks.held).isFalse()
        assertThat(f.server.log.last()).isEqualTo("release")
        assertThat(testScheduler.currentTime).isEqualTo(SonosSessionController.START_TIMEOUT_MS)
    }

    @Test
    fun `a failure in the middle of the first songs is typed, unsubscribes and lets everything go`() = test { f ->
        f.speaker.failOn = { action, nth ->
            if (action == "AddURIToQueue" && nth == 2) SonosResult.Err(SonosFailure.Upnp("AddURIToQueue", UpnpErrorKind.NotCoordinator)) else null
        }

        val result = f.controller.connect(device, queue())

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.Upnp("AddURIToQueue", UpnpErrorKind.NotCoordinator)))
        assertThat(f.speaker.actions).doesNotContain("Play")
        assertThat(f.subscriber.unsubscribed).containsExactly(SonosService.AV_TRANSPORT, SonosService.RENDERING_CONTROL)
        assertThat(f.server.log.last()).isEqualTo("release")
        assertThat(f.locks.held).isFalse()
        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Failed(device, SonosFailure.Upnp("AddURIToQueue", UpnpErrorKind.NotCoordinator)))
        assertThat(f.controller.activeSession).isNull()
    }

    @Test
    fun `a failure while the rest is queued keeps the session and says so`() = test { f ->
        f.speaker.failOn = { action, nth ->
            if (action == "AddURIToQueue" && nth == 5) SonosResult.Err(SonosFailure.Upnp("AddURIToQueue", UpnpErrorKind.ResourceNotFound)) else null
        }

        val session = (f.controller.connect(device, queue(current = 0, positionMs = 0)) as SonosResult.Ok).value
        advanceUntilIdle()

        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Active(device))
        assertThat(session.loadFailure.value).isEqualTo(SonosFailure.Upnp("AddURIToQueue", UpnpErrorKind.ResourceNotFound))
        assertThat(f.locks.held).isTrue()
    }

    @Test
    fun `a speaker that never starts the song ends in a timeout`() = test { f ->
        f.speaker.hangOn = "Play"

        val result = f.controller.connect(device, queue())

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.Timeout))
        assertThat(f.subscriber.unsubscribed).hasSize(2)
        assertThat(f.locks.held).isFalse()
    }

    @Test
    fun `a song that cannot be served as the current one is refused before touching the speaker`() = test { f ->
        val result = f.controller.connect(device, queue(listOf(QueueEntry("..", "x", null, null, 1, null, false), QueueEntry("ok", "y", null, null, 1, null, false)), current = 0))

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.NotOnSpeaker))
        assertThat(f.speaker.calls).isEmpty()
        assertThat(f.locks.held).isFalse()
    }

    @Test
    fun `songs that cannot be served are left out and the others keep their place`() = test { f ->
        val songs = listOf("a", "..", "b", "c").map { QueueEntry(it, it, null, null, 100_000, null, false) }

        val session = (f.controller.connect(device, queue(songs, current = 2, positionMs = 0)) as SonosResult.Ok).value

        assertThat(session.included).containsExactly(0, 2, 3).inOrder()
        assertThat(f.speaker.current).isEqualTo(2)
    }

    @Test
    fun `connecting again replaces the first attempt without leaving anything behind`() = test { f ->
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        f.server.answer = {
            if (calls++ == 0) gate.await()
            SonosResult.Ok("http://192.168.1.20:8080")
        }
        val first = CompletableDeferred<SonosResult<ActiveSession>>()
        backgroundScope.launch { first.complete(f.controller.connect(device, queue())) }
        runCurrent()

        val second = f.controller.connect(device, queue(current = 1, positionMs = 0))

        assertThat(second).isInstanceOf(SonosResult.Ok::class.java)
        assertThat(first.await()).isEqualTo(SonosResult.Err(SonosFailure.Superseded))
        assertThat(f.locks.held).isTrue()
        assertThat(f.server.log.count { it == "release" }).isEqualTo(0) // the server stays for the session that is running
        assertThat(f.subscriber.subscribed).hasSize(2)
        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Active(device))
    }

    @Test
    fun `connecting to another speaker while one plays hands the first one back cleanly`() = test { f ->
        f.controller.connect(device, queue())
        val other = SonosDevice(SonosUdn("RINCON_2"), "Salón", "192.168.1.51", 1)

        val result = f.controller.connect(other, queue())

        assertThat(result).isInstanceOf(SonosResult.Ok::class.java)
        assertThat(f.subscriber.unsubscribed).hasSize(2)
        assertThat(f.subscriber.subscribed).hasSize(4)
        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Active(other))
        assertThat(f.locks.held).isTrue()
    }

    @Test
    fun `disconnecting while connecting ends the attempt and gives nothing back`() = test { f ->
        f.server.answer = { awaitCancellation() }
        val attempt = CompletableDeferred<SonosResult<ActiveSession>>()
        backgroundScope.launch { attempt.complete(f.controller.connect(device, queue())) }
        runCurrent()

        val handBack = f.controller.disconnect()

        assertThat(handBack).isNull()
        assertThat(attempt.await()).isEqualTo(SonosResult.Err(SonosFailure.Superseded))
        assertThat(f.locks.held).isFalse()
        assertThat(f.server.log.last()).isEqualTo("release")
        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Idle)
    }

    @Test
    fun `disconnecting gives back the song and second the speaker is at, pauses it and leaves its queue`() = test { f ->
        f.controller.connect(device, queue(current = 2, positionMs = 95_000))
        advanceTimeBy(10_000)
        runCurrent()
        val callsBefore = f.speaker.calls.size

        val handBack = f.controller.disconnect()

        assertThat(handBack).isEqualTo(HandBack(songId = "s2", positionMs = 105_000, wasPlaying = true))
        assertThat(f.speaker.transport).isEqualTo("PAUSED_PLAYBACK")
        assertThat(f.speaker.actions.drop(callsBefore)).doesNotContain("RemoveAllTracksFromQueue")
        assertThat(f.speaker.queue).hasSize(5)
        assertThat(f.subscriber.unsubscribed).containsExactly(SonosService.AV_TRANSPORT, SonosService.RENDERING_CONTROL)
        assertThat(f.server.log.last()).isEqualTo("release")
        assertThat(f.locks.log.last()).isEqualTo("release")
        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Idle)
        assertThat(f.controller.activeSession).isNull()
    }

    @Test
    fun `the song handed back is the one the speaker moved on to by itself`() = test { f ->
        f.controller.connect(device, queue(current = 0, positionMs = 0))
        f.speaker.current = 4
        f.speaker.call("Seek", listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to "0:00:30"))

        val handBack = f.controller.disconnect()

        assertThat(handBack?.songId).isEqualTo("s3")
        assertThat(handBack?.positionMs).isEqualTo(30_000)
    }

    @Test
    fun `a speaker that was already paused is handed back as not playing and is not touched again`() = test { f ->
        f.controller.connect(device, queue(play = false, positionMs = 12_000))
        val before = f.speaker.actions.count { it == "Pause" }

        val handBack = f.controller.disconnect()

        assertThat(handBack?.wasPlaying).isFalse()
        assertThat(f.speaker.actions.count { it == "Pause" }).isEqualTo(before)
    }

    @Test
    fun `something that is not ours playing on the speaker is neither paused nor handed back as a song`() = test { f ->
        f.controller.connect(device, queue())
        f.speaker.queue[f.speaker.current - 1] = "x-sonos-spotify:spotify%3atrack%3a1"
        val pausesBefore = f.speaker.actions.count { it == "Pause" }

        val handBack = f.controller.disconnect()

        assertThat(handBack?.songId).isNull()
        assertThat(f.speaker.actions.count { it == "Pause" }).isEqualTo(pausesBefore)
        assertThat(f.speaker.transport).isEqualTo("PLAYING")
    }

    @Test
    fun `a speaker that cannot be reached still lets go of everything and hands back what was known`() = test { f ->
        f.controller.connect(device, queue(current = 1, positionMs = 20_000))
        advanceTimeBy(5_000)
        runCurrent()
        f.speaker.failOn = { _, _ -> SonosResult.Err(SonosFailure.Timeout) }

        val handBack = f.controller.disconnect()

        assertThat(handBack?.songId).isEqualTo("s1")
        assertThat(handBack?.positionMs).isAtLeast(24_000)
        assertThat(f.locks.held).isFalse()
        assertThat(f.server.log.last()).isEqualTo("release")
        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Idle)
    }

    @Test
    fun `a speaker that hangs does not hold the disconnect for more than a few seconds`() = test { f ->
        f.controller.connect(device, queue())
        f.speaker.hangOn = "Pause"
        val started = testScheduler.currentTime

        f.controller.disconnect()

        assertThat(testScheduler.currentTime - started).isAtMost(10_000)
        assertThat(f.locks.held).isFalse()
    }

    @Test
    fun `disconnecting with no session does nothing`() = test { f ->
        val handBack = f.controller.disconnect()

        assertThat(handBack).isNull()
        assertThat(f.speaker.calls).isEmpty()
        assertThat(f.server.log).isEmpty()
        assertThat(f.locks.log).isEmpty()
    }

    @Test
    fun `it can connect again after disconnecting`() = test { f ->
        f.controller.connect(device, queue())
        f.controller.disconnect()

        val result = f.controller.connect(device, queue())

        assertThat(result).isInstanceOf(SonosResult.Ok::class.java)
        assertThat(f.locks.held).isTrue()
        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Active(device))
    }

    @Test
    fun `when the scope ends everything is let go`() = test { f ->
        f.controller.connect(device, queue())

        f.scope.cancel()
        runCurrent()
        advanceUntilIdle()

        assertThat(f.locks.held).isFalse()
        assertThat(f.server.log.last()).isEqualTo("release")
        assertThat(f.subscriber.unsubscribed).hasSize(2)
    }

    @Test
    fun `when the caller is cancelled while connecting nothing is left behind`() = test { f ->
        f.server.answer = { awaitCancellation() }
        val caller = backgroundScope.launch { f.controller.connect(device, queue()) }
        runCurrent()

        caller.cancel()
        runCurrent()
        advanceUntilIdle()

        assertThat(f.locks.held).isFalse()
        assertThat(f.server.log.last()).isEqualTo("release")
    }
}
