package com.p3kIndustries.P3kBT2PC.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.p3kIndustries.P3kBT2PC.services.ForwardingSnapshot
import com.p3kIndustries.P3kBT2PC.services.ForwardingStatus

private val COLOUR_GREEN = Color.rgb(0x4A, 0xDE, 0x80)
private val COLOUR_ORANGE = Color.rgb(0xFB, 0x92, 0x3C)
private val COLOUR_RED = Color.rgb(0xF8, 0x71, 0x71)
private val COLOUR_GREY = Color.rgb(0x9C, 0xA3, 0xAF)
private val COLOUR_TEXT = Color.rgb(225, 234, 246)

private const val STATUS_TEXT_SIZE_SP = 22f
private const val DETAIL_TEXT_SIZE_SP = 13f
private const val BATTERY_GOOD_PERCENT = 50
private const val BATTERY_LOW_PERCENT = 20
private const val ACCESSIBILITY_HINT = "Enable Settings → Accessibility → P3k Gamepad controller capture " +
    "to keep forwarding while locked or in other apps"

/**
 * The status block under the connection row: the connection status in large text, the latest error, the
 * controller battery and name, and a hint to switch on the accessibility service when it is off.
 */
class StatusPanel(context: Context, onOpenAccessibilitySettings: () -> Unit) : LinearLayout(context) {
    private val statusText = createText(STATUS_TEXT_SIZE_SP, COLOUR_RED).apply { typeface = Typeface.DEFAULT_BOLD }
    private val errorText = createText(DETAIL_TEXT_SIZE_SP, COLOUR_RED)
    private val deviceText = createText(DETAIL_TEXT_SIZE_SP, COLOUR_TEXT)
    private val batteryText = createText(DETAIL_TEXT_SIZE_SP, COLOUR_GREY)
    private val accessibilityRow = createAccessibilityRow(onOpenAccessibilitySettings)

    init {
        orientation = VERTICAL
        addView(statusText)
        addView(errorText)
        addView(createDeviceRow())
        addView(accessibilityRow)
        showAccessibilityHint(false)
    }

    fun show(snapshot: ForwardingSnapshot) {
        statusText.text = statusLabel(snapshot)
        statusText.setTextColor(if (snapshot.status == ForwardingStatus.CONNECTED) COLOUR_GREEN else COLOUR_RED)
        errorText.text = snapshot.error.orEmpty()
        errorText.visibility = if (snapshot.error == null) GONE else VISIBLE
        deviceText.text = snapshot.deviceName
        showBattery(snapshot.controllerBattery)
    }

    fun showAccessibilityHint(isVisible: Boolean) {
        accessibilityRow.visibility = if (isVisible) VISIBLE else GONE
    }

    private fun statusLabel(snapshot: ForwardingSnapshot) = when (snapshot.status) {
        ForwardingStatus.NOT_CONNECTED -> "Not connected"
        ForwardingStatus.SEARCHING -> "Searching…"
        ForwardingStatus.CONNECTING -> "Connecting…"
        ForwardingStatus.CONNECTED -> "Connected to ${snapshot.host}:${snapshot.port}"
        ForwardingStatus.RECONNECTING -> "Reconnecting to ${snapshot.host}:${snapshot.port}…"
        ForwardingStatus.DISCONNECTED -> "Disconnected"
    }

    private fun showBattery(percent: Int?) {
        if (percent == null) {
            batteryText.text = "Controller battery: unknown"
            batteryText.setTextColor(COLOUR_GREY)
            return
        }
        batteryText.text = "Controller battery $percent%"
        batteryText.setTextColor(batteryColour(percent))
    }

    private fun batteryColour(percent: Int) = when {
        percent >= BATTERY_GOOD_PERCENT -> COLOUR_GREEN
        percent >= BATTERY_LOW_PERCENT -> COLOUR_ORANGE
        else -> COLOUR_RED
    }

    private fun createText(sizeSp: Float, colour: Int) = TextView(context).apply {
        textSize = sizeSp
        setTextColor(colour)
    }

    private fun createDeviceRow(): LinearLayout {
        val row = LinearLayout(context)
        row.addView(deviceText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(batteryText)
        return row
    }

    private fun createAccessibilityRow(onOpenSettings: () -> Unit): LinearLayout {
        val hintText = createText(DETAIL_TEXT_SIZE_SP, COLOUR_TEXT).apply { text = ACCESSIBILITY_HINT }
        val settingsButton = Button(context).apply {
            text = "Open Settings"
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            textSize = DETAIL_TEXT_SIZE_SP
            setOnClickListener { onOpenSettings() }
        }
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(hintText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(settingsButton)
        }
    }
}
