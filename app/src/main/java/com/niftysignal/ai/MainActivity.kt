package com.niftysignal.ai

import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import android.app.AlertDialog
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Switch
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.widget.Toast
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TextClock
import kotlin.math.max
import kotlin.math.min

class MainActivity : FragmentActivity() {
    private var appUnlocked = false
    private var authenticationInProgress = false

    private fun isAppLockEnabled(): Boolean =
        getSharedPreferences("mgrow_settings", MODE_PRIVATE).getBoolean("app_lock_enabled", false)

    override fun onResume() {
        super.onResume()
        if (::pageContent.isInitialized && isAppLockEnabled() && !appUnlocked && !authenticationInProgress) {
            authenticateAppLock()
        }

        // Re-evaluate the last validated feed snapshot when returning from another
        // screen (for example, after a fresh Upstox Option Chain fetch). This keeps
        // every visible panel on the same CentralMarketEngine result without
        // creating a second feed connection or using sample data.
        if (::pageContent.isInitialized && (!isAppLockEnabled() || appUnlocked)) {
            latestSnapshot?.let { applyMarketSnapshot(it) }
        }
    }

    private var marketFeedBound = false

    private val marketFeedServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val serviceBinder = binder as? MarketDataForegroundService.LocalBinder ?: return
            if (marketFeedBound && !isFinishing) {
                attachMarketDataFeed(serviceBinder.getService())
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            detachMarketDataFeed()
        }
    }

    override fun onStart() {
        super.onStart()
        val serviceIntent = Intent(this, MarketDataForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        marketFeedBound = bindService(serviceIntent, marketFeedServiceConnection, Context.BIND_AUTO_CREATE)
        startMarketIndicesFeed()
    }

    override fun onStop() {
        if (isAppLockEnabled() && !authenticationInProgress) appUnlocked = false
        detachMarketDataFeed()
        indicesFeed?.disconnect()
        indicesFeed = null
        if (marketFeedBound) {
            unbindService(marketFeedServiceConnection)
            marketFeedBound = false
        }
        super.onStop()
    }

    private fun authenticateAppLock() {
        if (authenticationInProgress || appUnlocked || !isAppLockEnabled()) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!keyguard.isDeviceSecure) {
            getSharedPreferences("mgrow_settings", MODE_PRIVATE).edit()
                .putBoolean("app_lock_enabled", false).apply()
            appUnlocked = true
            Toast.makeText(this, "Set a phone PIN, pattern or password to use App Lock.", Toast.LENGTH_LONG).show()
            return
        }
        authenticationInProgress = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticationInProgress = false
                    appUnlocked = true
                }

                override fun onAuthenticationFailed() {
                    Toast.makeText(this@MainActivity, "Fingerprint not recognized. Try again.", Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticationInProgress = false
                    Toast.makeText(this@MainActivity, "MGrow locked: $errString", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock MGrow")
            .setSubtitle("Verify your identity to access your market dashboard")
            .setDescription("Use fingerprint, face unlock, or your phone screen lock.")
            .setDeviceCredentialAllowed(true)
            .build()
        try {
            prompt.authenticate(promptInfo)
        } catch (error: Exception) {
            authenticationInProgress = false
            Toast.makeText(this, "Biometric lock is unavailable on this device.", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    @Suppress("DEPRECATION")
    private fun showMarketNotification(title: String, message: String, notificationId: Int) {
        val channelId = "mgrow_market_alerts"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "MGrow Market Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Price, indicator and confirmed setup alerts from MGrow" }
            manager.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
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

    // Premium midnight glass theme: high-contrast text with cyan/violet accents.
    private var bg = Color.rgb(16, 24, 39)
    private var surface = Color.argb(224, 29, 43, 67)
    private var surface2 = Color.argb(236, 36, 54, 83)
    private var border = Color.argb(210, 75, 101, 139)
    private var muted = Color.rgb(195, 208, 226)
    private var white = Color.rgb(255, 255, 255)
    private var green = Color.rgb(0, 200, 150)
    private var cyan = Color.rgb(0, 213, 255)
    private var red = Color.rgb(255, 85, 119)
    private var amber = Color.rgb(255, 200, 87)
    private var tf = "5 MIN"
    private var marketDataFeed: MarketDataFeed? = null
    private var latestSnapshot: MarketDataSnapshot? = null
    private var centralMarketState: CentralMarketState? = null
    private var currentPage = "Home"
    private var lastAiSignalKey: String? = null
    private lateinit var pageContent: LinearLayout
    private lateinit var bottomNav: LinearLayout

    private lateinit var chart: DemoChart
    private lateinit var tf5: TextView
    private lateinit var tf1: TextView

    private lateinit var feedStatusView: TextView
    private lateinit var indexPriceView: TextView
    private lateinit var indexHintView: TextView
    private var bankNiftyValueView: TextView? = null
    private var bankNiftyChangeView: TextView? = null
    private var bankNiftyStatusView: TextView? = null
    private var finNiftyValueView: TextView? = null
    private var finNiftyChangeView: TextView? = null
    private var finNiftyStatusView: TextView? = null
    private var indicesFeed: MarketIndicesFeed? = null
    private var latestIndicesSnapshot: MarketIndicesSnapshot? = null
    private lateinit var changeValueView: TextView
    private lateinit var dayHighValueView: TextView
    private lateinit var dayLowValueView: TextView
    private lateinit var signalStatusView: TextView
    private lateinit var signalBiasView: TextView
    private lateinit var signalReasonView: TextView
    private lateinit var signalTrendValueView: TextView
    private lateinit var signalSetupValueView: TextView
    private lateinit var signalConfirmValueView: TextView
    private var nextCandleDirectionView: TextView? = null
    private var nextCandleConfidenceView: TextView? = null
    private var nextCandleReasonView: TextView? = null
    private lateinit var vwapValueView: TextView
    private lateinit var ema9ValueView: TextView
    private lateinit var ema21ValueView: TextView
    private lateinit var rsiValueView: TextView
    private lateinit var supportValueView: TextView
    private lateinit var resistanceValueView: TextView
    private lateinit var chartModePill: TextView
    private lateinit var chartModeNoteView: TextView
    private lateinit var headerModePill: TextView
    private lateinit var workerConnectionDetailView: TextView
    private lateinit var candleCountsView: TextView
    private lateinit var receiptAgeView: TextView
    private var marketBiasView: TextView? = null
    private var marketDetailView: TextView? = null
    private var supertrendView: TextView? = null
    private var macdView: TextView? = null
    private var adxView: TextView? = null

    private val marketFeedListener = object : MarketDataFeed.Listener {
        override fun onStatusChanged(status: FeedConnectionState, message: String?) {
            runOnUiThread { updateFeedStatus(status, message) }
        }

        override fun onSnapshot(snapshot: MarketDataSnapshot) {
            runOnUiThread { applyMarketSnapshot(snapshot) }
        }
    }


    /**
     * Upstox market-data adapter. The dashboard consumes candles through
     * MarketDataFeed and never handles broker orders or broker secrets.
     */
    fun attachMarketDataFeed(feed: MarketDataFeed) {
        if (marketDataFeed === feed) return
        marketDataFeed?.disconnect()
        marketDataFeed = feed
        updateFeedStatus(FeedConnectionState.CONNECTING, "Connecting to market data")
        feed.connect(marketFeedListener)
    }

    fun detachMarketDataFeed() {
        marketDataFeed?.disconnect()
        marketDataFeed = null
        updateFeedStatus(FeedConnectionState.OFFLINE, "No market feed attached")
    }

    override fun onDestroy() {
        marketDataFeed?.disconnect()
        marketDataFeed = null
        super.onDestroy()
    }

    private fun updateFeedStatus(status: FeedConnectionState, message: String? = null) {
        val color = when (status) {
            FeedConnectionState.LIVE -> green
            FeedConnectionState.CONNECTING -> cyan
            FeedConnectionState.STALE, FeedConnectionState.ERROR -> red
            FeedConnectionState.OFFLINE -> amber
        }
        if (::feedStatusView.isInitialized) {
            feedStatusView.text = "● FEED " + status.name
            feedStatusView.setTextColor(color)
            feedStatusView.background = rounded(Color.argb(190, 41, 38, 66), color, 30)
        }
        if (::workerConnectionDetailView.isInitialized) {
            workerConnectionDetailView.text = when (status) {
                FeedConnectionState.LIVE -> "Upstox Worker • HTTPS • Live candles received"
                FeedConnectionState.STALE -> "Upstox Worker • Connected, but latest candles are stale"
                FeedConnectionState.CONNECTING -> "Upstox Worker • Checking secure connection…"
                FeedConnectionState.ERROR -> "Upstox Worker • " + (message ?: "Connection error")
                FeedConnectionState.OFFLINE -> "Upstox Worker • Offline"
            }
            workerConnectionDetailView.setTextColor(color)
        }
        if (::headerModePill.isInitialized) {
            headerModePill.text = when (status) {
                FeedConnectionState.LIVE -> "● LIVE DATA"
                FeedConnectionState.STALE -> "● STALE DATA"
                FeedConnectionState.CONNECTING -> "● CONNECTING"
                FeedConnectionState.ERROR -> "● FEED ERROR"
                FeedConnectionState.OFFLINE -> "● OFFLINE"
            }
            headerModePill.setTextColor(color)
            headerModePill.background = rounded(Color.argb(190, 41, 38, 66), color, 30)
        }
        if (status == FeedConnectionState.OFFLINE ||
            status == FeedConnectionState.ERROR ||
            status == FeedConnectionState.CONNECTING
        ) {
            clearLiveDashboard(message ?: "Live market data is unavailable.")
        }
    }

    private fun clearLiveDashboard(message: String) {
        latestSnapshot = null
        centralMarketState = null
        if (::indexPriceView.isInitialized) indexPriceView.text = "— — — — —"
        if (::indexHintView.isInitialized) indexHintView.text = message
        if (::changeValueView.isInitialized) changeValueView.text = "—"
        if (::dayHighValueView.isInitialized) dayHighValueView.text = "—"
        if (::dayLowValueView.isInitialized) dayLowValueView.text = "—"
        if (::candleCountsView.isInitialized) candleCountsView.text = "5-minute candles: waiting\n1-minute candles: waiting"
        if (::receiptAgeView.isInitialized) receiptAgeView.text = "Last data receipt: waiting"
        if (::signalBiasView.isInitialized) {
            signalBiasView.text = "WAIT"
            signalBiasView.setTextColor(amber)
        }
        if (::signalStatusView.isInitialized) {
            signalStatusView.text = "WAITING FOR LIVE DATA"
            signalStatusView.setTextColor(amber)
            signalStatusView.background = rounded(Color.argb(190, 41, 38, 66), amber, 30)
        }
        if (::signalReasonView.isInitialized) signalReasonView.text = message
        updateNextCandlePrediction(null)
        updateMarketIntelligence(null)
        updateRiskMeter(null)
        if (::signalTrendValueView.isInitialized) signalTrendValueView.text = "—"
        if (::signalSetupValueView.isInitialized) signalSetupValueView.text = "—"
        if (::signalConfirmValueView.isInitialized) signalConfirmValueView.text = "—"
        if (::vwapValueView.isInitialized) vwapValueView.text = "—"
        if (::ema9ValueView.isInitialized) ema9ValueView.text = "—"
        if (::ema21ValueView.isInitialized) ema21ValueView.text = "—"
        if (::rsiValueView.isInitialized) rsiValueView.text = "—"
        if (::supportValueView.isInitialized) supportValueView.text = "—"
        if (::resistanceValueView.isInitialized) resistanceValueView.text = "—"
        if (::chart.isInitialized) chart.setCandles(emptyList())
        if (::chartModePill.isInitialized) {
            chartModePill.text = "NO DATA"
            chartModePill.setTextColor(amber)
        }
        if (::chartModeNoteView.isInitialized) {
            chartModeNoteView.text = "WAITING FOR REAL MARKET CANDLES • NO SAMPLE DATA"
            chartModeNoteView.setTextColor(amber)
        }
    }

    private fun isValidMarketCandle(candle: MarketCandle): Boolean {
        val values = listOf(candle.open, candle.high, candle.low, candle.close, candle.volume)
        return values.all { it.isFinite() } &&
            candle.open > 0.0 && candle.high > 0.0 &&
            candle.low > 0.0 && candle.close > 0.0 &&
            candle.volume >= 0.0 &&
            candle.high >= maxOf(candle.open, candle.close, candle.low) &&
            candle.low <= minOf(candle.open, candle.close, candle.high)
    }

    private fun formatMarketValue(value: Double?): String {
        if (value == null || !value.isFinite()) return "—"
        return String.format(java.util.Locale.US, "%.2f", value)
    }

    private fun applyMarketSnapshot(incomingSnapshot: MarketDataSnapshot) {
        if (incomingSnapshot.fiveMinuteCandles.isEmpty() ||
            !incomingSnapshot.fiveMinuteCandles.all(::isValidMarketCandle) ||
            (incomingSnapshot.oneMinuteCandles.isNotEmpty() &&
                !incomingSnapshot.oneMinuteCandles.all(::isValidMarketCandle))
        ) {
            updateFeedStatus(FeedConnectionState.ERROR, "Feed returned empty or invalid OHLCV candles.")
            return
        }
        val central = CentralMarketEngine.evaluate(incomingSnapshot, System.currentTimeMillis())
        if (central == null) {
            updateFeedStatus(FeedConnectionState.ERROR, "Central engine rejected invalid or out-of-order market candles.")
            return
        }
        centralMarketState = central
        val snapshot = central.snapshot
        latestSnapshot = snapshot
        updateFeedStatus(
            if (central.isLive) FeedConnectionState.LIVE else FeedConnectionState.STALE,
            central.staleReason
        )

        val latest = snapshot.fiveMinuteCandles.last()
        if (::indexPriceView.isInitialized) indexPriceView.text = formatMarketValue(latest.close)
        if (::indexHintView.isInitialized) indexHintView.text = if (snapshot.isStale) {
            "Last available 5-minute NIFTY candle • NOT LIVE"
        } else {
            "Latest validated 5-minute NIFTY candle • Feed received just now"
        }

        val previousClose = snapshot.previousClose?.takeIf { it.isFinite() && it > 0.0 }
        if (::changeValueView.isInitialized) {
            if (previousClose != null) {
                val change = latest.close - previousClose
                val percent = change / previousClose * 100.0
                changeValueView.text = String.format(java.util.Locale.US, "%+.2f (%.2f%%)", change, percent)
                changeValueView.setTextColor(if (change >= 0.0) green else red)
            } else {
                changeValueView.text = "—"
                changeValueView.setTextColor(muted)
            }
        }

        val dayHigh = snapshot.dayHigh?.takeIf { it.isFinite() && it > 0.0 }
            ?: snapshot.fiveMinuteCandles.maxOfOrNull { it.high }
        val dayLow = snapshot.dayLow?.takeIf { it.isFinite() && it > 0.0 }
            ?: snapshot.fiveMinuteCandles.minOfOrNull { it.low }
        if (::dayHighValueView.isInitialized) dayHighValueView.text = formatMarketValue(dayHigh)
        if (::dayLowValueView.isInitialized) dayLowValueView.text = formatMarketValue(dayLow)
        if (::candleCountsView.isInitialized) candleCountsView.text =
            "5-minute candles: ${snapshot.fiveMinuteCandles.size}\n1-minute candles: ${snapshot.oneMinuteCandles.size}"
        if (::receiptAgeView.isInitialized) {
            val age = ((System.currentTimeMillis() - snapshot.receivedAtMillis).coerceAtLeast(0L) / 1000L)
            receiptAgeView.text = "Last data receipt: ${age}s ago • ${if (snapshot.isStale) "STALE / NOT LIVE" else "validated"}"
        }
        if (::chart.isInitialized) setTf(tf)

        // All AI panels consume the same validated CentralMarketState.
        // Stale snapshots are explicitly paused and never produce a live signal.
        val technical = central.signalDecision
        val unified = central.unifiedAiDecision
        val indicators = technical.indicators
        val liveAnalysis = central.isLive
        if (::signalBiasView.isInitialized) {
            signalBiasView.text = if (liveAnalysis) unified.bias.name else "WAIT"
            signalBiasView.setTextColor(
                if (!liveAnalysis) amber else when (unified.bias) {
                    TradeBias.BUY_CE -> green
                    TradeBias.BUY_PE -> red
                    else -> amber
                }
            )
        }
        if (::signalStatusView.isInitialized) {
            signalStatusView.text = if (liveAnalysis) unified.status else "AI PAUSED • NOT LIVE"
            signalStatusView.setTextColor(
                if (!liveAnalysis || unified.bias == TradeBias.WAIT) amber else green
            )
        }
        if (::signalReasonView.isInitialized) {
            signalReasonView.text = if (liveAnalysis) {
                (unified.reasons + unified.warnings).joinToString(" • ")
            } else central.staleReason ?: "Waiting for fresh validated market data."
        }
        if (::signalTrendValueView.isInitialized) signalTrendValueView.text =
            if (liveAnalysis) technical.trend else "PAUSED"
        if (::signalSetupValueView.isInitialized) signalSetupValueView.text =
            if (liveAnalysis) unified.alignmentScore?.let { "CONFLUENCE $it%" } ?: "WAIT" else "PAUSED"
        if (::signalConfirmValueView.isInitialized) {
            signalConfirmValueView.text = when {
                !liveAnalysis -> "PAUSED"
                unified.status == "ALL MODULES ALIGNED" -> "AI ALIGNED"
                unified.status == "CONFLICT • WAIT" -> "CONFLICT • WAIT"
                unified.status.startsWith("HIGH RISK") -> "HIGH RISK • WAIT"
                unified.bias == TradeBias.WAIT -> "CENTRAL WAIT"
                else -> "PARTIAL / WAIT"
            }
        }

        if (::vwapValueView.isInitialized) vwapValueView.text = formatMarketValue(indicators.vwap)
        if (::ema9ValueView.isInitialized) ema9ValueView.text = formatMarketValue(indicators.ema9)
        if (::ema21ValueView.isInitialized) ema21ValueView.text = formatMarketValue(indicators.ema21)
        if (::rsiValueView.isInitialized) rsiValueView.text = formatMarketValue(indicators.rsi14)
        if (::supportValueView.isInitialized) supportValueView.text = formatMarketValue(indicators.support)
        if (::resistanceValueView.isInitialized) resistanceValueView.text = formatMarketValue(indicators.resistance)

        updateMarketIntelligence(if (liveAnalysis) central.marketIntelligence else null)
        updateNextCandlePrediction(if (liveAnalysis) central.nextCandlePrediction else null)
        updateRiskMeter(if (liveAnalysis) central.riskAssessment else null)

    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, stroke: Int = border, radius: Int = 18): GradientDrawable =
        GradientDrawable().apply {
            colors = intArrayOf(
                color,
                Color.argb(
                    min(255, Color.alpha(color) + 12),
                    Color.red(color), Color.green(color), Color.blue(color)
                )
            )
            orientation = GradientDrawable.Orientation.TL_BR
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun selectedTypeface(style: Int): Typeface {
        val selected = getSharedPreferences("mgrow_settings", MODE_PRIVATE)
            .getString("font_family", "Roboto") ?: "Roboto"
        val family = when (selected) {
            "Times New Roman", "Georgia", "Garamond", "Baskerville", "Playfair Display",
            "Bodoni", "Merriweather", "Rockwell", "Arvo", "Roboto Slab", "Clarendon" -> "serif"
            "Fira Code", "JetBrains Mono", "Courier New" -> "monospace"
            "Pacifico", "Lobster", "Dancing Script", "Brush Script", "Caveat" -> "cursive"
            else -> "sans-serif"
        }
        return Typeface.create(family, style)
    }

    private fun text(s: String, size: Float = 14f, color: Int = white, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            typeface = selectedTypeface(if (bold) Typeface.BOLD else Typeface.NORMAL)
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(13), dp(15), dp(13))
        background = rounded(surface, Color.argb(150, 167, 139, 250))
    }

    private fun addGap(root: LinearLayout, h: Int = 12) {
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))
    }

    private fun helpButton(title: String, marathi: String, english: String): TextView =
        text("ⓘ", 17f, cyan, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(2), dp(5), dp(2))
            contentDescription = "Help: $title"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("$title  •  मराठी / English")
                    .setMessage("मराठी:\n$marathi\n\nEnglish:\n$english")
                    .setPositiveButton("समजले / Got it", null)
                    .show()
            }
        }

    private fun sectionHelp(title: String): Pair<String, String>? = when (title) {
        "AI SIGNAL ENGINE" -> Pair(
            "बाजारातील अटी तपासून BUY CE, BUY PE किंवा WAIT अशी सूचना देणारा विभाग. Live data जोडलेले आणि तपासलेले असेल तेव्हाच सिग्नल वापरायचा.",
            "Checks market conditions and suggests BUY CE, BUY PE, or WAIT. Use signals only after live data is connected and validated."
        )
        "LIVE MARKET CHART" -> Pair(
            "NIFTY चा कँडल चार्ट पाहण्यासाठी. 5 MIN मुख्य ट्रेंडसाठी आणि 1 MIN entry confirmation साठी आहे. सध्या हा फक्त sample preview आहे.",
            "Displays NIFTY candles. Use 5 MIN for the main trend and 1 MIN for entry confirmation. This is currently a sample preview only."
        )
        "TECHNICAL ANALYTICS" -> Pair(
            "VWAP, EMA आणि RSI सारखे indicators बाजाराची दिशा व momentum समजण्यास मदत करतात. आकडे Live Data जोडल्यावरच दिसतील.",
            "VWAP, EMA, and RSI help assess market direction and momentum. Values will appear only after live data is connected."
        )
        "MARKET LEVELS" -> Pair(
            "Support, Resistance, CPR आणि मागील दिवसाचे High/Low हे संभाव्य किंमत अडथळे किंवा आधार दाखवतात. हे निश्चित भाव नसतात.",
            "Support, resistance, CPR, and previous-day high/low show possible price reaction areas. They are not guaranteed levels."
        )
        "MARKET INTELLIGENCE" -> Pair(
            "Volume, trend strength आणि false breakout filters वापरून कमकुवत किंवा खोटे breakout ओळखण्यास मदत होते.",
            "Uses volume, trend-strength, and false-breakout filters to help assess whether a breakout may be weak or unreliable."
        )
        "SIGNAL ALERTS" -> Pair(
            "निवडलेल्या market conditions पूर्ण झाल्यावर सूचना मिळवण्यासाठी. Live data आणि alert logic जोडल्यावरच alerts सुरू होतील.",
            "Notifies you when selected market conditions occur. Alerts will activate only after live data and alert logic are connected."
        )
        "AI TRADE RISK METER" -> Pair(
            "Indicators, trend, price action आणि market sentiment यांतील जोखीम घटक एकत्र मोजून 0 ते 100 Risk Score दाखवेल. कमी score म्हणजे तुलनेने कमी risk; हा नफ्याचा probability नाही. Live data शिवाय score दाखवला जाणार नाही.",
            "Combines risk factors from indicators, trend, price action, and market sentiment into a 0–100 risk score. A lower score means comparatively lower assessed risk; it is not a probability of profit. No score is shown without validated live data."
        )
        else -> null
    }

    private fun section(root: LinearLayout, title: String, subtitle: String? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(text(title, 16f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (subtitle != null) row.addView(text(subtitle, 10f, muted, true))
        sectionHelp(title)?.let { row.addView(helpButton(title, it.first, it.second)) }
        root.addView(row)
        addGap(root, 8)
    }

    private fun pill(label: String, color: Int): TextView = text(label, 10f, color, true).apply {
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = rounded(Color.argb(190, 41, 38, 66), color, 30)
    }

    private fun metric(label: String, value: String, color: Int = white): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(label, 10f, muted, true))
            addView(text(value, 17f, color, true))
        }


    private fun dynamicMetric(
        label: String,
        value: String,
        color: Int = white,
        captureValue: (TextView) -> Unit
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(label, 10f, muted, true))
        val valueView = text(value, 15f, color, true)
        captureValue(valueView)
        addView(valueView)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(R.drawable.mgrow_splash)
        applySavedAppearance()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = false
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(bg, surface2, Color.rgb(7, 20, 15))
            )
            // Keep content and the bottom navigation clear of Android system bars,
            // including gesture navigation and Android 15 edge-to-edge layouts.
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    view.setPadding(0, bars.top, 0, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        0,
                        insets.systemWindowInsetTop,
                        0,
                        insets.systemWindowInsetBottom
                    )
                }
                insets
            }
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding(dp(16), dp(14), dp(16), dp(18))
        }
        pageContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(pageContent)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        bottomNav = LinearLayout(this).apply { visibility = View.GONE }
        root.addView(bottomNav, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        root.requestApplyInsets()
        showPage("Home")
        // The foreground service owns the read-only Cloudflare Worker feed across Activity lifecycles.
    }

    private fun isProPanelEnabled(panelNumber: Int): Boolean =
        getSharedPreferences("mgrow_settings", MODE_PRIVATE)
            .getBoolean("pro_panel_$panelNumber", true)

    private fun showPage(page: String) {
        val allowedPages = setOf("Home", "AI", "Chart", "Options", "More")
        currentPage = page.takeIf { it in allowedPages } ?: "Home"
        pageContent.removeAllViews()
        when (currentPage) {
            "Home" -> pageContent.addView(buildHomeDashboard())
            "AI" -> {
                pageContent.addView(buildPageHeading("AI INTELLIGENCE", "Validated NIFTY intraday analysis"))
                pageContent.addView(buildSignalCard()); addGap(pageContent, 12)
                pageContent.addView(buildInsights()); addGap(pageContent, 12)
                pageContent.addView(buildNextCandleCard()); addGap(pageContent, 12)
                pageContent.addView(buildRiskMeter()); addGap(pageContent, 12)
                pageContent.addView(buildEntryRiskTargetCard()); addGap(pageContent, 12)
                pageContent.addView(buildAiDetectionCenter())
            }
            "Chart" -> {
                pageContent.addView(buildPageHeading("CHART & INDICATORS", "Live candles and technical studies"))
                pageContent.addView(buildChartCard()); addGap(pageContent, 12)
                pageContent.addView(buildIndicators()); addGap(pageContent, 12)
                pageContent.addView(buildLevels()); addGap(pageContent, 12)
                pageContent.addView(buildAdvancedMarketStudies())
            }
            "Options" -> {
                pageContent.addView(buildPageHeading("NIFTY OPTION CHAIN", "Read-only Upstox option-chain snapshot"))
                val panel = card()
                panel.addView(text("LIVE OPTION CHAIN", 12f, cyan, true)); addGap(panel, 8)
                panel.addView(text("View strikes, CE / PE OI, PCR and available option-chain snapshot data. This is market analysis only; no broker orders are placed.", 11f, muted))
                addGap(panel, 12)
                panel.addView(Button(this).apply {
                    text = "OPEN OPTION CHAIN"
                    setOnClickListener { startActivity(Intent(this@MainActivity, LiveOptionChainActivity::class.java)) }
                }, LinearLayout.LayoutParams(-1, dp(48)))
                pageContent.addView(panel)
            }
            "More" -> {
                pageContent.addView(buildPageHeading("MORE TOOLS", "Reports, alerts and app preferences"))
                pageContent.addView(buildReports()); addGap(pageContent, 12)
                pageContent.addView(buildAlerts()); addGap(pageContent, 12)
                pageContent.addView(buildSystemHealthPanel()); addGap(pageContent, 12)
                pageContent.addView(buildNewsUnavailablePanel()); addGap(pageContent, 12)
                pageContent.addView(buildSettings())
            }
        }
        renderBottomNavigation()
        latestSnapshot?.let { applyMarketSnapshot(it) }
        latestIndicesSnapshot?.let { applyMarketIndicesSnapshot(it) }
    }

    private fun buildPageHeading(title: String, subtitle: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(7), dp(2), dp(15))
            addView(text(title, 20f, white, true))
            addView(text(subtitle, 11f, muted))
        }

    private fun renderBottomNavigation() {
        bottomNav.removeAllViews()
        bottomNav.orientation = LinearLayout.HORIZONTAL
        bottomNav.setPadding(dp(5), dp(5), dp(5), dp(5))
        bottomNav.background = rounded(Color.argb(245, 10, 19, 31), border, 16)
        val items = listOf(
            "Home" to "⌂",
            "AI" to "✧",
            "Chart" to "▥",
            "Options" to "◈",
            "More" to "•••"
        )
        items.forEach { (page, symbol) ->
            val selected = currentPage == page
            val item = TextView(this).apply {
                text = "$symbol\n$page"
                gravity = Gravity.CENTER
                textSize = 10f
                setLineSpacing(0f, 0.95f)
                typeface = selectedTypeface(if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (selected) cyan else muted)
                setPadding(dp(2), dp(7), dp(2), dp(7))
                background = if (selected) rounded(Color.argb(220, 28, 47, 73), cyan, 12) else null
                isClickable = true
                isFocusable = true
                contentDescription = "$page section"
                setOnClickListener { showPage(page) }
            }
            bottomNav.addView(item, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                leftMargin = dp(2); rightMargin = dp(2)
            })
        }
        bottomNav.visibility = View.VISIBLE
    }

    private fun buildFeatureGrid(): View {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun tile(title: String, subtitle: String, content: View): LinearLayout {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            }
            val heading = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(2), 0, dp(2), dp(6))
            }
            heading.addView(text(title, 12f, white, true))
            heading.addView(text(subtitle, 8f, muted, true))
            column.addView(heading)
            column.addView(content, LinearLayout.LayoutParams(-1, -2))
            return column
        }

        fun addPair(left: LinearLayout, right: LinearLayout) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            row.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
            row.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
            outer.addView(row)
            addGap(outer, 14)
        }

        addPair(
            tile("TECHNICAL ANALYTICS", "WAITING FOR LIVE FEED", buildIndicators()),
            tile("MARKET LEVELS", "WAITING FOR LIVE FEED", buildLevels())
        )
        addPair(
            tile("MARKET INTELLIGENCE", "LIVE FILTERS NOT ACTIVE YET", buildInsights()),
            tile("SIGNAL ALERTS", "SETTINGS READY • TRIGGERS PENDING", buildAlerts())
        )
        return outer
    }

    private fun buildDateTimeView(): LinearLayout {
        val dateClock = TextClock(this).apply {
            format12Hour = "EEE, dd MMM yyyy"
            format24Hour = "EEE, dd MMM yyyy"
            textSize = 11f
            setTextColor(muted)
            timeZone = java.util.TimeZone.getDefault().id
        }
        val timeClock = TextClock(this).apply {
            format12Hour = "hh:mm:ss a"
            format24Hour = "HH:mm:ss"
            textSize = 13f
            setTextColor(cyan)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            timeZone = java.util.TimeZone.getDefault().id
        }
        val is24 = android.text.format.DateFormat.is24HourFormat(this)
        dateClock.setFormat12Hour("EEE, dd MMM yyyy")
        dateClock.setFormat24Hour("EEE, dd MMM yyyy")
        timeClock.setFormat12Hour(if (is24) "HH:mm:ss" else "hh:mm:ss a")
        timeClock.setFormat24Hour("HH:mm:ss")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(dateClock)
            addView(timeClock)
        }
    }

    private fun buildSystemHealthPanel(): View {
        val box = card()
        val snapshot = latestSnapshot
        val ageSeconds = snapshot?.let {
            ((System.currentTimeMillis() - it.receivedAtMillis).coerceAtLeast(0L) / 1000L)
        }
        val status = when {
            snapshot == null -> "WAITING FOR FIRST SNAPSHOT"
            snapshot.isStale -> "STALE • NOT LIVE"
            else -> "FRESH VALIDATED SNAPSHOT"
        }
        box.addView(text("MARKET FEED HEALTH", 11f, cyan, true))
        addGap(box, 6)
        box.addView(text(status, 15f, if (snapshot != null && !snapshot.isStale) green else amber, true))
        addGap(box, 6)
        box.addView(text("Last receipt age: ${ageSeconds?.let { "$it sec" } ?: "--"}", 10f, muted))
        box.addView(text("5-minute candles: ${snapshot?.fiveMinuteCandles?.size ?: 0}", 10f, muted))
        box.addView(text("1-minute candles: ${snapshot?.oneMinuteCandles?.size ?: 0}", 10f, muted))
        box.addView(text("Order execution: DISABLED • Broker access: READ ONLY", 10f, amber, true))
        return box
    }

    private fun buildNewsUnavailablePanel(): View {
        val box = card()
        box.addView(text("NEWS FEED: DATA UNAVAILABLE", 12f, amber, true))
        addGap(box, 5)
        box.addView(text(
            "No verified news provider is connected to MGrow. Headlines and sentiment will remain unavailable rather than showing invented or unverified news.",
            10f, muted
        ))
        return box
    }

    private fun startMarketIndicesFeed() {
        if (indicesFeed != null) return
        val feed = MarketIndicesFeed()
        indicesFeed = feed
        feed.connect(object : MarketIndicesFeed.Listener {
            override fun onIndices(snapshot: MarketIndicesSnapshot) {
                runOnUiThread { applyMarketIndicesSnapshot(snapshot) }
            }

            override fun onError(message: String) {
                runOnUiThread { showMarketIndicesUnavailable(message) }
            }
        })
    }

    private fun applyMarketIndicesSnapshot(snapshot: MarketIndicesSnapshot) {
        latestIndicesSnapshot = snapshot
        fun render(
            quote: MarketIndexQuote?,
            valueView: TextView?,
            changeView: TextView?,
            statusView: TextView?
        ) {
            if (quote == null) {
                valueView?.text = "—"
                changeView?.text = "Quote unavailable"
                statusView?.text = "● NOT AVAILABLE"
                statusView?.setTextColor(amber)
                return
            }
            valueView?.text = String.format(java.util.Locale.US, "%,.2f", quote.lastPrice)
            changeView?.text = String.format(
                java.util.Locale.US, "%+.2f (%+.2f%%)", quote.change, quote.changePercent
            )
            changeView?.setTextColor(if (quote.change >= 0.0) green else red)
            statusView?.text = if (quote.isFresh) "● LIVE" else "● LAST AVAILABLE • NOT LIVE"
            statusView?.setTextColor(if (quote.isFresh) green else amber)
        }
        render(snapshot.quotes["BANKNIFTY"], bankNiftyValueView, bankNiftyChangeView, bankNiftyStatusView)
        render(snapshot.quotes["FINNIFTY"], finNiftyValueView, finNiftyChangeView, finNiftyStatusView)
    }

    private fun showMarketIndicesUnavailable(message: String) {
        listOf(bankNiftyValueView, finNiftyValueView).forEach { it?.text = "—" }
        listOf(bankNiftyChangeView, finNiftyChangeView).forEach { it?.text = "Waiting for fresh quote" }
        listOf(bankNiftyStatusView, finNiftyStatusView).forEach {
            it?.text = "● DATA UNAVAILABLE • NOT LIVE"
            it?.setTextColor(amber)
        }
    }

    private fun buildMarketIndicesCard(): View {
        val panel = card().apply {
            background = rounded(Color.rgb(10, 27, 45), Color.rgb(48, 119, 171), 18)
        }
        panel.addView(text("MARKET INDICES", 12f, cyan, true))
        addGap(panel, 4)
        panel.addView(text("BANKNIFTY & FINNIFTY • UPSTOX READ-ONLY QUOTES", 9f, muted))
        addGap(panel, 10)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun indexTile(
            title: String,
            setViews: (TextView, TextView, TextView) -> Unit
        ): View {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), dp(10), dp(8), dp(10))
                background = rounded(surface2, border, 14)
            }
            tile.addView(text(title, 11f, white, true))
            addGap(tile, 6)
            val value = text("—", 20f, white, true)
            tile.addView(value)
            addGap(tile, 3)
            val change = text("Waiting for quote", 9f, muted, true)
            tile.addView(change)
            addGap(tile, 5)
            val status = text("● CONNECTING", 8f, amber, true)
            tile.addView(status)
            setViews(value, change, status)
            return tile
        }
        row.addView(indexTile("BANK NIFTY") { value, change, status ->
            bankNiftyValueView = value
            bankNiftyChangeView = change
            bankNiftyStatusView = status
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(5) })
        row.addView(indexTile("FINNIFTY") { value, change, status ->
            finNiftyValueView = value
            finNiftyChangeView = change
            finNiftyStatusView = status
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(5) })
        panel.addView(row)
        latestIndicesSnapshot?.let { applyMarketIndicesSnapshot(it) }
        return panel
    }

    private fun buildHomeDashboard(): View {
        val blueAccent = Color.rgb(65, 176, 255)
        val blueText = Color.rgb(190, 224, 250)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(18))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = TextView(this).apply {
            text = "M"
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(17, 65, 105), blueAccent, 14)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        }
        header.addView(logo)
        addGap(header, 0)
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        brand.addView(text("MGrow", 27f, white, true))
        brand.addView(text("NIFTY 50  •  UPSTOX LIVE DATA", 10f, blueAccent, true))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        feedStatusView = pill("● CONNECTING", blueAccent).apply {
            background = rounded(Color.rgb(12, 43, 70), blueAccent, 30)
        }
        header.addView(feedStatusView)
        root.addView(header)
        addGap(root, 18)

        val market = card().apply {
            background = rounded(Color.rgb(9, 30, 58), blueAccent, 22)
        }
        market.addView(text("NIFTY 50  •  LIVE INDEX VALUE", 12f, blueAccent, true))
        addGap(market, 10)
        indexPriceView = text("— — —", 36f, white, true)
        market.addView(indexPriceView)
        addGap(market, 5)
        indexHintView = text("Waiting for validated Upstox market data", 11f, blueText)
        market.addView(indexHintView)
        addGap(market, 18)

        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metrics.addView(dynamicMetric("CHANGE", "—", blueAccent) { changeValueView = it },
            LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(dynamicMetric("DAY HIGH", "—", white) { dayHighValueView = it },
            LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(dynamicMetric("DAY LOW", "—", white) { dayLowValueView = it },
            LinearLayout.LayoutParams(0, -2, 1f))
        market.addView(metrics)
        root.addView(market)
        addGap(root, 14)
        root.addView(buildMarketIndicesCard())
        addGap(root, 14)

        val connection = card().apply {
            background = rounded(Color.rgb(12, 31, 51), Color.rgb(48, 119, 171), 18)
        }
        connection.addView(text("LIVE DATA CONNECTION", 12f, blueAccent, true))
        addGap(connection, 8)
        workerConnectionDetailView = text("Upstox Worker • Connecting securely…", 12f, blueText, true)
        connection.addView(workerConnectionDetailView)
        addGap(connection, 7)
        connection.addView(text(
            "Read-only feed  •  Automatic reconnect  •  Stale data is never marked LIVE",
            10f, muted
        ))
        root.addView(connection)
        addGap(root, 14)

        val health = card().apply {
            background = rounded(Color.rgb(10, 27, 45), Color.rgb(48, 119, 171), 18)
        }
        health.addView(text("MARKET DATA DETAILS", 12f, blueAccent, true))
        addGap(health, 9)
        candleCountsView = text("5-minute candles: waiting\n1-minute candles: waiting", 11f, white)
        health.addView(candleCountsView)
        addGap(health, 7)
        receiptAgeView = text("Last data receipt: waiting", 11f, blueText)
        health.addView(receiptAgeView)
        addGap(health, 12)
        health.addView(text(
            "LIVE DATA ONLY  •  BROKER ACCESS READ ONLY  •  NO BUY/SELL EXECUTION",
            10f, blueAccent, true
        ))
        root.addView(health)
        addGap(root, 14)
        val chartSection = card()
        chartSection.addView(text("NIFTY LIVE CHART", 12f, blueAccent, true))
        addGap(chartSection, 8)
        chartSection.addView(buildChartCard())
        root.addView(chartSection)
        addGap(root, 12)
        val optionChainCard = card().apply {
            background = rounded(Color.rgb(10, 27, 45), Color.rgb(48, 119, 171), 18)
        }
        optionChainCard.addView(text("NIFTY OPTION CHAIN", 12f, blueAccent, true))
        addGap(optionChainCard, 6)
        optionChainCard.addView(text(
            "Read-only Upstox option-chain snapshot • cached last available data • automatic 30-second refresh while open",
            10f, muted
        ))
        addGap(optionChainCard, 8)
        val openOptionChain = Button(this).apply {
            text = "OPEN OPTION CHAIN"
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(17, 65, 105), blueAccent, 12)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, LiveOptionChainActivity::class.java))
            }
        }
        optionChainCard.addView(openOptionChain, LinearLayout.LayoutParams(-1, dp(46)))
        root.addView(optionChainCard)
        addGap(root, 12)
        root.addView(buildDateTimeView().apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        })
        return root
    }

    private fun isMarathi(): Boolean =
        getSharedPreferences("mgrow_settings", MODE_PRIVATE).getString("language", "English") == "मराठी"

    private fun applySavedAppearance() {
        val prefs = getSharedPreferences("mgrow_settings", MODE_PRIVATE)
        val selectedTheme = prefs.getString("theme", "Premium Black Glass") ?: "Premium Black Glass"
        // The 20 migrated FYERS reference palettes are local display-only preferences.
        // No market feed, broker credentials, or network configuration is touched here.
        val migratedPalette = when (selectedTheme) {
            "Black Neon Green" -> arrayOf("#050A08", "#101B15", "#39FF14", "#F7FFF9", "#AFC3B5")
            "Midnight Blue" -> arrayOf("#071323", "#10283B", "#00BFFF", "#F4FAFF", "#AFC4D5")
            "Cyber Purple" -> arrayOf("#10091B", "#241535", "#B45CFF", "#FBF7FF", "#C8B8D8")
            "Graphite Silver" -> arrayOf("#17191D", "#292E35", "#C0C0C0", "#F5F7FA", "#B9C1CB")
            "Emerald Pro" -> arrayOf("#071810", "#102B1D", "#00C878", "#F2FFF7", "#AAC9B7")
            "Royal Gold" -> arrayOf("#100E0A", "#211A10", "#FFD700", "#FFF9E8", "#CFC3A6")
            "Crimson Pro" -> arrayOf("#1B090C", "#32151B", "#FF3B5C", "#FFF5F6", "#D5B8BE")
            "Arctic White" -> arrayOf("#F5F8FC", "#FFFFFF", "#1677FF", "#17202A", "#526171")
            "Slate Professional" -> arrayOf("#202A35", "#303D4B", "#38BDF8", "#F3F7FB", "#B8C7D5")
            "Sunset Orange" -> arrayOf("#21100A", "#3A2015", "#FF8C42", "#FFF8F2", "#D8B9A5")
            "Rose Gold" -> arrayOf("#21151A", "#38252D", "#FFB6C1", "#FFF7FA", "#D8BBC6")
            "Titanium Blue" -> arrayOf("#111B2A", "#202F43", "#6495ED", "#F4F7FF", "#B7C5DC")
            "Matrix Green" -> arrayOf("#020B05", "#0B1D10", "#00FF66", "#F2FFF5", "#A8CDB2")
            "Deep Ocean Teal" -> arrayOf("#071B1C", "#123334", "#00D6C9", "#F1FFFE", "#AED0CD")
            "AMOLED Black" -> arrayOf("#000000", "#101010", "#00E676", "#FFFFFF", "#B8C4BC")
            "Luxury Champagne" -> arrayOf("#1B1710", "#302719", "#F7D794", "#FFF9EC", "#D5C8AE")
            "Electric Blue" -> arrayOf("#050B20", "#111F42", "#2979FF", "#F4F7FF", "#B7C8E8")
            "Cyber Red" -> arrayOf("#16070B", "#2B1119", "#FF1744", "#FFF5F7", "#D8B9C2")
            "Premium Light" -> arrayOf("#F7F8F4", "#FFFFFF", "#00A86B", "#17231C", "#52645A")
            "Frosted Cyan" -> arrayOf("#071522", "#102F43", "#00E5FF", "#F2FBFF", "#B0C9D6")
            else -> null
        }
        if (migratedPalette != null) {
            bg = Color.parseColor(migratedPalette[0])
            surface = Color.parseColor(migratedPalette[1])
            surface2 = Color.parseColor(migratedPalette[1])
            border = Color.parseColor(migratedPalette[2])
            white = Color.parseColor(migratedPalette[3])
            muted = Color.parseColor(migratedPalette[4])
        } else {
            when (selectedTheme) {
                "Premium Black Glass", "Pure Black", "MGrow Neon" -> {
                    bg = Color.rgb(4, 8, 10); surface = Color.rgb(12, 22, 20)
                    surface2 = Color.rgb(17, 31, 29); border = Color.rgb(27, 112, 83)
                    muted = Color.rgb(166, 190, 183); white = Color.rgb(244, 255, 250)
                }
                "Futuristic AI", "Cyber Neon" -> {
                    bg = Color.rgb(7, 9, 24); surface = Color.rgb(18, 22, 48)
                    surface2 = Color.rgb(27, 32, 68); border = Color.rgb(76, 91, 190)
                    muted = Color.rgb(190, 198, 235); white = Color.rgb(248, 249, 255)
                }
                "Bloomberg Terminal", "Carbon Fiber" -> {
                    bg = Color.rgb(15, 17, 20); surface = Color.rgb(32, 35, 40)
                    surface2 = Color.rgb(43, 47, 53); border = Color.rgb(112, 103, 77)
                    muted = Color.rgb(205, 205, 195); white = Color.rgb(255, 250, 230)
                }
                "Angel One Inspired", "Ocean Blue" -> {
                    bg = Color.rgb(5, 25, 45); surface = Color.rgb(18, 48, 72)
                    surface2 = Color.rgb(20, 62, 88); border = Color.rgb(50, 125, 155)
                    muted = Color.rgb(195, 220, 235); white = Color.WHITE
                }
                "Minimal Pro", "Graphite" -> {
                    bg = Color.rgb(19, 21, 27); surface = Color.rgb(38, 42, 52)
                    surface2 = Color.rgb(48, 54, 66); border = Color.rgb(95, 105, 125)
                    muted = Color.rgb(210, 215, 225); white = Color.WHITE
                }
                "Blue Ocean Glass", "Blue Glass", "TradingView Inspired" -> {
                    bg = Color.rgb(13, 24, 45); surface = Color.argb(226, 28, 43, 69)
                    surface2 = Color.argb(238, 40, 61, 96); border = Color.argb(220, 88, 151, 220)
                    muted = Color.rgb(190, 211, 238); white = Color.rgb(248, 251, 255)
                }
                "Luxury Gold" -> {
                    bg = Color.rgb(18, 16, 12); surface = Color.rgb(39, 34, 25)
                    surface2 = Color.rgb(54, 46, 31); border = Color.rgb(166, 130, 55)
                    muted = Color.rgb(220, 205, 170); white = Color.rgb(255, 247, 225)
                }
                else -> {
                    bg = Color.rgb(4, 8, 10); surface = Color.rgb(12, 22, 20)
                    surface2 = Color.rgb(17, 31, 29); border = Color.rgb(27, 112, 83)
                    muted = Color.rgb(166, 190, 183); white = Color.rgb(244, 255, 250)
                }
            }
        }
        green = Color.rgb(57, 255, 136); red = Color.rgb(255, 82, 105); amber = Color.rgb(255, 195, 70)
        cyan = when (prefs.getString("accent", "Cyan")) {
            "Violet" -> Color.rgb(167, 110, 255); "Blue" -> Color.rgb(65, 145, 255)
            "Green" -> Color.rgb(57, 255, 136); "Orange" -> Color.rgb(255, 145, 55)
            "Red" -> Color.rgb(255, 75, 95); "Pink" -> Color.rgb(255, 90, 180)
            "Gold" -> Color.rgb(255, 195, 55); "Teal" -> Color.rgb(0, 220, 190)
            else -> Color.rgb(0, 220, 255)
        }
    }

    private fun loadReportRecords(key: String): org.json.JSONArray {
        val raw = getSharedPreferences("mgrow_reports", MODE_PRIVATE).getString(key, "[]") ?: "[]"
        return try { org.json.JSONArray(raw) } catch (_: Exception) { org.json.JSONArray() }
    }

    private fun saveReportRecords(key: String, records: org.json.JSONArray) {
        getSharedPreferences("mgrow_reports", MODE_PRIVATE).edit()
            .putString(key, records.toString()).apply()
    }

    private fun recordAiSignal(decision: SignalDecision, price: Double, receivedAt: Long) {
        val key = "${decision.bias.name}|${decision.confirmed}|${decision.trend}"
        if (key == lastAiSignalKey) return
        lastAiSignalKey = key
        val records = loadReportRecords("ai_signals")
        val item = org.json.JSONObject().apply {
            put("time", receivedAt)
            put("bias", decision.bias.name)
            put("confirmed", decision.confirmed)
            put("trend", decision.trend)
            put("price", price)
            put("reason", decision.reasons.joinToString("; "))
        }
        records.put(item)
        while (records.length() > 500) records.remove(0)
        saveReportRecords("ai_signals", records)
    }

    private fun buildAdvancedMarketStudies(): View {
        val box = card()
        box.addView(text("ADVANCED INDICATOR STUDIES", 11f, cyan, true)); addGap(box, 8)
        val central = centralMarketState?.takeIf { it.isLive }
        val stoch = central?.stochasticRsi
        val sc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(11), dp(12), dp(11)); background = rounded(surface2, border, 14)
            addView(text("STOCHASTIC RSI (14,14)", 10f, muted, true))
            addView(text(stoch?.let { String.format(java.util.Locale.US, "%.1f / 100", it) } ?: "DATA UNAVAILABLE", 20f,
                stoch?.let { if (it >= 80) red else if (it <= 20) green else cyan } ?: amber, true))
            addView(text(when { stoch == null -> "Requires at least 28 validated closing prices"; stoch >= 80 -> "Upper momentum zone • not a sell signal by itself"; stoch <= 20 -> "Lower momentum zone • not a buy signal by itself"; else -> "Mid-range momentum" }, 9f, muted))
        }
        box.addView(sc); addGap(box, 10)
        box.addView(text("VOLUME PROFILE • CLOSE-BASED APPROXIMATION", 10f, muted, true))
        val profile = central?.volumeProfile
        if (profile == null || !profile.available) {
            box.addView(text("Unavailable: validated candle volume is missing or zero.", 11f, amber))
        } else {
            val maxVolume = profile.levels.maxOfOrNull { it.volume } ?: 0.0
            box.addView(text("Point of Control (approx.): ${formatMarketValue(profile.pointOfControl)}", 15f, green, true))
            profile.levels.forEach { level ->
                val ratio = if (maxVolume > 0.0) level.volume / maxVolume else 0.0
                box.addView(text("${formatMarketValue(level.price)}  •  " + "▰".repeat((ratio * 12).toInt().coerceAtLeast(1)), 10f, muted))
            }
            box.addView(text("Close-price buckets only; indicative, not exchange tick-level volume-at-price.", 9f, amber))
        }
        return box
    }

    private fun buildEntryRiskTargetCard(): View {
        val box = card()
        box.addView(text("CENTRAL AI REFERENCE LEVELS", 11f, cyan, true)); addGap(box, 6)
        val central = centralMarketState?.takeIf { it.isLive }
        val unified = central?.unifiedAiDecision
        val levels = unified?.referenceLevels
        if (central == null || unified == null || unified.bias == TradeBias.WAIT || levels == null) {
            box.addView(text("WAIT • Central AI has not approved a unified setup.", 13f, amber, true))
            box.addView(text("No entry, stop or target is shown when the engines conflict, confirmation is missing, or data is stale.", 10f, muted))
            return box
        }
        box.addView(text(
            "${levels.direction.name} • ${unified.status}",
            12f, if (levels.direction == TradeBias.BUY_CE) green else red, true
        )); addGap(box, 7)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(metric("ENTRY REF.", formatMarketValue(levels.entry)), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(metric("STOP REF.", formatMarketValue(levels.stop), red), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(metric("TARGET REF.", formatMarketValue(levels.target), green), LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row); addGap(box, 7)
        box.addView(text(
            "Central engine reference only • R:R 1:${String.format(java.util.Locale.US, "%.1f", levels.riskReward)} • NIFTY index levels, not option premium. No order instruction.",
            9f, amber
        ))
        return box
    }

    /**
     * Visible, read-only detection panel. It evaluates only the already validated
     * snapshot; it does not create or modify any market-feed connection.
     */
    private fun buildAiDetectionCenter(): View {
        val box = card()
        box.addView(text("AUTOMATIC NIFTY DETECTIONS", 12f, cyan, true))
        addGap(box, 5)
        val snapshot = latestSnapshot
        val report = centralMarketState?.aiPerformanceReport ?: AdvancedAiAnalytics.unavailable(
            System.currentTimeMillis(),
            if (snapshot == null) "Waiting for the first validated live NIFTY snapshot."
            else "Latest market snapshot is stale; waiting for fresh validated candles."
        )
        val visibleDetections = report.detections.filter { item ->
            val requiredPanels = when (item.name) {
                "Chart pattern detection" -> listOf(7)
                "Auto support & resistance" -> listOf(6, 19)
                "Smart money / volume spike" -> listOf(8, 9)
                "Option chain & OI analysis" -> listOf(5)
                "Fakeout detection" -> listOf(10)
                "RSI divergence" -> listOf(4)
                "Market sentiment & news" -> listOf(12, 15)
                "AI risk & volatility" -> listOf(13, 14)
                "Candlestick & scalping setup" -> listOf(11)
                "AI master signal & market bias" -> listOf(2)
                else -> emptyList()
            }
            requiredPanels.any { isProPanelEnabled(it) }
        }
        val available = visibleDetections.count { it.score != null }
        val passed = visibleDetections.count { it.passed }
        val failed = visibleDetections.count { it.failed }
        box.addView(text(
            "PASSED $passed  •  FAILED $failed  •  WAITING ${visibleDetections.size - available}",
            11f, white, true
        ))
        box.addView(text(
            "60% आणि त्यापेक्षा जास्त = PASSED  •  60% पेक्षा कमी = FAILED",
            9f, muted
        ))
        addGap(box, 8)
        if (visibleDetections.isEmpty()) {
            box.addView(text("सर्व AI detection panels Settings मध्ये बंद आहेत.", 10f, muted))
        }
        visibleDetections.forEach { item ->
            val status = when {
                item.score == null && item.detail.startsWith("No verified live news") -> "SOURCE NOT CONNECTED"
                item.score == null && item.detail.startsWith("Recent verified Upstox option-chain") -> "SOURCE NOT READY"
                item.score == null -> "WAITING FOR DATA"
                item.score >= 60 -> "PASSED • ${item.score}%"
                else -> "FAILED • ${item.score}%"
            }
            val tint = when {
                item.score == null -> amber
                item.score >= 60 -> green
                else -> red
            }
            box.addView(text("${item.name}  —  $status", 10f, tint, true))
            box.addView(text(item.detail, 9f, muted))
            addGap(box, 5)
        }
        val central = centralMarketState
        val unified = central?.unifiedAiDecision
        if (central != null && unified != null) {
            addGap(box, 7)
            box.addView(text(
                "CENTRAL AI RESULT • ${unified.bias.name} • ${unified.status}",
                10f, if (unified.bias == TradeBias.WAIT) amber else cyan, true
            ))
            unified.alignmentScore?.let {
                box.addView(text("Rule-based confluence: $it/100 • not win probability", 9f, muted))
            }
            box.addView(text(
                "Option-chain PCR: ${unified.optionChainPcr?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "NOT INCLUDED"}",
                9f, muted
            ))
            unified.warnings.forEach { box.addView(text(it, 9f, amber)) }
            box.addView(text("Detection checks are supporting evidence only; they do not issue separate BUY CE / BUY PE decisions.", 9f, muted))
        }
        box.addView(text(
            "Scores हे heuristic analysis आहेत; नफ्याची शक्यता किंवा verified win-rate नाहीत. OI फक्त recent Upstox API snapshot मधून; News verified source नसल्यास DATA UNAVAILABLE.",
            9f, amber
        ))
        return box
    }

    private fun buildReports(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val manualTrades = loadReportRecords("manual_trades")
        val manualPnl = (0 until manualTrades.length()).sumOf {
            manualTrades.optJSONObject(it)?.optDouble("pnl", 0.0) ?: 0.0
        }
        val journal = card()
        journal.addView(text("MANUAL TRADE JOURNAL", 12f, cyan, true))
        addGap(journal, 5)
        journal.addView(text(
            "${manualTrades.length()} saved entries  •  Gross P&L ${if (manualPnl >= 0.0) "+" else ""}${formatMarketValue(manualPnl)}",
            12f, if (manualPnl >= 0.0) green else red, true
        ))
        journal.addView(text("Manual record keeping only. This does not place, modify or close broker orders. Brokerage and taxes are not included in gross P&L.", 9f, amber))
        addGap(journal, 8)
        val journalActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val addTrade = Button(this).apply {
            text = "ADD MANUAL ENTRY"
            setOnClickListener { showManualTradeDialog() }
        }
        val export = Button(this).apply {
            text = "EXPORT CSV"
            setOnClickListener { shareReportsCsv() }
        }
        journalActions.addView(addTrade, LinearLayout.LayoutParams(0, dp(46), 1f))
        journalActions.addView(export, LinearLayout.LayoutParams(0, dp(46), 1f))
        journal.addView(journalActions)
        if (manualTrades.length() == 0) {
            addGap(journal, 5)
            journal.addView(text("No manual trades recorded yet.", 10f, muted))
        } else {
            addGap(journal, 8)
            journal.addView(text("RECENT MANUAL ENTRIES", 10f, muted, true))
            val fmt = java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault())
            for (i in manualTrades.length() - 1 downTo maxOf(0, manualTrades.length() - 5)) {
                val trade = manualTrades.optJSONObject(i) ?: continue
                val pnl = trade.optDouble("pnl", 0.0)
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(text(trade.optString("side"), 10f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text("${if (pnl >= 0.0) "+" else ""}${formatMarketValue(pnl)}", 11f, if (pnl >= 0.0) green else red, true))
                journal.addView(row)
                journal.addView(text(
                    "${fmt.format(java.util.Date(trade.optLong("time")))} • Entry ${formatMarketValue(trade.optDouble("entry"))} • Exit ${formatMarketValue(trade.optDouble("exit"))} • Qty ${trade.optInt("qty")}",
                    9f, muted
                ))
            }
        }
        root.addView(journal); addGap(root, 12)
        val signals = loadReportRecords("ai_signals")
        val confirmed = (0 until signals.length()).count { signals.optJSONObject(it)?.optBoolean("confirmed", false) == true }
        val ce = (0 until signals.length()).count { signals.optJSONObject(it)?.optString("bias") == TradeBias.BUY_CE.name }
        val pe = (0 until signals.length()).count { signals.optJSONObject(it)?.optString("bias") == TradeBias.BUY_PE.name }
        val wait = (0 until signals.length()).count { signals.optJSONObject(it)?.optString("bias") == TradeBias.WAIT.name }
        val aiReportCard = card()
        aiReportCard.addView(text("SMART AI PERFORMANCE REPORT", 12f, cyan, true)); addGap(aiReportCard, 6)
        val savedAiReport = getSharedPreferences("mgrow_ai_reports", MODE_PRIVATE)
            .getString("latest_detection_report", null)
        if (savedAiReport == null) {
            aiReportCard.addView(text("ताजा validated live NIFTY data मिळाल्यावर report तयार होईल.", 11f, muted))
        } else {
            try {
                val reportJson = org.json.JSONObject(savedAiReport)
                val items = reportJson.optJSONArray("detections") ?: org.json.JSONArray()
                var passed = 0
                var failed = 0
                var available = 0
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    if (!item.isNull("score")) {
                        available++
                        if (item.optInt("score") >= 60) passed++ else failed++
                    }
                }
                aiReportCard.addView(text("PASSED $passed  •  FAILED $failed  •  DATA UNAVAILABLE ${items.length() - available}", 11f, white, true))
                aiReportCard.addView(text("Pass score: ${if (available == 0) 0 else passed * 100 / available}%  •  Rule: 60% and above Passed", 10f, muted))
                addGap(aiReportCard, 7)
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val name = item.optString("name")
                    val score = if (item.isNull("score")) null else item.optInt("score")
                    val status = when {
                        score == null -> "DATA UNAVAILABLE"
                        score >= 60 -> "✓ PASSED • $score%"
                        else -> "✕ FAILED • $score%"
                    }
                    val tint = when {
                        score == null -> amber
                        score >= 60 -> green
                        else -> red
                    }
                    aiReportCard.addView(text("$name  —  $status", 10f, tint, true))
                    aiReportCard.addView(text(item.optString("detail"), 9f, muted))
                    addGap(aiReportCard, 4)
                }
                aiReportCard.addView(text("हे heuristic detection scores आहेत; verified win-rate किंवा trade probability नाहीत. OI आणि News साठी स्वतंत्र verified data जोडलेले नसल्यास ते unavailable राहतील.", 9f, amber))
            } catch (_: Exception) {
                aiReportCard.addView(text("AI report वाचता आला नाही. पुढील fresh update ची प्रतीक्षा करा.", 10f, amber))
            }
        }
        root.addView(aiReportCard); addGap(root, 12)
        val summary = card()
        summary.addView(text("AI SIGNAL HISTORY", 11f, cyan, true)); addGap(summary, 7)
        summary.addView(text(signals.length().toString(), 30f, white, true)); summary.addView(text("Recorded signal changes", 10f, muted)); addGap(summary, 10)
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metrics.addView(metric("BUY CE", ce.toString(), green), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric("BUY PE", pe.toString(), red), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric("WAIT", wait.toString(), amber), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric("CONFIRMED", confirmed.toString(), cyan), LinearLayout.LayoutParams(0, -2, 1f))
        summary.addView(metrics); root.addView(summary); addGap(root, 12)
        root.addView(buildSignalActivityCalendar(signals)); addGap(root, 12)
        val history = card(); history.addView(text("RECENT AI SIGNALS", 11f, cyan, true)); addGap(history, 8)
        if (signals.length() == 0) history.addView(text("No validated live AI signals recorded yet.", 12f, muted))
        else {
            val fmt = java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault())
            for (i in signals.length() - 1 downTo maxOf(0, signals.length() - 15)) {
                val item = signals.optJSONObject(i) ?: continue
                val bias = item.optString("bias", "WAIT")
                val tint = when (bias) { TradeBias.BUY_CE.name -> green; TradeBias.BUY_PE.name -> red; else -> amber }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(text(bias.replace("_", " "), 12f, tint, true), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text(formatMarketValue(item.optDouble("price", Double.NaN)), 12f, white, true))
                history.addView(row)
                history.addView(text(fmt.format(java.util.Date(item.optLong("time"))) + " • " + item.optString("trend", "Trend unavailable") + " • " + if (item.optBoolean("confirmed", false)) "Confirmed" else "Not confirmed", 9f, muted))
                addGap(history, 6)
            }
        }
        root.addView(history); addGap(root, 12)
        val note = card(); note.addView(text("STRATEGY REVIEW", 11f, cyan, true)); addGap(note, 5)
        note.addView(text("Signal history is an audit log, not a verified win-rate report. Backtesting and outcome tracking must include historical candles, brokerage, taxes and slippage before performance claims.", 10f, amber))
        root.addView(note)
        root.addView(text("AI signal history is not a verified win-rate report. Manual entries are user-entered records only.", 10f, muted))
        return root
    }

    private fun buildSignalActivityCalendar(signals: org.json.JSONArray): View {
        val prefs = getSharedPreferences("mgrow_report_ui", MODE_PRIVATE)
        val month = java.util.Calendar.getInstance().apply {
            timeInMillis = prefs.getLong("calendar_month", System.currentTimeMillis())
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val keyFormat = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        val counts = HashMap<String, Int>()
        for (i in 0 until signals.length()) {
            val item = signals.optJSONObject(i) ?: continue
            val time = item.optLong("time", 0L)
            if (time > 0L) {
                val key = keyFormat.format(java.util.Date(time))
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        val box = card()
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val monthLabel = text(
            java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).format(month.time),
            14f, white, true
        )
        val previous = Button(this).apply {
            text = "‹"
            setOnClickListener {
                month.add(java.util.Calendar.MONTH, -1)
                prefs.edit().putLong("calendar_month", month.timeInMillis).apply()
                showPage("Reports")
            }
        }
        val next = Button(this).apply {
            text = "›"
            setOnClickListener {
                month.add(java.util.Calendar.MONTH, 1)
                prefs.edit().putLong("calendar_month", month.timeInMillis).apply()
                showPage("Reports")
            }
        }
        heading.addView(previous, LinearLayout.LayoutParams(dp(46), dp(42)))
        heading.addView(monthLabel, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(8); rightMargin = dp(8)
        })
        heading.addView(next, LinearLayout.LayoutParams(dp(46), dp(42)))
        box.addView(text("MONTHLY SIGNAL ACTIVITY", 11f, cyan, true))
        addGap(box, 5)
        box.addView(heading)
        addGap(box, 5)
        val weekdays = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("S", "M", "T", "W", "T", "F", "S").forEach { day ->
            weekdays.addView(text(day, 10f, muted, true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, dp(24), 1f))
        }
        box.addView(weekdays)
        val firstWeekday = month.get(java.util.Calendar.DAY_OF_WEEK) - 1
        val daysInMonth = month.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        var dayNumber = 1
        while (dayNumber <= daysInMonth) {
            val week = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (column in 0..6) {
                if (dayNumber == 1 && column < firstWeekday || dayNumber > daysInMonth) {
                    week.addView(View(this), LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    })
                } else {
                    val date = month.clone() as java.util.Calendar
                    date.set(java.util.Calendar.DAY_OF_MONTH, dayNumber)
                    val key = keyFormat.format(date.time)
                    val count = counts[key] ?: 0
                    val cell = text(
                        if (count > 0) "$dayNumber\n$count" else dayNumber.toString(),
                        9f, if (count > 0) green else muted, count > 0
                    ).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(2), dp(3), dp(2), dp(3))
                        background = rounded(surface2, if (count > 0) green else border, 8)
                        isClickable = true
                        setOnClickListener {
                            Toast.makeText(
                                this@MainActivity,
                                "${java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(date.time)} • $count recorded AI signals",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    week.addView(cell, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    })
                    dayNumber++
                }
            }
            box.addView(week)
        }
        addGap(box, 5)
        box.addView(text(
            "Calendar shows the count of saved AI signal changes per date only. It does not label signals as wins/losses or calculate trading accuracy.",
            9f, amber
        ))
        return box
    }

    private fun showManualTradeDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val sideSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("BUY CE", "BUY PE"))
        }
        fun input(hint: String, type: Int): EditText = EditText(this).apply {
            this.hint = hint
            inputType = type
            setSingleLine(true)
        }
        val entry = input("Entry premium (₹)", android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val exit = input("Exit premium (₹)", android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val qty = input("Quantity (units)", android.text.InputType.TYPE_CLASS_NUMBER)
        form.addView(text("OPTION SIDE", 10f, muted, true))
        form.addView(sideSpinner)
        form.addView(entry)
        form.addView(exit)
        form.addView(qty)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Manual Trade")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("SAVE TRADE", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val buy = entry.text.toString().toDoubleOrNull()
                val sell = exit.text.toString().toDoubleOrNull()
                val quantity = qty.text.toString().toIntOrNull()
                if (buy == null || sell == null || !buy.isFinite() || !sell.isFinite() ||
                    buy <= 0.0 || sell <= 0.0 || quantity == null || quantity <= 0
                ) {
                    Toast.makeText(this, "Enter valid entry, exit and quantity.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val pnl = (sell - buy) * quantity
                val side = if (sideSpinner.selectedItemPosition == 0) "BUY CE" else "BUY PE"
                val records = loadReportRecords("manual_trades")
                records.put(org.json.JSONObject().apply {
                    put("time", System.currentTimeMillis())
                    put("side", side)
                    put("entry", buy)
                    put("exit", sell)
                    put("qty", quantity)
                    put("pnl", pnl)
                })
                while (records.length() > 1000) records.remove(0)
                saveReportRecords("manual_trades", records)
                dialog.dismiss()
                showPage("Reports")
                Toast.makeText(this, "Manual trade saved.", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun shareReportsCsv() {
        val manual = loadReportRecords("manual_trades")
        val signals = loadReportRecords("ai_signals")
        val csv = StringBuilder()
        csv.append("MANUAL TRADES\nTime,Side,Entry,Exit,Quantity,Gross P&L\n")
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        for (i in 0 until manual.length()) {
            val t = manual.optJSONObject(i) ?: continue
            csv.append(formatter.format(java.util.Date(t.optLong("time")))).append(',')
                .append(t.optString("side")).append(',')
                .append(t.optDouble("entry")).append(',')
                .append(t.optDouble("exit")).append(',')
                .append(t.optInt("qty")).append(',')
                .append(t.optDouble("pnl")).append('\n')
        }
        csv.append("\nAI SIGNAL LOG\nTime,Bias,Confirmed,Trend,Index Price,Reason\n")
        for (i in 0 until signals.length()) {
            val s = signals.optJSONObject(i) ?: continue
            val safeReason = s.optString("reason").replace(",", " ").replace("\n", " ")
            csv.append(formatter.format(java.util.Date(s.optLong("time")))).append(',')
                .append(s.optString("bias")).append(',')
                .append(s.optBoolean("confirmed")).append(',')
                .append(s.optString("trend")).append(',')
                .append(s.optDouble("price")).append(',')
                .append(safeReason).append('\n')
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "MGrow Trading Reports")
            putExtra(Intent.EXTRA_TEXT, csv.toString())
        }
        startActivity(Intent.createChooser(send, "Share MGrow report data"))
    }

    private fun buildSettings(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val prefs = getSharedPreferences("mgrow_settings", MODE_PRIVATE)

        fun settingRow(title: String, subtitle: String, action: () -> Unit): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(surface, border, 14)
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            row.addView(text(title, 15f, white, true))
            row.addView(text(subtitle, 11f, muted))
            return row
        }

        val themes = arrayOf(
            "Premium Black Glass", "Futuristic AI", "Bloomberg Terminal",
            "Angel One Inspired", "Cyber Neon", "Minimal Pro",
            "Blue Ocean Glass", "Carbon Fiber", "TradingView Inspired", "Luxury Gold",
            "Black Neon Green", "Midnight Blue", "Cyber Purple", "Graphite Silver",
            "Emerald Pro", "Royal Gold", "Crimson Pro", "Arctic White",
            "Slate Professional", "Sunset Orange", "Rose Gold", "Titanium Blue",
            "Matrix Green", "Deep Ocean Teal", "AMOLED Black", "Luxury Champagne",
            "Electric Blue", "Cyber Red", "Premium Light", "Frosted Cyan"
        )
        root.addView(settingRow(
            if (isMarathi()) "अॅप थीम" else "App Theme",
            (prefs.getString("theme", "Premium Black Glass") ?: "Premium Black Glass") + "  •  30 display themes"
        ) {
            AlertDialog.Builder(this).setTitle("Choose app theme")
                .setSingleChoiceItems(themes, themes.indexOf(prefs.getString("theme", "Premium Black Glass"))) { dialog, which ->
                    prefs.edit().putString("theme", themes[which]).apply()
                    dialog.dismiss()
                    recreate()
                }.setNegativeButton("Cancel", null).show()
        })
        addGap(root, 10)
        val fontOptions = arrayOf(
            "Helvetica", "Arial", "Inter", "Roboto", "Montserrat", "Poppins", "Futura",
            "Open Sans", "Lato", "Times New Roman", "Georgia", "Garamond", "Baskerville",
            "Playfair Display", "Bodoni", "Merriweather", "Rockwell", "Arvo", "Roboto Slab",
            "Clarendon", "Pacifico", "Lobster", "Dancing Script", "Brush Script", "Caveat",
            "Fira Code", "JetBrains Mono", "Courier New", "Bebas Neue"
        )
        root.addView(settingRow(
            if (isMarathi()) "फॉन्ट शैली" else "Font Style",
            (prefs.getString("font_family", "Roboto") ?: "Roboto") +
                "  •  29 styles (device may use the closest system font)"
        ) {
            val currentFont = prefs.getString("font_family", "Roboto") ?: "Roboto"
            AlertDialog.Builder(this).setTitle("Choose font style")
                .setSingleChoiceItems(fontOptions, fontOptions.indexOf(currentFont)) { dialog, which ->
                    prefs.edit().putString("font_family", fontOptions[which]).apply()
                    dialog.dismiss()
                    recreate()
                }.setNegativeButton("Cancel", null).show()
        })
        addGap(root, 12)
        section(root, "MGROW PRO • PANEL LIBRARY", "20 DASHBOARD PANELS")
        root.addView(text(
            "These switches control the corresponding sections on Home, Market, and AI pages. Preferences are saved locally on this device.",
            10f, muted
        ))
        addGap(root, 6)
        root.addView(Switch(this).apply {
            text = "Live Market Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_1", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_1", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "AI Master Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_2", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_2", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "Advanced Chart Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_3", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_3", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "Indicator Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_4", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_4", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "Option Chain Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_5", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_5", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "Support Resistance Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_6", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_6", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "Pattern Scanner Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_7", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_7", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "Volume Intelligence Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_8", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_8", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "Smart Money Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_9", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_9", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "Fakeout Detection Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_10", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_10", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "Scalping Setup Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_11", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_11", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "Market Sentiment Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_12", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_12", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "Volatility Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_13", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_13", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "AI Risk Meter Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_14", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_14", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "News Intelligence Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_15", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_15", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "Alert Center Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_16", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_16", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "Multi-Timeframe Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_17", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_17", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "AI Performance Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_18", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_18", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "Market Levels Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_19", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_19", checked).apply()
            }
        })
        root.addView(Switch(this).apply {
            text = "System Health Panel"
            setTextColor(white)
            isChecked = prefs.getBoolean("pro_panel_20", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("pro_panel_20", checked).apply()
            }
        })
        addGap(root, 9)
        val accents = arrayOf("Cyan", "Violet", "Blue", "Green", "Orange", "Red", "Pink", "Gold", "Teal")
        root.addView(settingRow(
            "Accent Colour",
            (prefs.getString("accent", "Cyan") ?: "Cyan") + "  •  9 colour choices"
        ) {
            AlertDialog.Builder(this).setTitle("Choose accent colour")
                .setSingleChoiceItems(accents, accents.indexOf(prefs.getString("accent", "Cyan"))) { dialog, which ->
                    prefs.edit().putString("accent", accents[which]).apply()
                    dialog.dismiss()
                    recreate()
                }.setNegativeButton("Cancel", null).show()
        })
        addGap(root, 9)
        val languages = arrayOf("English", "मराठी", "हिन्दी", "ગુજરાતી", "தமிழ்", "తెలుగు")
        root.addView(settingRow(
            if (isMarathi()) "भाषा / Language" else "Language",
            (prefs.getString("language", "English") ?: "English") + "  •  6 languages"
        ) {
            AlertDialog.Builder(this).setTitle("Choose language")
                .setSingleChoiceItems(languages, languages.indexOf(prefs.getString("language", "English"))) { dialog, which ->
                    prefs.edit().putString("language", languages[which]).apply()
                    dialog.dismiss()
                    recreate()
                }.setNegativeButton("Cancel", null).show()
        })
        addGap(root, 9)
        addGap(root, 12)
        section(root, if (isMarathi()) "मार्केट डेटा कनेक्शन" else "MARKET DATA CONNECTION")
        val connectionCard = card()
        connectionCard.addView(text("UPSTOX • CLOUDFLARE WORKER", 11f, cyan, true))
        addGap(connectionCard, 6)
        workerConnectionDetailView = text("Upstox Worker • Checking connection…", 11f, muted)
        connectionCard.addView(workerConnectionDetailView)
        addGap(connectionCard, 8)
        connectionCard.addView(text("Read-only market data. Broker credentials stay on the secure server; no order execution.", 10f, amber))
        root.addView(connectionCard)
        addGap(root, 12)
        section(root, if (isMarathi()) "सूचना आणि अलर्ट" else "NOTIFICATIONS & ALERTS")
        root.addView(buildAlerts())
        addGap(root, 12)
        section(root, if (isMarathi()) "सुरक्षा / SECURITY" else "SECURITY")
        val appLock = Switch(this).apply {
            text = if (isMarathi()) "फिंगरप्रिंट / बायोमेट्रिक App Lock" else "Fingerprint / Biometric App Lock"
            setTextColor(white)
            isChecked = prefs.getBoolean("app_lock_enabled", false)
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                    if (!keyguard.isDeviceSecure) {
                        isChecked = false
                        Toast.makeText(this@MainActivity, "First set a phone PIN, pattern or password in Android Settings.", Toast.LENGTH_LONG).show()
                    } else {
                        prefs.edit().putBoolean("app_lock_enabled", true).apply()
                        appUnlocked = false
                        authenticateAppLock()
                    }
                } else {
                    prefs.edit().putBoolean("app_lock_enabled", false).apply()
                    appUnlocked = true
                    authenticationInProgress = false
                }
            }
        }
        root.addView(appLock)
        root.addView(text(
            if (isMarathi()) "App background मध्ये गेल्यावर पुन्हा लॉक होईल. Fingerprint/Face किंवा फोनचा PIN वापरता येईल." else
                "MGrow locks again when you leave the app. Unlock with fingerprint/face or your phone PIN, pattern or password.",
            10f, muted
        ))
        addGap(root, 12)
        section(root, if (isMarathi()) "इतर पर्याय" else "OTHER OPTIONS")
        root.addView(settingRow(
            if (isMarathi()) "ऑफलाइन टेस्टिंग लॅब" else "OFFLINE TESTING LAB",
            "Sample candles and local Excel option-chain tests • never changes live feed"
        ) { showPage("Testing Lab") })
        addGap(root, 9)
        val keepAwake = Switch(this).apply {
            text = if (isMarathi()) "स्क्रीन चालू ठेवा" else "Keep screen awake"
            setTextColor(white)
            isChecked = prefs.getBoolean("keep_awake", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("keep_awake", checked).apply()
                if (checked) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        root.addView(keepAwake)
        if (prefs.getBoolean("keep_awake", false)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        addGap(root, 8)
        root.addView(text(
            if (isMarathi()) "टीप: Theme बदलल्यावर अॅप पुन्हा उघडेल. काही तांत्रिक/ट्रेडिंग संज्ञा English मध्येच राहतील." else
                "Note: The app restarts to apply a theme. Some technical and trading terms remain in English.",
            10f, amber
        ))
        return root
    }

    private fun buildHeader(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(text("MGrow", 24f, white, true))
        titleCol.addView(text("AI NIFTY OPTIONS TERMINAL", 9f, cyan, true))
        wrap.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        wrap.addView(buildDateTimeView())
        headerModePill = pill("● CONNECTING", cyan)
        wrap.addView(headerModePill)
        return wrap
    }

    private fun buildMarketCard(): View {
        val box = card()
        box.background = rounded(Color.argb(235, 25, 39, 62), Color.argb(220, 74, 101, 140), 22)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("NIFTY 50", 13f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(helpButton(
            "NIFTY 50",
            "NIFTY 50 हा भारतातील प्रमुख 50 कंपन्यांचा निर्देशांक आहे. येथे feed मधून आलेली शेवटची validated किंमत, बदल आणि दिवसाचे High/Low दिसतील.",
            "NIFTY 50 is an index of 50 major Indian companies. This card shows the latest validated feed price, change, and day high/low."
        ))
        feedStatusView = pill("● FEED OFFLINE", amber)
        top.addView(feedStatusView)
        box.addView(top)
        addGap(box, 10)
        indexPriceView = text("— — — — —", 30f, white, true)
        box.addView(indexPriceView)
        indexHintView = text("No market feed attached. Sample chart is not live data.", 11f, muted)
        box.addView(indexHintView)
        addGap(box, 13)
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metrics.addView(dynamicMetric("CHANGE", "—", muted) { changeValueView = it }, LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(dynamicMetric("DAY HIGH", "—", white) { dayHighValueView = it }, LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(dynamicMetric("DAY LOW", "—", white) { dayLowValueView = it }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(metrics)
        return box
    }

    private fun buildSignalCard(): View {
        val box = card()
        box.background = rounded(Color.argb(238, 29, 43, 67), Color.argb(225, 75, 101, 139), 22)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("AI MARKET SETUP", 11f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(helpButton(
            "BUY CE / BUY PE / WAIT",
            "BUY CE म्हणजे Call Option खरेदीचा विचार; BUY PE म्हणजे Put Option खरेदीचा विचार; WAIT म्हणजे setup पूर्ण नाही. SignalEngine फक्त validated candles तपासतो. ही सूचना आहे, ऑर्डर नाही.",
            "BUY CE suggests considering a Call; BUY PE suggests considering a Put; WAIT means the setup is incomplete. SignalEngine evaluates validated candles only. This is not an order."
        ))
        signalStatusView = pill("WAITING FOR LIVE DATA", amber)
        top.addView(signalStatusView)
        box.addView(top)
        addGap(box, 8)
        signalBiasView = text("WAIT", 34f, amber, true)
        box.addView(signalBiasView)
        signalReasonView = text("Signal engine is on standby until a real market feed is attached.", 12f, white)
        box.addView(signalReasonView)
        addGap(box, 12)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(dynamicMetric("TREND", "—", muted) { signalTrendValueView = it }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(dynamicMetric("5M SETUP", "—", muted) { signalSetupValueView = it }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(dynamicMetric("1M CONFIRM", "—", muted) { signalConfirmValueView = it }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row)
        addGap(box, 8)
        box.addView(text("No fabricated prices or sample candles are used by the signal engine.", 10f, amber))
        return box
    }

    private fun buildChartCard(): View {
        val box = card()
        val tfRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tf5 = text("5 MIN", 12f, cyan, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(15), dp(8), dp(15), dp(8))
            background = rounded(Color.argb(205, 50, 42, 83), cyan, 12)
            setOnClickListener { setTf("5 MIN") }
        }
        tf1 = text("1 MIN", 12f, muted, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(15), dp(8), dp(15), dp(8))
            background = rounded(surface2)
            setOnClickListener { setTf("1 MIN") }
        }
        tfRow.addView(tf5)
        tfRow.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        tfRow.addView(tf1)
        tfRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tfRow.addView(helpButton(
            "5 MIN / 1 MIN",
            "5 MIN मुख्य trend पाहण्यासाठी आणि 1 MIN entry confirmation साठी आहे. Feed जोडलेला नसेल तर chart स्पष्टपणे SAMPLE दाखवतो.",
            "Use 5 MIN for the main trend and 1 MIN for entry confirmation. Without a connected feed, the chart is clearly marked SAMPLE."
        ))
        chartModePill = pill("NO DATA", amber)
        tfRow.addView(chartModePill)
        box.addView(tfRow)
        addGap(box, 10)
        chart = DemoChart()
        box.addView(chart, LinearLayout.LayoutParams(-1, dp(220)))
        addGap(box, 7)
        chartModeNoteView = text("WAITING FOR REAL MARKET CANDLES • NO SAMPLE DATA", 10f, amber)
        box.addView(chartModeNoteView)
        return box
    }

    private fun buildIndicators(): View {
        val box = card()
        val rows = listOf(
            Triple("VWAP", "—", cyan),
            Triple("EMA 9", "—", green),
            Triple("EMA 21", "—", amber),
            Triple("RSI (14)", "—", white)
        )
        rows.forEachIndexed { i, item ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(item.first, 13f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
            val indicatorHelp = when (item.first) {
                "VWAP" -> Pair("VWAP म्हणजे दिवसभरातील volume नुसार मोजलेली सरासरी किंमत. किंमत VWAP च्या वर/खाली आहे का ते trend समजण्यासाठी पाहतात.", "VWAP is the volume-weighted average price for the session. Traders compare price with VWAP to assess intraday direction.")
                "EMA 9" -> Pair("EMA 9 हा अलीकडील 9 candles च्या किमतींना जास्त महत्त्व देणारा moving average आहे. तो अल्पकालीन trend दाखवतो.", "EMA 9 is a moving average that gives more weight to recent prices across 9 periods. It helps show short-term direction.")
                "EMA 21" -> Pair("EMA 21 हा 21 periods चा moving average आहे. EMA 9 सोबत तुलना करून trend समजण्यास मदत होते.", "EMA 21 is a 21-period moving average. Comparing it with EMA 9 can help assess the trend.")
                else -> Pair("RSI (14) हा momentum indicator आहे. साधारण 70 पेक्षा वर overbought आणि 30 पेक्षा खाली oversold स्थितीचा संकेत असू शकतो; एकट्या RSI वर ट्रेड घेऊ नये.", "RSI (14) is a momentum indicator. Readings above 70 may indicate overbought and below 30 may indicate oversold conditions; do not trade using RSI alone.")
            }
            row.addView(helpButton(item.first, indicatorHelp.first, indicatorHelp.second))
            val valueView = text(item.second, 14f, item.third, true)
            when (item.first) {
                "VWAP" -> vwapValueView = valueView
                "EMA 9" -> ema9ValueView = valueView
                "EMA 21" -> ema21ValueView = valueView
                "RSI (14)" -> rsiValueView = valueView
            }
            row.addView(valueView)
            box.addView(row)
            if (i != rows.lastIndex) {
                val line = View(this).apply { setBackgroundColor(border) }
                box.addView(line, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(9); bottomMargin = dp(9) })
            }
        }
        return box
    }

    private fun buildLevels(): View {
        val box = card()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(dynamicMetric("RESISTANCE", "—", red) { resistanceValueView = it }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(dynamicMetric("SUPPORT", "—", green) { supportValueView = it }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row)
        addGap(box, 12)
        box.addView(text("CPR  —     Previous High / Low  —     Opening Range  —", 11f, muted))
        box.addView(text("Support/resistance use validated 5-minute candles; other levels remain pending.", 10f, amber))
        return box
    }

    private fun buildInsights(): View {
        val box = card()
        box.addView(text("MARKET REGIME • SUPPORTING INPUT", 10f, muted, true))
        marketBiasView = text("Awaiting validated 5-minute candles", 16f, white, true)
        box.addView(marketBiasView)
        addGap(box, 6)
        marketDetailView = text("Volume confirmation • False-breakout filter • Trend strength", 11f, muted)
        box.addView(marketDetailView)
        addGap(box, 8)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun metric(label: String, setter: (TextView) -> Unit) {
            val value = pill("$label: —", muted)
            setter(value)
            chips.addView(value)
        }
        metric("SUPERTREND") { supertrendView = it }
        chips.addView(View(this), LinearLayout.LayoutParams(dp(5), 1))
        metric("MACD") { macdView = it }
        chips.addView(View(this), LinearLayout.LayoutParams(dp(5), 1))
        metric("ADX") { adxView = it }
        box.addView(chips)
        return box
    }

    private fun buildNextCandleCard(): View {
        val box = card()
        box.background = rounded(Color.argb(238, 22, 38, 58), Color.argb(210, 0, 213, 255), 22)
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(text("NEXT 5M MODEL • CENTRAL CONFLUENCE", 11f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(helpButton(
            "SMART AI NEXT CANDLE",
            "पुढील 5 मिनिटांच्या candle ची दिशा 5M indicators, market trend, support/resistance, price action आणि 1M retest/rejection वापरून अंदाजली जाते. Option Chain live उपलब्ध नसेल तर तो वापरला जात नाही. WAIT म्हणजे पुरेशी पुष्टी नाही.",
            "Estimates the next 5-minute candle direction using 5M indicators, market trend, support/resistance, price action and 1M retest/rejection. Option Chain is excluded unless live data is available. WAIT means confirmation is insufficient. This is a heuristic, not a validated probability."
        ))
        box.addView(titleRow)
        addGap(box, 9)
        nextCandleDirectionView = text("WAIT", 30f, amber, true)
        box.addView(nextCandleDirectionView)
        nextCandleConfidenceView = text("Heuristic confidence: —", 11f, muted, true)
        box.addView(nextCandleConfidenceView)
        addGap(box, 7)
        nextCandleReasonView = text("Waiting for fresh 5M and 1M candles.", 11f, white)
        box.addView(nextCandleReasonView)
        addGap(box, 7)
        box.addView(text("Option Chain: not connected • excluded from calculation", 10f, amber))
        box.addView(text("Read-only analysis • No orders • Confidence is not a win probability", 10f, muted))
        return box
    }

    private fun updateNextCandlePrediction(result: NextCandlePrediction?) {
        val central = centralMarketState
        val unified = central?.unifiedAiDecision
        val direction = result?.direction ?: NextCandleDirection.WAIT
        val aligned = when (direction) {
            NextCandleDirection.GREEN -> unified?.bias == TradeBias.BUY_CE
            NextCandleDirection.RED -> unified?.bias == TradeBias.BUY_PE
            NextCandleDirection.WAIT -> false
        }
        val label = when {
            central?.isLive != true -> "PAUSED • NOT LIVE"
            unified?.bias == TradeBias.WAIT -> unified.status
            aligned -> "NEXT-CANDLE MODEL ALIGNS WITH CENTRAL AI"
            direction == NextCandleDirection.WAIT -> "NEXT-CANDLE CONFIRMATION WAITING"
            else -> "NEXT-CANDLE MODEL DISAGREES • CENTRAL AI WAIT"
        }
        val tint = if (aligned) green else amber
        nextCandleDirectionView?.text = label
        nextCandleDirectionView?.setTextColor(tint)
        nextCandleConfidenceView?.text = result?.heuristicConfidence?.let {
            "Supporting model score: $it/100 • not a probability or separate signal"
        } ?: "Supporting model score: unavailable"
        nextCandleReasonView?.text = result?.reasons?.joinToString(" • ")
            ?: "Waiting for fresh validated market data."
    }

    private fun updateMarketIntelligence(result: MarketIntelligenceSnapshot?) {
        marketBiasView?.text = result?.bias ?: "Waiting for fresh market data"
        marketDetailView?.text = result?.let {
            "Trend strength " + (it.adx?.let { value -> String.format(java.util.Locale.US, "%.1f", value) } ?: "N/A") +
                " • Volume " + it.volumeStatus + " • Breakout " + it.breakoutStatus
        } ?: "Market intelligence paused until fresh validated candles arrive."
        supertrendView?.text = "SUPERTREND: " + (result?.supertrend ?: "N/A")
        macdView?.text = "MACD: " + (result?.macd ?: "N/A")
        adxView?.text = "ADX: " + (result?.adx?.let { value -> String.format(java.util.Locale.US, "%.1f", value) } ?: "N/A")
        val tint = if (result == null) muted else when (result.bias) {
            "BULLISH" -> green
            "BEARISH" -> red
            else -> amber
        }
        marketBiasView?.setTextColor(tint)
    }

    private fun buildAlerts(): View {
        val box = card()
        box.addView(text("NOTIFICATION SETTINGS", 10f, cyan, true))
        addGap(box, 6)

        val categorySwitches = mutableListOf<Switch>()
        fun alertSwitch(label: String, key: String) {
            val alertPrefs = getSharedPreferences("mgrow_alerts", MODE_PRIVATE)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(text(label, 12f, white), LinearLayout.LayoutParams(0, -2, 1f))
            val toggle = Switch(this).apply {
                isChecked = alertPrefs.getBoolean(key, false)
                setOnCheckedChangeListener { _, checked ->
                    alertPrefs.edit().putBoolean(key, checked).apply()
                }
            }
            if (key == "enabled") {
                toggle.setOnCheckedChangeListener { _, checked ->
                    alertPrefs.edit().putBoolean(key, checked).apply()
                    categorySwitches.forEach { it.isEnabled = checked }
                }
            } else {
                categorySwitches.add(toggle)
                toggle.isEnabled = alertPrefs.getBoolean("enabled", false)
            }
            row.addView(toggle)
            box.addView(row)
            addGap(box, 5)
        }

        alertSwitch("Enable all notifications", "enabled")
        View(this).also {
            it.setBackgroundColor(border)
            box.addView(it, LinearLayout.LayoutParams(-1, dp(1)))
        }
        addGap(box, 7)
        alertSwitch("CE / PE setup confirmed", "setup")
        alertSwitch("VWAP breakout", "vwap")
        alertSwitch("EMA 9 / 21 crossover", "ema")
        alertSwitch("Trend reversal", "reversal")
        alertSwitch("1-minute retest confirmation", "retest")

        val permissionButton = Button(this).apply {
            text = if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) "ALLOW PHONE NOTIFICATIONS" else "NOTIFICATION PERMISSION READY"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 301)
                } else {
                    Toast.makeText(this@MainActivity, "Notification permission is ready.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        box.addView(permissionButton, LinearLayout.LayoutParams(-1, dp(46)))
        addGap(box, 6)

        val testButton = Button(this).apply {
            text = "SEND TEST NOTIFICATION"
            setOnClickListener {
                val enabled = getSharedPreferences("mgrow_alerts", MODE_PRIVATE)
                    .getBoolean("enabled", false)
                val permissionGranted = Build.VERSION.SDK_INT < 33 ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (!enabled) {
                    Toast.makeText(this@MainActivity, "First enable notifications above.", Toast.LENGTH_SHORT).show()
                } else if (!permissionGranted) {
                    Toast.makeText(this@MainActivity, "Allow notification permission first.", Toast.LENGTH_SHORT).show()
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 301)
                    }
                } else {
                    showMarketNotification(
                        "MGrow notification test",
                        "Phone notifications are working. Market alerts will activate after live data and alert rules are connected.",
                        9001
                    )
                }
            }
        }
        box.addView(testButton, LinearLayout.LayoutParams(-1, dp(46)))
        addGap(box, 6)
        box.addView(text(
            "Market alerts run only Monday–Friday, 9:15 AM–3:30 PM IST, with fresh validated live data. Each enabled alert type is sent once per detected event. No orders are placed.",
            10f, amber
        ))
        return box
    }

    // Risk score is a weighted assessment of adverse conditions, not a win probability.
    // Category inputs are normalized from 0 (low risk) to 100 (high risk) after live data integration.
    private fun calculateTradeRisk(
        indicatorRisk: Double,
        trendRisk: Double,
        priceActionRisk: Double,
        sentimentRisk: Double
    ): Int {
        val score = indicatorRisk.coerceIn(0.0, 100.0) * 0.30 +
            trendRisk.coerceIn(0.0, 100.0) * 0.25 +
            priceActionRisk.coerceIn(0.0, 100.0) * 0.25 +
            sentimentRisk.coerceIn(0.0, 100.0) * 0.20
        return score.toInt().coerceIn(0, 100)
    }

    private var riskGaugeView: RiskGaugeView? = null
    private var riskScoreView: TextView? = null
    private var riskBandView: TextView? = null

    // The existing RiskAssessmentEngine remains the single source of truth.
    // Call this with fully validated component inputs when the live risk model is ready.
    private fun updateRiskMeter(assessment: RiskAssessment?) {
        riskGaugeView?.setAssessment(assessment)
        riskScoreView?.text = assessment?.let { "${it.score}/100" } ?: "—/100"
        riskBandView?.text = when (assessment?.band) {
            RiskBand.LOW -> "LOW RISK"
            RiskBand.MODERATE -> "MODERATE RISK"
            RiskBand.HIGH -> "HIGH RISK"
            null -> "DATA UNAVAILABLE"
        }
        riskBandView?.setTextColor(when (assessment?.band) {
            RiskBand.LOW -> green
            RiskBand.MODERATE -> amber
            RiskBand.HIGH -> red
            null -> muted
        })
    }

    private inner class RiskGaugeView : View(this@MainActivity) {
        private var assessment: RiskAssessment? = null
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(19).toFloat()
            strokeCap = Paint.Cap.BUTT
        }
        private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            strokeWidth = dp(4).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan }

        fun setAssessment(value: RiskAssessment?) {
            assessment = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height * 0.84f
            val radius = min(width * 0.39f, height * 0.72f)
            val oval = android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            val segments = listOf(
                Color.rgb(0, 200, 120),
                Color.rgb(255, 193, 7),
                Color.rgb(255, 82, 82)
            )
            var angle = 180f
            val sweep = 60f
            segments.forEach { color ->
                arcPaint.color = color
                canvas.drawArc(oval, angle, sweep - 3f, false, arcPaint)
                angle += sweep
            }
            val score = assessment?.score
            if (score != null) {
                val needleAngle = Math.toRadians((180.0 + score * 1.8))
                val needleLength = radius * 0.78f
                val nx = cx + kotlin.math.cos(needleAngle).toFloat() * needleLength
                val ny = cy + kotlin.math.sin(needleAngle).toFloat() * needleLength
                canvas.drawLine(cx, cy, nx, ny, needlePaint)
                canvas.drawCircle(cx, cy, dp(8).toFloat(), hubPaint)
            }
        }
    }

    private fun buildRiskMeter(): View {
        val box = card()
        box.background = rounded(Color.argb(238, 23, 37, 60), Color.argb(225, 0, 213, 255), 22)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text("OVERALL TRADE RISK", 12f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(helpButton(
            "AI TRADE RISK METER",
            "हा gauge आधीच्या Overall Trade Risk चाच score दाखवेल. RiskAssessmentEngine मधील एकच calculation वापरले जाईल. कमी score म्हणजे तुलनेने कमी जोखीम; हा नफ्याचा probability नाही. सर्व validated inputs मिळेपर्यंत score उपलब्ध दाखवला जाणार नाही.",
            "This gauge displays the existing Overall Trade Risk score using the same RiskAssessmentEngine calculation. A lower score means comparatively lower assessed risk; it is not a probability of profit. No score is shown until all validated inputs are available."
        ))
        box.addView(top)
        addGap(box, 8)

        val gauge = RiskGaugeView()
        riskGaugeView = gauge
        box.addView(gauge, LinearLayout.LayoutParams(-1, dp(150)))

        val scoreRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val score = text("—/100", 30f, cyan, true)
        riskScoreView = score
        scoreRow.addView(score, LinearLayout.LayoutParams(0, -2, 1f))
        val band = pill("DATA UNAVAILABLE", muted)
        riskBandView = band
        scoreRow.addView(band)
        box.addView(scoreRow)
        addGap(box, 6)
        box.addView(text("LOW 0–30  •  MODERATE 31–60  •  HIGH 61–100", 10f, muted, true))
        addGap(box, 6)
        box.addView(text(
            "Risk components: Indicators 30% • Trend 25% • Price Action 25% • Sentiment 20%. Score updates only from the existing risk engine; missing or stale inputs show DATA UNAVAILABLE.",
            10f, amber
        ))
        updateRiskMeter(null)
        return box
    }

    private fun setTf(value: String) {
        tf = value
        tf5.setTextColor(if (tf == "5 MIN") cyan else muted)
        tf1.setTextColor(if (tf == "1 MIN") cyan else muted)
        tf5.background = rounded(if (tf == "5 MIN") Color.argb(210, 50, 42, 83) else surface2, if (tf == "5 MIN") cyan else border, 12)
        tf1.background = rounded(if (tf == "1 MIN") Color.argb(210, 50, 42, 83) else surface2, if (tf == "1 MIN") cyan else border, 12)
        val snapshot = latestSnapshot
        val candles = when {
            snapshot == null -> emptyList()
            tf == "5 MIN" -> snapshot.fiveMinuteCandles
            else -> snapshot.oneMinuteCandles
        }
        chart.setCandles(candles)
        val live = snapshot != null &&
            snapshot.isStale == false &&
            centralMarketState?.isLive == true &&
            candles.isNotEmpty()
        val timeframe = if (tf == "5 MIN") "5M" else "1M"
        chartModePill.text = when {
            candles.isEmpty() -> "NO DATA"
            live -> "LIVE $timeframe"
            else -> "LAST AVAILABLE"
        }
        chartModePill.setTextColor(if (live) green else amber)
        chartModeNoteView.text = when {
            candles.isEmpty() -> "NO $timeframe CANDLES AVAILABLE • NO SAMPLE DATA"
            live -> "VALIDATED LIVE MARKET DATA • $timeframe CANDLES"
            else -> "MARKET CLOSED / STALE • LAST AVAILABLE $timeframe CANDLES • NOT LIVE"
        }
        chartModeNoteView.setTextColor(if (live) green else amber)
    }

    private inner class DemoChart : View(this@MainActivity) {
        private var liveCandles: List<MarketCandle> = emptyList()
        private val grid = Paint(3).apply { color = border; strokeWidth = dp(1).toFloat() }

        fun setCandles(candles: List<MarketCandle>) {
            liveCandles = candles.takeLast(60)
            invalidate()
        }
        private val labelPaint = Paint(3).apply { color = muted; textSize = dp(9).toFloat() }
        private val wick = Paint(3).apply { strokeWidth = dp(2).toFloat() }
        private val body = Paint(3)
        private val line = Paint(3).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            c.drawColor(Color.argb(245, 7, 20, 17))
            val l = dp(34).toFloat()
            val r = width - dp(8).toFloat()
            val t = dp(18).toFloat()
            val b = height - dp(24).toFloat()
            for (i in 0..4) {
                val y = t + (b - t) * i / 4f
                c.drawLine(l, y, r, y, grid)
                c.drawText("—", dp(5).toFloat(), y - dp(3), labelPaint)
            }
            if (liveCandles.isNotEmpty()) {
                val candles = liveCandles.takeLast(45)
                val minPrice = candles.minOf { it.low }
                val maxPrice = candles.maxOf { it.high }
                val priceRange = (maxPrice - minPrice).takeIf { it > 0.0 } ?: 1.0
                fun liveY(price: Double): Float =
                    b - (((price - minPrice) / priceRange).toFloat() * (b - t))
                val step = (r - l) / candles.size
                candles.forEachIndexed { i, candle ->
                    val x = l + step * (i + 0.5f)
                    val candleColor = if (candle.close >= candle.open) green else red
                    wick.color = candleColor
                    body.color = candleColor
                    c.drawLine(x, liveY(candle.high), x, liveY(candle.low), wick)
                    val half = max(dp(2).toFloat(), step * 0.27f)
                    c.drawRect(
                        x - half,
                        min(liveY(candle.open), liveY(candle.close)),
                        x + half,
                        max(liveY(candle.open), liveY(candle.close)) + dp(1),
                        body
                    )
                }
                val closes = candles.map { it.close }
                val chartIndicators = centralMarketState?.let {
                    if (tf == "5 MIN") it.fiveMinuteChartIndicators else it.oneMinuteChartIndicators
                }
                val ema9Values = chartIndicators?.ema9Series?.takeIf { it.size == candles.size }
                    ?: List(candles.size) { null }
                val ema21Values = chartIndicators?.ema21Series?.takeIf { it.size == candles.size }
                    ?: List(candles.size) { null }
                val ema9Path = Path()
                val ema21Path = Path()
                var started9 = false
                var started21 = false
                candles.forEachIndexed { i, _ ->
                    val x = l + step * (i + 0.5f)
                    ema9Values[i]?.let { value ->
                        if (!started9) { ema9Path.moveTo(x, liveY(value)); started9 = true }
                        else ema9Path.lineTo(x, liveY(value))
                    }
                    ema21Values[i]?.let { value ->
                        if (!started21) { ema21Path.moveTo(x, liveY(value)); started21 = true }
                        else ema21Path.lineTo(x, liveY(value))
                    }
                }
                line.color = cyan
                c.drawPath(ema9Path, line)
                line.color = amber
                c.drawPath(ema21Path, line)
                val vwap = chartIndicators?.vwap
                if (vwap != null) {
                    line.color = white
                    line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5).toFloat(), dp(4).toFloat()), 0f)
                    c.drawLine(l, liveY(vwap), r, liveY(vwap), line)
                    line.pathEffect = null
                }
                val chartIsLive = centralMarketState?.isLive == true && latestSnapshot?.isStale == false
                val chartStatus = if (chartIsLive) "LIVE" else "LAST AVAILABLE • NOT LIVE"
                c.drawText(chartStatus + " • EMA 9 / EMA 21" + if (vwap == null) " • VWAP N/A" else " • VWAP", l, dp(12).toFloat(), labelPaint)
                return
            }

            labelPaint.textAlign = Paint.Align.CENTER
            labelPaint.textSize = dp(12).toFloat()
            c.drawText("WAITING FOR REAL MARKET CANDLES", width / 2f, height / 2f, labelPaint)
            labelPaint.textAlign = Paint.Align.LEFT
        }
    }
}