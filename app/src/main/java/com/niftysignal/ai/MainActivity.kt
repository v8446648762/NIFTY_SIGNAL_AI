package com.niftysignal.ai

import android.app.Activity
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private val bg = Color.rgb(5, 7, 11)
    private val surface = Color.rgb(13, 18, 25)
    private val surface2 = Color.rgb(19, 27, 37)
    private val border = Color.rgb(39, 53, 64)
    private val muted = Color.rgb(137, 157, 171)
    private val white = Color.rgb(239, 247, 249)
    private val green = Color.rgb(183, 255, 74)
    private val cyan = Color.rgb(48, 220, 255)
    private val red = Color.rgb(255, 93, 112)
    private val amber = Color.rgb(183, 255, 74)
    private var tf = "5 MIN"
    private lateinit var chart: DemoChart
    private lateinit var tf5: TextView
    private lateinit var tf1: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, stroke: Int = border, radius: Int = 18): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
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
        background = rounded(surface)
    }

    private fun addGap(root: LinearLayout, h: Int = 12) {
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))
    }

    private fun section(root: LinearLayout, title: String, subtitle: String? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(text(title, 16f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (subtitle != null) row.addView(text(subtitle, 10f, muted, true))
        root.addView(row)
        addGap(root, 8)
    }

    private fun pill(label: String, color: Int): TextView = text(label, 10f, color, true).apply {
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = rounded(Color.rgb(24, 43, 60), color, 30)
    }

    private fun metric(label: String, value: String, color: Int = white): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(label, 10f, muted, true))
            addView(text(value, 17f, color, true))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
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
                section(this, "TECHNICAL ANALYTICS", "LIVE CALCULATION PENDING")
                addView(buildIndicators())
                addGap(this, 18)
                section(this, "MARKET LEVELS", "AUTO DETECTION")
                addView(buildLevels())
                addGap(this, 18)
                section(this, "MARKET INTELLIGENCE", "SMART FILTERS")
                addView(buildInsights())
                addGap(this, 18)
                section(this, "SIGNAL ALERTS", "NOTIFICATIONS")
                addView(buildAlerts())
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
        box.background = rounded(Color.rgb(10, 20, 24), Color.rgb(48, 220, 255), 20)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("NIFTY 50", 13f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
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
        box.background = rounded(Color.rgb(11, 23, 19), Color.rgb(183, 255, 74), 20)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("AI MARKET SETUP", 11f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
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
            background = rounded(Color.rgb(15, 39, 43), cyan, 12)
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

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setBackgroundColor(Color.rgb(8, 12, 17))
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
        tf5.background = rounded(if (tf == "5 MIN") Color.rgb(18, 48, 66) else surface2, if (tf == "5 MIN") cyan else border, 12)
        tf1.background = rounded(if (tf == "1 MIN") Color.rgb(18, 48, 66) else surface2, if (tf == "1 MIN") cyan else border, 12)
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
            c.drawColor(surface)
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