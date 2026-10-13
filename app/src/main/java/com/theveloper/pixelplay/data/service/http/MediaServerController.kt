package com.theveloper.pixelplay.data.service.http

/** Starts and stops the media server process. Both calls return immediately. */
interface MediaServerController {

    /** Asks for the server to run. Safe to call while it already runs. Throws if the system refuses. */
    fun start(deviceIpHint: String?)
    fun stop()
}
