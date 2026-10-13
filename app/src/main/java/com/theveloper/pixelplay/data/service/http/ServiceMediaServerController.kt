package com.theveloper.pixelplay.data.service.http

import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** Runs the media server as the foreground service the app already declares. */
internal class ServiceMediaServerController @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaServerController {

    override fun start(deviceIpHint: String?) {
        val intent = Intent(context, MediaFileHttpServerService::class.java).apply {
            action = MediaFileHttpServerService.ACTION_START_SERVER
            deviceIpHint?.let { putExtra(MediaFileHttpServerService.EXTRA_CAST_DEVICE_IP, it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    override fun stop() {
        context.stopService(Intent(context, MediaFileHttpServerService::class.java))
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class MediaServerModule {

    @Binds
    abstract fun bindMediaServerController(impl: ServiceMediaServerController): MediaServerController
}
