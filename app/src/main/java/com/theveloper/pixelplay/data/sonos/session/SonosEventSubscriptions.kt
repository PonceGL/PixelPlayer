package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.SonosSubscriber
import com.theveloper.pixelplay.data.sonos.net.Subscription
import com.theveloper.pixelplay.data.sonos.protocol.AvTransportChange
import com.theveloper.pixelplay.data.sonos.protocol.RenderingChange
import com.theveloper.pixelplay.data.sonos.protocol.SonosEventParser
import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind
import com.theveloper.pixelplay.data.sonos.server.SonosEventRouter
import com.theveloper.pixelplay.data.sonos.server.SonosNotification
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface SonosEvent {
    data class AvTransport(val change: AvTransportChange, val seq: Long?) : SonosEvent
    data class Rendering(val change: RenderingChange, val seq: Long?) : SonosEvent

    /** Events were lost, or a new subscription replaced a lapsed one: reread the speaker's full state. */
    data object NeedFullResync : SonosEvent

    /**
     * Events from this speaker are not arriving: it stayed silent for two seconds after a subscription, or the
     * subscription was lost. One per service and per occurrence, and there is no event for "arriving again":
     * the consumer sees that by receiving events.
     */
    data class CallbackUnreachable(val host: String) : SonosEvent
}

/**
 * Keeps the AVTransport and RenderingControl event subscriptions of one speaker alive for as long as
 * the flow from [start] is collected: renews them, subscribes again when the speaker forgets them, and
 * turns the NOTIFY messages routed by [router] into [SonosEvent]s.
 */
