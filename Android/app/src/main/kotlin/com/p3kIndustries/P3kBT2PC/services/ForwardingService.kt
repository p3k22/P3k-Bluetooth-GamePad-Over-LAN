package com.p3kIndustries.P3kBT2PC.services

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

private const val TAG = "P3kForwarding"
private const val FIRST_SDK_WITH_SERVICE_TYPES = 29
private const val NOTIFICATION_ID = 1

/**
 * Keeps the process in the foreground while a connection to Windows is open, so forwarding goes on when
 * the app is in the background or the phone is locked. [Forwarder] starts it with the connection and stops
 * it when the connection ends. The service itself only shows the notification and holds the power locks.
 */
class ForwardingService : Service() {
    private lateinit var forwarder: Forwarder
    private lateinit var locks: ForwardingLocks
    private val stateListener: () -> Unit = { applyForwardingState() }

    override fun onCreate() {
        super.onCreate()
        forwarder = Forwarder.get(this)
        locks = ForwardingLocks(this)
        ForwardingNotification.createChannel(this)
        forwarder.addStateListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires startForeground soon after startForegroundService, so it comes before anything else.
        if (!showNotification()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISCONNECT) {
            forwarder.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        applyForwardingState()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        forwarder.removeStateListener(stateListener)
        locks.releaseAll()
        super.onDestroy()
    }

    /** Returns false when Android refuses to make this a foreground service. */
    private fun showNotification(): Boolean {
        val notification = ForwardingNotification.build(this, forwarder.snapshot().host)
        return try {
            startForegroundWithType(notification)
            true
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Foreground service not allowed: ${error.message}")
            false
        } catch (error: SecurityException) {
            Log.w(TAG, "Foreground service permission missing: ${error.message}")
            false
        }
    }

    /** Foreground service types exist from Android 10; older versions take the notification alone. */
    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= FIRST_SDK_WITH_SERVICE_TYPES) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun applyForwardingState() {
        val isConnected = forwarder.snapshot().status == ForwardingStatus.CONNECTED
        locks.update(isConnected, forwarder.isForwardingController)
    }

    companion object {
        const val ACTION_DISCONNECT = "com.p3kIndustries.P3kBT2PC.action.DISCONNECT"
    }
}
