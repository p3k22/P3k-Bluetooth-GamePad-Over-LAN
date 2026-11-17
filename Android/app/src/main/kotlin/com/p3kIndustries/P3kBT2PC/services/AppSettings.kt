package com.p3kIndustries.P3kBT2PC.services

import android.content.SharedPreferences

private const val KEY_HOST = "host"
private const val KEY_PORT = "port"
private const val KEY_TOUCH_MODE = "touchMode"
private const val DEFAULT_HOST = ""
private const val DEFAULT_PORT = "8080"

/** The values that survive between runs: the last Windows address and whether touchscreen mode was on. */
class AppSettings(private val preferences: SharedPreferences) {
    val host: String
        get() = preferences.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST

    val port: String
        get() = preferences.getString(KEY_PORT, DEFAULT_PORT) ?: DEFAULT_PORT

    var touchMode: Boolean
        get() = preferences.getBoolean(KEY_TOUCH_MODE, false)
        set(enabled) {
            preferences.edit().putBoolean(KEY_TOUCH_MODE, enabled).apply()
        }

    fun saveDestination(host: String, port: Int) {
        preferences.edit()
            .putString(KEY_HOST, host)
            .putString(KEY_PORT, port.toString())
            .apply()
    }
}