internal class SonosEventSubscriptions(
    private val subscriber: SonosSubscriber,
    private val router: SonosEventRouter,
    private val callbackBase: () -> String?,
) {

    fun start(host: String): Flow<SonosEvent> = channelFlow {
        for (service in SUBSCRIBED_SERVICES) launch { keepAlive(host, service) }
    }

    private enum class SubscriptionEnd {
        /** The speaker answered 412: it forgot the subscription, so there is nothing to give up. */
        Forgotten,

        /** Renewals kept failing until the subscription would have expired; the speaker may still hold it. */
        Lapsed,

        /** The phone's callback address is not the one that was subscribed with. */
        AddressChanged,
    }

    private suspend fun ProducerScope<SonosEvent>.keepAlive(host: String, service: SonosService) {
        // Bounded: a consumer that stalls loses the oldest notifications, which shows up as a SEQ gap and
        // becomes a full resync, instead of growing memory for as long as the speaker keeps sending.
        val notifications = Channel<SonosNotification>(NOTIFICATION_BUFFER, BufferOverflow.DROP_OLDEST)
        val key = router.register(host) { notifications.trySend(it) }
        var current: Subscription? = null
        try {
            var wait = 0L
            var replacing = false
            while (true) {
                if (wait > 0) delay(wait)
                val base = callbackBase()
                val result = if (base == null) null else subscriber.subscribe(host, service, "$base$EVENTS_PATH/$key")
                if (result !is SonosResult.Ok) {
                    wait = if (wait == 0L) INITIAL_BACKOFF_MS else minOf(wait * 2, MAX_BACKOFF_MS)
                    continue
                }
                wait = 0
                current = result.value
                val end = follow(host, service, result.value, base, notifications, resyncOnFirst = replacing)
                send(SonosEvent.CallbackUnreachable(host))
                if (end != SubscriptionEnd.Forgotten) {
                    withTimeoutOrNull(UNSUBSCRIBE_TIMEOUT_MS) { subscriber.unsubscribe(host, service, result.value.sid) }
                }
                current = null
                replacing = true
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    current?.let { withTimeoutOrNull(UNSUBSCRIBE_TIMEOUT_MS) { subscriber.unsubscribe(host, service, it.sid) } }
                } finally {
                    router.unregister(key)
                }
            }
        }
    }

    /** Runs until the subscription is lost and says why. */
    private suspend fun ProducerScope<SonosEvent>.follow(
        host: String,
        service: SonosService,
        subscription: Subscription,
        subscribedBase: String?,
        notifications: ReceiveChannel<SonosNotification>,
        resyncOnFirst: Boolean,
    ): SubscriptionEnd = coroutineScope {
        val ended = CompletableDeferred<SubscriptionEnd>()
        val heard = AtomicBoolean(false)
        val renewals = launch { renewUntilLost(host, service, subscription, subscribedBase, ended) }
        val watchdog = launch {
            delay(CALLBACK_CHECK_MS)
            if (!heard.get()) send(SonosEvent.CallbackUnreachable(host))
        }
        var sequence = EventSequence()
        var mustResync = resyncOnFirst
        while (true) {
            val notification = select<SonosNotification?> {
                notifications.onReceive { it }
                ended.onAwait { null }
            } ?: break
            if (notification.sid != null && notification.sid != subscription.sid) continue
            heard.set(true)
            val seq = notification.seq
            var check: SequenceCheck = SequenceCheck.InOrder
            if (seq != null) {
                val last = sequence.lastSeq
                // A repeat, or a late one that crossed a newer one on its way, adds nothing and must not undo it.
                if (last != null && (seq == last || (seq != 0L && seq < last))) continue
                val (next, result) = sequence.accept(seq)
                sequence = next
                check = result
            }
            deliver(service, notification, seq)
            if (check != SequenceCheck.InOrder || mustResync) send(SonosEvent.NeedFullResync)
            mustResync = false
        }
        val end = ended.await()
        renewals.cancel()
        watchdog.cancel()
        end
    }

    /** Renews at half of the granted timeout; a network error is retried until the subscription would expire. */
    private suspend fun renewUntilLost(
        host: String,
        service: SonosService,
        subscription: Subscription,
        subscribedBase: String?,
        ended: CompletableDeferred<SubscriptionEnd>,
    ) {
        var granted = subscription
        while (true) {
            val halfMs = granted.timeoutSeconds * MILLIS_PER_SECOND / 2
            delay(halfMs)
            if (callbackBase() != subscribedBase) {
                ended.complete(SubscriptionEnd.AddressChanged)
                return
            }
            var waitedMs = halfMs
            while (true) {
                val renewed = subscriber.renew(host, service, subscription.sid, granted.timeoutSeconds)
                if (renewed is SonosResult.Ok) {
                    granted = renewed.value
                    break
                }
                val failure = (renewed as SonosResult.Err).failure
                val forgotten = failure is SonosFailure.Upnp && failure.kind == UpnpErrorKind.PreconditionFailed
                if (forgotten) {
                    ended.complete(SubscriptionEnd.Forgotten)
                    return
                }
                if (waitedMs + RENEW_RETRY_MS >= granted.timeoutSeconds * MILLIS_PER_SECOND) {
                    ended.complete(SubscriptionEnd.Lapsed)
                    return
                }
                delay(RENEW_RETRY_MS)
                waitedMs += RENEW_RETRY_MS
            }
        }
    }

    private suspend fun ProducerScope<SonosEvent>.deliver(service: SonosService, notification: SonosNotification, seq: Long?) {
        when (service) {
            SonosService.AV_TRANSPORT ->
                SonosEventParser.parseAvTransport(notification.body)?.let { send(SonosEvent.AvTransport(it, seq)) }
            SonosService.RENDERING_CONTROL ->
                SonosEventParser.parseRendering(notification.body)?.let { send(SonosEvent.Rendering(it, seq)) }
            else -> Unit
        }
    }

    private companion object {
        val SUBSCRIBED_SERVICES = listOf(SonosService.AV_TRANSPORT, SonosService.RENDERING_CONTROL)
        const val EVENTS_PATH = "/sonos/events"
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val NOTIFICATION_BUFFER = 64
        const val RENEW_RETRY_MS = 5_000L
        const val CALLBACK_CHECK_MS = 2_000L
        const val UNSUBSCRIBE_TIMEOUT_MS = 2_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
