package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.SonosFixtures
import com.theveloper.pixelplay.data.sonos.model.TransportState
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.SonosSubscriber
import com.theveloper.pixelplay.data.sonos.net.Subscription
import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind
import com.theveloper.pixelplay.data.sonos.server.SonosEventRouter
import com.theveloper.pixelplay.data.sonos.server.SonosNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SonosEventSubscriptionsTest {

    private val host = "192.168.1.50"
    private val phone = "http://192.168.1.20:8080"
    private val avBody = SonosFixtures.notify("AVTransport_0")
    private val renderingBody = SonosFixtures.notify("RenderingControl_0")

    private val av = SonosService.AV_TRANSPORT
    private val rendering = SonosService.RENDERING_CONTROL
    private val forgotten = SonosResult.Err(SonosFailure.Upnp("SUBSCRIBE", UpnpErrorKind.PreconditionFailed))

    private class Call(val atMs: Long, val service: SonosService, val sid: String? = null, val callbackUrl: String? = null, val timeoutSeconds: Int? = null)

    private class FakeSubscriber(private val now: () -> Long) : SonosSubscriber {
        val subscribes = mutableListOf<Call>()
        val renews = mutableListOf<Call>()
        val unsubscribes = mutableListOf<Call>()
        val lastSid = mutableMapOf<SonosService, String>()
        private val attempts = mutableMapOf<SonosService, Int>()

        var onSubscribe: (service: SonosService, attempt: Int) -> SonosResult<Subscription> =
            { service, attempt -> SonosResult.Ok(Subscription("sid-${service.name}-$attempt", 120)) }
        var onRenew: (service: SonosService, sid: String, attempt: Int) -> SonosResult<Subscription> =
            { _, sid, _ -> SonosResult.Ok(Subscription(sid, 120)) }
        var onUnsubscribe: suspend () -> Unit = {}
        var beforeSubscribeReturns: suspend (SonosService) -> Unit = {}
        private val renewAttempts = mutableMapOf<SonosService, Int>()

        override suspend fun subscribe(host: String, service: SonosService, callbackUrl: String, timeoutSeconds: Int): SonosResult<Subscription> {
            subscribes += Call(now(), service, callbackUrl = callbackUrl)
            val attempt = attempts.merge(service, 1, Int::plus)!!
            beforeSubscribeReturns(service)
            return onSubscribe(service, attempt).also { if (it is SonosResult.Ok) lastSid[service] = it.value.sid }
        }

        override suspend fun renew(host: String, service: SonosService, sid: String, timeoutSeconds: Int): SonosResult<Subscription> {
            renews += Call(now(), service, sid, timeoutSeconds = timeoutSeconds)
            val attempt = renewAttempts.merge(service, 1, Int::plus)!!
            return onRenew(service, sid, attempt)
        }

        override suspend fun unsubscribe(host: String, service: SonosService, sid: String): SonosResult<Unit> {
            unsubscribes += Call(now(), service, sid)
            onUnsubscribe()
            return SonosResult.Ok(Unit)
        }
    }

    private class Fixture(val scope: TestScope, val host: String) {
        val router = SonosEventRouter()
        val subscriber = FakeSubscriber { scope.currentTime }
        var callbackBase: String? = "http://192.168.1.20:8080"
        val events = mutableListOf<SonosEvent>()

        fun start(gate: CompletableDeferred<Unit>? = null): Job {
            val subscriptions = SonosEventSubscriptions(subscriber, router) { callbackBase }
            return scope.backgroundScope.launch {
                subscriptions.start(host).collect {
                    gate?.await()
                    events += it
                }
            }
        }

        fun keyOf(service: SonosService): String =
            subscriber.subscribes.last { it.service == service }.callbackUrl!!.substringAfterLast('/')

        /** Delivers a NOTIFY the way the media server does, by default with the SID the speaker handed out last. */
        fun notify(service: SonosService, seq: Long?, body: String, sid: String? = subscriber.lastSid[service]) {
            val registration = checkNotNull(router.find(keyOf(service))) { "no registration for $service" }
            assertThat(registration.expectedHost).isEqualTo(host)
            registration.sink.onNotification(SonosNotification(sid, seq, body))
        }

        inline fun <reified T : SonosEvent> of(): List<T> = events.filterIsInstance<T>()
    }

    private fun test(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        block(Fixture(this, host))
    }

    @Test
    fun `subscribes to both services with a callback url made of the phone address and a private key`() = test { f ->
        f.start()
        runCurrent()

        assertThat(f.subscriber.subscribes.map { it.service }).containsExactly(av, rendering)
        for (call in f.subscriber.subscribes) {
            assertThat(call.callbackUrl).startsWith("$phone/sonos/events/")
            val key = call.callbackUrl!!.substringAfterLast('/')
            assertThat(key).hasLength(32)
            assertThat(f.router.find(key)!!.expectedHost).isEqualTo(host)
        }
        assertThat(f.keyOf(av)).isNotEqualTo(f.keyOf(rendering))
    }

    @Test
    fun `the first AVTransport and RenderingControl notifications become events with their real values`() = test { f ->
        f.start()
        runCurrent()

        f.notify(av, 0, avBody)
        f.notify(rendering, 0, renderingBody)
        runCurrent()

        val avEvent = f.of<SonosEvent.AvTransport>().single()
        assertThat(avEvent.seq).isEqualTo(0)
        assertThat(avEvent.change.transportState).isEqualTo(TransportState.STOPPED)
        assertThat(f.of<SonosEvent.Rendering>().single().change.volume).isEqualTo(8)
        assertThat(f.of<SonosEvent.NeedFullResync>()).isEmpty()
    }

    @Test
    fun `a gap in SEQ asks for a full resync after delivering the event`() = test { f ->
        f.start()
        runCurrent()

        f.notify(av, 0, avBody)
        f.notify(av, 1, avBody)
        f.notify(av, 3, avBody)
        runCurrent()

        assertThat(f.events.map { it::class }).containsExactly(
            SonosEvent.AvTransport::class, SonosEvent.AvTransport::class, SonosEvent.AvTransport::class,
            SonosEvent.NeedFullResync::class,
        ).inOrder()
    }

    @Test
    fun `a first notification that is not SEQ 0 is a gap`() = test { f ->
        f.start()
        runCurrent()

        f.notify(av, 4, avBody)
        runCurrent()

        assertThat(f.of<SonosEvent.NeedFullResync>()).hasSize(1)
    }

    @Test
    fun `SEQ going back to zero delivers that event and asks for one resync`() = test { f ->
        f.start()
        runCurrent()

        f.notify(av, 0, avBody)
        f.notify(av, 1, avBody)
        f.notify(av, 0, avBody)
        runCurrent()

        assertThat(f.events.map { it::class }).containsExactly(
            SonosEvent.AvTransport::class, SonosEvent.AvTransport::class, SonosEvent.AvTransport::class,
            SonosEvent.NeedFullResync::class,
        ).inOrder()
    }

    @Test
    fun `a repeated SEQ, zero included, is delivered once and does not ask for a resync`() = test { f ->
        f.start()
        runCurrent()

        f.notify(av, 0, avBody)
        f.notify(av, 0, avBody)
        f.notify(av, 1, avBody)
        f.notify(av, 1, avBody)
        f.notify(rendering, 0, renderingBody)
        f.notify(rendering, 0, renderingBody)
        runCurrent()

        assertThat(f.of<SonosEvent.AvTransport>().map { it.seq }).containsExactly(0L, 1L).inOrder()
        assertThat(f.of<SonosEvent.Rendering>()).hasSize(1)
        assertThat(f.of<SonosEvent.NeedFullResync>()).isEmpty()
    }

    @Test
    fun `a late SEQ is dropped instead of being applied after a newer one`() = test { f ->
        f.start()
        runCurrent()

        for (seq in listOf(0L, 1L, 2L, 4L, 3L)) f.notify(av, seq, avBody)
        runCurrent()

        assertThat(f.of<SonosEvent.AvTransport>().map { it.seq }).containsExactly(0L, 1L, 2L, 4L).inOrder()
        assertThat(f.of<SonosEvent.NeedFullResync>()).hasSize(1)
    }

    @Test
    fun `events without SEQ are delivered and leave the sequence alone`() = test { f ->
        f.start()
        runCurrent()

        f.notify(av, 0, avBody)
        f.notify(av, null, avBody)
        f.notify(av, 1, avBody)
        runCurrent()

        assertThat(f.of<SonosEvent.AvTransport>().map { it.seq }).containsExactly(0L, null, 1L).inOrder()
        assertThat(f.of<SonosEvent.NeedFullResync>()).isEmpty()
    }

    @Test
    fun `each service has its own sequence`() = test { f ->
        f.start()
        runCurrent()

        for (seq in 0L..2L) {
            f.notify(av, seq, avBody)
            f.notify(rendering, seq, renderingBody)
        }
        runCurrent()
        assertThat(f.of<SonosEvent.NeedFullResync>()).isEmpty()

        f.notify(rendering, 4, renderingBody)
        runCurrent()
        assertThat(f.of<SonosEvent.Rendering>().map { it.seq }).containsExactly(0L, 1L, 2L, 4L).inOrder()
        assertThat(f.of<SonosEvent.NeedFullResync>()).hasSize(1)
    }

    @Test
    fun `without a SEQ 0 in two seconds the callback is reported unreachable once per service and the subscription stays`() = test { f ->
        f.start()
        runCurrent()

        advanceTimeBy(1_999)
        assertThat(f.events).isEmpty()
        advanceTimeBy(2)
        runCurrent()
        advanceTimeBy(20_000)

        assertThat(f.of<SonosEvent.CallbackUnreachable>().map { it.host }).containsExactly(host, host)
        assertThat(f.subscriber.subscribes).hasSize(2)

        f.notify(av, 0, avBody)
        runCurrent()
        assertThat(f.of<SonosEvent.AvTransport>()).hasSize(1)
    }

    @Test
    fun `only the service that stays silent is reported`() = test { f ->
        f.start()
        runCurrent()

        f.notify(av, 0, avBody)
        advanceTimeBy(5_000)

        assertThat(f.of<SonosEvent.CallbackUnreachable>()).hasSize(1)
    }

    @Test
    fun `a notification within two seconds means the callback is reachable`() = test { f ->
        f.start()
        runCurrent()
        advanceTimeBy(1_000)

        f.notify(av, 0, avBody)
        f.notify(rendering, 0, renderingBody)
        advanceTimeBy(5_000)

        assertThat(f.of<SonosEvent.CallbackUnreachable>()).isEmpty()
    }

    @Test
    fun `a notification of somebody else does not count as heard`() = test { f ->
        f.start()
        runCurrent()

        f.notify(av, 0, avBody, sid = "uuid:somebody-else")
        advanceTimeBy(2_001)
        runCurrent()

        assertThat(f.of<SonosEvent.AvTransport>()).isEmpty()
        assertThat(f.of<SonosEvent.CallbackUnreachable>()).hasSize(2)
    }

    @Test
    fun `a notification that arrives before subscribe returns is not lost`() = test { f ->
        f.subscriber.beforeSubscribeReturns = { service ->
            if (service == av) f.notify(av, 0, avBody, sid = "sid-${av.name}-1")
        }
        f.start()
        runCurrent()
        advanceTimeBy(3_000)

        assertThat(f.of<SonosEvent.AvTransport>()).hasSize(1)
        assertThat(f.of<SonosEvent.CallbackUnreachable>()).hasSize(1) // only rendering stayed silent
    }

    @Test
    fun `a garbage body still counts as heard and advances the sequence`() = test { f ->
        f.start()
        runCurrent()

        f.notify(av, 0, "<<<not xml")
        f.notify(av, 1, avBody)
        advanceTimeBy(5_000)

        assertThat(f.of<SonosEvent.AvTransport>().map { it.seq }).containsExactly(1L)
        assertThat(f.of<SonosEvent.NeedFullResync>()).isEmpty()
        assertThat(f.of<SonosEvent.CallbackUnreachable>()).hasSize(1) // rendering only
    }

    @Test
    fun `hostile numbers and metadata inside a valid notification do not break the flow`() = test { f ->
        val hostile = """<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0"><e:property><LastChange>""" +
            "&lt;Event xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/AVT/&quot;&gt;&lt;InstanceID val=&quot;0&quot;&gt;" +
            "&lt;CurrentTrackDuration val=&quot;99999999999:99:99&quot;/&gt;" +
            "&lt;CurrentTrackMetaData val=&quot;&amp;lt;DIDL-Lite&quot;/&gt;" +
            "&lt;CurrentTrack val=&quot;99999999999999999999&quot;/&gt;&lt;/InstanceID&gt;&lt;/Event&gt;" +
            "</LastChange></e:property></e:propertyset>"
        f.start()
        runCurrent()

        f.notify(av, 0, hostile)
        f.notify(av, 1, avBody)
        runCurrent()

        assertThat(f.of<SonosEvent.AvTransport>().map { it.seq }).containsExactly(0L, 1L).inOrder()
    }

    @Test
    fun `the subscription is renewed at half of the granted timeout with its own SID`() = test { f ->
        f.start()
        runCurrent()

        advanceTimeBy(59_999)
        assertThat(f.subscriber.renews).isEmpty()
        advanceTimeBy(2)
        runCurrent()

        assertThat(f.subscriber.renews.map { it.service }).containsExactly(av, rendering)
        assertThat(f.subscriber.renews.map { it.atMs }.distinct()).containsExactly(60_000L)
        assertThat(f.subscriber.renews.associate { it.service to it.sid })
            .containsExactly(av, "sid-${av.name}-1", rendering, "sid-${rendering.name}-1")
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(f.subscriber.renews).hasSize(4)
    }

    @Test
    fun `the renewal follows the timeout the speaker granted at subscribe`() = test { f ->
        f.subscriber.onSubscribe = { service, attempt -> SonosResult.Ok(Subscription("sid-${service.name}-$attempt", 1800)) }
        f.start()
        runCurrent()

        advanceTimeBy(899_000)
        assertThat(f.subscriber.renews).isEmpty()
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(f.subscriber.renews).isNotEmpty()
    }

    @Test
    fun `a renewal that grants a different timeout moves the next renewal`() = test { f ->
        f.subscriber.onRenew = { _, sid, _ -> SonosResult.Ok(Subscription(sid, 300)) }
        f.start()
        runCurrent()

        advanceTimeBy(60_001)
        runCurrent()
        assertThat(f.subscriber.renews.filter { it.service == av }).hasSize(1)
        advanceTimeBy(149_000)
        assertThat(f.subscriber.renews.filter { it.service == av }).hasSize(1)
        advanceTimeBy(2_000)
        runCurrent()

        val avRenews = f.subscriber.renews.filter { it.service == av }
        assertThat(avRenews.map { it.atMs }).containsExactly(60_000L, 210_000L).inOrder()
        assertThat(avRenews.map { it.timeoutSeconds }).containsExactly(120, 300).inOrder()
    }

    @Test
    fun `a speaker that forgot the subscription gets a new one, no unsubscribe, and a resync with the first notification`() = test { f ->
        f.subscriber.onRenew = { _, _, _ -> forgotten }
        f.start()
        runCurrent()
        f.notify(av, 0, avBody)
        runCurrent()
        assertThat(f.of<SonosEvent.NeedFullResync>()).isEmpty()

        advanceTimeBy(60_001)
        runCurrent()

        assertThat(f.subscriber.subscribes.filter { it.service == av }).hasSize(2)
        assertThat(f.subscriber.unsubscribes).isEmpty()
        f.notify(av, 0, avBody)
        runCurrent()
        assertThat(f.of<SonosEvent.NeedFullResync>()).hasSize(1)
    }

    @Test
    fun `the SID of an abandoned subscription is ignored after subscribing again`() = test { f ->
        f.subscriber.onRenew = { _, _, _ -> forgotten }
        f.start()
        runCurrent()
        advanceTimeBy(60_001)
        runCurrent()

        f.notify(av, 0, avBody, sid = "sid-${av.name}-1")
        runCurrent()

        assertThat(f.of<SonosEvent.AvTransport>()).isEmpty()
    }

    @Test
    fun `a renew that fails once because of the network is retried and the subscription is kept`() = test { f ->
        f.subscriber.onRenew = { _, sid, attempt -> if (attempt == 1) SonosResult.Err(SonosFailure.Timeout) else SonosResult.Ok(Subscription(sid, 120)) }
        f.start()
        runCurrent()

        advanceTimeBy(65_001)
        runCurrent()

        assertThat(f.subscriber.renews.filter { it.service == av }.map { it.atMs }).containsExactly(60_000L, 65_000L).inOrder()
        assertThat(f.subscriber.subscribes).hasSize(2)
        assertThat(f.subscriber.unsubscribes).isEmpty()
        assertThat(f.of<SonosEvent.CallbackUnreachable>()).hasSize(2) // only the watchdog of the first subscription
    }

    @Test
    fun `a renew that keeps failing is retried until the subscription would expire, then replaced and the old one given up`() = test { f ->
        f.subscriber.onRenew = { _, _, _ -> SonosResult.Err(SonosFailure.Timeout) }
        f.start()
        runCurrent()
        advanceTimeBy(20_000) // silence already reported by the watchdog
        val reportedBefore = f.of<SonosEvent.CallbackUnreachable>().size

        advanceTimeBy(100_000)
        runCurrent()

        val avRenews = f.subscriber.renews.filter { it.service == av }.map { it.atMs }
        assertThat(avRenews).containsExactly(
            60_000L, 65_000L, 70_000L, 75_000L, 80_000L, 85_000L, 90_000L, 95_000L, 100_000L, 105_000L, 110_000L, 115_000L,
        ).inOrder()
        val oldUnsubscribe = f.subscriber.unsubscribes.single { it.service == av }
        assertThat(oldUnsubscribe.sid).isEqualTo("sid-${av.name}-1")
        assertThat(oldUnsubscribe.atMs).isEqualTo(115_000L)
        assertThat(f.subscriber.subscribes.filter { it.service == av }).hasSize(2)
        assertThat(f.of<SonosEvent.CallbackUnreachable>().size).isGreaterThan(reportedBefore)
    }

    @Test
    fun `losing a subscription tells the consumer that events stopped arriving`() = test { f ->
        f.subscriber.onRenew = { _, _, _ -> forgotten }
        f.start()
        runCurrent()
        f.notify(av, 0, avBody)
        f.notify(rendering, 0, renderingBody)
        advanceTimeBy(5_000)
        assertThat(f.of<SonosEvent.CallbackUnreachable>()).isEmpty()

        advanceTimeBy(55_500) // the renewal at 60 s fails; the watchdog of the new subscription is still 2 s away
        runCurrent()

        assertThat(f.of<SonosEvent.CallbackUnreachable>().map { it.host }).containsExactly(host, host)
    }

    @Test
    fun `a speaker that cannot be subscribed again is reported at once and not only when a notification is missing`() = test { f ->
        f.subscriber.onRenew = { _, _, _ -> forgotten }
        f.subscriber.onSubscribe = { service, attempt ->
            if (attempt >= 2) SonosResult.Err(SonosFailure.ConnectionRefused) else SonosResult.Ok(Subscription("sid-${service.name}-$attempt", 120))
        }
        f.start()
        runCurrent()
        f.notify(av, 0, avBody)
        f.notify(rendering, 0, renderingBody)
        advanceTimeBy(60_001)
        runCurrent()
        assertThat(f.of<SonosEvent.CallbackUnreachable>()).hasSize(2)

        advanceTimeBy(300_000)

        assertThat(f.of<SonosEvent.CallbackUnreachable>()).hasSize(2) // once per loss, not once per failed attempt
    }

    @Test
    fun `when the address of the phone changes the next renewal subscribes again with the new address`() = test { f ->
        f.start()
        runCurrent()
        f.callbackBase = "http://192.168.7.9:9999"

        advanceTimeBy(60_001)
        runCurrent()

        val avSubscribes = f.subscriber.subscribes.filter { it.service == av }
        assertThat(avSubscribes).hasSize(2)
        assertThat(avSubscribes[1].callbackUrl).startsWith("http://192.168.7.9:9999/sonos/events/")
        assertThat(avSubscribes[1].callbackUrl!!.substringAfterLast('/')).isEqualTo(avSubscribes[0].callbackUrl!!.substringAfterLast('/'))
        assertThat(f.subscriber.unsubscribes.filter { it.service == av }.map { it.sid }).containsExactly("sid-${av.name}-1")
        f.notify(av, 0, avBody)
        runCurrent()
        assertThat(f.of<SonosEvent.NeedFullResync>()).hasSize(1)
    }

    @Test
    fun `when the phone has no address anymore nothing is renewed and it subscribes again once it is back`() = test { f ->
        f.start()
        runCurrent()
        f.callbackBase = null

        advanceTimeBy(60_001)
        runCurrent()
        val before = f.subscriber.subscribes.size
        advanceTimeBy(5_000)
        assertThat(f.subscriber.subscribes).hasSize(before)

        f.callbackBase = phone
        advanceTimeBy(31_000)
        runCurrent()

        assertThat(f.subscriber.subscribes.filter { it.service == av }).hasSize(2)
        f.notify(av, 0, avBody)
        runCurrent()
        assertThat(f.of<SonosEvent.NeedFullResync>()).hasSize(1)
    }

    @Test
    fun `repeated failures to subscribe wait 1, 2, 4 seconds up to a cap of 30`() = test { f ->
        f.subscriber.onSubscribe = { _, _ -> SonosResult.Err(SonosFailure.ConnectionRefused) }
        f.start()
        runCurrent()

        advanceTimeBy(120_000)
        runCurrent()

        val avAttempts = f.subscriber.subscribes.filter { it.service == av }.map { it.atMs }
        assertThat(avAttempts.take(8)).containsExactly(0L, 1_000L, 3_000L, 7_000L, 15_000L, 31_000L, 61_000L, 91_000L).inOrder()
    }

    @Test
    fun `the pause starts again at one second after a subscription succeeded`() = test { f ->
        f.subscriber.onSubscribe = { service, attempt ->
            if (service == av && attempt in listOf(1, 2, 3, 5)) SonosResult.Err(SonosFailure.ConnectionRefused)
            else SonosResult.Ok(Subscription("sid-${service.name}-$attempt", 120))
        }
        f.subscriber.onRenew = { _, _, _ -> forgotten }
        f.start()
        runCurrent()

        advanceTimeBy(80_000)
        runCurrent()

        // fails at 0, 1 s, 3 s; works at 7 s; the renewal at 67 s fails with 412; the new attempt fails at 67 s; next one 1 s later.
        assertThat(f.subscriber.subscribes.filter { it.service == av }.map { it.atMs })
            .containsExactly(0L, 1_000L, 3_000L, 7_000L, 67_000L, 68_000L).inOrder()
    }

    @Test
    fun `a failing service does not hold back the other one`() = test { f ->
        f.subscriber.onSubscribe = { service, attempt ->
            if (service == rendering) SonosResult.Err(SonosFailure.ConnectionRefused)
            else SonosResult.Ok(Subscription("sid-${service.name}-$attempt", 120))
        }
        val job = f.start()
        runCurrent()
        f.notify(av, 0, avBody)
        advanceTimeBy(3_500)
        runCurrent()

        assertThat(f.of<SonosEvent.AvTransport>()).hasSize(1)
        assertThat(f.subscriber.subscribes.filter { it.service == rendering }.map { it.atMs })
            .containsExactly(0L, 1_000L, 3_000L).inOrder()

        job.cancel()
        runCurrent()
        assertThat(f.subscriber.unsubscribes.map { it.service }).containsExactly(av)
    }

    @Test
    fun `a missing callback address is retried later and nothing is sent without it`() = test { f ->
        f.callbackBase = null
        f.start()
        runCurrent()
        advanceTimeBy(3_000)
        assertThat(f.subscriber.subscribes).isEmpty()

        f.callbackBase = phone
        advanceTimeBy(10_000)
        runCurrent()

        assertThat(f.subscriber.subscribes.map { it.service }.toSet()).containsExactly(av, rendering)
    }

    @Test
    fun `cancelling unsubscribes both services and removes their keys`() = test { f ->
        val job = f.start()
        runCurrent()
        val keys = listOf(f.keyOf(av), f.keyOf(rendering))

        job.cancel()
        runCurrent()

        assertThat(f.subscriber.unsubscribes.associate { it.service to it.sid })
            .containsExactly(av, "sid-${av.name}-1", rendering, "sid-${rendering.name}-1")
        keys.forEach { assertThat(f.router.find(it)).isNull() }
    }

    @Test
    fun `cancelling while the first subscribe keeps failing leaves nothing behind`() = test { f ->
        f.subscriber.onSubscribe = { _, _ -> SonosResult.Err(SonosFailure.Timeout) }
        val job = f.start()
        runCurrent()
        advanceTimeBy(500)
        val keys = listOf(f.keyOf(av), f.keyOf(rendering))

        job.cancel()
        runCurrent()

        assertThat(job.isCompleted).isTrue()
        assertThat(f.subscriber.unsubscribes).isEmpty()
        keys.forEach { assertThat(f.router.find(it)).isNull() }
    }

    @Test
    fun `cancelling while a lost subscription is being replaced does not unsubscribe the dead SID again`() = test { f ->
        f.subscriber.onRenew = { _, _, _ -> forgotten }
        f.subscriber.onSubscribe = { service, attempt ->
            if (attempt >= 2) SonosResult.Err(SonosFailure.Timeout) else SonosResult.Ok(Subscription("sid-${service.name}-$attempt", 120))
        }
        val job = f.start()
        runCurrent()
        advanceTimeBy(60_500)
        runCurrent()

        job.cancel()
        runCurrent()

        assertThat(f.subscriber.unsubscribes).isEmpty()
    }

    @Test
    fun `a speaker that never answers the unsubscribe cannot hold the cleanup for more than two seconds`() = test { f ->
        f.subscriber.onUnsubscribe = { awaitCancellation() }
        val job = f.start()
        runCurrent()
        val keys = listOf(f.keyOf(av), f.keyOf(rendering))

        job.cancel()
        runCurrent()
        advanceTimeBy(1_999)
        assertThat(job.isCompleted).isFalse()
        advanceTimeBy(2)
        runCurrent()

        assertThat(job.isCompleted).isTrue()
        keys.forEach { assertThat(f.router.find(it)).isNull() }
    }

    @Test
    fun `a consumer that stops reading cannot make the queue grow without bound`() = test { f ->
        val gate = CompletableDeferred<Unit>()
        f.start(gate)
        runCurrent()

        for (seq in 0L until 500L) f.notify(av, seq, avBody)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertThat(f.of<SonosEvent.AvTransport>().size).isLessThan(300)
        assertThat(f.of<SonosEvent.NeedFullResync>()).isNotEmpty()
    }
}
