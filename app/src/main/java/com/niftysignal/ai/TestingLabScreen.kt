package com.niftysignal.ai

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Isolated offline test screen. It never reads or writes MainActivity live-feed state.
 * Synthetic candles are labelled DEMO and must never be treated as live signals.
 */
object TestingLabScreen {
    private val bg = Color.rgb(16, 24, 39)
    private val surface = Color.rgb(29, 43, 67)
    private val white = Color.WHITE
    private val muted = Color.rgb(195, 208, 226)
    private val cyan = Color.rgb(0, 213, 255)
    private val green = Color.rgb(0, 200, 150)
    private val amber = Color.rgb(255, 200, 87)

    fun create(context: Context): View {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(context).apply { addView(content) }
        content.addView(label(context, "TESTING LAB", 22f, white, true))
        content.addView(label(context, "OFFLINE • LIVE DASHBOARD UNCHANGED", 11f, amber, true))
        content.addView(space(context, 14))
        content.addView(card(context).apply {
            addView(label(context, "Sample Candle Test", 17f, white, true))
            addView(label(context, "५ मिनिटांचे आणि १ मिनिटाचे कृत्रिम candles वापरून EMA 9/21, RSI 14, VWAP, Support/Resistance आणि CE/PE/WAIT logic तपासा.", 13f, muted))
            addView(label(context, "This test uses generated demo candles only. It does not connect to the broker feed, change the live dashboard, or place orders.", 12f, muted))
        })
        content.addView(space(context, 12))
        val result = card(context)
        result.addView(label(context, "TEST RESULT", 15f, cyan, true))
        val resultText = label(context, "Test अजून चालवलेली नाही. Run Sample Test दाबा.", 13f, muted)
        result.addView(resultText)
        content.addView(result)
        content.addView(space(context, 12))
        val run = Button(context).apply {
            text = "RUN SAMPLE TEST"
            setTextColor(Color.BLACK)
            setBackgroundColor(cyan)
            setOnClickListener {
                val five = sampleCandles(30, 300_000L)
                val one = sampleCandles(5, 60_000L)
                val snapshot = TechnicalAnalysis.analyze(five, one)
                resultText.text = buildString {
                    append("DATA SOURCE: DEMO ONLY\n")
                    append("5m candles: ${five.size} • 1m candles: ${one.size}\n")
                    append("EMA 9: ${fmt(snapshot.ema9)}\n")
                    append("EMA 21: ${fmt(snapshot.ema21)}\n")
                    append("RSI 14: ${fmt(snapshot.rsi14)}\n")
                    append("VWAP: ${fmt(snapshot.vwap)}\n")
                    append("Support: ${fmt(snapshot.support)}\n")
                    append("Resistance: ${fmt(snapshot.resistance)}\n")
                    append("AI RULE OUTPUT: ${snapshot.bias.name}\n")
                    append(snapshot.reason)
                    append("\n\nहे फक्त logic test आहे; live-market accuracy नाही.")
                }
                resultText.setTextColor(white)
            }
        }
        content.addView(run)
        content.addView(space(context, 10))
        content.addView(label(context, "Excel Option Chain Test", 17f, white, true))
        content.addView(label(context, "Excel file upload आणि OI / Change in OI / PCR / strike-wise CE-PE analysis पुढील टप्प्यात जोडू. आधी तुझी workbook पाहून columns आणि timestamps पडताळू; त्यानुसार parser व tests बनवू.", 13f, muted))
        content.addView(space(context, 12))
        content.addView(label(context, "SAFETY", 14f, amber, true))
        content.addView(label(context, "Demo results Reports किंवा Live AI Signals मध्ये save होत नाहीत. Live feed state आणि existing trade records या screen मधून बदलत नाहीत.", 12f, muted))
        return scroll
    }

    private fun sampleCandles(count: Int, interval: Long): List<MarketCandle> {
        val now = System.currentTimeMillis()
        return (0 until count).map { i ->
            val close = 24000.0 + i * 4.0
            val open = close - 1.5
            val high = close + if (i == count - 1) 2.0 else 1.0
            val low = open - 1.0
            MarketCandle(now - (count - i) * interval, open, high, low, close, 1000.0 + i * 10.0)
        }
    }

    private fun fmt(value: Double?): String =
        value?.takeIf { it.isFinite() }?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—"

    private fun label(c: Context, value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(c).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 5, 0, 5)
        }

    private fun space(c: Context, height: Int) = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(1, height)
    }

    private fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16, 14, 16, 14)
        background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = 18f
            setStroke(1, Color.rgb(75, 101, 139))
        }
    }
}
