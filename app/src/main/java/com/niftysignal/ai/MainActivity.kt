package com.niftysignal.ai

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
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
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    // Soft blue-purple glass theme inspired by the approved reference image.
    private val bg = Color.rgb(91, 166, 235)
    private val surface = Color.argb(112, 224, 243, 255)
    private val surface2 = Color.argb(142, 209, 232, 255)
    private val border = Color.argb(190, 239, 250, 255)
    private val muted = Color.rgb(57, 72, 96)
    private val white = Color.rgb(24, 32, 52)
    private val green = Color.rgb(0, 145, 112)
    private val cyan = Color.rgb(35, 91, 166)
    private val red = Color.rgb(210, 55, 103)
    private val amber = Color.rgb(142, 93, 12)
    private var tf = "5 MIN"
    private var paperSide = TradeBias.BUY_CE
    private var paperEntry = 0.0
    private var paperQuantity = 0
    private var paperOpen = false
    private val paperHistory = mutableListOf<String>()
    private lateinit var paperStatus: TextView
    private lateinit var paperHistoryView: TextView
    private lateinit var chart: DemoChart
    private lateinit var tf5: TextView
    private lateinit var tf1: TextView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(105, 174, 239)
        window.navigationBarColor = Color.rgb(124, 156, 218)
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(91, 184, 255),
                    Color.rgb(133, 190, 250),
                    Color.rgb(194, 151, 213)
                )
            )
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding(dp(16), dp(14), dp(16), dp(18))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(buildHeader())
                addGap(this, 16)
                addView(buildMarketCard())
                addGap(this, 18)
                section(this, "AI SIGNAL ENGINE", "ANALYSIS")
                addView(buildSignalCard())
                addGap(this, 18)
                section(this, "LIVE MARKET CHART", "NIFTY 50")
                addView(buildChartCard())
                addGap(this, 18)
                addView(buildFeatureGrid())
                addGap(this, 18)
                section(this, "PAPER TRADING", "MANUAL PRACTICE")
                addView(buildPaperTrading())
                addGap(this, 18)
                addView(text("MGrow   /   MARKET INTELLIGENCE", 10f, muted, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(12))
                })
            })
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildBottomBar())
        setContentView(root)
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
            tile("TECHNICAL ANALYTICS", "LIVE CALCULATION", buildIndicators()),
            tile("MARKET LEVELS", "AUTO DETECTION", buildLevels())
        )
        addPair(
            tile("MARKET INTELLIGENCE", "SMART FILTERS", buildInsights()),
            tile("SIGNAL ALERTS", "NOTIFICATIONS", buildAlerts())
        )
        return outer
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
        wrap.addView(pill("●  DEMO MODE", amber))
        return wrap
    }

    private fun buildMarketCard(): View {
        val box = card()
        box.background = rounded(Color.argb(118, 232, 247, 255), Color.argb(225, 248, 253, 255), 22)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("NIFTY 50", 13f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(helpButton("NIFTY 50", "NIFTY 50 हा भारतातील प्रमुख 50 कंपन्यांचा निर्देशांक आहे. हा कार्ड Live Index किंमत, बदल, Day High आणि Day Low दाखवेल. सध्या Feed Offline आहे.", "NIFTY 50 is an index of 50 major Indian companies. This card will show the live index value, change, day high, and day low. The feed is currently offline."))
        top.addView(pill("●  FEED OFFLINE", amber))
        box.addView(top)
        addGap(box, 10)
        box.addView(text("— — — — —", 30f, white, true))
        box.addView(text("Live index value will appear after data connection", 11f, muted))
        addGap(box, 13)
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metrics.addView(metric("CHANGE", "—", muted), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric("DAY HIGH", "—", white), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric("DAY LOW", "—", white), LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(metrics)
        return box
    }

    private fun buildSignalCard(): View {
        val box = card()
        box.background = rounded(Color.argb(126, 224, 235, 255), Color.argb(225, 255, 255, 255), 22)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("AI MARKET SETUP", 11f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(helpButton("BUY CE / BUY PE / WAIT", "BUY CE म्हणजे Call Option खरेदीचा विचार; BUY PE म्हणजे Put Option खरेदीचा विचार; WAIT म्हणजे सध्या स्पष्ट setup नाही. ही सूचना आहे, ऑर्डर नाही. स्वतः तपासूनच ट्रेड घ्या.", "BUY CE suggests considering a Call option; BUY PE suggests considering a Put option; WAIT means there is no clear setup. This is an analysis suggestion, not an order. Verify independently before trading."))
        top.addView(pill("WAITING FOR LIVE DATA", amber))
        box.addView(top)
        addGap(box, 8)
        box.addView(text("WAIT", 34f, amber, true))
        box.addView(text("Signal suggestions will activate only after real market data is connected and validated.", 12f, white))
        addGap(box, 12)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(metric("TREND", "—", muted), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(metric("SETUP SCORE", "— / 100", muted), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(metric("CONFIRMATION", "—", muted), LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row)
        addGap(box, 8)
        box.addView(text("Signal engine remains safely on standby until verified live data is connected.", 10f, amber))
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
        val spacer = View(this)
        tfRow.addView(spacer, LinearLayout.LayoutParams(dp(8), 1))
        tfRow.addView(tf1)
        tfRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tfRow.addView(helpButton("5 MIN / 1 MIN", "5 MIN चार्ट मुख्य trend पाहण्यासाठी वापर. 1 MIN चार्टवर entry आधी छोटा confirmation तपास. दोन्ही chart सध्या sample आहेत.", "Use the 5 MIN chart to assess the main trend. Check the 1 MIN chart for short-term confirmation before entry. Both charts are currently samples."))
        tfRow.addView(pill("SAMPLE CHART", amber))
        box.addView(tfRow)
        addGap(box, 10)
        chart = DemoChart()
        box.addView(chart, LinearLayout.LayoutParams(-1, dp(220)))
        addGap(box, 7)
        box.addView(text("SIMULATED PREVIEW  •  LIVE FEED NOT CONNECTED", 10f, amber))
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
            row.addView(text(item.second, 14f, item.third, true))
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
        row.addView(metric("RESISTANCE", "—", red), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(metric("SUPPORT", "—", green), LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row)
        addGap(box, 12)
        box.addView(text("CPR  —     Previous High / Low  —     Opening Range  —", 11f, muted))
        box.addView(text("Automatic levels will calculate from validated live candles.", 10f, amber))
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
        listOf("CE / PE setup alert", "VWAP breakout alert", "EMA crossover alert", "Trend reversal alert").forEach {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(it, 12f, white), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(pill("INACTIVE", muted))
            box.addView(row)
            addGap(box, 9)
        }
        box.addView(text("Alerts are inactive until live data and signal logic are connected.", 10f, amber))
        return box
    }


    private fun buildPaperTrading(): View {
        val box = card()
        box.addView(text("Practice trades only • No broker connection", 11f, amber, true))
        addGap(box, 8)

        val sideRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ceButton = Button(this).apply {
            text = "BUY CE"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(91, 65, 170))
            setOnClickListener { paperSide = TradeBias.BUY_CE }
        }
        val peButton = Button(this).apply {
            text = "BUY PE"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(150, 45, 120))
            setOnClickListener { paperSide = TradeBias.BUY_PE }
        }
        sideRow.addView(ceButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        sideRow.addView(peButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
        box.addView(sideRow)
        addGap(box, 8)

        fun field(hint: String, inputType: Int): EditText = EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            setTextColor(white)
            setHintTextColor(muted)
            textSize = 14f
            setSingleLine(true)
            background = rounded(Color.argb(125, 226, 242, 255), border, 12)
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
                if (entry == null || entry <= 0.0 || qty == null || qty <= 0) {
                    paperStatus.text = "Enter a valid premium and quantity."
                    return@setOnClickListener
                }
                paperEntry = entry
                paperQuantity = qty
                paperOpen = true
                paperStatus.text = "OPEN: ${paperSide.name} • Entry ₹${"%.2f".format(paperEntry)} • Qty $paperQuantity"
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
                if (!paperOpen) {
                    paperStatus.text = "There is no open practice trade."
                    return@setOnClickListener
                }
                if (exit == null || exit <= 0.0) {
                    paperStatus.text = "Enter a valid exit premium."
                    return@setOnClickListener
                }
                val pnl = (exit - paperEntry) * paperQuantity
                val record = "${paperSide.name} | Entry ₹${"%.2f".format(paperEntry)} | Exit ₹${"%.2f".format(exit)} | Qty $paperQuantity | P&L ₹${"%.2f".format(pnl)}"
                paperHistory.add(0, record)
                paperHistoryView.text = paperHistory.take(10).joinToString("\\n")
                paperStatus.text = "CLOSED • Gross P&L: ₹${"%.2f".format(pnl)} (before charges)"
                paperOpen = false
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
        box.addView(text("Note: Records are temporary and clear when the app process restarts. P&L is gross, excluding brokerage, taxes and slippage.", 10f, amber))
        return box
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = rounded(Color.argb(145, 219, 237, 255), Color.argb(210, 255, 255, 255), 20)
        }
        listOf("⌂\nDashboard", "▥\nChart", "✦\nAI Signals", "◷\nAlerts").forEachIndexed { i, label ->
            val color = if (i == 0) cyan else muted
            bar.addView(text(label, 10f, color, i == 0).apply {
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(3), dp(5), dp(3))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return bar
    }

    private fun setTf(value: String) {
        tf = value
        tf5.setTextColor(if (tf == "5 MIN") cyan else muted)
        tf1.setTextColor(if (tf == "1 MIN") cyan else muted)
        tf5.background = rounded(if (tf == "5 MIN") Color.argb(210, 50, 42, 83) else surface2, if (tf == "5 MIN") cyan else border, 12)
        tf1.background = rounded(if (tf == "1 MIN") Color.argb(210, 50, 42, 83) else surface2, if (tf == "1 MIN") cyan else border, 12)
        chart.invalidate()
    }

    private inner class DemoChart : View(this@MainActivity) {
        private val grid = Paint(3).apply { color = border; strokeWidth = dp(1).toFloat() }
        private val labelPaint = Paint(3).apply { color = muted; textSize = dp(9).toFloat() }
        private val wick = Paint(3).apply { strokeWidth = dp(2).toFloat() }
        private val body = Paint(3)
        private val line = Paint(3).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            c.drawColor(Color.argb(155, 116, 176, 225))
            val l = dp(34).toFloat()
            val r = width - dp(8).toFloat()
            val t = dp(18).toFloat()
            val b = height - dp(24).toFloat()
            for (i in 0..4) {
                val y = t + (b - t) * i / 4f
                c.drawLine(l, y, r, y, grid)
                c.drawText("—", dp(5).toFloat(), y - dp(3), labelPaint)
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