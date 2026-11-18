package com.niftysignal.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Owns the read-only market feed independently from MainActivity.
 * The Activity may detach while this started foreground service keeps polling.
 */
class MarketDataForegroundService : Service(), MarketDataFeed, MarketDataFeed.Listener {
    companion object {
        private const val CHANNEL_ID = "mgrow_live_feed_service"
        private const val NOTIFICATION_ID = 17420
    }

    inner class LocalBinder : Binder() {
        fun getService(): MarketDataForegroundService = this@MarketDataForegroundService
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<MarketDataFeed.Listener>()
    @Volatile private var feed: UpstoxWorkerMarketDataFeed? = null
    @Volatile private var latestSnapshot: MarketDataSnapshot? = null
    @Volatile private var latestState: FeedConnectionState = FeedConnectionState.CONNECTING
    @Volatile private var latestMessage: String? = "Starting secure market feed"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (feed == null) {
            latestState = FeedConnectionState.CONNECTING
            latestMessage = "Connecting securely to MGrow market data"
            val newFeed = UpstoxWorkerMarketDataFeed()
            feed = newFeed
            newFeed.connect(this)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun connect(listener: MarketDataFeed.Listener) {
        listeners.add(listener)
        listener.onStatusChanged(latestState, latestMessage)
        latestSnapshot?.let(listener::onSnapshot)
    }

    /** Detaches only the screen listener; the started service keeps the feed alive. */
    override fun disconnect() {
        // The Activity uses a single listener instance. Remove it without stopping the feed.
        listeners.clear()
    }

    fun refreshNow(onComplete: (() -> Unit)? = null) {
        feed?.refreshNow(onComplete) ?: onComplete?.invoke()
    }

    override fun onStatusChanged(status: FeedConnectionState, message: String?) {
        latestState = status
        latestMessage = message
        updateForegroundNotification(status, message)
        listeners.forEach { it.onStatusChanged(status, message) }
    }

    override fun onSnapshot(snapshot: MarketDataSnapshot) {
        latestSnapshot = snapshot
        listeners.forEach { it.onSnapshot(snapshot) }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits dataSync foreground services to six hours per 24-hour period.
        stopSelf(startId)
    }

    override fun onDestroy() {
        feed?.disconnect()
        feed = null
        listeners.clear()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MGrow Live Market Data",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when the read-only NIFTY market data connection is active."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification("Connecting to secure NIFTY data…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(status: FeedConnectionState, message: String?) {
        val text = when (status) {
            FeedConnectionState.LIVE -> "NIFTY live feed active • refreshes every 20 seconds"
            FeedConnectionState.CONNECTING -> "Connecting to secure NIFTY data…"
            FeedConnectionState.STALE -> "Market data is stale • signals paused"
            FeedConnectionState.ERROR -> message ?: "Market data connection error"
            FeedConnectionState.OFFLINE -> "Market data feed is offline"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(message: String): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            17420,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("MGrow Live Market Data")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
