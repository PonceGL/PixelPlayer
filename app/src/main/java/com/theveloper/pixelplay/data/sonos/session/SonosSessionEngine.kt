package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.TransportState
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
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
import kotlinx.coroutines.launch

/** A playback error of one song, as the speaker reported it or as the engine concluded from its behaviour. */
internal data class PlaybackError(val kind: TransportErrorKind, val trackUri: String?, val trackLabel: String?)

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
    private var job: Job? = null
    private var ownWindow = OwnChangeWindow()
    private var eventsApplied = 0L
    private var lastReadAtMs = 0L
    private var playWatch: PlayWatch? = null

    private class PlayWatch(val startedAtMs: Long, var sawPlaying: Boolean = false)

    fun start() {
        if (job != null) return
        lastReadAtMs = elapsedMs()
        job = scope.launch {
            launch { events.collect(::apply) }
            launch { readLoop() }
            launch { pollLoop() }
            requestRead()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Marks a command of this app, so the changes it causes are not taken for somebody else's. */
    fun noteCommand(atMs: Long) {
        synchronized(lock) { ownWindow = ownWindow.recordCommand(atMs) }
    }

    /** Asks for a full read. Requests made before the read starts are answered by that one read. */
    fun requestRead() {
        if (readPending.compareAndSet(false, true)) readRequests.trySend(Unit)
    }

    /** The speaker accepted a Play: if it ends up stopped without ever playing, the song cannot be played. */
    fun onPlayAccepted() {
        synchronized(lock) { playWatch = PlayWatch(elapsedMs()) }
        scope.launch {
            delay(FIRST_VERDICT_MS)
            requestRead()
            delay(LAST_VERDICT_MS - FIRST_VERDICT_MS)
            requestRead()
            delay(READ_GRACE_MS)
            synchronized(lock) { playWatch = null }
        }
    }

    private suspend fun readLoop() {
        for (request in readRequests) {
            // From here on a new request means "read again": what it was about may have happened after this read began.
            readPending.set(false)
            val startedAtMs = elapsedMs()
            val startedVersion = synchronized(lock) { eventsApplied }
            val result = readState()
            synchronized(lock) {
                lastReadAtMs = elapsedMs()
                when (result) {
                    is SonosResult.Err -> mutableState.value = mutableState.value.copy(readFailure = result.failure)
                    is SonosResult.Ok ->
                        // An event that arrived meanwhile is newer than what was read: read again.
                        if (eventsApplied != startedVersion) requestRead() else applyRead(result.value, startedAtMs)
                }
            }
        }
    }

    private suspend fun pollLoop() {
        while (true) {
            delay(POLL_TICK_MS)
            val current = mutableState.value
            val interval = if (!current.callbackReachable && current.playback.transportState == TransportState.PLAYING) {
                UNREACHABLE_POLL_MS
            } else {
                SAFETY_POLL_MS
            }
            if (elapsedMs() - synchronized(lock) { lastReadAtMs } >= interval) requestRead()
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

    private fun applyRendering(change: RenderingChange) {
        eventsApplied++
        val current = mutableState.value
        mutableState.value = current.copy(
            playback = SonosStateReducer.reduce(current.playback, change),
            callbackReachable = true,
        )
    }

    private fun applyAvTransport(change: AvTransportChange) {
        eventsApplied++
        val now = elapsedMs()
        val old = mutableState.value
        val playback = SonosStateReducer.reduce(old.playback, change)
        val songChanged = playback.currentTrackUri != old.playback.currentTrackUri
        val stateChanged = playback.transportState != old.playback.transportState
        val positionNow = old.positionAt(now)
        val playing = playback.transportState == TransportState.PLAYING

        val anchor = when {
            songChanged -> PositionAnchor(0L, now, playing, playback.currentTrackDurationMs)
            stateChanged -> PositionAnchor(positionNow, now, playing, playback.currentTrackDurationMs)
            old.anchor != null -> old.anchor.copy(durationMs = playback.currentTrackDurationMs ?: old.anchor.durationMs)
            else -> null
        }
        val origin = if (songChanged || stateChanged) ownWindow.classify(now) else old.lastChange
        val reportedError = playback.lastTransportError?.takeIf { it != old.playback.lastTransportError }
        var lastError = reportedError?.let { PlaybackError(it.kind, playback.currentTrackUri, it.trackLabel) } ?: old.lastError
        // An error stays until the speaker plays again or reports itself healthy; a PLAYING event that follows
        // the error report in the same state does not clear it.
        if ((playing && stateChanged) || (change.transportStatus != null && playback.lastTransportError == null)) lastError = null
        val ended = when {
            playing -> false
            old.playback.transportState == TransportState.PLAYING && playback.transportState == TransportState.STOPPED ->
                finishedByItself(old, playback, positionNow, origin)
            else -> old.queueEnded
        }
        if (playing) playWatch?.sawPlaying = true

        mutableState.value = old.copy(
            playback = playback,
            anchor = anchor,
            callbackReachable = true,
            lastError = lastError,
            lastChange = origin,
            queueEnded = ended,
        )
        evaluatePlayWatch(playback)
        if (songChanged || stateChanged) requestRead()
    }

    private fun applyRead(snapshot: SpeakerSnapshot, startedAtMs: Long) {
        val now = elapsedMs()
        val old = mutableState.value
        // The read has no volume and no error text: those only come by event.
        val playback = snapshot.playback.copy(
            volume = old.playback.volume,
            muted = old.playback.muted,
            lastTransportError = old.playback.lastTransportError,
        )
        val sameSong = playback.currentTrackUri == old.playback.currentTrackUri
        val position = snapshot.positionMs ?: if (sameSong) old.positionAt(now) else 0L
        val playing = playback.transportState == TransportState.PLAYING
        val anchor = PositionAnchor(position, (startedAtMs + now) / 2, playing, playback.currentTrackDurationMs)
        if (playing) playWatch?.sawPlaying = true

        mutableState.value = old.copy(
            playback = playback,
            anchor = anchor,
            readFailure = null,
            lastError = if (playing) null else old.lastError,
            queueEnded = if (playing) false else old.queueEnded,
        )
        evaluatePlayWatch(playback)
    }

    /** A song that never played, once the speaker had time to start it, is one it cannot play. */
    private fun evaluatePlayWatch(playback: SonosPlaybackState) {
        val watch = playWatch ?: return
        if (watch.sawPlaying || playback.transportState != TransportState.STOPPED) return
        if (elapsedMs() - watch.startedAtMs < FIRST_VERDICT_MS) return
        val error = PlaybackError(TransportErrorKind.UNSUPPORTED_FORMAT, playback.currentTrackUri, playback.currentTrack?.title)
        mutableState.value = mutableState.value.copy(lastError = error)
        playWatch = null
    }

    private fun finishedByItself(old: SonosSessionState, playback: SonosPlaybackState, positionNow: Long, origin: ChangeOrigin?): Boolean {
        val duration = playback.currentTrackDurationMs ?: old.playback.currentTrackDurationMs ?: return false
        return origin == ChangeOrigin.EXTERNAL &&
            playback.lastTransportError == null &&
            playback.playMode == PlayMode.NORMAL &&
            playback.currentTrackNumber != null && playback.currentTrackNumber == playback.numberOfTracks &&
            positionNow >= duration - END_TOLERANCE_MS
    }

    private companion object {
        const val POLL_TICK_MS = 5_000L
        const val UNREACHABLE_POLL_MS = 5_000L
        const val SAFETY_POLL_MS = 30_000L
        const val FIRST_VERDICT_MS = 1_500L
        const val LAST_VERDICT_MS = 5_000L
        const val READ_GRACE_MS = 2_000L
        const val END_TOLERANCE_MS = 3_000L
    }
}
