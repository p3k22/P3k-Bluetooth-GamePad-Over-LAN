package com.p3kIndustries.P3kBT2PC.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.p3kIndustries.P3kBT2PC.events.Device

private const val FIRST_SDK_WITH_MOTION_SOURCES = 34

/**
 * Receives game controller buttons and sticks system-wide, also in other apps and on the lock screen
 * (joystick motion needs Android 14). It takes the events only while [Forwarder] is forwarding a Bluetooth
 * controller; otherwise it lets them through and stops asking for motion, so the controller keeps working on
 * the phone as usual. Logs the arrival timing of every controller event to logcat under tag P3kA11y.
 */
class ControllerAccessibilityService : AccessibilityService() {
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val forwarder by lazy { Forwarder.get(this) }
    private val stateListener: () -> Unit = { applyCaptureState() }
    private var isCapturingMotion = false

    // Screen and lock state for the log lines, refreshed only when it changes: asking Android on every event
    // costs two cross-process calls and added about 1.7 ms before each event was forwarded.
    private var phoneState = ""
    private var isPhoneStateReceiverRegistered = false
    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshPhoneState()
    }

    override fun onServiceConnected() {
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
        forwarder.addStateListener(stateListener)
        applyCaptureState()
        registerPhoneStateReceiver()
        Log.i(TAG, "connected sdk=${Build.VERSION.SDK_INT} $phoneState")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        forwarder.removeStateListener(stateListener)
        unregisterPhoneStateReceiver()
        return super.onUnbind(intent)
    }

    /** Returns true (the key is taken) only for controller keys while they are being forwarded. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!Device.isController(event.device)) {
            return false
        }
        val arrivedNs = System.nanoTime()
        val isForwarded = forwarder.isForwardingController
        // Forward first; the timing line is written afterwards so it never delays the input.
        if (isForwarded) forwarder.handleKey(event)
        val eventNs = event.eventTime * NANOS_PER_MILLI
        Log.d(
            TAG,
            "key code=${event.keyCode} action=${event.action} arrivedNs=$arrivedNs eventNs=$eventNs $phoneState",
        )
        return isForwarded
    }

    override fun onMotionEvent(event: MotionEvent) {
        val arrivedNs = System.nanoTime()
        if (forwarder.isForwardingController) forwarder.handleMotion(event)
        val eventNs = if (Build.VERSION.SDK_INT >= 34) event.eventTimeNanos else event.eventTime * NANOS_PER_MILLI
        val leftStickX = event.getAxisValue(MotionEvent.AXIS_X)
        Log.d(TAG, "motion arrivedNs=$arrivedNs samplesNs=$eventNs leftX=$leftStickX $phoneState")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        forwarder.removeStateListener(stateListener)
        unregisterPhoneStateReceiver()
        super.onDestroy()
    }

    private fun registerPhoneStateReceiver() {
        if (isPhoneStateReceiverRegistered) return
        val screenChanges = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // System broadcasts only, so the receiver need not be exported.
        ContextCompat.registerReceiver(this, phoneStateReceiver, screenChanges, ContextCompat.RECEIVER_NOT_EXPORTED)
        isPhoneStateReceiverRegistered = true
        refreshPhoneState()
    }

    private fun unregisterPhoneStateReceiver() {
        if (!isPhoneStateReceiverRegistered) return
        unregisterReceiver(phoneStateReceiver)
        isPhoneStateReceiverRegistered = false
    }

    private fun refreshPhoneState() {
        phoneState = "screenOn=${powerManager.isInteractive} locked=${keyguardManager.isKeyguardLocked}"
    }

    /** Asks Android for joystick motion only while it is being forwarded. Re-applied when that changes. */
    private fun applyCaptureState() {
        val shouldCapture = forwarder.isForwardingController
        if (shouldCapture == isCapturingMotion || Build.VERSION.SDK_INT < FIRST_SDK_WITH_MOTION_SOURCES) {
            return
        }
        val info = serviceInfo ?: return
        info.motionEventSources = if (shouldCapture) InputDevice.SOURCE_JOYSTICK else 0
        serviceInfo = info
        isCapturingMotion = shouldCapture
    }


    companion object {
        private const val TAG = "P3kA11y"
        private const val NANOS_PER_MILLI = 1_000_000L

        /** True when the user has switched this service on in Settings → Accessibility. */
        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java)
            val enabledServices = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val ownName = ComponentName(context, ControllerAccessibilityService::class.java)
            return enabledServices.any { enabled ->
                val serviceInfo = enabled.resolveInfo.serviceInfo
                serviceInfo.packageName == ownName.packageName && serviceInfo.name == ownName.className
            }
        }
    }
}
