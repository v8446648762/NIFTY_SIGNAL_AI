package com.niftysignal.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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

    private fun isWithinNiftyMarketHours(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
        calendar.timeInMillis = nowMillis
        val day = calendar.get(java.util.Calendar.DAY_OF_WEEK)
        val minuteOfDay = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            calendar.get(java.util.Calendar.MINUTE)
        return day != java.util.Calendar.SATURDAY &&
            day != java.util.Calendar.SUNDAY &&
            minuteOfDay >= (9 * 60 + 15) &&
            minuteOfDay < (15 * 60 + 30)
    }

    private fun dispatchMarketAlerts(snapshot: MarketDataSnapshot) {
        val ageMillis = System.currentTimeMillis() - snapshot.receivedAtMillis
        if (snapshot.isStale || ageMillis < -5000L || ageMillis > 90000L ||
            !isWithinNiftyMarketHours()
        ) return

        val permissionGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) return

        val prefs = getSharedPreferences("mgrow_alerts", MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return
        val five = snapshot.fiveMinuteCandles
        if (five.size < 22) return

        val latest5 = five.last()
        val previous5 = five[five.lastIndex - 1]
        val closes = five.map { it.close }
        val currentVwap = TechnicalAnalysis.sessionVwap(five)
        val previousVwap = TechnicalAnalysis.sessionVwap(five.dropLast(1))
        val currentEma9 = TechnicalAnalysis.ema(closes, 9)
        val currentEma21 = TechnicalAnalysis.ema(closes, 21)
        val previousEma9 = TechnicalAnalysis.ema(closes.dropLast(1), 9)
        val previousEma21 = TechnicalAnalysis.ema(closes.dropLast(1), 21)
        val decision = SignalEngine.evaluate(five, snapshot.oneMinuteCandles)

        fun sendOnce(category: String, eventKey: String, title: String, message: String, id: Int) {
            if (!prefs.getBoolean(category, false)) return
            val preferenceKey = "last_alert_${category}"
            if (prefs.getString(preferenceKey, null) == eventKey) return
            AlertNotifications.show(this, title, message, id)
            prefs.edit().putString(preferenceKey, eventKey).apply()
        }

        val biasLabel = when (decision.bias) {
            TradeBias.BUY_CE -> "BUY CE"
            TradeBias.BUY_PE -> "BUY PE"
            TradeBias.WAIT -> "WAIT"
        }
        if (decision.confirmed && decision.bias != TradeBias.WAIT) {
            sendOnce(
                "setup", "${latest5.timestampMillis}|${decision.bias.name}",
                "MGrow • ${biasLabel} setup",
                "5M setup and 1M confirmation detected near NIFTY ${String.format(java.util.Locale.US, "%.2f", latest5.close)}. Review the chart before acting.",
                9201
            )
        }

        if (currentVwap != null && previousVwap != null) {
            val crossedUp = previous5.close <= previousVwap && latest5.close > currentVwap
            val crossedDown = previous5.close >= previousVwap && latest5.close < currentVwap
            if (crossedUp || crossedDown) {
                val direction = if (crossedUp) "above" else "below"
                sendOnce(
                    "vwap", "${latest5.timestampMillis}|${if (crossedUp) "UP" else "DOWN"}",
                    "MGrow • VWAP breakout",
                    "NIFTY 5M candle crossed ${direction} VWAP near ${String.format(java.util.Locale.US, "%.2f", latest5.close)}.",
                    9202
                )
            }
        }

        if (currentEma9 != null && currentEma21 != null &&
            previousEma9 != null && previousEma21 != null
        ) {
            val crossedUp = previousEma9 <= previousEma21 && currentEma9 > currentEma21
            val crossedDown = previousEma9 >= previousEma21 && currentEma9 < currentEma21
            if (crossedUp || crossedDown) {
                val direction = if (crossedUp) "bullish" else "bearish"
                sendOnce(
                    "ema", "${latest5.timestampMillis}|${if (crossedUp) "UP" else "DOWN"}",
                    "MGrow • EMA 9/21 crossover",
                    "${direction.replaceFirstChar { it.uppercase() }} EMA 9/21 crossover on NIFTY 5M.",
                    9203
                )
            }
        }

        val previousBias = prefs.getString("last_observed_confirmed_bias", null)
        if (decision.confirmed && decision.bias != TradeBias.WAIT) {
            if (previousBias != null && previousBias != decision.bias.name) {
                sendOnce(
                    "reversal", "${latest5.timestampMillis}|${decision.bias.name}",
                    "MGrow • Trend reversal",
                    "Confirmed signal changed from ${previousBias} to ${decision.bias.name}. Check the live chart.",
                    9204
                )
            }
            prefs.edit().putString("last_observed_confirmed_bias", decision.bias.name).apply()
        }

        if (decision.confirmed && snapshot.oneMinuteCandles.size >= 2) {
            val latest1 = snapshot.oneMinuteCandles.last()
            val retestConfirmed = when (decision.bias) {
                TradeBias.BUY_CE -> latest1.low <= previous5.high * 1.0005 &&
                    latest1.close > previous5.high
                TradeBias.BUY_PE -> latest1.high >= previous5.low * 0.9995 &&
                    latest1.close < previous5.low
                TradeBias.WAIT -> false
            }
            if (retestConfirmed) {
                sendOnce(
                    "retest", "${latest1.timestampMillis}|${decision.bias.name}",
                    "MGrow • 1M retest confirmation",
                    "${decision.bias.name} retest/rejection condition detected near NIFTY ${String.format(java.util.Locale.US, "%.2f", latest1.close)}.",
                    9205
                )
            }
        }
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
