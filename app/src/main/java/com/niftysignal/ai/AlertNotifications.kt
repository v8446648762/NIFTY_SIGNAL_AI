package com.niftysignal.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object AlertNotifications {
    private const val CHANNEL_ID = "mgrow_market_alerts"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MGrow Market Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Price, indicator and confirmed setup alerts from MGrow"
            }
            manager.createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    fun show(context: Context, title: String, message: String, notificationId: Int) {
        createChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        manager.notify(notificationId, notification)
    }
}
