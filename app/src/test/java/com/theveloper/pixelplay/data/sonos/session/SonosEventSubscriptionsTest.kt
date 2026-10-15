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
    private val avBody = SonosFixtures.notify("AVTransport_0")
    private val renderingBody = SonosFixtures.notify("RenderingControl_0")

    private class Call(val atMs: Long, val service: SonosService, val sid: String? = null, val callbackUrl: String? = null)

    private class FakeSubscriber(private val now: () -> Long) : SonosSubscriber {
        val subscribes = mutableListOf<Call>()
        val renews = mutableListOf<Call>()
        val unsubscribes = mutableListOf<Call>()
        var onSubscribe: (service: SonosService, attempt: Int) -> SonosResult<Subscription> =
            { service, attempt -> SonosResult.Ok(Subscription("sid-${service.name}-$attempt", 120)) }
        var onRenew: (service: SonosService, sid: String) -> SonosResult<Subscription> =
            { _, sid -> SonosResult.Ok(Subscription(sid, 120)) }
        var onUnsubscribe: suspend () -> Unit = {}

        override suspend fun subscribe(host: String, service: SonosService, callbackUrl: String, timeoutSeconds: Int): SonosResult<Subscription> {
            subscribes += Call(now(), service, callbackUrl = callbackUrl)
            return onSubscribe(service, subscribes.size)
        }

        override suspend fun renew(host: String, service: SonosService, sid: String, timeoutSeconds: Int): SonosResult<Subscription> {
            renews += Call(now(), service, sid)
            return onRenew(service, sid)
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
        private var job: Job? = null

        fun start(): Job {
            val subscriptions = SonosEventSubscriptions(subscriber, router) { callbackBase }
            return scope.backgroundScope.launch { subscriptions.start(host).collect { events += it } }.also { job = it }
        }

        fun keyOf(service: SonosService): String =
            subscriber.subscribes.last { it.service == service }.callbackUrl!!.substringAfterLast('/')

        fun notify(service: SonosService, seq: Long?, body: String, sid: String? = "sid-${service.name}-1", from: String = host) {
            val registration = checkNotNull(router.find(keyOf(service))) { "no registration for $service" }
            assertThat(registration.expectedHost).isEqualTo(from)
            registration.sink.onNotification(SonosNotification(sid, seq, body))
        }
    }

    private fun test(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        block(Fixture(this, host))
    }

    private val av = SonosService.AV_TRANSPORT
    private val rendering = SonosService.RENDERING_CONTROL

    @Test
    fun `subscribes to both services with a callback url made of the phone address and a private key`() = test { f ->
        f.start()
        runCurrent()

        assertThat(f.subscriber.subscribes.map { it.service }).containsExactly(av, rendering)
        for (call in f.subscriber.subscribes) {
            assertThat(call.callbackUrl).startsWith("http://192.168.1.20:8080/sonos/events/")
            val key = call.callbackUrl!!.substringAfterLast('/')
            assertThat(key).hasLength(32)
            assertThat(f.router.find(key)!!.expectedHost).isEqualTo(host)
        }
        assertThat(f.keyOf(av)).isNotEqualTo(f.keyOf(rendering))
    }

    @Test
    fun `the first AVTransport and RenderingControl notifications become events`() = test { f ->
        f.start()
        runCurrent()

        f.notify(av, 0, avBody)
        f.notify(rendering, 0, renderingBody, sid = "sid-${rendering.name}-2")
        runCurrent()

        val avEvent = f.events.filterIsInstance<SonosEvent.AvTransport>().single()
        assertThat(avEvent.seq).isEqualTo(0)
        assertThat(avEvent.change.transportState).isNotNull()
        assertThat(avEvent.change.transportState).isNotEqualTo(TransportState.TRANSITIONING)
        val renderingEvent = f.events.filterIsInstance<SonosEvent.Rendering>().single()
        assertThat(renderingEvent.change.volume).isNotNull()
        assertThat(f.events.filterIsInstance<SonosEvent.NeedFullResync>()).isEmpty()
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
    fun `SEQ going back to zero asks for a full resync`() = test { f ->
        f.start()
        runCurrent()

        f.notify(av, 0, avBody)
        f.notify(av, 1, avBody)
        f.notify(av, 0, avBody)
        runCurrent()

        assertThat(f.events.last()).isEqualTo(SonosEvent.NeedFullResync)
    }

    @Test
    fun `a repeated SEQ is delivered once and does not ask for a resync`() = test { f ->
        f.start()
        runCurrent()

        f.notify(av, 0, avBody)
        f.notify(av, 1, avBody)
        f.notify(av, 1, avBody)
        runCurrent()

        assertThat(f.events.filterIsInstance<SonosEvent.AvTransport>()).hasSize(2)
        assertThat(f.events.filterIsInstance<SonosEvent.NeedFullResync>()).isEmpty()
    }

    @Test
    fun `without a SEQ 0 in two seconds the callback is reported unreachable and the subscription stays`() = test { f ->
        f.start()
        runCurrent()

        advanceTimeBy(1_999)
        assertThat(f.events).isEmpty()
        advanceTimeBy(2)
        runCurrent()

        assertThat(f.events.filterIsInstance<SonosEvent.CallbackUnreachable>().map { it.host }).containsExactly(host, host)
        assertThat(f.subscriber.subscribes).hasSize(2)

        f.notify(av, 0, avBody)
        runCurrent()
        assertThat(f.events.filterIsInstance<SonosEvent.AvTransport>()).hasSize(1)
    }

    @Test
    fun `a notification within two seconds means the callback is reachable`() = test { f ->
        f.start()
        runCurrent()
        advanceTimeBy(1_000)

        f.notify(av, 0, avBody)
        f.notify(rendering, 0, renderingBody, sid = "sid-${rendering.name}-2")
        advanceTimeBy(5_000)

        assertThat(f.events.filterIsInstance<SonosEvent.CallbackUnreachable>()).isEmpty()
    }

    @Test
    fun `the subscription is renewed at half of the granted timeout`() = test { f ->
        f.start()
        runCurrent()

        advanceTimeBy(59_999)
        assertThat(f.subscriber.renews).isEmpty()
        advanceTimeBy(2)
        runCurrent()

        assertThat(f.subscriber.renews.map { it.service }).containsExactly(av, rendering)
        assertThat(f.subscriber.renews.map { it.atMs }.distinct()).containsExactly(60_000L)
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(f.subscriber.renews).hasSize(4)
    }

    @Test
    fun `the renewal follows the timeout the speaker granted`() = test { f ->
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
    fun `a 412 on renew subscribes again and the first notification of the new subscription asks for a resync`() = test { f ->
        f.subscriber.onRenew = { _, _ ->
            SonosResult.Err(SonosFailure.Upnp("SUBSCRIBE", UpnpErrorKind.PreconditionFailed))
        }
        f.start()
        runCurrent()
        f.notify(av, 0, avBody)
        runCurrent()
        assertThat(f.events.filterIsInstance<SonosEvent.NeedFullResync>()).isEmpty()

        advanceTimeBy(60_001)
        runCurrent()

        assertThat(f.subscriber.subscribes.filter { it.service == av }).hasSize(2)
        f.notify(av, 0, avBody, sid = "sid-${av.name}-3")
        runCurrent()
        assertThat(f.events.filterIsInstance<SonosEvent.NeedFullResync>()).hasSize(1)
    }

    @Test
    fun `a renew that fails for any other reason also subscribes again`() = test { f ->
        f.subscriber.onRenew = { _, _ -> SonosResult.Err(SonosFailure.Timeout) }
        f.start()
        runCurrent()

        advanceTimeBy(60_001)
        runCurrent()

        assertThat(f.subscriber.subscribes.filter { it.service == av }).hasSize(2)
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
    fun `a missing callback address is retried later and nothing is sent without it`() = test { f ->
        f.callbackBase = null
        f.start()
        runCurrent()
        advanceTimeBy(3_000)
        assertThat(f.subscriber.subscribes).isEmpty()

        f.callbackBase = "http://192.168.1.20:8080"
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

        assertThat(f.subscriber.unsubscribes.map { it.service }).containsExactly(av, rendering)
        assertThat(f.subscriber.unsubscribes.map { it.sid }).containsExactly("sid-${av.name}-1", "sid-${rendering.name}-2")
        keys.forEach { assertThat(f.router.find(it)).isNull() }
    }

    @Test
    fun `a speaker that never answers the unsubscribe cannot hold the cleanup for more than two seconds`() = test { f ->
        f.subscriber.onUnsubscribe = { awaitCancellation() }
        val job = f.start()
        runCurrent()
        val keys = listOf(f.keyOf(av), f.keyOf(rendering))

        job.cancel()
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()

        assertThat(job.isCompleted).isTrue()
        keys.forEach { assertThat(f.router.find(it)).isNull() }
    }

    @Test
    fun `a body that is not XML does not break the flow`() = test { f ->
        f.start()
        runCurrent()

        f.notify(av, 0, "<<<not xml")
        f.notify(av, 1, avBody)
        runCurrent()

        assertThat(f.events.filterIsInstance<SonosEvent.AvTransport>()).hasSize(1)
        assertThat(f.events.filterIsInstance<SonosEvent.CallbackUnreachable>()).isEmpty()
    }

    @Test
    fun `a notification of a subscription that was abandoned is ignored`() = test { f ->
        f.start()
        runCurrent()

        f.notify(av, 0, avBody, sid = "uuid:somebody-else")
        runCurrent()

        assertThat(f.events.filterIsInstance<SonosEvent.AvTransport>()).isEmpty()
    }

    @Test
    fun `a notification without SID header is still accepted`() = test { f ->
        f.start()
        runCurrent()

        f.notify(av, 0, avBody, sid = null)
        runCurrent()

        assertThat(f.events.filterIsInstance<SonosEvent.AvTransport>()).hasSize(1)
    }

}
