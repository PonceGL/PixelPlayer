package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.RepeatMode
import com.theveloper.pixelplay.data.sonos.model.SonosDevice
import com.theveloper.pixelplay.data.sonos.model.TrackMetadata
import com.theveloper.pixelplay.data.sonos.model.TransportState
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.safeName
import com.theveloper.pixelplay.data.sonos.net.SonosSubscriber
import com.theveloper.pixelplay.data.sonos.net.SonosTransport
import com.theveloper.pixelplay.data.sonos.server.SonosEventRouter
import com.theveloper.pixelplay.data.sonos.server.SonosMediaAccess
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Serves the phone's media to a speaker: starts the server and says where it can be reached. */
internal interface SonosMediaServer {
    /** The base address of the server (`http://ip:port`) once it runs, or why it cannot. Does not throw. */
    suspend fun acquire(speakerHost: String): SonosResult<String>

    fun release()

    /** The address the server has right now, which changes when the phone changes network. */
    fun currentAddress(): String?
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
    /** What the speaker needs to know about a song of the app, with an address it can get it from now; null for a song that cannot be served. */
    val trackFor: (QueueEntry) -> TrackMetadata?,
    /** True once the whole queue is on the speaker: until then it is still being filled and cannot be edited. */
    val loaded: StateFlow<Boolean>,
)

/** Where to put the music back on the phone. */
internal data class HandBack(val songId: String?, val positionMs: Long, val wasPlaying: Boolean, val repeat: RepeatMode?)

