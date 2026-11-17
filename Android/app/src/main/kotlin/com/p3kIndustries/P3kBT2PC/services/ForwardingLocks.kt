package com.p3kIndustries.P3kBT2PC.services

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager

private const val CPU_LOCK_TAG = "P3kGamepad:forwarding"
private const val SCREEN_LOCK_TAG = "P3kGamepad:screen"

/**
 * The power locks that keep forwarding alive: the Wi-Fi low latency lock and a CPU wake lock while connected,
 * and a screen wake lock while a Bluetooth controller is forwarded. Android only delivers controller input
 * to the accessibility service while the screen is on, so the screen lock keeps the locked phone awake.
 * Every lock is released as soon as its condition ends.
 */
class ForwardingLocks(context: Context) {
    private val wifiLatency = WifiLatencyLock(context)
    private val powerManager = context.applicationContext.getSystemService(PowerManager::class.java)
    private val cpuLock = createLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_LOCK_TAG)

    // SCREEN_DIM_WAKE_LOCK is deprecated for apps that show an activity (they should use FLAG_KEEP_SCREEN_ON),
    // but this service has no window and must keep the screen on while the phone is locked, which only a
    // wake lock still does.
    @Suppress("DEPRECATION")
    private val screenLock = createLock(PowerManager.SCREEN_DIM_WAKE_LOCK, SCREEN_LOCK_TAG)

    fun update(isConnected: Boolean, isForwardingController: Boolean) {
        wifiLatency.setEnabled(isConnected)
        setHeld(cpuLock, isConnected)
        setHeld(screenLock, isForwardingController)
    }

    fun releaseAll() = update(isConnected = false, isForwardingController = false)

    private fun createLock(levelAndFlags: Int, tag: String) =
        powerManager.newWakeLock(levelAndFlags, tag).apply { setReferenceCounted(false) }

    // The locks have no timeout on purpose: a connection can last for hours, and update() releases them
    // when it ends, as does the service's onDestroy.
    @SuppressLint("WakelockTimeout")
    private fun setHeld(lock: PowerManager.WakeLock, shouldHold: Boolean) {
        if (shouldHold && !lock.isHeld) lock.acquire()
        if (!shouldHold && lock.isHeld) lock.release()
    }
}
