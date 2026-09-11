package com.theveloper.pixelplay.data.worker

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.theveloper.pixelplay.data.download.DownloadsFeatureGate
import com.theveloper.pixelplay.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps [CloudDownloadSchedulerWorker]'s periodic registration in `WorkManager` in sync with
 * [DownloadsFeatureGate.isEnabled], applied to a `WorkManager` entry rather than a UI screen:
 * with the feature off, nothing gets scheduled at all, not even a worker that would
 * immediately no-op.
 *
 * Reacts to the *flow*, not just a value read once at startup: [DownloadsFeatureGate.isEnabled]
 * can flip at runtime (the user's own switch, or the remote kill switch resolving after this
 * class was constructed), and a periodic registration made while the flag was briefly on must
 * not survive it turning back off.
 *
 * Constructed once, like [DownloadsFeatureGate] itself, and kept alive for the process'
 * lifetime by whoever forces this `@Singleton` into existence at startup — see
 * `PixelPlayApplication.onCreate`.
 */
@Singleton
class CloudDownloadScheduler @Inject constructor(
    private val workManager: WorkManager,
    featureGate: DownloadsFeatureGate,
    @AppScope appScope: CoroutineScope,
) {
    init {
        appScope.launch {
            featureGate.isEnabled.collect { enabled ->
                // Caught here, not left to propagate: an exception escaping this lambda would
                // terminate the whole collect — with no other code re-subscribing to
                // isEnabled, every flag change for the rest of the process' life would then be
                // silently ignored, not just this one enqueue/cancel call.
                try {
                    if (enabled) {
                        // KEEP means a device that already has this unique work registered
                        // ignores the request passed here entirely, constraints included — a
                        // future change to periodicWork()'s constraints needs either a new
                        // work name or ExistingPeriodicWorkPolicy.UPDATE to actually reach an
                        // install that enqueued the old one, not just new installs.
                        workManager.enqueueUniquePeriodicWork(
                            CloudDownloadSchedulerWorker.WORK_NAME,
                            ExistingPeriodicWorkPolicy.KEEP,
                            CloudDownloadSchedulerWorker.periodicWork(),
                        )
                    } else {
                        workManager.cancelUniqueWork(CloudDownloadSchedulerWorker.WORK_NAME)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    Timber.tag(TAG).e(error, "Failed to update the cloud download worker's schedule")
                }
            }
        }
    }

    private companion object {
        const val TAG = "CloudDownloadSched"
    }
}
