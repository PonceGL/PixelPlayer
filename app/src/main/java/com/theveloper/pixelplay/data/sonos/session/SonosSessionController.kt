package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.RepeatMode
import com.theveloper.pixelplay.data.sonos.model.SonosDevice
import com.theveloper.pixelplay.data.sonos.model.TransportState
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.SonosSubscriber
import com.theveloper.pixelplay.data.sonos.net.SonosTransport
import com.theveloper.pixelplay.data.sonos.server.SonosEventRouter
import com.theveloper.pixelplay.data.sonos.server.SonosMediaAccess
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Serves the phone's media to a speaker: starts the server and says where it can be reached. */
internal interface SonosMediaServer {
    /** The base address of the server (`http://ip:port`) once it runs, or why it cannot. */
    suspend fun acquire(speakerHost: String): SonosResult<String>

    fun release()
}

/** Keeps the Wi-Fi and the CPU awake while a speaker pulls music from the phone. Both calls are safe to repeat. */
internal interface SonosPowerLocks {
    fun acquire()
    fun release()
}

/** The queue the user had on this phone when they chose a speaker. */
internal data class LocalQueue(
    val entries: List<QueueEntry>,
    val currentIndex: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val repeat: RepeatMode,
    /** The order of [entries] is already a shuffled one the user chose. */
    val shuffledByApp: Boolean,
)

/**
 * A speaker playing the user's queue.
 *
 * @property included the positions in the [LocalQueue] of the songs that went to the speaker, in order
 * @property window the part of [included] that is on the speaker (its first song is the speaker's track 1)
 * @property loadFailure why the rest of the queue could not be completed after the song had started
 */
internal class ActiveSession(
    val device: SonosDevice,
    val commander: SonosCommander,
    val engine: SonosSessionEngine,
    val included: List<Int>,
    val window: IntRange,
    val loadFailure: StateFlow<SonosFailure?>,
)

/** Where to put the music back on the phone. */
internal data class HandBack(val songId: String?, val positionMs: Long, val wasPlaying: Boolean)

/**
 * Starts, runs and ends the session with a speaker. One session at a time: connecting again replaces the
 * previous one. The media server and the power locks are shared by whatever session exists, so they are let go
 * when none is left, whichever way it ends (failure, disconnection, a cancelled caller, or the end of [scope]).
 */
