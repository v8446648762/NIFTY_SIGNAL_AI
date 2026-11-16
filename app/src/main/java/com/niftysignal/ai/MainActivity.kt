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
import android.widget.Switch
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.content.Intent
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
    }

    override fun onStop() {
        if (isAppLockEnabled() && !authenticationInProgress) appUnlocked = false
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
    private var paperSide = TradeBias.BUY_CE
    private var paperOpen = false
    private var openPaperTrade: PaperTrade? = null
    private val paperHistory = mutableListOf<String>()
    private var marketDataFeed: MarketDataFeed? = null
    private var latestSnapshot: MarketDataSnapshot? = null
    private var currentPage = "Home"
    private var lastAiSignalKey: String? = null
    private lateinit var pageContent: LinearLayout
    private lateinit var bottomNav: LinearLayout

    private lateinit var paperStatus: TextView
    private lateinit var paperHistoryView: TextView
    private lateinit var paperSelectionView: TextView
    private lateinit var chart: DemoChart
    private lateinit var tf5: TextView
    private lateinit var tf1: TextView

    private lateinit var feedStatusView: TextView
    private lateinit var indexPriceView: TextView
    private lateinit var indexHintView: TextView
    private lateinit var changeValueView: TextView
    private lateinit var dayHighValueView: TextView
    private lateinit var dayLowValueView: TextView
    private lateinit var signalStatusView: TextView
    private lateinit var signalBiasView: TextView
    private lateinit var signalReasonView: TextView
    private lateinit var signalTrendValueView: TextView
    private lateinit var signalSetupValueView: TextView
    private lateinit var signalConfirmValueView: TextView
    private lateinit var vwapValueView: TextView
    private lateinit var ema9ValueView: TextView
    private lateinit var ema21ValueView: TextView
    private lateinit var rsiValueView: TextView
    private lateinit var supportValueView: TextView
    private lateinit var resistanceValueView: TextView
    private lateinit var chartModePill: TextView
    private lateinit var chartModeNoteView: TextView

    private val marketFeedListener = object : MarketDataFeed.Listener {
        override fun onStatusChanged(status: FeedConnectionState, message: String?) {
            runOnUiThread { updateFeedStatus(status, message) }
        }

        override fun onSnapshot(snapshot: MarketDataSnapshot) {
            runOnUiThread { applyMarketSnapshot(snapshot) }
        }
    }


    /**
     * Future Upstox integration point. The adapter supplies candles through
     * MarketDataFeed; the dashboard itself never handles broker orders or secrets.
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
        if (::feedStatusView.isInitialized) {
            val color = when (status) {
                FeedConnectionState.LIVE -> green
                FeedConnectionState.CONNECTING -> cyan
                FeedConnectionState.STALE, FeedConnectionState.ERROR -> red
                FeedConnectionState.OFFLINE -> amber
            }
            feedStatusView.text = "● FEED " + status.name
            feedStatusView.setTextColor(color)
            feedStatusView.background = rounded(Color.argb(190, 41, 38, 66), color, 30)
        }
        if (status == FeedConnectionState.OFFLINE ||
            status == FeedConnectionState.STALE ||
            status == FeedConnectionState.ERROR
        ) {
            clearLiveDashboard(message ?: "Live market data is unavailable.")
        } else if (status == FeedConnectionState.CONNECTING) {
            clearLiveDashboard(message ?: "Connecting to market data...")
        }
    }

    private fun clearLiveDashboard(message: String) {
        latestSnapshot = null
        if (::indexPriceView.isInitialized) indexPriceView.text = "— — — — —"
        if (::indexHintView.isInitialized) indexHintView.text = message
        if (::changeValueView.isInitialized) changeValueView.text = "—"
        if (::dayHighValueView.isInitialized) dayHighValueView.text = "—"
        if (::dayLowValueView.isInitialized) dayLowValueView.text = "—"
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
            chartModePill.text = "SAMPLE CHART"
            chartModePill.setTextColor(amber)
        }
        if (::chartModeNoteView.isInitialized) {
            chartModeNoteView.text = "SIMULATED PREVIEW  •  LIVE FEED NOT CONNECTED"
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

    private fun applyMarketSnapshot(snapshot: MarketDataSnapshot) {
        val ageMillis = System.currentTimeMillis() - snapshot.receivedAtMillis
        if (ageMillis < -5000L || ageMillis > 90000L) {
            updateFeedStatus(FeedConnectionState.STALE, "Market update is older than 90 seconds; waiting for fresh data.")
            return
        }
        if (snapshot.fiveMinuteCandles.isEmpty() ||
            snapshot.oneMinuteCandles.isEmpty() ||
            !snapshot.fiveMinuteCandles.all(::isValidMarketCandle) ||
            !snapshot.oneMinuteCandles.all(::isValidMarketCandle)
        ) {
            updateFeedStatus(FeedConnectionState.ERROR, "Feed returned empty or invalid OHLCV candles.")
            return
        }

        latestSnapshot = snapshot
        updateFeedStatus(FeedConnectionState.LIVE, null)

        val latest = snapshot.fiveMinuteCandles.last()
        indexPriceView.text = formatMarketValue(latest.close)
        indexHintView.text = "Latest validated 5-minute NIFTY candle • Feed received just now"

        val previousClose = snapshot.previousClose?.takeIf { it.isFinite() && it > 0.0 }
        if (previousClose != null) {
            val change = latest.close - previousClose
            val percent = change / previousClose * 100.0
            changeValueView.text = String.format(java.util.Locale.US, "%+.2f (%.2f%%)", change, percent)
            changeValueView.setTextColor(if (change >= 0.0) green else red)
        } else {
            changeValueView.text = "—"
            changeValueView.setTextColor(muted)
        }

        val dayHigh = snapshot.dayHigh?.takeIf { it.isFinite() && it > 0.0 }
            ?: snapshot.fiveMinuteCandles.maxOfOrNull { it.high }
        val dayLow = snapshot.dayLow?.takeIf { it.isFinite() && it > 0.0 }
            ?: snapshot.fiveMinuteCandles.minOfOrNull { it.low }
        dayHighValueView.text = formatMarketValue(dayHigh)
        dayLowValueView.text = formatMarketValue(dayLow)

        val decision = SignalEngine.evaluate(snapshot.fiveMinuteCandles, snapshot.oneMinuteCandles)
        recordAiSignal(decision, latest.close, snapshot.receivedAtMillis)
        val biasText = when (decision.bias) {
            TradeBias.BUY_CE -> "BUY CE"
            TradeBias.BUY_PE -> "BUY PE"
            TradeBias.WAIT -> "WAIT"
        }
        signalBiasView.text = biasText
        signalBiasView.setTextColor(
            when (decision.bias) {
                TradeBias.BUY_CE -> green
                TradeBias.BUY_PE -> red
                TradeBias.WAIT -> amber
            }
        )
        signalStatusView.text = if (decision.confirmed) "SETUP CONFIRMED" else "NO CONFIRMED SETUP"
        val signalColor = if (decision.confirmed) green else amber
        signalStatusView.setTextColor(signalColor)
        signalStatusView.background = rounded(Color.argb(190, 41, 38, 66), signalColor, 30)
        signalTrendValueView.text = decision.trend
        signalSetupValueView.text = if (decision.confirmed) "5M VALID" else "WAIT"
        signalConfirmValueView.text = if (decision.confirmed) "1M OK" else "PENDING"

        val marathiReason = when (decision.bias) {
            TradeBias.BUY_CE -> "मराठी: 5 मिनिटांचा तेजीचा setup आणि 1 मिनिटाची पुष्टी मिळाली."
            TradeBias.BUY_PE -> "मराठी: 5 मिनिटांचा मंदीचा setup आणि 1 मिनिटाची पुष्टी मिळाली."
            TradeBias.WAIT -> "मराठी: सर्व अटी एकत्र जुळलेल्या नाहीत; पुष्टी येईपर्यंत थांबा."
        }
        signalReasonView.text = decision.reasons.joinToString("\n") + "\n" + marathiReason

        vwapValueView.text = formatMarketValue(decision.indicators.vwap)
        ema9ValueView.text = formatMarketValue(decision.indicators.ema9)
        ema21ValueView.text = formatMarketValue(decision.indicators.ema21)
        rsiValueView.text = formatMarketValue(decision.indicators.rsi14)
        supportValueView.text = formatMarketValue(decision.indicators.support)
        resistanceValueView.text = formatMarketValue(decision.indicators.resistance)

        val visibleCandles = if (tf == "5 MIN") snapshot.fiveMinuteCandles else snapshot.oneMinuteCandles
        chart.setCandles(visibleCandles)
        chartModePill.text = "LIVE " + if (tf == "5 MIN") "5M" else "1M"
        chartModePill.setTextColor(green)
        chartModeNoteView.text = "LIVE MARKET DATA  •  " + tf + " CANDLES"
        chartModeNoteView.setTextColor(green)
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

    private fun text(s: String, size: Float = 14f, color: Int = white, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
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
                intArrayOf(bg, surface2, Color.rgb(36, 27, 70))
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
        bottomNav = LinearLayout(this)
        root.addView(bottomNav, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        root.requestApplyInsets()
        showPage("Home")
    }

    private fun showPage(page: String) {
        currentPage = page
        pageContent.removeAllViews()
        when (page) {
            "Home" -> {
                pageContent.addView(buildHomeDashboard())
            }
            "Chart" -> {
                pageContent.addView(buildHeader())
                addGap(pageContent, 14)
                section(pageContent, "LIVE MARKET CHART", "NIFTY 50")
                pageContent.addView(buildChartCard())
                addGap(pageContent, 18)
                section(pageContent, "MARKET LEVELS", "SUPPORT & RESISTANCE")
                pageContent.addView(buildLevels())
            }
            "AI" -> {
                pageContent.addView(buildHeader())
                addGap(pageContent, 14)
                section(pageContent, "AI SIGNAL ENGINE", "CE / PE / WAIT")
                pageContent.addView(buildSignalCard())
                addGap(pageContent, 18)
                section(pageContent, "MARKET INTELLIGENCE", "PRICE ACTION & TREND")
                pageContent.addView(buildInsights())
                addGap(pageContent, 18)
                section(pageContent, "AI TRADE RISK METER", "RISK ASSESSMENT")
                pageContent.addView(buildRiskMeter())
            }
            "Tools" -> {
                pageContent.addView(buildHeader())
                addGap(pageContent, 14)
                section(pageContent, "TECHNICAL ANALYTICS", "INDICATORS")
                pageContent.addView(buildIndicators())
                addGap(pageContent, 18)
                section(pageContent, "PAPER TRADING", "MANUAL PRACTICE ONLY")
                pageContent.addView(buildPaperTrading())
            }
            "Alerts" -> {
                pageContent.addView(buildHeader())
                addGap(pageContent, 14)
                section(pageContent, "SMART ALERTS", "NOTIFICATIONS")
                pageContent.addView(buildAlerts())
            }
            "Reports" -> {
                pageContent.addView(buildHeader())
                addGap(pageContent, 14)
                section(pageContent, "TRADING REPORTS", "MANUAL + AI PERFORMANCE")
                pageContent.addView(buildReports())
            }
            "Testing" -> {
                pageContent.addView(buildHeader())
                addGap(pageContent, 14)
                pageContent.addView(TestingLabScreen.create(this))
            }
            "Settings" -> {
                pageContent.addView(buildHeader())
                addGap(pageContent, 14)
                section(pageContent, "APP SETTINGS", "PERSONALIZE MGROW")
                pageContent.addView(buildSettings())
            }
        }
        addGap(pageContent, 12)
        pageContent.addView(text("MGrow  /  ${page.uppercase()} SECTION", 10f, muted, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        })
        renderBottomNavigation()
    }

    private fun renderBottomNavigation() {
        bottomNav.removeAllViews()
        bottomNav.orientation = LinearLayout.HORIZONTAL
        bottomNav.gravity = Gravity.CENTER
        bottomNav.setPadding(dp(6), dp(8), dp(6), dp(8))
        bottomNav.background = rounded(Color.argb(245, 15, 27, 46), border, 18)
        val items = listOf(
            Triple("Home", "⌂", if (isMarathi()) "होम" else "Home"),
            Triple("Chart", "▥", if (isMarathi()) "चार्ट" else "Chart"),
            Triple("AI", "✦", "AI"),
            Triple("Alerts", "♧", if (isMarathi()) "अलर्ट" else "Alerts"),
            Triple("Tools", "⚙", if (isMarathi()) "टूल्स" else "Tools"),
            Triple("Testing", "⌁", if (isMarathi()) "टेस्ट" else "Test"),
            Triple("Settings", "☷", if (isMarathi()) "सेटिंग" else "Settings")
        )
        items.forEach { item ->
            val selected = currentPage == item.first
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(3), dp(3), dp(3))
                background = if (selected) rounded(Color.argb(70, 0, 213, 255), Color.argb(100, 0, 213, 255), 12) else null
                isClickable = true
                isFocusable = true
                setOnClickListener { showPage(item.first) }
            }
            tab.addView(text(item.second, 19f, if (selected) cyan else muted, true).apply {
                gravity = Gravity.CENTER
            })
            tab.addView(text(item.third, 9f, if (selected) cyan else muted, selected).apply {
                gravity = Gravity.CENTER
            })
            bottomNav.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
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

    private fun buildHomeDashboard(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandRow.addView(text("MGrow AI", 21f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        brandRow.addView(pill("Dashboard", green))
        root.addView(brandRow)
        addGap(root, 6)
        root.addView(buildDateTimeView())
        addGap(root, 16)
        root.addView(text("NIFTY 50", 29f, white, true))
        addGap(root, 5)
        root.addView(text("Market Overview  •  Live data जोडल्यावर अपडेट", 13f, muted, true))
        addGap(root, 18)

        fun feature(title: String, detail: String, symbol: String, destination: String): LinearLayout {
            val glass = getSharedPreferences("mgrow_settings", MODE_PRIVATE)
                .getString("theme", "Midnight") == "Blue Glass"
            val iconColor = if (glass) Color.rgb(104, 205, 255) else cyan
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(12), dp(14))
                minimumHeight = dp(112)
                background = if (glass) {
                    GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Color.argb(242, 38, 65, 103), Color.argb(224, 24, 42, 70))
                    ).apply {
                        cornerRadius = dp(22).toFloat()
                        setStroke(dp(1), Color.argb(190, 105, 174, 235))
                    }
                } else rounded(surface, border, 18)
                elevation = if (glass) dp(7).toFloat() else dp(2).toFloat()
                isClickable = true
                isFocusable = true
                contentDescription = "$title. $detail"
                setOnClickListener { showPage(destination) }

                val titleRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val iconChip = TextView(this@MainActivity).apply {
                    text = symbol
                    textSize = 21f
                    gravity = Gravity.CENTER
                    setTextColor(iconColor)
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Color.argb(115, 40, 145, 220), Color.argb(90, 91, 91, 205))
                    ).apply {
                        cornerRadius = dp(13).toFloat()
                        setStroke(dp(1), Color.argb(145, 105, 190, 255))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        rightMargin = dp(9)
                    }
                }
                titleRow.addView(iconChip)
                titleRow.addView(text(title, 15f, white, true))
                addView(titleRow)
                addView(View(this@MainActivity), LinearLayout.LayoutParams(1, dp(8)))
                addView(text(detail, 12f, muted))
            }
        }

        fun addFeatureRow(left: View, right: View) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            row.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(7) })
            row.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(7) })
            root.addView(row)
            addGap(root, 10)
        }

        addFeatureRow(
            feature("Market Overview", "Price, Trend, Chart", "↗", "Chart"),
            feature("AI Signals", "CE / PE / WAIT", "✦", "AI")
        )
        addFeatureRow(
            feature("Technical Analysis", "VWAP, EMA, RSI • Feed required", "⌁", "Tools"),
            feature("Option Chain", "Planned after data integration", "⛓", "Chart")
        )
        addFeatureRow(
            feature("AI Intelligence", "Filters activate with live candles", "◎", "AI"),
            feature("Risk Manager", "Risk score • Live data required", "⬡", "AI")
        )
        addFeatureRow(
            feature("Alerts", "Preferences ready • Triggers pending", "♧", "Alerts"),
            feature("Paper Trading", "Manual practice • Session only", "▤", "Tools")
        )
        val reportsTile = feature("Reports", "Manual Trades • AI Signals • Performance", "▤", "Reports")
        reportsTile.layoutParams = LinearLayout.LayoutParams(-1, -2)
        root.addView(reportsTile)
        addGap(root, 10)
        val note = text("Select a card or use the bottom navigation to open each section.", 11f, muted, true)
        note.gravity = Gravity.CENTER
        root.addView(note)
        return root
    }

    private fun isMarathi(): Boolean =
        getSharedPreferences("mgrow_settings", MODE_PRIVATE).getString("language", "English") == "मराठी"

    private fun applySavedAppearance() {
        val prefs = getSharedPreferences("mgrow_settings", MODE_PRIVATE)
        when (prefs.getString("theme", "Midnight")) {
            "Pure Black" -> {
                bg = Color.rgb(0, 0, 0); surface = Color.rgb(18, 18, 22)
                surface2 = Color.rgb(24, 27, 35); border = Color.rgb(70, 82, 105)
                muted = Color.rgb(205, 215, 230); white = Color.WHITE
            }
            "Ocean Blue" -> {
                bg = Color.rgb(5, 25, 45); surface = Color.rgb(18, 48, 72)
                surface2 = Color.rgb(20, 62, 88); border = Color.rgb(50, 125, 155)
                muted = Color.rgb(195, 220, 235); white = Color.WHITE
            }
            "Blue Glass" -> {
                bg = Color.rgb(13, 24, 45)
                surface = Color.argb(226, 28, 43, 69)
                surface2 = Color.argb(238, 40, 61, 96)
                border = Color.argb(220, 88, 151, 220)
                muted = Color.rgb(190, 211, 238)
                white = Color.rgb(248, 251, 255)
            }
            "Graphite" -> {
                bg = Color.rgb(19, 21, 27); surface = Color.rgb(38, 42, 52)
                surface2 = Color.rgb(48, 54, 66); border = Color.rgb(95, 105, 125)
                muted = Color.rgb(210, 215, 225); white = Color.WHITE
            }
            else -> {
                bg = Color.rgb(16, 24, 39); surface = Color.argb(224, 29, 43, 67)
                surface2 = Color.argb(236, 36, 54, 83); border = Color.argb(210, 75, 101, 139)
                muted = Color.rgb(195, 208, 226); white = Color.WHITE
            }
        }
        green = Color.rgb(0, 200, 150)
        red = Color.rgb(255, 85, 119)
        amber = Color.rgb(255, 200, 87)
        cyan = when (prefs.getString("accent", "Cyan")) {
            "Violet" -> Color.rgb(167, 110, 255)
            "Blue" -> Color.rgb(65, 145, 255)
            "Green" -> Color.rgb(0, 220, 145)
            "Orange" -> Color.rgb(255, 145, 55)
            "Red" -> Color.rgb(255, 75, 95)
            "Pink" -> Color.rgb(255, 90, 180)
            "Gold" -> Color.rgb(255, 195, 55)
            "Teal" -> Color.rgb(0, 190, 180)
            else -> Color.rgb(0, 213, 255)
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

    private fun buildReports(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val manual = loadReportRecords("manual_trades")
        val signals = loadReportRecords("ai_signals")
        var totalPnl = 0.0
        var wins = 0
        var losses = 0
        for (i in 0 until manual.length()) {
            val trade = manual.optJSONObject(i) ?: continue
            val pnl = trade.optDouble("pnl", 0.0)
            totalPnl += pnl
            if (pnl > 0.0) wins++ else if (pnl < 0.0) losses++
        }
        val confirmed = (0 until signals.length()).count {
            signals.optJSONObject(it)?.optBoolean("confirmed", false) == true
        }
        val ceCount = (0 until signals.length()).count {
            signals.optJSONObject(it)?.optString("bias") == TradeBias.BUY_CE.name
        }
        val peCount = (0 until signals.length()).count {
            signals.optJSONObject(it)?.optString("bias") == TradeBias.BUY_PE.name
        }
        val waitCount = (0 until signals.length()).count {
            signals.optJSONObject(it)?.optString("bias") == TradeBias.WAIT.name
        }

        val summary = card()
        summary.addView(text("MANUAL TRADE PERFORMANCE", 11f, cyan, true))
        addGap(summary, 5)
        summary.addView(text(
            String.format(java.util.Locale.US, "₹ %+.2f", totalPnl),
            27f, if (totalPnl >= 0.0) green else red, true
        ))
        summary.addView(text("Gross P&L • before brokerage, taxes and slippage", 10f, muted))
        addGap(summary, 10)
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metrics.addView(metric("TRADES", manual.length().toString()), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric("WINS", wins.toString(), green), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric("LOSSES", losses.toString(), red), LinearLayout.LayoutParams(0, -2, 1f))
        summary.addView(metrics)
        root.addView(summary)
        addGap(root, 12)

        val addTrade = Button(this).apply {
            text = if (isMarathi()) "＋ मॅन्युअल ट्रेड जोडा" else "＋ ADD MANUAL TRADE"
            setOnClickListener { showManualTradeDialog() }
        }
        root.addView(addTrade, LinearLayout.LayoutParams(-1, dp(48)))
        addGap(root, 12)

        val aiCard = card()
        aiCard.addView(text("AI SIGNAL PERFORMANCE LOG", 11f, cyan, true))
        addGap(aiCard, 7)
        aiCard.addView(text("Recorded signal changes: ${signals.length()}", 20f, white, true))
        aiCard.addView(text(
            "CE: $ceCount   •   PE: $peCount   •   WAIT: $waitCount   •   Confirmed: $confirmed",
            11f, muted
        ))
        addGap(aiCard, 5)
        aiCard.addView(text(
            if (signals.length() == 0) "No validated live AI signals recorded yet. Sample chart signals are not included."
            else "This is a signal log, not a win-rate report. Signal outcomes need subsequent validated market candles.",
            10f, amber
        ))
        root.addView(aiCard)
        addGap(root, 12)

        val history = card()
        history.addView(text("RECENT MANUAL TRADES", 11f, cyan, true))
        addGap(history, 7)
        if (manual.length() == 0) {
            history.addView(text("No manual trades recorded yet.", 12f, muted))
        } else {
            val formatter = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault())
            for (i in manual.length() - 1 downTo maxOf(0, manual.length() - 10)) {
                val trade = manual.optJSONObject(i) ?: continue
                val side = trade.optString("side", "CE")
                val pnl = trade.optDouble("pnl", 0.0)
                val line = "$side • Qty ${trade.optInt("qty")} • Entry ₹${trade.optDouble("entry")} • Exit ₹${trade.optDouble("exit")} • P&L ₹${String.format(java.util.Locale.US, "%.2f", pnl)}"
                history.addView(text(line, 11f, if (pnl >= 0.0) green else red, true))
                history.addView(text(formatter.format(java.util.Date(trade.optLong("time"))), 9f, muted))
                addGap(history, 5)
            }
        }
        root.addView(history)
        addGap(root, 12)

        val export = Button(this).apply {
            text = "SHARE REPORT DATA (CSV)"
            setOnClickListener { shareReportsCsv() }
        }
        root.addView(export, LinearLayout.LayoutParams(-1, dp(48)))
        addGap(root, 6)
        root.addView(text(
            "Reports are saved locally on this phone. Manual P&L is gross and does not include charges. AI signal accuracy will be calculated only after outcome tracking is implemented.",
            10f, amber
        ))
        return root
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

        val themes = arrayOf("Midnight", "Pure Black", "Ocean Blue", "Blue Glass", "Graphite")
        root.addView(settingRow(
            if (isMarathi()) "अॅप थीम" else "App Theme",
            (prefs.getString("theme", "Midnight") ?: "Midnight") + "  •  5 display themes"
        ) {
            AlertDialog.Builder(this).setTitle("Choose app theme")
                .setSingleChoiceItems(themes, themes.indexOf(prefs.getString("theme", "Midnight"))) { dialog, which ->
                    prefs.edit().putString("theme", themes[which]).apply()
                    dialog.dismiss()
                    recreate()
                }.setNegativeButton("Cancel", null).show()
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
        wrap.addView(pill("●  DEMO MODE", amber))
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
        chartModePill = pill("SAMPLE CHART", amber)
        tfRow.addView(chartModePill)
        box.addView(tfRow)
        addGap(box, 10)
        chart = DemoChart()
        box.addView(chart, LinearLayout.LayoutParams(-1, dp(220)))
        addGap(box, 7)
        chartModeNoteView = text("SIMULATED PREVIEW  •  LIVE FEED NOT CONNECTED", 10f, amber)
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
        box.addView(text("MARKET BIAS", 10f, muted, true))
        box.addView(text("Awaiting live market feed", 16f, white, true))
        addGap(box, 6)
        box.addView(text("Volume confirmation • False-breakout filter • Trend strength", 11f, muted))
        addGap(box, 8)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chips.addView(pill("SUPERTREND", muted))
        chips.addView(View(this), LinearLayout.LayoutParams(dp(7), 1))
        chips.addView(pill("MACD", muted))
        chips.addView(View(this), LinearLayout.LayoutParams(dp(7), 1))
        chips.addView(pill("ADX", muted))
        box.addView(chips)
        return box
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
            "Alert preferences are saved on this phone. Market-triggered alerts will activate after FYERS live data and signal conditions are connected. No orders are placed.",
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
    private fun updateRiskMeter(inputs: RiskInputs?) {
        val assessment = inputs?.let { RiskAssessmentEngine.evaluate(it) }
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

    private fun buildPaperTrading(): View {
        val box = card()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("Practice trades only • No broker connection", 11f, amber, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(helpButton(
            "PAPER TRADING",
            "हा फक्त सरावासाठी आहे. CE/PE, Entry Premium, Quantity आणि Exit Premium स्वतः भरा. P&L हा gross आहे; brokerage व taxes वजा केलेले नाहीत. कोणतीही broker order पाठवली जात नाही.",
            "This is for practice only. Enter CE/PE, entry premium, quantity, and exit premium manually. P&L is gross before brokerage and taxes. No broker order is sent."
        ))
        box.addView(header)
        addGap(box, 8)

        val sideRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ceButton = Button(this).apply {
            text = "BUY CE"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(91, 76, 190))
            setOnClickListener {
                paperSide = TradeBias.BUY_CE
                paperSelectionView.text = "Selected side: BUY CE"
                setBackgroundColor(Color.rgb(91, 76, 190))
            }
        }
        val peButton = Button(this).apply {
            text = "BUY PE"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(65, 54, 86))
            setOnClickListener {
                paperSide = TradeBias.BUY_PE
                paperSelectionView.text = "Selected side: BUY PE"
                setBackgroundColor(Color.rgb(190, 48, 105))
                ceButton.setBackgroundColor(Color.rgb(65, 54, 86))
            }
        }
        // Local helper is applied after both controls are created so the selected side is visible.
        ceButton.setOnClickListener {
            paperSide = TradeBias.BUY_CE
            paperSelectionView.text = "Selected side: BUY CE"
            ceButton.setBackgroundColor(Color.rgb(91, 76, 190))
            peButton.setBackgroundColor(Color.rgb(65, 54, 86))
        }
        peButton.setOnClickListener {
            paperSide = TradeBias.BUY_PE
            paperSelectionView.text = "Selected side: BUY PE"
            peButton.setBackgroundColor(Color.rgb(190, 48, 105))
            ceButton.setBackgroundColor(Color.rgb(65, 54, 86))
        }
        sideRow.addView(ceButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        sideRow.addView(peButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
        box.addView(sideRow)
        paperSelectionView = text("Selected side: BUY CE", 10f, cyan, true)
        box.addView(paperSelectionView)
        addGap(box, 8)

        fun field(hint: String, inputType: Int): EditText = EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            setTextColor(white)
            setHintTextColor(muted)
            textSize = 14f
            setSingleLine(true)
            background = rounded(Color.argb(245, 36, 54, 83), border, 12)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }

        val entryField = field("Entry premium (e.g. 120.50)", 8194)
        val qtyField = field("Quantity (units)", 2)
        val entryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        entryRow.addView(entryField, LinearLayout.LayoutParams(0, dp(52), 1.4f))
        entryRow.addView(qtyField, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(8) })
        box.addView(entryRow)
        addGap(box, 8)

        lateinit var closeButton: Button
        lateinit var openButton: Button
        openButton = Button(this).apply {
            text = "SAVE PAPER ENTRY"
            setOnClickListener {
                val entry = entryField.text.toString().toDoubleOrNull()
                val qty = qtyField.text.toString().toIntOrNull()
                if (paperOpen) {
                    paperStatus.text = "Close the current practice trade before opening another."
                    return@setOnClickListener
                }
                if (entry == null || !entry.isFinite() || entry <= 0.0 || qty == null || qty <= 0) {
                    paperStatus.text = "Enter a valid premium and quantity."
                    return@setOnClickListener
                }
                openPaperTrade = PaperTrade(
                    side = paperSide,
                    entryPremium = entry,
                    quantity = qty,
                    openedAtMillis = System.currentTimeMillis()
                )
                paperOpen = true
                paperStatus.text = "OPEN: " + paperSide.name + " • Entry ₹" +
                    String.format(java.util.Locale.US, "%.2f", entry) + " • Qty " + qty
                entryField.isEnabled = false
                qtyField.isEnabled = false
                openButton.isEnabled = false
                closeButton.isEnabled = true
            }
        }
        box.addView(openButton, LinearLayout.LayoutParams(-1, dp(48)))
        addGap(box, 8)

        val exitField = field("Exit premium (e.g. 135.00)", 8194)
        box.addView(exitField, LinearLayout.LayoutParams(-1, dp(52)))
        addGap(box, 8)

        closeButton = Button(this).apply {
            text = "CLOSE PAPER TRADE"
            isEnabled = false
            setOnClickListener {
                val exit = exitField.text.toString().toDoubleOrNull()
                val opened = openPaperTrade
                if (!paperOpen || opened == null) {
                    paperStatus.text = "There is no open practice trade."
                    return@setOnClickListener
                }
                if (exit == null || !exit.isFinite() || exit <= 0.0) {
                    paperStatus.text = "Enter a valid exit premium."
                    return@setOnClickListener
                }
                val closed = opened.copy(
                    exitPremium = exit,
                    closedAtMillis = System.currentTimeMillis()
                )
                val pnl = closed.grossPnl ?: 0.0
                val record = closed.side.name + " | Entry ₹" +
                    String.format(java.util.Locale.US, "%.2f", closed.entryPremium) +
                    " | Exit ₹" + String.format(java.util.Locale.US, "%.2f", exit) +
                    " | Qty " + closed.quantity + " | P&L ₹" +
                    String.format(java.util.Locale.US, "%.2f", pnl)
                paperHistory.add(0, record)
                paperHistoryView.text = paperHistory.take(10).joinToString("\n")
                paperStatus.text = "CLOSED • Gross P&L: ₹" +
                    String.format(java.util.Locale.US, "%.2f", pnl) + " (before charges)"
                paperStatus.setTextColor(if (pnl >= 0.0) green else red)
                paperOpen = false
                openPaperTrade = null
                entryField.isEnabled = true
                qtyField.isEnabled = true
                openButton.isEnabled = true
                closeButton.isEnabled = false
                exitField.text.clear()
            }
        }
        box.addView(closeButton, LinearLayout.LayoutParams(-1, dp(48)))
        addGap(box, 8)

        paperStatus = text("No paper trade open.", 12f, muted)
        box.addView(paperStatus)
        addGap(box, 10)
        box.addView(text("RECENT PRACTICE TRADES", 10f, cyan, true))
        paperHistoryView = text("No trades recorded in this session.", 11f, muted)
        box.addView(paperHistoryView)
        addGap(box, 6)
        box.addView(text("Records are temporary for this app session. P&L is gross, excluding brokerage, taxes and slippage.", 10f, amber))
        return box
    }

    private fun setTf(value: String) {
        tf = value
        tf5.setTextColor(if (tf == "5 MIN") cyan else muted)
        tf1.setTextColor(if (tf == "1 MIN") cyan else muted)
        tf5.background = rounded(if (tf == "5 MIN") Color.argb(210, 50, 42, 83) else surface2, if (tf == "5 MIN") cyan else border, 12)
        tf1.background = rounded(if (tf == "1 MIN") Color.argb(210, 50, 42, 83) else surface2, if (tf == "1 MIN") cyan else border, 12)
        val snapshot = latestSnapshot
        if (snapshot != null) {
            val candles = if (tf == "5 MIN") snapshot.fiveMinuteCandles else snapshot.oneMinuteCandles
            chart.setCandles(candles)
            chartModePill.text = "LIVE " + if (tf == "5 MIN") "5M" else "1M"
            chartModeNoteView.text = "LIVE MARKET DATA  •  " + tf + " CANDLES"
        } else {
            chart.invalidate()
        }
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
            c.drawColor(Color.argb(245, 20, 34, 57))
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
                c.drawText("LIVE CANDLES", l, dp(12).toFloat(), labelPaint)
                return
            }

            val values = if (tf == "5 MIN")
                listOf(25190, 25202, 25196, 25210, 25204, 25218, 25208, 25222, 25215, 25228, 25216, 25205, 25214, 25202, 25218, 25224, 25212, 25230, 25220, 25235, 25225, 25218, 25229, 25200)
            else
                listOf(25205, 25210, 25202, 25214, 25208, 25218, 25212, 25222, 25216, 25226, 25218, 25210, 25216, 25208, 25220, 25225, 25214, 25228, 25222, 25232, 25224, 25218, 25226, 25200)
            val step = (r - l) / values.size
            fun y(v: Float) = b - ((v - 25180f) / 65f) * (b - t)
            val e9 = Path()
            val e21 = Path()
            values.forEachIndexed { i, close ->
                val open = if (i == 0) close - 4 else values[i - 1]
                val high = max(open, close) + 3 + i % 4
                val low = min(open, close) - 2 - i % 3
                val x = l + step * (i + .5f)
                val color = if (close >= open) green else red
                wick.color = color
                body.color = color
                c.drawLine(x, y(high.toFloat()), x, y(low.toFloat()), wick)
                val half = max(dp(2).toFloat(), step * .27f)
                c.drawRect(x - half, min(y(open.toFloat()), y(close.toFloat())), x + half,
                    max(y(open.toFloat()), y(close.toFloat())) + dp(1), body)
                val v9 = close - 3f + i % 3
                val v21 = close - 7f + i % 2
                if (i == 0) { e9.moveTo(x, y(v9)); e21.moveTo(x, y(v21)) }
                else { e9.lineTo(x, y(v9)); e21.lineTo(x, y(v21)) }
            }
            line.color = cyan
            c.drawPath(e9, line)
            line.color = amber
            c.drawPath(e21, line)
            c.drawText("EMA 9", l, height - dp(7).toFloat(), labelPaint)
            c.drawText("EMA 21", l + dp(52), height - dp(7).toFloat(), labelPaint)
            c.drawText("PREVIEW", r - dp(55), dp(12).toFloat(), labelPaint)
        }
    }
}