package com.theveloper.pixelplay.data.download.model

import kotlinx.serialization.Serializable

/**
 * A cached, per-server capability probe result.
 *
 * @property supportsRange Whether the server honors `Range` requests with a `206` instead of
 * silently ignoring them and returning a full `200` body — an ambiguity that causes a
 * truncate-and-restart loop if guessed wrong.
 * @property probedAt Wall-clock epoch millis of the last time this value was established —
 * either a real probe or a live invalidation ([DownloadServerCapabilitiesStore]). Wall-clock,
 * not [android.os.SystemClock.elapsedRealtime], because this must survive a reboot: a 30-day
 * TTL measured against a clock that resets to zero on every restart would never expire.
 */
@Serializable
data class ServerCapabilities(
    val supportsRange: Boolean,
    val probedAt: Long,
)
