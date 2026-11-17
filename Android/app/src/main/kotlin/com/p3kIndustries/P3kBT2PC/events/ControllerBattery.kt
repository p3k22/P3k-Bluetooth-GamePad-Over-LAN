package com.p3kIndustries.P3kBT2PC.events

import android.os.Build
import android.view.InputDevice
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

private const val FIRST_SDK_WITH_BATTERY_STATE = 31
private const val PERCENT_PER_FRACTION = 100

/** Reads the battery level of a game controller as reported by Android (Android 12 and newer). */
object ControllerBattery {
    /** The charge in percent (0..100), or null when the device is gone or Android does not know the level. */
    fun percent(deviceId: Int): Int? {
        if (Build.VERSION.SDK_INT < FIRST_SDK_WITH_BATTERY_STATE) return null
        return readPercent(deviceId)
    }

    @RequiresApi(FIRST_SDK_WITH_BATTERY_STATE)
    private fun readPercent(deviceId: Int): Int? {
        val inputDevice = InputDevice.getDevice(deviceId) ?: return null
        val batteryState = inputDevice.batteryState
        if (!batteryState.isPresent) return null
        val capacity = batteryState.capacity
        if (capacity.isNaN()) return null
        return (capacity * PERCENT_PER_FRACTION).roundToInt().coerceIn(0, PERCENT_PER_FRACTION)
    }
}
