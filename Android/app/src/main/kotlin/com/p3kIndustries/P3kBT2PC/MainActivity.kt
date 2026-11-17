package com.p3kIndustries.P3kBT2PC

import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.p3kIndustries.P3kBT2PC.events.Device
import com.p3kIndustries.P3kBT2PC.models.InputSnapshot
import com.p3kIndustries.P3kBT2PC.models.PadState
import com.p3kIndustries.P3kBT2PC.services.AppSettings
import com.p3kIndustries.P3kBT2PC.services.Destination
import com.p3kIndustries.P3kBT2PC.services.InputActivityLog
import com.p3kIndustries.P3kBT2PC.services.InputTimingLog
import com.p3kIndustries.P3kBT2PC.services.LanDiscovery
import com.p3kIndustries.P3kBT2PC.services.Server
import com.p3kIndustries.P3kBT2PC.services.WifiLatencyLock
import com.p3kIndustries.P3kBT2PC.ui.MainScreen
import com.p3kIndustries.P3kBT2PC.ui.formatMetrics

private const val RAW_KEY_LOG_TAG = "P3kInput"
private const val METRICS_REFRESH_MS = 250L
private const val TOUCH_MODE_DEVICE_INFO = "Touchscreen gamepad · multitouch enabled"

/** Coordinates the screen, the gamepad input, the LAN discovery and the TCP link to the Windows app. */
class MainActivity : AppCompatActivity(), InputManager.InputDeviceListener, MainScreen.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val discovery = LanDiscovery()
    private val inputLog = InputActivityLog()
    private val server = Server { message -> mainHandler.post { onServerStatus(message) } }
    private val device = Device { next -> if (!touchMode) accept(next) }
    private lateinit var screen: MainScreen
    private lateinit var settings: AppSettings
    private lateinit var inputManager: InputManager
    private lateinit var wifiLatency: WifiLatencyLock
    private var multicastLock: WifiManager.MulticastLock? = null
    private var touchMode = false
    private var searching = false
    private var latest = PadState()

    private val refreshMetrics = object : Runnable {
        override fun run() {
            screen.showMetrics(formatMetrics(server.stats, inputLog))
            screen.showConnectButtonBusy(server.busy)
            screen.showDeviceInfo(if (touchMode) TOUCH_MODE_DEVICE_INFO else device.name)
            mainHandler.postDelayed(this, METRICS_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wifiLatency = WifiLatencyLock(applicationContext)
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(this, mainHandler)
        settings = AppSettings(getPreferences(Context.MODE_PRIVATE))
        showScreen()
        device.refresh()
        mainHandler.post(refreshMetrics)
        mainHandler.post { findWindows() }
    }

    private fun showScreen() {
        screen = MainScreen(this, this, settings.host, settings.port) { next -> if (touchMode) accept(next) }
        val root = screen.root
        setContentView(root)
        root.requestFocus()
        requestImmediateControllerInput(root)
        screen.setTouchModeChecked(settings.touchMode)
    }

    /**
     * By default Android holds joystick events until the next display frame (up to one frame, ~8–11 ms here).
     * Unbuffered dispatch hands each controller report to the app as soon as it arrives.
     */
    private fun requestImmediateControllerInput(focusedView: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            focusedView.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK or InputDevice.SOURCE_CLASS_BUTTON)
        }
    }

    // MainScreen.Listener

    override fun onTouchModeToggled(enabled: Boolean) {
        screen.gamepad.release()
        sendNeutralState()
        touchMode = enabled
        screen.gamepad.interactive = enabled
        latest = if (enabled) PadState() else device.state
        screen.gamepad.state = latest
        server.send(snapshot(latest))
        settings.touchMode = enabled
    }

    override fun onResetCountsClicked() {
        inputLog.reset()
    }

    override fun onShareLogClicked() {
        val modeName = if (touchMode) "touch" else device.name
        val logText = inputLog.shareText(modeName, server.stats)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logText)
        }
        startActivity(Intent.createChooser(shareIntent, "Share input log"))
    }

    override fun onConnectClicked() {
        if (server.busy) {
            cancelSearch()
            server.disconnect()
            return
        }
        val host = screen.enteredHost
        val port = screen.enteredPort
        if (host.isBlank() || port == null || port !in 1..65535) {
            screen.showStatus("Enter a Windows IP/hostname and port 1–65535")
        } else {
            connect(Destination(host.trim(), port, "Manual"))
        }
    }

    override fun onFindWindowsClicked() {
        if (searching) cancelSearch() else findWindows()
    }

    // Input state

    private fun snapshot(state: PadState) = InputSnapshot(
        state,
        if (touchMode) "touch" else "bluetooth",
        if (touchMode) "Phone touchscreen" else device.name,
        SystemClock.elapsedRealtimeNanos(),
    )

    private fun accept(next: PadState) {
        inputLog.recordChange(latest, next)
        latest = next
        screen.gamepad.state = next
        server.send(snapshot(next))
    }

    private fun sendNeutralState() {
        latest = PadState()
        server.send(snapshot(latest))
    }

    private fun onServerStatus(message: String) {
        screen.showStatus(message)
        inputLog.addEntry(message)
        wifiLatency.setEnabled(server.connected)
    }

    // Connection and discovery

    private fun connect(destination: Destination) {
        cancelSearch()
        screen.showDestination(destination.host, destination.port)
        settings.saveDestination(destination.host, destination.port)
        server.connect(destination.host, destination.port, snapshot(latest))
    }

    private fun findWindows() {
        if (server.busy) {
            screen.showStatus("Disconnect before searching for another Windows app")
            return
        }
        searching = true
        screen.showSearching(true)
        screen.showStatus("Searching LAN for Windows app…")
        acquireMulticastLock()
        discovery.discover(
            found = { destination -> mainHandler.post { onServerFound(destination) } },
            completed = { mainHandler.post { onSearchFinished() } },
        )
    }

    private fun onServerFound(destination: Destination) {
        if (searching && !server.busy) connect(destination)
    }

    private fun onSearchFinished() {
        if (!searching) return
        cancelSearch()
        screen.showStatus("No Windows app found. Use its IP or add its firewall exception.")
    }

    private fun cancelSearch() {
        searching = false
        discovery.cancel()
        screen.showSearching(false)
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("P3k-gamepad-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        if (multicastLock?.isHeld == true) multicastLock?.release()
        multicastLock = null
    }

    // Controller events

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Device.isController(event.device)) logRawControllerKey(event)
        if (!touchMode && device.key(event)) {
            InputTimingLog.key(event)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!touchMode && device.motion(event)) {
            InputTimingLog.motion(event)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun logRawControllerKey(event: KeyEvent) {
        val isFirstDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_X && isFirstDown) inputLog.recordRawSquareDown()
        if (event.repeatCount == 0 || event.action == KeyEvent.ACTION_UP) {
            val rawKeyText = "RAW key=${event.keyCode} action=${event.action} device=${event.deviceId} " +
                "selected=${device.selectedId} repeat=${event.repeatCount} scan=${event.scanCode} " +
                "time=${event.eventTime}"
            inputLog.addEntry(rawKeyText)
            Log.i(RAW_KEY_LOG_TAG, rawKeyText)
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        device.refresh()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        device.refresh()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        device.refresh()
    }

    // Lifecycle

    override fun onPause() {
        if (!isChangingConfigurations) {
            screen.gamepad.release()
            device.neutral()
            sendNeutralState()
            server.disconnect()
            cancelSearch()
            wifiLatency.setEnabled(false)
        }
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        discovery.cancel()
        server.disconnect()
        wifiLatency.setEnabled(false)
        inputManager.unregisterInputDeviceListener(this)
        super.onDestroy()
    }
}
