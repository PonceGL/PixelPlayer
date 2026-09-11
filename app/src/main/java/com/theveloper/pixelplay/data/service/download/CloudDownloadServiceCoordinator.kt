package com.theveloper.pixelplay.data.service.download

import com.theveloper.pixelplay.data.download.engine.CloudDownloadEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The Android-framework-free half of [CloudDownloadForegroundService]: when to actually run an
 * engine pass, and when the result of one means the service should stop. Split out so this —
 * the part with real decisions in it — is testable in a plain JVM test, without a `Service`,
 * `Context`, or any Android test infrastructure this project doesn't already have for one.
 */
@Singleton
class CloudDownloadServiceCoordinator @Inject constructor(
    private val engine: CloudDownloadEngine,
) {
    private var passJob: Job? = null

    /**
     * Launches one engine pass in [scope], unless one is already running — a second trigger
     * arriving mid-pass (two `onStartCommand` calls close together) is a no-op rather than a
     * second concurrent [CloudDownloadEngine.runQueue]: two overlapping passes could each try
     * to claim the same row before either finishes transitioning it out of `QUEUED`. The
     * in-flight pass already reflects the queue as of when it started; a row enqueued after
     * that isn't picked up until the *next* trigger, not this one — an accepted gap, not
     * silently pretended away.
     *
     * Calls [onIdle] once the pass finds no work worth keeping the service alive for —
     * [CloudDownloadEngine.hasActiveWork], not the in-memory progress store, decides that,
     * because the store only reflects what this process has already touched and a fresh
     * restart's store is empty even with real pending work.
     *
     * A pass that throws is caught here, not left to propagate: [scope] is a live foreground
     * `Service`'s scope with no installed exception handler, so an uncaught exception here
     * would crash the whole app process over one broken engine pass, not just fail this pass.
     * [onIdle] is deliberately **not** called on failure — a pass that never ran to completion
     * hasn't actually confirmed the queue is empty, so the safer default is to leave the
     * service running for the next trigger to retry, not to stop it on a false "nothing left".
     * [CancellationException] is rethrown, never swallowed: a cancelled pass isn't a failure to
     * log, it's the scope shutting down.
     */
    fun triggerPass(scope: CoroutineScope, onIdle: () -> Unit) {
        if (passJob?.isActive == true) return
        passJob = scope.launch {
            try {
                engine.start()
                if (!engine.hasActiveWork()) onIdle()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Timber.tag(TAG).e(error, "Cloud downloads engine pass failed")
            }
        }
    }

    private companion object {
        const val TAG = "CloudDownloadCoord"
    }
}
