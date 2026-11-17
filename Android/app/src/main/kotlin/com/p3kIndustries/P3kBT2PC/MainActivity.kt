package com.p3kIndustries.P3kBT2PC

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.p3kIndustries.P3kBT2PC.models.InputSource
import com.p3kIndustries.P3kBT2PC.services.AppSettings
import com.p3kIndustries.P3kBT2PC.services.ControllerAccessibilityService
import com.p3kIndustries.P3kBT2PC.services.ForwardingSnapshot
import com.p3kIndustries.P3kBT2PC.services.Forwarder
import com.p3kIndustries.P3kBT2PC.ui.MainScreen
import com.p3kIndustries.P3kBT2PC.ui.formatLatency

private const val SCREEN_REFRESH_MS = 250L
private const val FIRST_SDK_WITH_NOTIFICATION_PERMISSION = 33
private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1

/**
 * The screen of the app. It shows what [Forwarder] reports and passes the user's actions to it. The connection
 * itself lives in the forwarder, so it survives this activity leaving the screen or the phone being locked.
 */
class MainActivity : AppCompatActivity(), MainScreen.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var forwarder: Forwarder
    private lateinit var settings: AppSettings
    private lateinit var screen: MainScreen
    private var shownDestination: Pair<String, Int>? = null

    private val refreshScreen = object : Runnable {
        override fun run() {
            showSnapshot(forwarder.snapshot())
            mainHandler.postDelayed(this, SCREEN_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        forwarder = Forwarder.get(this)
        settings = AppSettings.forApplication(this)
        showScreen()
        // The permission dialog pauses this activity, which cancels a search, so searching waits for the next tap.
        val isAskingPermission = requestNotificationPermission()
        if (!isAskingPermission) mainHandler.post { searchOnLaunch() }
    }

    /** A fresh launch looks for the Windows app at once; an app that is already connected is left alone. */
    private fun searchOnLaunch() {
        val snapshot = forwarder.snapshot()
        if (!snapshot.isBusy && !snapshot.isSearching) forwarder.find()
    }

    private fun showScreen() {
        screen = MainScreen(this, this, settings.host, settings.port) { next ->
            forwarder.accept(next, InputSource.TOUCH)
        }
        val root = screen.root
        setContentView(root)
        root.requestFocus()
        requestImmediateControllerInput(root)
        screen.setTouchModeChecked(forwarder.snapshot().touchMode)
        shownDestination = settings.host to (settings.port.toIntOrNull() ?: 0)
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

    /**
     * The foreground service's notification is only visible once the user allows notifications (Android 13+).
     * Returns true when the permission dialog was opened.
     */
    private fun requestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < FIRST_SDK_WITH_NOTIFICATION_PERMISSION) return false
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return false
        ActivityCompat.requestPermissions(this, arrayOf(permission), NOTIFICATION_PERMISSION_REQUEST_CODE)
        return true
    }

    // MainScreen.Listener

    override fun onTouchModeToggled(enabled: Boolean) {
        screen.gamepad.release()
        forwarder.setTouchMode(enabled)
        screen.gamepad.interactive = enabled
        screen.gamepad.state = forwarder.snapshot().padState
    }

    override fun onShareLogClicked() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, forwarder.shareLogText())
        }
        startActivity(Intent.createChooser(shareIntent, "Share input log"))
    }

    override fun onConnectClicked() {
        if (forwarder.snapshot().isBusy) {
            forwarder.disconnect()
            return
        }
        screen.hideKeyboard()
        forwarder.connect(screen.enteredHost, screen.enteredPort)
    }

    override fun onFindWindowsClicked() {
        if (forwarder.snapshot().isSearching) forwarder.cancelSearch() else forwarder.find()
    }

    override fun onOpenAccessibilitySettingsClicked() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // Showing the forwarder

    private fun showSnapshot(snapshot: ForwardingSnapshot) {
        showDestinationIfChanged(snapshot)
        screen.showStatus(snapshot)
        screen.showMetrics(formatLatency(snapshot))
        screen.showConnectButtonBusy(snapshot.isBusy)
        screen.showSearching(snapshot.isSearching)
    }

    /** A Windows app found by the LAN search puts its address into the fields. */
    private fun showDestinationIfChanged(snapshot: ForwardingSnapshot) {
        val destination = snapshot.host to snapshot.port
        if (snapshot.host.isBlank() || destination == shownDestination) return
        shownDestination = destination
        screen.showDestination(snapshot.host, snapshot.port)
    }

    // Controller events while the app is in front and the accessibility service does not deliver them

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return forwarder.handleKey(event) || super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return forwarder.handleMotion(event) || super.onGenericMotionEvent(event)
    }

    // Lifecycle: leaving the app or locking the phone never ends the connection.

    override fun onStart() {
        super.onStart()
        forwarder.setUiVisible(true)
        mainHandler.post(refreshScreen)
    }

    override fun onResume() {
        super.onResume()
        screen.showAccessibilityHint(!ControllerAccessibilityService.isEnabled(this))
        screen.gamepad.state = forwarder.snapshot().padState
        forwarder.setPadStateObserver { next -> screen.gamepad.state = next }
    }

    override fun onPause() {
        forwarder.setPadStateObserver(null)
        if (!isChangingConfigurations) {
            screen.gamepad.release()
            forwarder.releaseTouchInput()
            forwarder.cancelSearch()
        }
        super.onPause()
    }

    override fun onStop() {
        mainHandler.removeCallbacks(refreshScreen)
        forwarder.setUiVisible(false)
        super.onStop()
    }
}
