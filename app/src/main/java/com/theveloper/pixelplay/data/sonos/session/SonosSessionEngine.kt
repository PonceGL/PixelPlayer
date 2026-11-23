package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.TransportState
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.safeName
import com.theveloper.pixelplay.data.sonos.protocol.AvTransportChange
import com.theveloper.pixelplay.data.sonos.protocol.RenderingChange
import com.theveloper.pixelplay.data.sonos.protocol.TransportErrorKind
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A playback error of one song, as the speaker reported it or as the engine concluded from its behaviour.
 * [trackUri] is only known for the second kind: the speaker's own report names the file ([trackLabel]) but
 * its current song may already be the next one.
 */
internal data class PlaybackError(val kind: TransportErrorKind, val trackUri: String?, val trackLabel: String?) {
    // The address of a song carries the access token of the media server.
    override fun toString(): String = "PlaybackError(kind=$kind)"
}

/**
 * Everything the app knows about the session with a speaker.
 *
 * @property anchor where the position was last known, to interpolate it between reads ([positionAt])
 * @property callbackReachable false once the speaker did not call back; events then cannot be relied on
 * @property lastChange who caused the last change of state or song: this app or somebody else
 * @property queueEnded the last song of a normal queue finished by itself
 * @property readFailure why the last read of the speaker failed; the rest of the state is the last known
 */
internal data class SonosSessionState(
    val playback: SonosPlaybackState = SonosPlaybackState(),
    val anchor: PositionAnchor? = null,
    val callbackReachable: Boolean = true,
    val lastError: PlaybackError? = null,
    val lastChange: ChangeOrigin? = null,
    val queueEnded: Boolean = false,
    val readFailure: SonosFailure? = null,
) {
    fun positionAt(nowElapsedMs: Long): Long = anchor?.let { PositionTracker.positionAt(it, nowElapsedMs) } ?: 0L
}

/**
 * The single state of a session. The speaker is the source of truth: its events are applied as they come and
 * its full state is read after anything that could have left them behind (a gap, a new song, a seek, a
 * silent callback, or just time passing).
 */
