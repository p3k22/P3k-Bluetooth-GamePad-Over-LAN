package com.p3kIndustries.P3kBT2PC.services

import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import com.p3kIndustries.P3kBT2PC.events.ControllerBattery
import com.p3kIndustries.P3kBT2PC.events.Device
import com.p3kIndustries.P3kBT2PC.models.InputSnapshot
import com.p3kIndustries.P3kBT2PC.models.InputSource
import com.p3kIndustries.P3kBT2PC.models.PadState
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "P3kForwarder"
private const val RAW_KEY_LOG_TAG = "P3kInput"
private const val BATTERY_REFRESH_MS = 5_000L
private const val TOUCH_SOURCE_NAME = "Phone touchscreen"
private const val TOUCH_MODE_DEVICE_INFO = "Touchscreen gamepad · multitouch enabled"
private const val MIN_PORT = 1
private const val MAX_PORT = 65535
private const val MESSAGE_INVALID_DESTINATION = "Enter a Windows IP/hostname and port 1–65535"
private const val MESSAGE_DISCONNECT_FIRST = "Disconnect before searching for another Windows app"
private const val MESSAGE_NOTHING_FOUND = "No Windows app found. Use its IP or add its firewall exception."
private const val RECONNECT_DELAY_MS = 3_000L
private const val RECONNECT_GIVE_UP_MS = 10 * 60_000L
private const val MESSAGE_GAVE_UP = "Stopped reconnecting after 10 minutes. Tap Connect IP to try again."
private const val NOT_RECONNECTING = 0L

/**
 * Owns the whole forwarding pipeline for the process: the TCP link, the gamepad, the touch state, the LAN
 * search and the counters. The activity only shows it, and the foreground service and the accessibility
 * service keep it alive and feed it while the app is in the background.
 *
 * Everything here runs on the main thread (activity, accessibility callbacks, service callbacks). The link's
 * writer and reader threads report back by posting to the main thread, and [Server.send] only enqueues.
 */
