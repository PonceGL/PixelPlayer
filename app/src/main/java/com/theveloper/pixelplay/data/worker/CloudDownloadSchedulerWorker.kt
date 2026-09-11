package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.theveloper.pixelplay.data.download.DownloadsFeatureGate
import com.theveloper.pixelplay.data.download.engine.CloudDownloadEngine
import com.theveloper.pixelplay.data.service.download.CloudDownloadForegroundService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Keeps the cloud-download queue moving with the app closed, not just while
 * [CloudDownloadForegroundService] happens to already be running: this is what wakes a row out
 * of `RETRY_WAIT` once its backoff is over, and what recovers the queue after the app process
 * was killed outright — [CloudDownloadForegroundService]'s own `START_REDELIVER_INTENT` only
 * survives a *system-initiated* restart of an already-running service, not a process that was
 * never started again.
 *
 * Does no downloading itself. Its only job is the same one-line decision [shouldStartService]
 * makes: is the feature on, and is there anything queued worth starting the service for. The
 * actual work happens inside the service it starts.
 */
@HiltWorker
class CloudDownloadSchedulerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val featureGate: DownloadsFeatureGate,
    private val engine: CloudDownloadEngine,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val featureEnabled = featureGate.isEnabled.value
        // `&&` short-circuits: hasQueuedWork()'s DB read never runs once the flag is already
        // known off, instead of always paying for it only to discard the result.
        val hasQueuedWork = featureEnabled && engine.hasQueuedWork()
        if (!shouldStartService(featureEnabled, hasQueuedWork)) return Result.success()

        // A caller with no visible UI of its own (this worker) can have the launch itself
        // denied on API 31+ — see CloudDownloadForegroundService.start's own doc. Retrying
        // gives the next backoff window a chance to land in a state where it isn't; there is
        // no better signal available here than "it didn't work this time".
        return if (CloudDownloadForegroundService.start(applicationContext)) Result.success() else Result.retry()
    }

    companion object {
        const val WORK_NAME = "cloud_download_scheduler"

        /**
         * Pulled out of [doWork] purely so the decision itself — not [DownloadsFeatureGate] or
         * [CloudDownloadEngine] — is what a JVM test exercises. Two independent "no" reasons on
         * purpose, not one collapsed flag: a disabled feature must never start the service, even
         * with rows still sitting in the table from before it was turned off, and an empty queue
         * must never start it just because the feature happens to be on — see
         * [CloudDownloadForegroundService]'s own doc on a ghost notification.
         */
        internal fun shouldStartService(featureEnabled: Boolean, hasQueuedWork: Boolean): Boolean =
            featureEnabled && hasQueuedWork

        /**
         * `UNMETERED` is fixed here, not read from a preference — the setting that lets a user
         * choose `CONNECTED` instead is a later phase's job. Network only: no
         * `setRequiresCharging`/`setRequiresStorageNotLow` — those fit a once-a-day
         * maintenance sweep (see `SyncWorker.periodicMaintenanceWork`), not a queue a user is
         * actively waiting on, which should run as soon as a suitable network is back.
         */
        fun periodicWork(): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
            return PeriodicWorkRequestBuilder<CloudDownloadSchedulerWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        }
    }
}
