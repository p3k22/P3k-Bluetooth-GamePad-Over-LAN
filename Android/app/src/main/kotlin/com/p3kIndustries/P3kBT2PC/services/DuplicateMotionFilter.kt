package com.p3kIndustries.P3kBT2PC.services

import android.os.Build
import android.view.MotionEvent

private const val FIRST_SDK_WITH_EVENT_NANOS = 34

/**
 * While the accessibility service captures joystick motion and the activity is in front, Android shows
 * the same report to both. Both copies carry the same device and nanosecond time stamp, so the second is dropped.
 * Below Android 14 the accessibility service gets no motion events, so there is nothing to drop.
 */
class DuplicateMotionFilter {
    private var lastDeviceId = NO_DEVICE_ID
    private var lastEventTimeNs = NO_TIME

    fun isDuplicate(event: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT < FIRST_SDK_WITH_EVENT_NANOS) return false
        val isSameReport = event.deviceId == lastDeviceId && event.eventTimeNanos == lastEventTimeNs
        lastDeviceId = event.deviceId
        lastEventTimeNs = event.eventTimeNanos
        return isSameReport
    }

    private companion object {
        const val NO_DEVICE_ID = -1
        const val NO_TIME = -1L
    }
}
