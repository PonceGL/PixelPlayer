package com.theveloper.pixelplay.spike.sonos

import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.theveloper.pixelplay.data.service.http.MediaFileHttpServerService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SPIKE (fase F). Observación "de campo" que NO depende de la pantalla ni del cable:
 *  - Un hilo a nivel de proceso (no de la Activity) que sobrevive a pantalla apagada y a cerrar la pantalla de depuración.
 *  - Cada 5 s escribe `field_tick` con pantalla/Doze/ahorro, batería, Wi-Fi, bucket de standby y si el servidor sigue vivo.
 *  - Un receptor registra `screen_on/off`, cambios de Doze y de ahorro de batería.
 *  - Opcionalmente toma WifiLock y/o WakeLock parcial (para comparar con el caso base, sin locks).
 *  - Lleva el monitor del Sonos (SonosQueue.monitor) en su propio hilo.
 * Todo se escribe a disco en cada evento: si el teléfono se desconecta de adb, el log queda intacto.
 */
object SonosField {

    data class Config(val wifiLock: String /* none | HIGH_PERF | LOW_LATENCY */, val wakeLock: Boolean)

    private val running = AtomicBoolean(false)
    private val stopFlag = AtomicBoolean(false)
    private var thread: Thread? = null
    private var monitorThread: Thread? = null
    private val monitorStop = AtomicBoolean(false)
    private var receiver: BroadcastReceiver? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var startedAt = 0L
    @Volatile private var lastConfig: Config? = null

    fun isRunning() = running.get()

    fun statusLine(): String {
        val c = lastConfig
        return if (!running.get()) "observación: detenida"
        else "observación: activa ${(SystemClock.elapsedRealtime() - startedAt) / 1000}s · wifiLock=${c?.wifiLock} wakeLock=${c?.wakeLock}"
    }

    @Synchronized
    fun start(context: Context, sonosIp: String?, config: Config) {
        if (running.get()) return
        val app = context.applicationContext
        val log = SonosSpike.current(app)
        stopFlag.set(false)
        startedAt = SystemClock.elapsedRealtime()
        lastConfig = config

        val pm = app.getSystemService(PowerManager::class.java)
        val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (config.wifiLock != "none") {
            val mode = when (config.wifiLock) {
                "LOW_LATENCY" -> if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                else -> @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "sonos-spike").apply { setReferenceCounted(false); acquire() }
        }
        if (config.wakeLock) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonos-spike:field").apply { setReferenceCounted(false); acquire() }
        }

        val rec = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val l = SonosSpike.current(app)
                l.event(
                    "field_broadcast", "action" to i.action,
                    "interactive" to pm.isInteractive, "doze" to pm.isDeviceIdleMode, "power_save" to pm.isPowerSaveMode,
                )
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(app, rec, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = rec

        log.event(
            "field_start", "wifi_lock" to config.wifiLock, "wake_lock" to config.wakeLock,
            "ignoring_battery_optimizations" to pm.isIgnoringBatteryOptimizations(app.packageName),
            "sdk" to Build.VERSION.SDK_INT, "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
            "battery" to battery(app), "wifi" to wifi(wm),
        )

        running.set(true)
        thread = Thread({ loop(app, pm, wm) }, "sonos-field").apply { isDaemon = false; start() }

        if (!sonosIp.isNullOrBlank()) {
            monitorStop.set(false)
            monitorThread = Thread({
                try {
                    SonosQueue.monitor(app, sonosIp, monitorStop, maxSeconds = 6 * 3600)
                } catch (t: Throwable) {
                    SonosSpike.current(app).err("Monitor falló: ${t.javaClass.simpleName}: ${t.message}")
                }
            }, "sonos-monitor").apply { isDaemon = false; start() }
        }
    }

    @Synchronized
    fun stop(context: Context) {
        if (!running.get()) return
        val app = context.applicationContext
        stopFlag.set(true)
        monitorStop.set(true)
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        wifiLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wifiLock = null
        wakeLock = null
        running.set(false)
        SonosSpike.current(app).event("field_stop", "elapsed_s" to (SystemClock.elapsedRealtime() - startedAt) / 1000)
    }

    private fun loop(app: Context, pm: PowerManager, wm: WifiManager) {
        var lastElapsed = SystemClock.elapsedRealtime()
        var lastUptime = SystemClock.uptimeMillis()
        var n = 0L
        while (!stopFlag.get()) {
            Thread.sleep(5000)
            val elapsed = SystemClock.elapsedRealtime()
            val uptime = SystemClock.uptimeMillis()
            val dElapsed = elapsed - lastElapsed
            val dUptime = uptime - lastUptime
            lastElapsed = elapsed
            lastUptime = uptime
            n++
            val log = SonosSpike.current(app)
            val usage = app.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            log.event(
                "field_tick",
                "n" to n,
                // Un tick tardío (late_ms alto) = el hilo no se ejecutó: CPU dormida o proceso limitado.
                "late_ms" to (dElapsed - 5000),
                "slept_ms" to (dElapsed - dUptime),
                "interactive" to pm.isInteractive,
                "doze" to pm.isDeviceIdleMode,
                "power_save" to pm.isPowerSaveMode,
                "ignoring_battery_opt" to pm.isIgnoringBatteryOptimizations(app.packageName),
                "standby_bucket" to runCatching { usage?.appStandbyBucket }.getOrNull(),
                "battery" to battery(app),
                "wifi" to wifi(wm),
                "server_running" to MediaFileHttpServerService.isServerRunning,
                "wifi_lock_held" to (wifiLock?.isHeld == true),
                "wake_lock_held" to (wakeLock?.isHeld == true),
                "pid" to Process.myPid(),
                "process_age_s" to (elapsed - Process.getStartElapsedRealtime()) / 1000,
            )
        }
    }

    private fun battery(app: Context): Map<String, Any?> {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return emptyMap()
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val bm = app.getSystemService(BatteryManager::class.java)
        return mapOf(
            "pct" to if (level >= 0) level * 100.0 / scale else null,
            "plugged" to i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            "temp_c" to i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0,
            "current_ua" to runCatching { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull(),
        )
    }

    @Suppress("DEPRECATION")
    private fun wifi(wm: WifiManager): Map<String, Any?> = runCatching {
        val c = wm.connectionInfo
        mapOf("rssi" to c.rssi, "link_mbps" to c.linkSpeed, "freq_mhz" to c.frequency)
    }.getOrDefault(emptyMap())
}