/**
 * Starts, runs and ends the session with a speaker. One session at a time, and one operation at a time:
 * connecting or disconnecting while another operation is running asks it to give way and waits for its turn,
 * so the steps of two operations never interleave. The media server and the power locks are shared by
 * whatever session exists, so they are let go when none is left, whichever way it ends (failure,
 * disconnection, a cancelled caller, or the end of [scope]) and never while another operation still needs them.
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
        /** Told when the session could not start, or when a coroutine of it died. */
        val outcome = CompletableDeferred<SonosFailure?>()
        val job = SupervisorJob(scope.coroutineContext[Job])
        val runScope = CoroutineScope(
            scope.coroutineContext + job + CoroutineExceptionHandler { _, error ->
                Timber.tag(TAG).e("A coroutine of the speaker session failed: %s", error.javaClass.simpleName)
                outcome.complete(SonosFailure.Network(error.javaClass.simpleName))
            },
        )
        val superseded = CompletableDeferred<Unit>()
        val reader = SonosStateReader(transport, device.host)

        @Volatile var session: ActiveSession? = null

        @Volatile var loading: Job? = null
    }

    private val operations = Mutex()
    private val lock = Any()
    private var current: Runtime? = null
    private val revisions = AtomicLong()

    val activeSession: ActiveSession? get() = synchronized(lock) { current?.session }

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion {
            synchronized(lock) { current = null }
            releaseShared()
            holder.set(SonosConnection.Idle)
        }
    }

    suspend fun connect(device: SonosDevice, queue: LocalQueue): SonosResult<ActiveSession> {
        if (!scope.isActive) return SonosResult.Err(SonosFailure.Superseded)
        giveWay()
        return operations.withLock { connectInTurn(device, queue) }
    }

    /**
     * Ends the session and says where to continue on the phone. The speaker is paused, only if it is playing a
     * song of ours, and its queue is left as it is.
     */
    suspend fun disconnect(): HandBack? {
        giveWay()
        return operations.withLock {
            withContext(NonCancellable) {
                val runtime = synchronized(lock) { current.also { current = null } } ?: return@withContext null
                Timber.tag(TAG).d("disconnect")
                val handBack = runtime.session?.let { handBackOf(runtime, it) }
                teardown(runtime)
                releaseShared()
                holder.set(SonosConnection.Idle)
                handBack
            }
        }
    }

    /** An attempt that is still starting ends as soon as it can, so the operation that follows is not kept waiting. */
    private fun giveWay() {
        synchronized(lock) { current }?.superseded?.complete(Unit)
    }

    private suspend fun connectInTurn(device: SonosDevice, queue: LocalQueue): SonosResult<ActiveSession> {
        if (!scope.isActive) return SonosResult.Err(SonosFailure.Superseded)
        val runtime = Runtime(device)
        var result: SonosResult<ActiveSession>? = null
        try {
            withContext(NonCancellable) { replacePrevious() }
            synchronized(lock) { current = runtime }
            holder.set(SonosConnection.Connecting(device))
            powerLocks.acquire()
            result = race(runtime, queue)
            Timber.tag(TAG).d("connect result: %s", if (result is SonosResult.Err) result.failure.safeName() else "ok")
            if (result is SonosResult.Ok) holder.set(SonosConnection.Active(device))
            return result
        } finally {
            withContext(NonCancellable) { conclude(runtime, result) }
        }
    }

    /** What was there before is paused if it was playing our music, then stopped. The new session starts clean. */
    private suspend fun replacePrevious() {
        val previous = synchronized(lock) { current } ?: return
        previous.superseded.complete(Unit)
        previous.session?.let { handBackOf(previous, it) }
        teardown(previous)
        access.clear()
    }

    /** The setup runs apart so that a newer operation can end it without waiting for its timeout. */
    private suspend fun race(runtime: Runtime, queue: LocalQueue): SonosResult<ActiveSession> = coroutineScope {
        // Building the addresses of a long queue is work for the controller's own threads, not the caller's.
        val setup = async(scope.coroutineContext.minusKey(Job)) {
            withTimeoutOrNull(START_TIMEOUT_MS) { setup(runtime, queue) } ?: SonosResult.Err(SonosFailure.Timeout)
        }
        select {
            setup.onAwait { it }
            runtime.superseded.onAwait {
                setup.cancel()
                SonosResult.Err(SonosFailure.Superseded)
            }
            // The scope of the controller ending ends the session with it, wherever the setup was.
            runtime.job.onJoin {
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
        Timber.tag(TAG).d("connect: songs=%d sent=%d start=%d window=%s playWhenReady=%s", queue.entries.size, tracks.size, start, window, queue.playWhenReady)
        access.publish(window.map { queue.entries[included[it]].songId })

        val subscriptions = SonosEventSubscriptions(subscriber, router) { server.currentAddress() ?: base }
        val engine = SonosSessionEngine(subscriptions.start(device.host), runtime.reader::read, elapsedMs, runtime.runScope)
        engine.start()
        val commander = SonosCommander(transport, device.host, device.id, elapsedMs, engine::noteCommand, engine::noteQueueEdit)

        val loadFailure = MutableStateFlow<SonosFailure?>(null)
        val loaded = MutableStateFlow(false)
        runtime.loading = runtime.runScope.launch {
            val result = commander.loadAndPlay(
                tracks = tracks,
                startIndex = start,
                startPositionMs = queue.positionMs,
                mode = PlayMode.of(shuffle = false, repeat = queue.repeat),
                playWhenReady = queue.playWhenReady,
                onStarted = {
                    if (queue.playWhenReady) engine.onPlayAccepted()
                    runtime.outcome.complete(null)
                },
                onProgress = {},
            )
            if (result is SonosResult.Err && !runtime.outcome.complete(result.failure)) loadFailure.value = result.failure
            if (result is SonosResult.Ok) loaded.value = true
        }
        // The session ending on its own (its scope was cancelled) must not leave the caller waiting for the timeout.
        val failure = select<SonosFailure?> {
            runtime.outcome.onAwait { it }
            runtime.job.onJoin { SonosFailure.Superseded }
        }
        failure?.let { return SonosResult.Err(it) }

        val trackFor: (QueueEntry) -> TrackMetadata? = { entry ->
            // The address of the phone can change while the session lasts: songs added later use the one it has then.
            SonosTrackMapper.urlsFor(entry, access, server.currentAddress() ?: base, revision)?.let { SonosTrackMapper.toTrack(entry, it) }
        }
        val session = ActiveSession(device, commander, engine, included, window, loadFailure.asStateFlow(), trackFor, loaded.asStateFlow())
        runtime.session = session
        return SonosResult.Ok(session)
    }

    private suspend fun handBackOf(runtime: Runtime, session: ActiveSession): HandBack {
        // The queueing must stop first: it holds the line to the speaker and would keep the pause waiting.
        runtime.loading?.cancelAndJoin()
        val fresh = withTimeoutOrNull(FINAL_READ_TIMEOUT_MS) { (runtime.reader.read() as? SonosResult.Ok)?.value }
        val known = session.engine.state.value
        val playback = fresh?.playback ?: known.playback
        val position = fresh?.positionMs ?: known.positionAt(elapsedMs())
        val songId = playback.currentTrackUri?.let { SonosTrackMapper.songIdOf(it, access) }
        // Only a song of ours is ours to pause: the speaker may have been taken over by somebody else. A speaker
        // that is loading a song (TRANSITIONING) is about to play it.
        val playing = songId != null &&
            (playback.transportState == TransportState.PLAYING || playback.transportState == TransportState.TRANSITIONING)
        if (playing) withTimeoutOrNull(PAUSE_TIMEOUT_MS) { session.commander.pause() }
        return HandBack(songId, position, wasPlaying = playing, repeat = playback.playMode?.repeat)
    }

    /**
     * Ends what [runtime] started. A session that ended because a newer operation asked it to is only stopped:
     * that operation decides what happens to the shared things. Any other end lets everything go.
     */
    private suspend fun conclude(runtime: Runtime, result: SonosResult<ActiveSession>?) {
        if (result is SonosResult.Ok) return
        teardown(runtime)
        val failure = (result as? SonosResult.Err)?.failure
        if (failure == SonosFailure.Superseded) return
        synchronized(lock) { if (current === runtime) current = null }
        releaseShared()
        holder.set(if (failure != null) SonosConnection.Failed(runtime.device, failure) else SonosConnection.Idle)
    }

    private suspend fun teardown(runtime: Runtime) {
        withTimeoutOrNull(TEARDOWN_TIMEOUT_MS) { runtime.job.cancelAndJoin() }
    }

    private fun releaseShared() {
        server.release()
        powerLocks.release()
        access.clear()
    }

    companion object {
        const val START_TIMEOUT_MS = 30_000L
        private const val FINAL_READ_TIMEOUT_MS = 1_500L
        private const val PAUSE_TIMEOUT_MS = 2_000L
        private const val TEARDOWN_TIMEOUT_MS = 4_000L
        private const val TAG = "SonosSession"
    }
}