internal class SonosSessionEngine(
    private val events: Flow<SonosEvent>,
    private val readState: suspend () -> SonosResult<SpeakerSnapshot>,
    private val elapsedMs: () -> Long,
    private val scope: CoroutineScope,
) {

    private val mutableState = MutableStateFlow(SonosSessionState())
    val state: StateFlow<SonosSessionState> = mutableState.asStateFlow()

    private val lock = Any()
    private val readRequests = Channel<Unit>(Channel.CONFLATED)
    private val readPending = AtomicBoolean(false)
    private var running: CoroutineScope? = null
    private var ownWindow = OwnChangeWindow()
    private var lastQueueEditAtMs: Long? = null
    private var transportEventsApplied = 0L
    private var lastReadAtMs = 0L
    private var failedReadRetried = false
    private var playWatch: PlayWatch? = null
    private var watchJob: Job? = null

    private class PlayWatch(val startedAtMs: Long, var sawPlaying: Boolean = false)

    fun start() {
        synchronized(lock) {
            if (running != null) return
            // A request left over from a run that was cancelled would otherwise block every new one.
            readPending.set(false)
            lastReadAtMs = elapsedMs()
            val engineScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
            running = engineScope
            engineScope.launch { events.collect(::apply) }
            engineScope.launch { readLoop(engineScope) }
            engineScope.launch { pollLoop() }
            engineScope.launch { logChanges() }
        }
        requestRead()
    }

    fun stop() {
        synchronized(lock) {
            running?.coroutineContext?.get(Job)?.cancel()
            running = null
            watchJob = null
            playWatch = null
        }
    }

    /** Marks a command of this app, so the changes it causes are not taken for somebody else's. */
    fun noteCommand(atMs: Long) {
        synchronized(lock) { ownWindow = ownWindow.recordCommand(atMs) }
    }

    /**
     * Marks a change of the speaker's queue made by this app. Moving or removing songs before the one that plays
     * gives it another track number, which is not a new song: for a few seconds a new number under the same
     * address is taken as that shift. It is a command too.
     */
    fun noteQueueEdit(atMs: Long) {
        synchronized(lock) {
            ownWindow = ownWindow.recordCommand(atMs)
            lastQueueEditAtMs = atMs
        }
    }

    private fun queueShiftAllowed(now: Long): Boolean =
        lastQueueEditAtMs?.let { now - it in 0..QUEUE_SHIFT_WINDOW_MS } == true

    /** Asks for a full read. Requests made before the read starts are answered by that one read. */
    fun requestRead() {
        if (readPending.compareAndSet(false, true)) readRequests.trySend(Unit)
    }

    /** The speaker accepted a Play: if it ends up stopped without ever playing, the song cannot be played. */
    fun onPlayAccepted() {
        synchronized(lock) {
            val engineScope = running ?: return
            val watch = PlayWatch(elapsedMs())
            playWatch = watch
            watchJob?.cancel()
            watchJob = engineScope.launch {
                delay(FIRST_VERDICT_MS)
                requestRead()
                delay(LAST_VERDICT_MS - FIRST_VERDICT_MS)
                requestRead()
                delay(READ_GRACE_MS)
                synchronized(lock) { if (playWatch === watch) playWatch = null }
            }
        }
    }

    private suspend fun readLoop(engineScope: CoroutineScope) {
        for (request in readRequests) {
            // From here on a new request means "read again": what it was about may have happened after this read began.
            readPending.set(false)
            val startedAtMs = elapsedMs()
            val startedVersion = synchronized(lock) { transportEventsApplied }
            val result = readState()
            synchronized(lock) {
                lastReadAtMs = elapsedMs()
                when (result) {
                    is SonosResult.Err -> {
                        mutableState.value = mutableState.value.copy(readFailure = result.failure)
                        // A failed read right after a gap would leave the state behind until the safety read: once more soon.
                        if (!failedReadRetried) {
                            failedReadRetried = true
                            engineScope.launch {
                                delay(RETRY_AFTER_FAILURE_MS)
                                requestRead()
                            }
                        }
                    }
                    is SonosResult.Ok -> {
                        failedReadRetried = false
                        // An event that arrived meanwhile is newer than what was read: read again.
                        if (transportEventsApplied != startedVersion) requestRead() else applyRead(result.value, startedAtMs)
                    }
                }
            }
        }
    }

    /** What the speaker is doing, one line per change and without addresses or titles. */
    private suspend fun logChanges() {
        mutableState
            .map { state ->
                val playback = state.playback
                "transport=${playback.transportState} track=${playback.currentTrackNumber}/${playback.numberOfTracks} " +
                    "mode=${playback.playMode} origin=${state.lastChange} queueEnded=${state.queueEnded} " +
                    "error=${state.lastError?.kind} readFailure=${state.readFailure?.safeName()} callback=${state.callbackReachable}"
            }
            .distinctUntilChanged()
            .collect { Timber.tag(LOG_TAG).d("state: %s", it) }
    }

    private suspend fun pollLoop() {
        while (true) {
            delay(POLL_TICK_MS)
            val now = elapsedMs()
            val current = mutableState.value
            val playing = current.playback.transportState == TransportState.PLAYING
            val duration = current.playback.currentTrackDurationMs?.takeIf { it > 0 }
            // A song that reached its end without an event (repeating one song, or a lost event) is looked at again.
            val atEnd = playing && duration != null && current.positionAt(now) >= duration
            val interval = if ((!current.callbackReachable && playing) || atEnd) FAST_POLL_MS else SAFETY_POLL_MS
            if (now - synchronized(lock) { lastReadAtMs } >= interval) requestRead()
        }
    }

    private fun apply(event: SonosEvent) {
        synchronized(lock) {
            when (event) {
                is SonosEvent.AvTransport -> applyAvTransport(event.change)
                is SonosEvent.Rendering -> applyRendering(event.change)
                SonosEvent.NeedFullResync -> requestRead()
                is SonosEvent.CallbackUnreachable -> mutableState.value = mutableState.value.copy(callbackReachable = false)
            }
        }
    }

    /** Volume does not make an unfinished read of the transport out of date, so it is not counted. */
    private fun applyRendering(change: RenderingChange) {
        val current = mutableState.value
        mutableState.value = current.copy(
            playback = SonosStateReducer.reduce(current.playback, change),
            callbackReachable = true,
        )
    }

    private fun applyAvTransport(change: AvTransportChange) {
        transportEventsApplied++
        val now = elapsedMs()
        val old = mutableState.value
        val playback = SonosStateReducer.reduce(old.playback, change)
        val transition = Transition.between(old, playback, now, queueShiftAllowed(now))
        val errorInThisEvent = change.transportStatus != null

        val anchor = when {
            transition.songChanged -> PositionAnchor(0L, now, transition.playing, playback.currentTrackDurationMs)
            transition.stateChanged -> PositionAnchor(transition.positionNow, now, transition.playing, playback.currentTrackDurationMs)
            old.anchor != null -> old.anchor.copy(durationMs = playback.currentTrackDurationMs ?: old.anchor.durationMs)
            else -> null
        }
        val origin = if (transition.changed) ownWindow.classify(now) else old.lastChange
        // The speaker names the failed file but its CurrentTrack may already be the next song, so only the label is kept.
        var lastError = if (errorInThisEvent) {
            playback.lastTransportError?.let { PlaybackError(it.kind, null, it.trackLabel) }
        } else {
            old.lastError
        }
        if (transition.playing && transition.stateChanged) lastError = null
        if (transition.playing) playWatch?.sawPlaying = true

        mutableState.value = old.copy(
            playback = playback,
            anchor = anchor,
            callbackReachable = true,
            lastError = lastError,
            lastChange = origin,
            queueEnded = endedByItself(old, playback, transition, origin, errorInThisEvent),
        )
        evaluatePlayWatch(playback, observedSinceMs = now)
        if (transition.changed) requestRead()
    }

    private fun applyRead(snapshot: SpeakerSnapshot, startedAtMs: Long) {
        val now = elapsedMs()
        val old = mutableState.value
        // The read has no volume, no error text and no queue-wide next song: those only come by event.
        val playback = snapshot.playback.copy(
            volume = old.playback.volume,
            muted = old.playback.muted,
            lastTransportError = old.playback.lastTransportError,
            nextTrackUri = old.playback.nextTrackUri,
        )
        val transition = Transition.between(old, playback, now, queueShiftAllowed(now))
        val position = snapshot.positionMs ?: if (transition.songChanged) 0L else transition.positionNow
        val anchor = PositionAnchor(position, (startedAtMs + now) / 2, transition.playing, playback.currentTrackDurationMs)
        // What a read finds that no event told is a change too: it may be somebody else's, or the end of the queue.
        val origin = if (transition.changed) ownWindow.classify(now) else old.lastChange
        if (transition.playing) playWatch?.sawPlaying = true

        mutableState.value = old.copy(
            playback = playback,
            anchor = anchor,
            readFailure = null,
            lastError = if (transition.playing) null else old.lastError,
            lastChange = origin,
            queueEnded = endedByItself(old, playback, transition, origin, errorInThisChange = false),
        )
        evaluatePlayWatch(playback, observedSinceMs = startedAtMs)
    }

    /** A song that never played, once the speaker had time to start it, is one it cannot play. */
    private fun evaluatePlayWatch(playback: SonosPlaybackState, observedSinceMs: Long) {
        val watch = playWatch ?: return
        val commandSincePlay = ownWindow.lastCommandAtMs?.let { it > watch.startedAtMs } == true
        if (commandSincePlay || mutableState.value.lastError != null) {
            playWatch = null
            return
        }
        // A read that began before the Play shows the speaker as it was, not what the Play did.
        if (watch.sawPlaying || playback.transportState != TransportState.STOPPED || observedSinceMs < watch.startedAtMs) return
        if (elapsedMs() - watch.startedAtMs < FIRST_VERDICT_MS) return
        val error = PlaybackError(TransportErrorKind.UNSUPPORTED_FORMAT, playback.currentTrackUri, playback.currentTrack?.title)
        mutableState.value = mutableState.value.copy(lastError = error)
        playWatch = null
    }

    /**
     * The end of a normal queue, which the speaker reports as STOPPED with the first song selected again, so
     * what ended is judged from the state before the change.
     */
    private fun endedByItself(
        old: SonosSessionState,
        playback: SonosPlaybackState,
        transition: Transition,
        origin: ChangeOrigin?,
        errorInThisChange: Boolean,
    ): Boolean {
        if (transition.playing) return false
        val endedNow = old.playback.transportState == TransportState.PLAYING && playback.transportState == TransportState.STOPPED
        if (!endedNow) return old.queueEnded
        val duration = old.playback.currentTrackDurationMs?.takeIf { it > 0 } ?: return false
        val last = old.playback.currentTrackNumber
        return origin == ChangeOrigin.EXTERNAL &&
            !errorInThisChange &&
            old.playback.playMode == PlayMode.NORMAL &&
            last != null && last == old.playback.numberOfTracks &&
            transition.positionNow >= duration - END_TOLERANCE_MS
    }

    private class Transition(val songChanged: Boolean, val stateChanged: Boolean, val playing: Boolean, val positionNow: Long) {
        val changed get() = songChanged || stateChanged

        companion object {
            fun between(old: SonosSessionState, playback: SonosPlaybackState, now: Long, queueShiftAllowed: Boolean): Transition {
                // The same song twice in a row in the queue has the same address but not the same number, unless the
                // queue was just edited: then a new number under the same address is the song moving in the queue.
                val sameAddress = playback.currentTrackUri == old.playback.currentTrackUri
                val numberChanged = playback.currentTrackNumber != null && playback.currentTrackNumber != old.playback.currentTrackNumber
                val songChanged = !sameAddress || (numberChanged && !queueShiftAllowed)
                return Transition(
                    songChanged = songChanged,
                    stateChanged = playback.transportState != old.playback.transportState,
                    playing = playback.transportState == TransportState.PLAYING,
                    positionNow = old.positionAt(now),
                )
            }
        }
    }

    private companion object {
        const val LOG_TAG = "SonosSession"
        const val POLL_TICK_MS = 5_000L
        const val QUEUE_SHIFT_WINDOW_MS = 3_000L
        const val FAST_POLL_MS = 5_000L
        const val SAFETY_POLL_MS = 30_000L
        const val RETRY_AFTER_FAILURE_MS = 2_000L
        const val FIRST_VERDICT_MS = 1_500L
        const val LAST_VERDICT_MS = 5_000L
        const val READ_GRACE_MS = 2_000L
        const val END_TOLERANCE_MS = 3_000L
    }
}
