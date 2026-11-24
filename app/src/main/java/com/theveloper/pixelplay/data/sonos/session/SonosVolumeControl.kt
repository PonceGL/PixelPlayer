package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The volume the user sees and changes. The speaker is the source of truth ([reported]); this adds what a screen
 * needs on top of it so that nothing jumps:
 * - What the user asks for is shown at once and not moved by the levels the speaker reports on its way there (for a
 *   short time), then the speaker's word wins again.
 * - A slider sends the last level, not every one, with a pause between sends.
 * - Keys pressed while one is on its way add up and go as one change (a held key is dozens a second).
 * - Turning up while muted takes the mute off first.
 * Only one command is on its way at a time.
 *
 * @param reported the volume the speaker says it has (events), null while unknown
 * @param onFailure told when the speaker did not take a command
 */
internal class SonosVolumeControl(
    private val sink: VolumeSink,
    private val reported: StateFlow<SonosVolume?>,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val onFailure: (SonosFailure) -> Unit,
) {

    private val mutableShown = MutableStateFlow(reported.value)
    val shown: StateFlow<SonosVolume?> = mutableShown.asStateFlow()

    private val lock = Any()
    private var wantedLevel: Int? = null
    private var pendingDelta = 0
    private var wantedMute: Boolean? = null
    private var unmuteFirst = false
    private var echoLevel: Int? = null
    private var echoMute: Boolean? = null
    private var echoUntilMs = 0L
    private var lastAbsoluteAtMs: Long? = null
    private var senderRunning = false
    private var settleToken = 0L
    private var settleJob: Job? = null

    /** What the speaker accepted last, kept until it says something itself: an older observation must not take its place. */
    private var confirmed: SonosVolume? = null

    private sealed interface Work {
        data object Unmute : Work
        data class Mute(val muted: Boolean) : Work
        data object Level : Work
        data class Relative(val delta: Int) : Work
    }

    init {
        scope.launch { reported.collect { onReported(it) } }
    }

    /** The user chose [level] on a slider. */
    fun setLevel(level: Int) {
        val clamped = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        synchronized(lock) {
            val current = mutableShown.value
            // Raising a muted speaker takes the mute off first, as a key does: a level alone leaves it silent.
            if (current?.muted == true && clamped > current.level) {
                unmuteFirst = true
                wantedMute = null
                showAsked(muted = false)
            }
            wantedLevel = clamped
            pendingDelta = 0
            showAsked(level = clamped)
            ensureSender()
        }
    }

    /** The user pressed a volume key: [delta] points up or down. */
    fun adjust(delta: Int) {
        if (delta == 0) return
        synchronized(lock) {
            val current = mutableShown.value
            if (current?.muted == true && delta > 0) {
                unmuteFirst = true
                wantedMute = null
                showAsked(muted = false)
            }
            val asked = wantedLevel
            if (asked != null) {
                val moved = (asked + delta).coerceIn(MIN_LEVEL, MAX_LEVEL)
                wantedLevel = moved
                showAsked(level = moved)
            } else {
                pendingDelta += delta
                mutableShown.value?.let { showAsked(level = (it.level + delta).coerceIn(MIN_LEVEL, MAX_LEVEL)) }
            }
            ensureSender()
        }
    }

    fun toggleMute() {
        synchronized(lock) {
            val target = !(mutableShown.value?.muted ?: false)
            unmuteFirst = false
            wantedMute = target
            showAsked(muted = target)
            ensureSender()
        }
    }

    /** Shows what was asked for and keeps the speaker's levels on the way there from moving it for a moment. */
    private fun showAsked(level: Int? = null, muted: Boolean? = null) {
        val current = mutableShown.value
        val until = now() + ECHO_MS
        if (level != null) {
            echoLevel = level
            echoUntilMs = until
        }
        if (muted != null) {
            echoMute = muted
            echoUntilMs = until
        }
        mutableShown.value = SonosVolume(level = level ?: current?.level ?: 0, muted = muted ?: current?.muted ?: false)
            .takeIf { current != null || level != null }
            ?: current
        scheduleSettle()
    }

    /** After the margin, whatever the speaker says is what is shown, also when it said nothing new. */
    private fun scheduleSettle() {
        val token = ++settleToken
        // Only the last one matters: a slider dragged for a second would leave dozens of sleeping coroutines.
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(ECHO_MS + 1)
            synchronized(lock) {
                if (token == settleToken && !busy()) {
                    echoLevel = null
                    echoMute = null
                    mutableShown.value = confirmed ?: reported.value
                }
            }
        }
    }

    private fun busy() = senderRunning || wantedLevel != null || pendingDelta != 0 || wantedMute != null || unmuteFirst

    private fun onReported(volume: SonosVolume?) {
        synchronized(lock) {
            if (volume == null) {
                confirmed = null
                mutableShown.value = null
                return
            }
            confirmed = null
            val inMargin = now() < echoUntilMs
            val keepLevel = inMargin && echoLevel != null && volume.level != echoLevel
            val keepMute = inMargin && echoMute != null && volume.muted != echoMute
            if (echoLevel == volume.level) echoLevel = null
            if (echoMute == volume.muted) echoMute = null
            val current = mutableShown.value
            mutableShown.value = SonosVolume(
                level = if (keepLevel) current?.level ?: volume.level else volume.level,
                muted = if (keepMute) current?.muted ?: volume.muted else volume.muted,
            )
        }
    }

    private fun ensureSender() {
        if (senderRunning) return
        senderRunning = true
        scope.launch { runSender() }
    }

    private suspend fun runSender() {
        while (true) {
            val work = synchronized(lock) {
                nextWork().also {
                    if (it == null) {
                        senderRunning = false
                        // Everything asked for was accepted: that is what the speaker has until it says otherwise, and the
                        // margin is counted again from here in case the last request was slow.
                        confirmed = mutableShown.value
                        scheduleSettle()
                    }
                }
            } ?: return
            val failure = when (work) {
                Work.Unmute -> sink.setMuted(false).failure()
                is Work.Mute -> sink.setMuted(work.muted).failure()
                Work.Level -> sendLevel()
                is Work.Relative -> when (val result = sink.adjust(work.delta)) {
                    is SonosResult.Ok -> {
                        synchronized(lock) {
                            val muted = mutableShown.value?.muted ?: false
                            // A level chosen meanwhile on a slider is what the user wants; otherwise the keys pressed
                            // meanwhile are not in the speaker's answer yet.
                            val level = (wantedLevel ?: (result.value + pendingDelta)).coerceIn(MIN_LEVEL, MAX_LEVEL)
                            echoLevel = level
                            echoUntilMs = now() + ECHO_MS
                            mutableShown.value = SonosVolume(level, muted)
                            scheduleSettle()
                        }
                        null
                    }
                    is SonosResult.Err -> result.failure
                }
            }
            if (failure != null) {
                synchronized(lock) {
                    wantedLevel = null
                    pendingDelta = 0
                    wantedMute = null
                    unmuteFirst = false
                    echoLevel = null
                    echoMute = null
                    confirmed = null
                    senderRunning = false
                    mutableShown.value = reported.value
                }
                onFailure(failure)
                return
            }
        }
    }

    /** A level goes out at least [MIN_GAP_MS] after the last one, and it is the latest asked for by then. */
    private suspend fun sendLevel(): SonosFailure? {
        val wait = lastAbsoluteAtMs?.let { it + MIN_GAP_MS - now() } ?: 0L
        if (wait > 0) delay(wait)
        val level = synchronized(lock) { wantedLevel.also { wantedLevel = null } } ?: return null
        lastAbsoluteAtMs = now()
        return sink.setVolume(level).failure()
    }

    private fun nextWork(): Work? = when {
        unmuteFirst -> Work.Unmute.also { unmuteFirst = false }
        wantedMute != null -> Work.Mute(checkNotNull(wantedMute)).also { wantedMute = null }
        wantedLevel != null -> Work.Level
        pendingDelta != 0 -> Work.Relative(pendingDelta).also { pendingDelta = 0 }
        else -> null
    }

    private fun <T> SonosResult<T>.failure(): SonosFailure? = (this as? SonosResult.Err)?.failure

    private companion object {
        const val MIN_LEVEL = 0
        const val MAX_LEVEL = 100
        const val ECHO_MS = 700L
        const val MIN_GAP_MS = 80L
    }
}
