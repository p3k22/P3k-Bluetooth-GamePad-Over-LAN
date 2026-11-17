package com.p3kIndustries.P3kBT2PC.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.p3kIndustries.P3kBT2PC.models.PadState
import com.p3kIndustries.P3kBT2PC.services.ForwardingSnapshot

/** The single screen of the app: title row, connection row, status lines, gamepad and metrics. */
class MainScreen(
    private val context: Context,
    private val listener: Listener,
    savedHost: String,
    savedPort: String,
    onGamepadChanged: (PadState) -> Unit,
) {
    interface Listener {
        fun onTouchModeToggled(enabled: Boolean)
        fun onResetCountsClicked()
        fun onShareLogClicked()
        fun onConnectClicked()
        fun onFindWindowsClicked()
        fun onOpenAccessibilitySettingsClicked()
    }

    val gamepad = GamepadView(context, onGamepadChanged)
    val root: LinearLayout

    private val touchModeSwitch = createTouchModeSwitch()
    private val hostField = createHostField(savedHost)
    private val portField = createPortField(savedPort)
    private val connectButton = createButton("Connect IP") { listener.onConnectClicked() }
    private val findButton = createButton("Find Windows") { listener.onFindWindowsClicked() }
    private val statusPanel = StatusPanel(context) { listener.onOpenAccessibilitySettingsClicked() }
    private val metricsText = createLabel("", 11f).apply { typeface = Typeface.MONOSPACE }

    init {
        root = createRoot()
        root.addView(createTitleRow())
        root.addView(createConnectionRow())
        root.addView(statusPanel)
        root.addView(gamepad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(metricsText)
        root.isFocusableInTouchMode = true
    }

    val enteredHost: String
        get() = hostField.text.toString()

    /** The port field as a number, or null when it is empty or not a number. */
    val enteredPort: Int?
        get() = portField.text.toString().toIntOrNull()

    fun setTouchModeChecked(enabled: Boolean) {
        touchModeSwitch.isChecked = enabled
    }

    fun showStatus(snapshot: ForwardingSnapshot) {
        statusPanel.show(snapshot)
    }

    fun showAccessibilityHint(isVisible: Boolean) {
        statusPanel.showAccessibilityHint(isVisible)
    }

    fun showMetrics(text: String) {
        metricsText.text = text
    }

    fun showConnectButtonBusy(busy: Boolean) {
        connectButton.text = if (busy) "Disconnect" else "Connect IP"
    }

    fun showSearching(searching: Boolean) {
        findButton.text = if (searching) "Cancel search" else "Find Windows"
    }

    /** Puts the connection target into the fields and puts the keyboard away. */
    fun showDestination(host: String, port: Int) {
        hostField.setText(host)
        portField.setText(port.toString())
        hideKeyboard()
    }

    fun hideKeyboard() {
        hostField.clearFocus()
        portField.clearFocus()
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(hostField.windowToken, 0)
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun createLabel(text: String, size: Float = 14f) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(Color.rgb(225, 234, 246))
    }

    private fun createButton(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        textSize = 13f
        setOnClickListener { onClick() }
    }

    private fun createRoot() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(3), dp(12), dp(4))
        setBackgroundColor(Color.rgb(27, 33, 44))
    }

    private fun createTouchModeSwitch() = Switch(context).apply {
        text = "Touchscreen mode"
        setTextColor(Color.WHITE)
        setOnCheckedChangeListener { _, enabled -> listener.onTouchModeToggled(enabled) }
    }

    private fun createHostField(savedHost: String) = EditText(context).apply {
        hint = "Windows IP / hostname"
        setSingleLine(true)
        textSize = 14f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.LTGRAY)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setText(savedHost)
        contentDescription = "Windows IP address"
    }

    private fun createPortField(savedPort: String) = EditText(context).apply {
        setSingleLine(true)
        textSize = 14f
        setTextColor(Color.WHITE)
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(savedPort)
        contentDescription = "Windows TCP port"
    }

    private fun createTitleRow(): LinearLayout {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(createLabel("P3k Gamepad over LAN", 19f), LinearLayout.LayoutParams(0, dp(40), 1f))
        row.addView(touchModeSwitch)
        row.addView(createButton("Reset counts") { listener.onResetCountsClicked() })
        row.addView(createButton("Share log") { listener.onShareLogClicked() })
        return row
    }

    private fun createConnectionRow(): LinearLayout {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(hostField, LinearLayout.LayoutParams(0, dp(45), 1f))
        row.addView(portField, LinearLayout.LayoutParams(dp(75), dp(45)))
        row.addView(connectButton)
        row.addView(findButton)
        return row
    }
}
