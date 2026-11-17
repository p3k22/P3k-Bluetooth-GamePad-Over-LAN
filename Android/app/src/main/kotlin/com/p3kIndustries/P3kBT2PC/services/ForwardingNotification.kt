package com.p3kIndustries.P3kBT2PC.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.p3kIndustries.P3kBT2PC.MainActivity
import com.p3kIndustries.P3kBT2PC.R

private const val CHANNEL_ID = "forwarding"

/** The ongoing notification of [ForwardingService]: "Forwarding to <host>", tap to open, Disconnect action. */
object ForwardingNotification {
    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.forwarding_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context, host: String): Notification {
        val disconnectIntent = Intent(context, ForwardingService::class.java)
            .setAction(ForwardingService.ACTION_DISCONNECT)
        val disconnectAction = NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.forwarding_disconnect),
            PendingIntent.getService(context, 0, disconnectIntent, PENDING_INTENT_FLAGS),
        ).build()
        val openIntent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.forwarding_to, host))
            .setContentIntent(PendingIntent.getActivity(context, 0, openIntent, PENDING_INTENT_FLAGS))
            .addAction(disconnectAction)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private const val PENDING_INTENT_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}
