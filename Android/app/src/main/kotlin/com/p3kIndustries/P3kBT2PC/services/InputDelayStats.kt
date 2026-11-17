package com.p3kIndustries.P3kBT2PC.services

import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.ArrayDeque

private const val WINDOW_SIZE = 200
private const val NANOS_PER_MILLI = 1_000_000L
private const val FIRST_SDK_WITH_EVENT_NANOS = 34

/**
 * Rolling average of how long Android took to hand each controller event to the app, measured from the kernel's
 * timestamp of the Bluetooth report. Recorded on the main thread; [meanMs] may be read from any thread.
 */
class InputDelayStats {
    private val recentDelaysMs = ArrayDeque<Double>()

    /** Mean of the last 200 delays in milliseconds, or null before the first controller event. */
    @Volatile
    var meanMs: Double? = null
        private set

    fun record(event: KeyEvent) {
        // Key events only carry millisecond timestamps, so these read up to 1 ms high.
        recordDelay(System.nanoTime() - event.eventTime * NANOS_PER_MILLI)
    }

    fun record(event: MotionEvent) {
        val eventNs = if (Build.VERSION.SDK_INT >= FIRST_SDK_WITH_EVENT_NANOS) {
            event.eventTimeNanos
        } else {
            event.eventTime * NANOS_PER_MILLI
        }
        recordDelay(System.nanoTime() - eventNs)
    }

    private fun recordDelay(delayNs: Long) {
        recentDelaysMs.addLast(delayNs.toDouble() / NANOS_PER_MILLI)
        while (recentDelaysMs.size > WINDOW_SIZE) recentDelaysMs.removeFirst()
        meanMs = recentDelaysMs.average()
    }
}
