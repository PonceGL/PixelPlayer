package com.theveloper.pixelplay.data.worker

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.theveloper.pixelplay.data.download.DownloadsFeatureGate
import com.theveloper.pixelplay.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
                if (enabled) {
                    workManager.enqueueUniquePeriodicWork(
                        CloudDownloadSchedulerWorker.WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        CloudDownloadSchedulerWorker.periodicWork(),
                    )
                } else {
                    workManager.cancelUniqueWork(CloudDownloadSchedulerWorker.WORK_NAME)
                }
            }
        }
    }
}
