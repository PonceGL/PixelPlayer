package com.theveloper.pixelplay.data.service.http

/** Starts and stops the media server process. Both calls return immediately. */
internal interface MediaServerController {
    fun start(deviceIpHint: String?)
    fun stop()
}
