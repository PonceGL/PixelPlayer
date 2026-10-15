package com.theveloper.pixelplay.data.sonos.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.theveloper.pixelplay.data.sonos.session.SonosPowerLocks

/**
 * A speaker pulls its music from the phone, so while a session lasts neither the Wi-Fi radio nor the CPU may
 * sleep, even with the screen off. The wake lock has a long limit as a safety net against a session that is
 * never ended; a session that really lasts longer than that simply loses the lock.
 */
internal class AndroidSonosPowerLocks(context: Context) : SonosPowerLocks {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val lock = Any()
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun acquire() = synchronized(lock) {
        if (wifiLock?.isHeld != true) {
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wakeLock?.isHeld != true) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
                setReferenceCounted(false)
                acquire(MAX_HOLD_MS)
            }
        }
    }

    override fun release() = synchronized(lock) {
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        wakeLock = null
    }

    private companion object {
        const val TAG = "PixelPlayer:SonosSession"
        const val MAX_HOLD_MS = 12L * 60 * 60 * 1000
    }
}