class Forwarder private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val settings = AppSettings.forApplication(appContext)
    private val discovery = LanDiscovery()
    private val server = Server { event -> mainHandler.post { onServerEvent(event) } }
    private val device = Device { next -> accept(next, InputSource.BLUETOOTH) }
    private val inputManager = appContext.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val motionFilter = DuplicateMotionFilter()
    private val inputDelay = InputDelayStats()
    private val stateListeners = CopyOnWriteArrayList<() -> Unit>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var padStateObserver: ((PadState) -> Unit)? = null

    private var status = ForwardingStatus.NOT_CONNECTED
    private var error: String? = null
    private var host = settings.host
    private var port = settings.port.toIntOrNull() ?: 0
    private var touchMode = settings.touchMode
    private var latest = PadState()
    private var controllerBattery: Int? = null
    private var isUiVisible = false
    private var sessionWanted = settings.sessionWanted
    // Only a session that has worked (or is being resumed) is retried; a wrong address just fails.
    private var retryWhenLost = false
    private var reconnectStartedAtMs = NOT_RECONNECTING
    private val reconnectAttempt = Runnable { attemptReconnect() }

    /** Counters and the event list behind the metrics line and the shared log. */
    val inputLog = InputActivityLog()

    /** True while a Bluetooth controller's input is being sent to Windows. */
    val isForwardingController get() = status == ForwardingStatus.CONNECTED && !touchMode

    /** True from a connect until the user disconnects or retrying gives up, including while reconnecting. */
    val isSessionActive
        get() = sessionWanted && status in setOf(
            ForwardingStatus.CONNECTING,
            ForwardingStatus.CONNECTED,
            ForwardingStatus.RECONNECTING,
        )

    /** The lock screen is kept on while a controller session is active, because input stops with the screen off. */
    val keepsScreenAwake get() = isSessionActive && !touchMode

    private val batteryRefresh = object : Runnable {
        override fun run() {
            refreshControllerBattery()
            if (shouldTrackBattery()) mainHandler.postDelayed(this, BATTERY_REFRESH_MS)
        }
    }

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = onInputDevicesChanged()
        override fun onInputDeviceChanged(deviceId: Int) = onInputDevicesChanged()
        override fun onInputDeviceRemoved(deviceId: Int) = onInputDevicesChanged()
    }

    init {
        inputManager.registerInputDeviceListener(deviceListener, mainHandler)
        device.refresh()
        // The process may be starting because the accessibility service came back after an update or a kill.
        mainHandler.post { resumeIfWanted() }
    }

    // Observing

    fun snapshot() = ForwardingSnapshot(
        status = status,
        host = host,
        port = port,
        error = error,
        stats = server.stats,
        touchMode = touchMode,
        deviceName = if (touchMode) TOUCH_MODE_DEVICE_INFO else device.name,
        controllerBattery = controllerBattery,
        phoneInputMeanMs = if (touchMode) null else inputDelay.meanMs,
        padState = latest,
        isBusy = server.busy || status == ForwardingStatus.RECONNECTING,
    )

    /** Called when the connection status, the touch mode or the gamepad changes. */
    fun addStateListener(listener: () -> Unit) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: () -> Unit) {
        stateListeners.remove(listener)
    }

    /** Called with every new pad state, so the on-screen gamepad can follow it. Pass null to stop. */
    fun setPadStateObserver(observer: ((PadState) -> Unit)?) {
        padStateObserver = observer
    }

    /** While the screen is visible the controller battery is read even when nothing is being forwarded. */
    fun setUiVisible(isVisible: Boolean) {
        isUiVisible = isVisible
        updateBatteryTracking()
    }

    fun shareLogText(): String {
        val modeName = if (touchMode) "touch" else device.name
        return inputLog.shareText(modeName, server.stats)
    }

    // Connection and discovery

    /** Connects to a Windows app; an empty host or a port outside 1..65535 only sets an error message. */
    fun connect(host: String, port: Int?) {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty() || port == null || port !in MIN_PORT..MAX_PORT) {
            error = MESSAGE_INVALID_DESTINATION
            notifyChanged()
            return
        }
        connectTo(Destination(trimmedHost, port, "Manual"))
    }

    /** Ends the connection, the connect attempt or the reconnecting. Safe to call when nothing is connected. */
    fun disconnect() {
        setSessionWanted(false)
        cancelSearch()
        val wasReconnecting = status == ForwardingStatus.RECONNECTING
        server.disconnect()
        // While waiting between attempts the link is idle and reports nothing, so the session is ended here.
        if (wasReconnecting) endConnection(error = null)
    }

    /** Reconnects to the last Windows app when the previous session was cut short rather than ended by the user. */
    fun resumeIfWanted() {
        val hasDestination = host.isNotBlank() && port in MIN_PORT..MAX_PORT
        if (!sessionWanted || !hasDestination || server.busy || status == ForwardingStatus.RECONNECTING) return
        inputLog.addEntry("Resuming the previous session with $host:$port")
        Log.i(TAG, "Resuming the previous session with $host:$port")
        retryWhenLost = true
        connectTo(Destination(host, port, "Resumed"))
    }

    /** Searches the LAN for a Windows app and connects to the first one that answers. */
    fun find() {
        if (server.busy) {
            error = MESSAGE_DISCONNECT_FIRST
            notifyChanged()
            return
        }
        acquireMulticastLock()
        error = null
        status = ForwardingStatus.SEARCHING
        discovery.discover(
            found = { destination -> mainHandler.post { onServerFound(destination) } },
            completed = { mainHandler.post { onSearchFinished() } },
        )
        notifyChanged()
    }

    fun cancelSearch() {
        if (status != ForwardingStatus.SEARCHING) return
        stopSearching()
        status = ForwardingStatus.NOT_CONNECTED
        notifyChanged()
    }

    private fun connectTo(destination: Destination) {
        stopSearching()
        mainHandler.removeCallbacks(reconnectAttempt)
        host = destination.host
        port = destination.port
        settings.saveDestination(destination.host, destination.port)
        setSessionWanted(true)
        error = null
        status = ForwardingStatus.CONNECTING
        startForwardingService()
        server.connect(destination.host, destination.port, snapshotOf(latest))
        notifyChanged()
    }

    private fun onServerFound(destination: Destination) {
        if (status == ForwardingStatus.SEARCHING && !server.busy) connectTo(destination)
    }

    private fun onSearchFinished() {
        if (status != ForwardingStatus.SEARCHING) return
        stopSearching()
        status = ForwardingStatus.NOT_CONNECTED
        error = MESSAGE_NOTHING_FOUND
        notifyChanged()
    }

    private fun stopSearching() {
        discovery.cancel()
        if (multicastLock?.isHeld == true) multicastLock?.release()
        multicastLock = null
    }

    private fun acquireMulticastLock() {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("P3k-gamepad-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /** Runs on the main thread for every event the link reports. */
    private fun onServerEvent(event: ServerEvent) {
        inputLog.addEntry(event.message)
        // An end event that arrives after a newer connection was started belongs to the old connection.
        when (event.state) {
            LinkState.CONNECTING -> if (server.busy) changeStatus(ForwardingStatus.CONNECTING, error = null)
            LinkState.CONNECTED -> if (server.busy) onConnected()
            LinkState.DISCONNECTED -> if (!server.busy) endConnection(error = null)
            LinkState.FAILED -> if (!server.busy) endConnection(error = event.message)
        }
    }

    private fun onConnected() {
        retryWhenLost = true
        reconnectStartedAtMs = NOT_RECONNECTING
        changeStatus(ForwardingStatus.CONNECTED, error = null)
    }

    /** A lost link is retried while the session is wanted; otherwise, or after giving up, the session ends. */
    private fun endConnection(error: String?) {
        val shouldRetry = sessionWanted && retryWhenLost
        if (shouldRetry && !hasGivenUpReconnecting()) {
            scheduleReconnect(reason = error)
            return
        }
        setSessionWanted(false)
        retryWhenLost = false
        reconnectStartedAtMs = NOT_RECONNECTING
        mainHandler.removeCallbacks(reconnectAttempt)
        stopForwardingService()
        changeStatus(ForwardingStatus.DISCONNECTED, if (shouldRetry) MESSAGE_GAVE_UP else error)
    }

    private fun scheduleReconnect(reason: String?) {
        if (reconnectStartedAtMs == NOT_RECONNECTING) reconnectStartedAtMs = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(reconnectAttempt)
        mainHandler.postDelayed(reconnectAttempt, RECONNECT_DELAY_MS)
        changeStatus(ForwardingStatus.RECONNECTING, reason)
    }

    private fun hasGivenUpReconnecting() = reconnectStartedAtMs != NOT_RECONNECTING &&
        SystemClock.elapsedRealtime() - reconnectStartedAtMs >= RECONNECT_GIVE_UP_MS

    private fun attemptReconnect() {
        if (!sessionWanted || server.busy) return
        connectTo(Destination(host, port, "Reconnect"))
    }

    private fun setSessionWanted(wanted: Boolean) {
        sessionWanted = wanted
        settings.sessionWanted = wanted
    }

    private fun changeStatus(next: ForwardingStatus, error: String?) {
        status = next
        this.error = error
        notifyChanged()
    }

    private fun startForwardingService() {
        val serviceIntent = Intent(appContext, ForwardingService::class.java)
        try {
            ContextCompat.startForegroundService(appContext, serviceIntent)
        } catch (failure: IllegalStateException) {
            // Android refuses a foreground service start from the background; forwarding then lasts only
            // while the app is in front.
            Log.w(TAG, "Foreground service not started: ${failure.message}")
            inputLog.addEntry("Foreground service not started: ${failure.message}")
        }
    }

    private fun stopForwardingService() {
        appContext.stopService(Intent(appContext, ForwardingService::class.java))
    }

    // Touch input and mode

    /** Takes a new state from the touchscreen or the gamepad; a state from the source that is not active is ignored. */
    fun accept(next: PadState, source: InputSource) {
        if (source != activeSource()) return
        inputLog.recordChange(latest, next)
        latest = next
        padStateObserver?.invoke(next)
        server.send(snapshotOf(next))
    }

    fun setTouchMode(enabled: Boolean) {
        sendNeutralState()
        touchMode = enabled
        latest = if (enabled) PadState() else device.state
        padStateObserver?.invoke(latest)
        server.send(snapshotOf(latest))
        settings.touchMode = enabled
        notifyChanged()
    }

    /** The touch gamepad is let go when the app leaves the screen; the connection stays up. */
    fun releaseTouchInput() {
        if (touchMode && latest != PadState()) sendNeutralState()
    }

    private fun sendNeutralState() {
        latest = PadState()
        server.send(snapshotOf(latest))
    }

    private fun activeSource() = if (touchMode) InputSource.TOUCH else InputSource.BLUETOOTH

    private fun snapshotOf(state: PadState): InputSnapshot {
        val source = activeSource()
        return InputSnapshot(
            state = state,
            source = source.protocolName,
            deviceName = if (touchMode) TOUCH_SOURCE_NAME else device.name,
            capturedNs = SystemClock.elapsedRealtimeNanos(),
            controllerBattery = if (touchMode) null else controllerBattery,
            phoneInputMeanMs = if (touchMode) null else inputDelay.meanMs,
        )
    }

    // Controller events

    /** Returns true when the key belonged to the selected gamepad and was forwarded. */
    fun handleKey(event: KeyEvent): Boolean {
        val isController = Device.isController(event.device)
        // Measured before the state change is sent, so the frame carrying this event includes its delay.
        if (isController && !touchMode) inputDelay.record(event)
        val isForwarded = !touchMode && device.key(event)
        // Logging comes after the send so it never delays the input.
        if (isController) logRawControllerKey(event)
        if (isForwarded) InputTimingLog.key(event)
        return isForwarded
    }

    /** Returns true when the motion belonged to the selected gamepad (or was already handled from the other path). */
    fun handleMotion(event: MotionEvent): Boolean {
        if (touchMode) return false
        if (motionFilter.isDuplicate(event)) return true
        if (Device.isController(event.device)) inputDelay.record(event)
        if (!device.motion(event)) return false
        InputTimingLog.motion(event)
        return true
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

    private fun onInputDevicesChanged() {
        device.refresh()
        notifyChanged()
    }

    // Controller battery

    private fun shouldTrackBattery() = isUiVisible || isForwardingController

    /** Starts or stops the 5 second battery refresh, and refreshes at once when it is wanted. */
    private fun updateBatteryTracking() {
        mainHandler.removeCallbacks(batteryRefresh)
        if (shouldTrackBattery()) mainHandler.post(batteryRefresh)
    }

    /** Sends an extra frame when the level changed, so Windows learns it without waiting for a button press. */
    private fun refreshControllerBattery() {
        val level = ControllerBattery.percent(device.selectedId)
        if (level == controllerBattery) return
        controllerBattery = level
        if (isForwardingController) server.send(snapshotOf(latest))
    }

    private fun notifyChanged() {
        updateBatteryTracking()
        stateListeners.forEach { listener -> listener() }
    }

    companion object {
        @Volatile private var instance: Forwarder? = null

        fun get(context: Context): Forwarder = instance ?: synchronized(this) {
            instance ?: Forwarder(context.applicationContext).also { instance = it }
        }
    }
}
