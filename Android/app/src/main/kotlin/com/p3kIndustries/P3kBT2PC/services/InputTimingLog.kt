package com.p3kIndustries.P3kBT2PC.services

import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Writes controller timing to logcat (tag P3kTiming) so latency can be measured from a PC with
 * `adb logcat -s P3kTiming`. Event times are stamped by the kernel when the Bluetooth report arrives;
 * they and System.nanoTime() share Android's monotonic clock, so arrived - event is Android's own delivery delay.
 */
object InputTimingLog {
    private const val TAG = "P3kTiming"

    fun motion(event: MotionEvent) {
        val arrivedNs = System.nanoTime()
        val sampleTimes = (0 until event.historySize).map { historicalTimeNs(event, it) } + eventTimeNs(event)
        Log.d(TAG, "motion arrivedNs=$arrivedNs samplesNs=${sampleTimes.joinToString(",")}")
    }

    fun key(event: KeyEvent) {
        val arrivedNs = System.nanoTime()
        val eventNs = event.eventTime * 1_000_000
        Log.d(TAG, "key code=${event.keyCode} action=${event.action} arrivedNs=$arrivedNs eventNs=$eventNs")
    }

    fun sent(queueDelayMs: Double) = Log.d(TAG, "sent queueMs=$queueDelayMs")

    fun acknowledged(roundTripMs: Double, windowsApplyUs: Double) =
        Log.d(TAG, "ack rttMs=$roundTripMs applyUs=$windowsApplyUs")

    private fun eventTimeNs(event: MotionEvent) =
        if (Build.VERSION.SDK_INT >= 34) event.eventTimeNanos else event.eventTime * 1_000_000

    private fun historicalTimeNs(event: MotionEvent, position: Int) =
        if (Build.VERSION.SDK_INT >= 34) event.getHistoricalEventTimeNanos(position)
        else event.getHistoricalEventTime(position) * 1_000_000
}
