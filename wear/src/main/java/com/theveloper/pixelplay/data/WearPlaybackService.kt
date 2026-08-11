package com.theveloper.pixelplay.data

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import timber.log.Timber

/**
 * Foreground [MediaSessionService] that hosts the watch's standalone local playback.
 *
 * Playback used to run inside a plain `@Singleton` repository owned by the Application, which left
 * the process with **no foreground component** once the activity went to the background. Wear OS
 * then reaped the cached process after a few minutes and audio died silently. Hosting the
 * ExoPlayer + MediaSession in a MediaSessionService lets media3 promote the process to a
 * "mediaPlayback" foreground service (with a media notification) whenever audio is playing, so the
 * OS keeps it alive until playback actually stops.
 *
 * [WearLocalPlayerRepository] drives this service's player through a `MediaController`.
 */
class WearPlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val isLowRamDevice = getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        val bufferProfile = wearLoadControlBufferProfileFor(isLowRamDevice)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferProfile.minBufferMs,
                bufferProfile.maxBufferMs,
                bufferProfile.bufferForPlaybackMs,
                bufferProfile.bufferForPlaybackAfterRebufferMs,
            )
            // Buffered *duration*, not buffered *bytes*, decides when to (re)start playback —
            // matches the phone's DualPlayerEngine and is what makes the profile above meaningful
            // across formats/bitrates instead of being overridden by ExoPlayer's byte threshold.
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Keep the CPU running while the watch dozes with the screen off, otherwise audio
            // decoding stalls a few seconds after the display turns off.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            // The default DefaultMediaSourceFactory registers ~15 extractor types (Matroska,
            // FLV, AVI, MPEG-TS…) that this service never plays — every file here comes from
            // [WatchAudioTranscoder] on the phone, which always writes plain (non-fragmented)
            // MP4/AAC-LC. ART verifies each extractor class the first time DefaultExtractorsFactory
            // touches it while sniffing the container, on the main thread; measured on-device this
            // cost 120-300ms per unused class, ~2s total, stacked right on top of playback start.
            // Scoping the factory to the one extractor we actually need removes that cost entirely.
            .setMediaSourceFactory(
                // Every other Mp4Extractor constructor/factory in this media3 version is
                // deprecated in favor of newFactory(SubtitleParser.Factory); our files are
                // audio-only, so subtitle parsing is simply unsupported.
                DefaultMediaSourceFactory(this, Mp4Extractor.newFactory(SubtitleParser.Factory.UNSUPPORTED))
            )
            .build()
        player = exoPlayer

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setId(MEDIA_SESSION_ID)
            .setSessionActivity(buildOpenAppIntent())
            .setCallback(MediaItemUriRestoringCallback())
            .build()

        Timber.tag(TAG).d("WearPlaybackService created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the user swipes the app away while nothing is playing, there's nothing to keep alive.
        val activePlayer = player
        if (activePlayer == null || !activePlayer.playWhenReady || activePlayer.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        Timber.tag(TAG).d("WearPlaybackService destroyed")
        super.onDestroy()
    }

    private fun buildOpenAppIntent(): PendingIntent {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: Intent()
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * A `MediaController` strips [MediaItem.localConfiguration] (the playable URI) when it hands
     * items across the binder to this service. The repository stashes the original URI in
     * `requestMetadata.mediaUri`; restore it here so the service's ExoPlayer can resolve the file.
     */
    private class MediaItemUriRestoringCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapTo(ArrayList(mediaItems.size)) { item ->
                if (item.localConfiguration != null) {
                    item
                } else {
                    item.requestMetadata.mediaUri
                        ?.let { uri -> item.buildUpon().setUri(uri).build() }
                        ?: item
                }
            }
            return Futures.immediateFuture(resolved)
        }
    }

    companion object {
        private const val TAG = "WearPlaybackService"
        private const val MEDIA_SESSION_ID = "wear-local-playback"
    }
}
