package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
        var hangFrom = 1
        var throwOn: String? = null
        private val counts = mutableMapOf<String, Int>()

        suspend fun call(action: String, arguments: List<Pair<String, String>>) { call("x", SonosService.AV_TRANSPORT, action, arguments, 0) }
        val actions get() = calls.map { it.first }
        val commands get() = actions.filterNot { it.startsWith("Get") }
        fun positionMs() = if (transport == "PLAYING") positionBase + (now() - playingSince) else positionBase

        override suspend fun call(host: String, service: SonosService, action: String, arguments: List<Pair<String, String>>, timeoutMs: Long): SonosResult<Map<String, String>> {
            calls += action to arguments
            val nth = counts.merge(action, 1, Int::plus)!!
            if (throwOn == action) throw IllegalStateException("boom")
            if (hangOn == action && nth >= hangFrom) awaitCancellation()
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

    private class FakeNetwork(private val now: () -> Long) : SonosTransport {
        private val speakers = mutableMapOf<String, FakeSpeaker>()
        fun at(host: String) = speakers.getOrPut(host) { FakeSpeaker(now) }
        override suspend fun call(host: String, service: SonosService, action: String, arguments: List<Pair<String, String>>, timeoutMs: Long) =
            at(host).call(host, service, action, arguments, timeoutMs)
    }

    private class FakeSubscriber : SonosSubscriber {
        val subscribed = CopyOnWriteArrayList<SonosService>()
        val unsubscribed = CopyOnWriteArrayList<SonosService>()
        val callbackKeys = CopyOnWriteArrayList<String>()
        override suspend fun subscribe(host: String, service: SonosService, callbackUrl: String, timeoutSeconds: Int): SonosResult<Subscription> {
            subscribed += service
            callbackKeys += callbackUrl.substringAfterLast('/')
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
        val network = FakeNetwork { test.currentTime }
        val speaker = network.at(device.host)
        val subscriber = FakeSubscriber()
        val server = FakeServer()
        val locks = FakeLocks()
        val holder = SonosSessionHolder()
        val router = SonosEventRouter()
        val access = SonosMediaAccess { SonosServerIdentity(token, 8080) }
        val controller = SonosSessionController(network, subscriber, router, access, server, locks, holder, { test.currentTime }, scope)
        val connections = mutableListOf<SonosConnection>()
    }

    private fun test(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        val fixture = Fixture(this, device, token)
        backgroundScope.launch { fixture.holder.state.collect { fixture.connections += it } }
        runCurrent()
        block(fixture)
    }

    /** Tasks of the background scope do not count for advanceUntilIdle, so time is moved on by hand. */
    private suspend fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
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
        settle()

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
        settle()

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
        settle()

        assertThat(f.locks.held).isFalse()
        assertThat(f.server.log.last()).isEqualTo("release")
    }

    private val other = SonosDevice(SonosUdn("RINCON_2"), "Salón", "192.168.1.51", 1)

    @Test
    fun `a disconnect that is still handing back does not take the server or the locks from a connect that follows`() = test { f ->
        f.controller.connect(device, queue())
        f.speaker.hangOn = "Pause"
        val disconnecting = backgroundScope.launch { f.controller.disconnect() }
        runCurrent()

        val result = f.controller.connect(other, queue())
        disconnecting.join()

        assertThat(result).isInstanceOf(SonosResult.Ok::class.java)
        assertThat(f.locks.held).isTrue()
        assertThat(f.server.log.last()).isEqualTo("acquire:192.168.1.51")
        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Active(other))
        assertThat(f.controller.activeSession).isNotNull()
    }

    @Test
    fun `a disconnect while a replacement is starting ends everything and the replacement says it was superseded`() = test { f ->
        f.controller.connect(device, queue())
        f.server.answer = { awaitCancellation() }
        val replacement = CompletableDeferred<SonosResult<ActiveSession>>()
        backgroundScope.launch { replacement.complete(f.controller.connect(other, queue())) }
        runCurrent()

        f.controller.disconnect()

        assertThat(replacement.await()).isEqualTo(SonosResult.Err(SonosFailure.Superseded))
        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Idle)
        assertThat(f.locks.held).isFalse()
        assertThat(f.server.log.last()).isEqualTo("release")
        assertThat(f.controller.activeSession).isNull()
    }

    @Test
    fun `a caller cancelled while it replaces a session leaves nothing running`() = test { f ->
        f.controller.connect(device, queue())
        f.speaker.hangOn = "Pause"
        val caller = backgroundScope.launch { f.controller.connect(other, queue()) }
        runCurrent()

        caller.cancel()
        settle()

        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Idle)
        assertThat(f.locks.held).isFalse()
        assertThat(f.server.log.last()).isEqualTo("release")
        assertThat(f.controller.activeSession).isNull()
    }

    @Test
    fun `an older attempt that fails never overwrites the state of the newer session`() = test { f ->
        val gate = CompletableDeferred<Unit>()
        var first = true
        f.server.answer = {
            if (first) {
                first = false
                gate.await()
                SonosResult.Err(SonosFailure.ServerUnavailable)
            } else {
                SonosResult.Ok("http://192.168.1.20:8080")
            }
        }
        val older = CompletableDeferred<SonosResult<ActiveSession>>()
        backgroundScope.launch { older.complete(f.controller.connect(device, queue())) }
        runCurrent()

        val newer = f.controller.connect(other, queue())
        gate.complete(Unit)

        assertThat(newer).isInstanceOf(SonosResult.Ok::class.java)
        assertThat(older.await()).isEqualTo(SonosResult.Err(SonosFailure.Superseded))
        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Active(other))
        assertThat(f.locks.held).isTrue()
    }

    @Test
    fun `replacing a session pauses the speaker that was playing and stops following it`() = test { f ->
        f.controller.connect(device, queue())

        f.controller.connect(other, queue())
        val callsAfterSwitch = f.speaker.calls.size
        advanceTimeBy(60_000)
        runCurrent()

        assertThat(f.speaker.transport).isEqualTo("PAUSED_PLAYBACK")
        assertThat(f.speaker.calls.size).isEqualTo(callsAfterSwitch)
        assertThat(f.network.at(other.host).transport).isEqualTo("PLAYING")
    }

    @Test
    fun `a disconnect while the rest of the queue is being loaded stops the loading and still pauses and hands back`() = test { f ->
        f.speaker.hangOn = "AddURIToQueue"
        f.speaker.hangFrom = 3
        val session = (f.controller.connect(device, queue(current = 0, positionMs = 0)) as SonosResult.Ok).value
        val addsBefore = f.speaker.actions.count { it == "AddURIToQueue" }

        val handBack = f.controller.disconnect()

        assertThat(handBack?.songId).isEqualTo("s0")
        assertThat(handBack?.wasPlaying).isTrue()
        assertThat(f.speaker.transport).isEqualTo("PAUSED_PLAYBACK")
        assertThat(f.speaker.actions.count { it == "AddURIToQueue" }).isEqualTo(addsBefore)
        assertThat(session.loadFailure.value).isNull()
    }

    @Test
    fun `a speaker that is loading the song counts as playing and is paused`() = test { f ->
        f.controller.connect(device, queue())
        f.speaker.transport = "TRANSITIONING"

        val handBack = f.controller.disconnect()

        assertThat(handBack?.wasPlaying).isTrue()
        assertThat(f.speaker.transport).isEqualTo("PAUSED_PLAYBACK")
    }

    @Test
    fun `two disconnects at once hand back once and let go once`() = test { f ->
        f.controller.connect(device, queue())
        val first = CompletableDeferred<HandBack?>()
        val second = CompletableDeferred<HandBack?>()

        backgroundScope.launch { first.complete(f.controller.disconnect()) }
        backgroundScope.launch { second.complete(f.controller.disconnect()) }
        settle()

        assertThat(listOf(first.await(), second.await()).count { it != null }).isEqualTo(1)
        assertThat(f.server.log.count { it == "release" }).isEqualTo(1)
        assertThat(f.locks.log.count { it == "release" }).isEqualTo(1)
        assertThat(f.speaker.actions.count { it == "Pause" }).isEqualTo(1)
    }

    @Test
    fun `a queue of more than a thousand sends and publishes only the window around the current song`() = test { f ->
        val songs = List(1500) { QueueEntry("s$it", "Song $it", null, null, 100_000, null, false) }

        val session = (f.controller.connect(device, queue(songs, current = 1200, positionMs = 0)) as SonosResult.Ok).value
        settle()

        assertThat(session.window).isEqualTo(1100..1499)
        assertThat(f.speaker.queue).hasSize(400)
        assertThat(f.speaker.current).isEqualTo(101)
        assertThat(f.speaker.queue[100]).startsWith("http://192.168.1.20:8080/sonos/song/s1200?")
        assertThat(f.access.authorize(token, "s1100")).isEqualTo(AccessDecision.Allowed)
        assertThat(f.access.authorize(token, "s1099")).isEqualTo(AccessDecision.NotPublished)
        assertThat(f.access.authorize(token, "s0")).isEqualTo(AccessDecision.NotPublished)
    }

    @Test
    fun `songs that cannot be served at the start, middle and end leave the others in place and published`() = test { f ->
        val songs = listOf("..", "a", "b", ".", "c", "..").map { QueueEntry(it, it, null, null, 100_000, null, false) }

        val session = (f.controller.connect(device, queue(songs, current = 4, positionMs = 0)) as SonosResult.Ok).value

        assertThat(session.included).containsExactly(1, 2, 4).inOrder()
        assertThat(f.speaker.current).isEqualTo(3)
        for (id in listOf("a", "b", "c")) assertThat(f.access.authorize(token, id)).isEqualTo(AccessDecision.Allowed)
        assertThat(f.access.authorize(token, "..")).isEqualTo(AccessDecision.NotPublished)
    }

    @Test
    fun `the current song being one that cannot be served is refused even with good songs around`() = test { f ->
        val songs = listOf("a", "..", "b").map { QueueEntry(it, it, null, null, 100_000, null, false) }

        val result = f.controller.connect(device, queue(songs, current = 1, positionMs = 0))

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.NotOnSpeaker))
        assertThat(f.speaker.calls).isEmpty()
    }

    @Test
    fun `no key of the router and no published song is left after any way of ending`() = test { f ->
        // disconnecting
        f.controller.connect(device, queue())
        f.controller.disconnect()
        assertWithMessage("after disconnect: keys").that(f.subscriber.callbackKeys.all { f.router.find(it) == null }).isTrue()
        assertWithMessage("after disconnect: published").that(f.access.authorize(token, "s1")).isNotEqualTo(AccessDecision.Allowed)

        // failing
        f.speaker.failOn = { action, _ -> if (action == "SetAVTransportURI") SonosResult.Err(SonosFailure.Timeout) else null }
        f.controller.connect(device, queue())
        assertWithMessage("after failing: keys").that(f.subscriber.callbackKeys.all { f.router.find(it) == null }).isTrue()
        assertWithMessage("after failing: published").that(f.access.authorize(token, "s1")).isNotEqualTo(AccessDecision.Allowed)
        f.speaker.failOn = { _, _ -> null }

        // replacing
        f.controller.connect(device, queue())
        val firstKeys = f.subscriber.callbackKeys.toList()
        f.controller.connect(other, queue(entries(2), current = 0, positionMs = 0))
        assertWithMessage("after replacing: keys").that(firstKeys.all { f.router.find(it) == null }).isTrue()
        assertWithMessage("after replacing: published").that(f.access.authorize(token, "s3")).isNotEqualTo(AccessDecision.Allowed)

        // ending the scope
        val keys = f.subscriber.callbackKeys.toList()
        f.scope.cancel()
        settle()
        assertWithMessage("after the scope ended: keys").that(f.subscriber.callbackKeys.all { f.router.find(it) == null }).isTrue()
        assertThat(keys).isNotEmpty()
    }

    @Test
    fun `a scope that ends while connecting returns at once and lets go`() = test { f ->
        f.server.answer = { awaitCancellation() }
        val attempt = CompletableDeferred<SonosResult<ActiveSession>>()
        backgroundScope.launch { attempt.complete(f.controller.connect(device, queue())) }
        runCurrent()

        f.scope.cancel()
        settle()

        assertThat(attempt.await()).isInstanceOf(SonosResult.Err::class.java)
        assertThat(testScheduler.currentTime).isLessThan(SonosSessionController.START_TIMEOUT_MS)
        assertThat(f.locks.held).isFalse()
        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Idle)
    }

    @Test
    fun `after the scope ended nothing can be connected and nothing is taken`() = test { f ->
        f.scope.cancel()
        settle()

        val result = f.controller.connect(device, queue())

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.Superseded))
        assertThat(f.locks.log).doesNotContain("acquire")
        assertThat(f.server.log.none { it.startsWith("acquire") }).isTrue()
    }

    @Test
    fun `an unexpected exception inside the session is a failure and not a crash`() = test { f ->
        f.speaker.throwOn = "RemoveAllTracksFromQueue"

        val result = f.controller.connect(device, queue())

        assertThat(result).isEqualTo(SonosResult.Err(SonosFailure.Network("IllegalStateException")))
        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Failed(device, SonosFailure.Network("IllegalStateException")))
        assertThat(f.locks.held).isFalse()
    }

    @Test
    fun `after a failure a new connection works with one clean take and release`() = test { f ->
        f.server.answer = { SonosResult.Err(SonosFailure.ServerUnavailable) }
        f.controller.connect(device, queue())
        f.server.answer = { SonosResult.Ok("http://192.168.1.20:8080") }

        val result = f.controller.connect(device, queue())

        assertThat(result).isInstanceOf(SonosResult.Ok::class.java)
        assertThat(f.holder.state.value).isEqualTo(SonosConnection.Active(device))
        assertThat(f.locks.held).isTrue()
        assertThat(f.locks.log).containsExactly("acquire", "release", "acquire").inOrder()
    }

    @Test
    fun `the failure of the rest of the queue is told once and a clean load tells nothing`() = test { f ->
        val clean = (f.controller.connect(device, queue()) as SonosResult.Ok).value
        settle()
        assertThat(clean.loadFailure.value).isNull()
        f.controller.disconnect()
        assertThat(clean.loadFailure.value).isNull()
    }

    @Test
    fun `something from another phone playing on the speaker is neither paused nor handed back`() = test { f ->
        f.controller.connect(device, queue())
        f.speaker.queue[f.speaker.current - 1] = "http://192.168.1.99:8080/sonos/song/s2?v=1&auth=someone-else"

        val handBack = f.controller.disconnect()

        assertThat(handBack?.songId).isNull()
        assertThat(f.speaker.transport).isEqualTo("PLAYING")
    }

    @Test
    fun `a failed connection shows connecting and then failed, without idle in between`() = test { f ->
        f.server.answer = { SonosResult.Err(SonosFailure.ServerUnavailable) }

        f.controller.connect(device, queue())
        runCurrent()

        assertThat(f.connections.drop(1).map { it::class }).containsExactly(SonosConnection.Connecting::class, SonosConnection.Failed::class).inOrder()
    }
}
