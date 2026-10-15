package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.SonosSubscriber
import com.theveloper.pixelplay.data.sonos.net.Subscription
import com.theveloper.pixelplay.data.sonos.protocol.AvTransportChange
import com.theveloper.pixelplay.data.sonos.protocol.RenderingChange
import com.theveloper.pixelplay.data.sonos.protocol.SonosEventParser
import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import com.theveloper.pixelplay.data.sonos.server.SonosEventRouter
import com.theveloper.pixelplay.data.sonos.server.SonosEventSink
import com.theveloper.pixelplay.data.sonos.server.SonosNotification
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
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

    /** The speaker did not call back within the expected time: the phone may not be reachable from it. */
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

    private suspend fun ProducerScope<SonosEvent>.keepAlive(host: String, service: SonosService) {
        val notifications = Channel<SonosNotification>(Channel.UNLIMITED)
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
                follow(host, service, result.value, notifications, resyncOnFirst = replacing)
                replacing = true
            }
        } finally {
            withContext(NonCancellable) {
                current?.let { withTimeoutOrNull(UNSUBSCRIBE_TIMEOUT_MS) { subscriber.unsubscribe(host, service, it.sid) } }
                router.unregister(key)
            }
        }
    }

    /** Runs until the speaker stops honouring [subscription]. */
    private suspend fun ProducerScope<SonosEvent>.follow(
        host: String,
        service: SonosService,
        subscription: Subscription,
        notifications: ReceiveChannel<SonosNotification>,
        resyncOnFirst: Boolean,
    ) = coroutineScope {
        val lost = CompletableDeferred<Unit>()
        val heard = AtomicBoolean(false)
        val renewals = launch {
            var granted = subscription
            while (true) {
                delay(granted.timeoutSeconds * MILLIS_PER_SECOND / 2)
                when (val renewed = subscriber.renew(host, service, subscription.sid, granted.timeoutSeconds)) {
                    is SonosResult.Ok -> granted = renewed.value
                    is SonosResult.Err -> {
                        lost.complete(Unit)
                        return@launch
                    }
                }
            }
        }
        val watchdog = launch {
            delay(CALLBACK_CHECK_MS)
            if (!heard.get()) send(SonosEvent.CallbackUnreachable(host))
        }
        var sequence = EventSequence()
        var mustResync = resyncOnFirst
        while (true) {
            val notification = select<SonosNotification?> {
                notifications.onReceive { it }
                lost.onAwait { null }
            } ?: break
            if (notification.sid != null && notification.sid != subscription.sid) continue
            heard.set(true)
            val seq = notification.seq
            var check: SequenceCheck = SequenceCheck.InOrder
            if (seq != null) {
                if (seq != 0L && seq == sequence.lastSeq) continue
                val (next, result) = sequence.accept(seq)
                sequence = next
                check = result
            }
            deliver(service, notification, seq)
            if (check != SequenceCheck.InOrder || mustResync) send(SonosEvent.NeedFullResync)
            mustResync = false
        }
        renewals.cancel()
        watchdog.cancel()
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
        const val CALLBACK_CHECK_MS = 2_000L
        const val UNSUBSCRIBE_TIMEOUT_MS = 2_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
