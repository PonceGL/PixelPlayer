package com.theveloper.pixelplay.data.service.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.theveloper.pixelplay.MainActivity
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.download.CloudDownloadStateStore
import com.theveloper.pixelplay.data.download.model.CloudDownloadProgress
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the cloud-download queue moving with the app closed. Deliberately thin: every real
 * decision (when to run a pass, when nothing's left worth staying alive for) lives in
 * [CloudDownloadServiceCoordinator], which doesn't know this class — or any `Service` —
 * exists. What's left here is purely Android plumbing: the notification, the lifecycle
 * callbacks, `onTimeout`.
 *
 * Shaped after `WatchTransferForegroundService`, with two deliberate differences:
 * [START_REDELIVER_INTENT] instead of `START_NOT_STICKY` (a service the system recreates
 * should pick the queue back up, not wait to be asked again — [CloudDownloadServiceCoordinator]
 * reconstructs everything from `cloud_downloads` on every pass regardless of why it's running,
 * so redelivery costs nothing extra), and `onTimeout` actually implemented (an unhandled
 * timeout crashes with `ForegroundServiceDidNotStopInTimeException` instead of just stopping).
 */
@AndroidEntryPoint
class CloudDownloadForegroundService : Service() {

    @Inject lateinit var coordinator: CloudDownloadServiceCoordinator
    @Inject lateinit var stateStore: CloudDownloadStateStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressObserverJob: Job? = null
    private var hasStartedForeground = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        observeProgress()
    }

    /** Idempotent: a second call while already in the foreground just re-triggers a pass —
     * see [CloudDownloadServiceCoordinator.triggerPass] for why that's safe to no-op instead
     * of overlapping. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasStartedForeground) {
            startInForeground(buildNotification(emptyList()))
        }
        coordinator.triggerPass(serviceScope) {
            stopForegroundCompat()
            stopSelf()
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        progressObserverJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Called on API 34 devices — API 35+ gets the two-argument overload below instead. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onTimeout(startId: Int) {
        Timber.tag(TAG).w("Foreground service hit its 6h runtime budget — stopping cleanly")
        stopSelf()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Timber.tag(TAG).w("Foreground service hit its 6h runtime budget (type=$fgsType) — stopping cleanly")
        stopSelf()
    }

    private fun observeProgress() {
        progressObserverJob?.cancel()
        progressObserverJob = serviceScope.launch {
            stateStore.progressByDownloadId.collect { progressMap ->
                if (!hasStartedForeground) return@collect // onStartCommand hasn't run yet
                notificationManager().notify(NOTIFICATION_ID, buildNotification(progressMap.values.toList()))
            }
        }
    }

    private fun startInForeground(notification: Notification) {
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            hasStartedForeground = true
        } catch (error: Exception) {
            // Covers a POST_NOTIFICATIONS denial and any other reason startForeground can
            // fail. The downloads keep running either way — the engine pass isn't gated on
            // this — the user just loses the one surface of control while the app is closed.
            // Requesting the permission, or offering an alternative surface, is a later
            // phase's job; here it's enough that this doesn't crash the service.
            Timber.tag(TAG).w(error, "Failed to start the cloud downloads foreground service")
        }
    }

    private fun stopForegroundCompat() {
        if (!hasStartedForeground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        hasStartedForeground = false
    }

    private fun buildNotification(progress: List<CloudDownloadProgress>): Notification {
        val active = progress.filterNot { it.state.isTerminal }
        val title = if (active.isEmpty()) {
            getString(R.string.cloud_download_notification_starting)
        } else {
            resources.getQuantityString(R.plurals.cloud_download_notification_title, active.size, active.size)
        }

        val totalExpected = active.map { it.expectedBytes }
            .takeIf { it.all { bytes -> bytes != null } }
            ?.sumOf { it!! }
        val totalDownloaded = active.sumOf { it.downloadedBytes }
        val percent = totalExpected?.takeIf { it > 0 }
            ?.let { (totalDownloaded * 100 / it).toInt().coerceIn(0, 100) }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.monochrome_player)
            .setContentTitle(title)
            .setContentIntent(createOpenAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(active.isNotEmpty())
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (percent != null) {
            builder.setContentText(
                getString(
                    R.string.cloud_download_notification_summary,
                    Formatter.formatShortFileSize(this, totalDownloaded),
                    Formatter.formatShortFileSize(this, totalExpected!!),
                )
            )
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, active.isNotEmpty())
        }

        return builder.build()
    }

    private fun createOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            setPackage(packageName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.cloud_download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.cloud_download_channel_description)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "CloudDownloadFgSvc"
        private const val NOTIFICATION_CHANNEL_ID = "pixelplay_cloud_downloads"
        private const val NOTIFICATION_ID = 1004

        /**
         * Returns whether the launch call itself succeeded — not whether the service went on
         * to reach the foreground (that's [startInForeground]'s own separate try/catch,
         * covering a `POST_NOTIFICATIONS` denial). A caller with no visible UI of its own (the
         * scheduler worker) needs this: `startForegroundService()` can throw on API 31+ when
         * called from a background execution context with none of the platform's foreground-
         * service-launch exemptions — this return value lets that caller react to it instead
         * of silently losing the attempt.
         */
        fun start(context: Context): Boolean {
            val intent = Intent(context, CloudDownloadForegroundService::class.java)
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { error ->
                Timber.tag(TAG).w(error, "Failed to start the cloud downloads foreground service")
            }.isSuccess
        }
    }
}
