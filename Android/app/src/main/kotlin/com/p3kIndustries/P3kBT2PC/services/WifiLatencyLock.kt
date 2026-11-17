package com.p3kIndustries.P3kBT2PC.services

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/** Effective only while connected to Wi-Fi, foregrounded and screen-on. */
class WifiLatencyLock(context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.WifiLock? = null

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            releaseLock()
            return
        }
        if (lock?.isHeld == true) return
        acquireLock()
    }

    private fun releaseLock() {
        if (lock?.isHeld == true) lock?.release()
        lock = null
    }

    private fun acquireLock() {
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        try {
            lock = wifiManager.createWifiLock(mode, "P3k-gamepad-low-latency").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: SecurityException) {
            // Input still works without the optional power optimisation.
            lock = null
        }
    }
}