internal class SonosSessionController(
    private val transport: SonosTransport,
    private val subscriber: SonosSubscriber,
    private val router: SonosEventRouter,
    private val access: SonosMediaAccess,
    private val server: SonosMediaServer,
    private val powerLocks: SonosPowerLocks,
    private val holder: SonosSessionHolder,
    private val elapsedMs: () -> Long,
    private val scope: CoroutineScope,
) {

    private inner class Runtime(val device: SonosDevice) {
        val job = Job(scope.coroutineContext[Job])
        val runScope = CoroutineScope(scope.coroutineContext + job)
        val superseded = CompletableDeferred<Unit>()
        val reader = SonosStateReader(transport, device.host)
        var session: ActiveSession? = null
    }

    private val lock = Any()
    private var current: Runtime? = null
    private val revisions = AtomicLong()

    val activeSession: ActiveSession? get() = synchronized(lock) { current?.session }

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion {
            releaseShared()
            holder.set(SonosConnection.Idle)
        }
    }

    suspend fun connect(device: SonosDevice, queue: LocalQueue): SonosResult<ActiveSession> {
        val runtime = Runtime(device)
        val previous = synchronized(lock) { current.also { current = runtime } }
        previous?.let {
            it.superseded.complete(Unit)
            teardown(it)
        }
        holder.set(SonosConnection.Connecting(device))
        powerLocks.acquire()

        var failure: SonosFailure? = null
        var succeeded = false
        try {
            val result = race(runtime, queue)
            when (result) {
                is SonosResult.Ok -> {
                    succeeded = true
                    synchronized(lock) { if (current === runtime) holder.set(SonosConnection.Active(device)) }
                }
                is SonosResult.Err -> failure = result.failure
            }
            return result
        } finally {
            if (!succeeded) withContext(NonCancellable) { abandon(runtime, failure) }
        }
    }

    /**
     * Ends the session and says where to continue on the phone. The speaker is paused, only if it is playing a
     * song of ours, and its queue is left as it is.
     */
    suspend fun disconnect(): HandBack? {
        val runtime = synchronized(lock) { current.also { current = null } } ?: return null
        runtime.superseded.complete(Unit)
        return withContext(NonCancellable) {
            val handBack = runtime.session?.let { handBackOf(runtime, it) }
            teardown(runtime)
            releaseShared()
            holder.set(SonosConnection.Idle)
            handBack
        }
    }

    /** The setup runs apart so that a newer connect (or a disconnect) can end it without waiting. */
    private suspend fun race(runtime: Runtime, queue: LocalQueue): SonosResult<ActiveSession> = coroutineScope {
        val setup = async {
            withTimeoutOrNull(START_TIMEOUT_MS) { setup(runtime, queue) } ?: SonosResult.Err(SonosFailure.Timeout)
        }
        select {
            setup.onAwait { it }
            runtime.superseded.onAwait {
                setup.cancel()
                SonosResult.Err(SonosFailure.Superseded)
            }
        }
    }

    private suspend fun setup(runtime: Runtime, queue: LocalQueue): SonosResult<ActiveSession> {
        val device = runtime.device
        val base = when (val acquired = server.acquire(device.host)) {
            is SonosResult.Err -> return acquired
            is SonosResult.Ok -> acquired.value
        }
        val included = queue.entries.indices.filter { access.servable(queue.entries[it].songId) }
        val start = included.indexOf(queue.currentIndex)
        if (start < 0) return SonosResult.Err(SonosFailure.NotOnSpeaker)

        val revision = revisions.incrementAndGet().toString()
        val tracks = included.map { index ->
            val entry = queue.entries[index]
            val urls = checkNotNull(SonosTrackMapper.urlsFor(entry, access, base, revision)) { "A servable song has addresses" }
            SonosTrackMapper.toTrack(entry, urls)
        }
        val window = SonosCommander.queueWindow(tracks.size, start)
        access.publish(window.map { queue.entries[included[it]].songId })

        val subscriptions = SonosEventSubscriptions(subscriber, router) { base }
        val engine = SonosSessionEngine(subscriptions.start(device.host), runtime.reader::read, elapsedMs, runtime.runScope)
        engine.start()
        val commander = SonosCommander(transport, device.host, device.id, elapsedMs, engine::noteCommand)

        val outcome = CompletableDeferred<SonosFailure?>()
        val loadFailure = MutableStateFlow<SonosFailure?>(null)
        runtime.runScope.launch {
            val result = commander.loadAndPlay(
                tracks = tracks,
                startIndex = start,
                startPositionMs = queue.positionMs,
                mode = PlayMode.of(shuffle = false, repeat = queue.repeat),
                playWhenReady = queue.playWhenReady,
                onStarted = {
                    if (queue.playWhenReady) engine.onPlayAccepted()
                    outcome.complete(null)
                },
                onProgress = {},
            )
            if (result is SonosResult.Err && !outcome.complete(result.failure)) loadFailure.value = result.failure
        }
        outcome.await()?.let { return SonosResult.Err(it) }

        val session = ActiveSession(device, commander, engine, included, window, loadFailure.asStateFlow())
        runtime.session = session
        return SonosResult.Ok(session)
    }

    private suspend fun handBackOf(runtime: Runtime, session: ActiveSession): HandBack {
        val fresh = withTimeoutOrNull(FINAL_READ_TIMEOUT_MS) { (runtime.reader.read() as? SonosResult.Ok)?.value }
        val known = session.engine.state.value
        val playback = fresh?.playback ?: known.playback
        val position = fresh?.positionMs ?: known.positionAt(elapsedMs())
        val songId = playback.currentTrackUri?.let { SonosTrackMapper.songIdOf(it, access) }
        // Only a song of ours is ours to pause: the speaker may have been taken over by somebody else.
        val playing = songId != null && playback.transportState == TransportState.PLAYING
        if (playing) withTimeoutOrNull(PAUSE_TIMEOUT_MS) { session.commander.pause() }
        return HandBack(songId, position, wasPlaying = playing)
    }

    /** Ends what [runtime] started. If it is still the current session, the shared things are let go too. */
    private suspend fun abandon(runtime: Runtime, failure: SonosFailure?) {
        teardown(runtime)
        val wasCurrent = synchronized(lock) { (current === runtime).also { if (it) current = null } }
        if (!wasCurrent) return
        releaseShared()
        holder.set(if (failure != null && failure != SonosFailure.Superseded) SonosConnection.Failed(runtime.device, failure) else SonosConnection.Idle)
    }

    private suspend fun teardown(runtime: Runtime) {
        withTimeoutOrNull(TEARDOWN_TIMEOUT_MS) { runtime.job.cancelAndJoin() }
    }

    private fun releaseShared() {
        server.release()
        powerLocks.release()
    }

    companion object {
        const val START_TIMEOUT_MS = 30_000L
        private const val FINAL_READ_TIMEOUT_MS = 1_500L
        private const val PAUSE_TIMEOUT_MS = 2_000L
        private const val TEARDOWN_TIMEOUT_MS = 4_000L
    }
}
