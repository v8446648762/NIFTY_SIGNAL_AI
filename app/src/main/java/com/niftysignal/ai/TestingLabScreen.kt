package com.niftysignal.ai

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    private val amber = Color.rgb(255, 200, 87)
    @Volatile private var optionChainResultView: TextView? = null
    @Volatile private var latestOptionChainAnalysis: OptionChainAnalysis? = null

    fun setOptionChainAnalysis(analysis: OptionChainAnalysis, report: String) {
        latestOptionChainAnalysis = analysis
        displayOptionChainAnalysis(report)
    }

    fun displayOptionChainAnalysis(report: String) {
        optionChainResultView?.post {
            optionChainResultView?.text = report
            optionChainResultView?.setTextColor(white)
        }
    }

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
        result.addView(label(context, "SAMPLE TEST RESULT", 15f, cyan, true))
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
        content.addView(space(context, 16))

        content.addView(label(context, "Excel Option Chain Test", 18f, white, true))
        content.addView(label(context, "तुझी NIFTY Option Chain .xlsx फाइल इथून निवड. फाइल फोनवरच वाचली जाईल; कोणत्याही server वर upload होणार नाही.", 13f, muted))
        content.addView(space(context, 8))
        val importButton = Button(context).apply {
            text = "IMPORT OPTION CHAIN XLSX"
            setTextColor(Color.BLACK)
            setBackgroundColor(cyan)
            setOnClickListener {
                context.startActivity(Intent(context, OptionChainImportActivity::class.java))
            }
        }
        content.addView(importButton)
        content.addView(space(context, 10))
        val analysisCard = card(context)
        analysisCard.addView(label(context, "OPTION CHAIN ANALYSIS RESULT", 14f, cyan, true))
        val analysisText = label(context, "अजून Excel फाइल import केलेली नाही.", 12f, muted)
        optionChainResultView = analysisText
        analysisCard.addView(analysisText)

        content.addView(analysisCard)

        // Advanced AI v1 is intentionally exercised only inside this offline Testing Lab.
        content.addView(space(context, 14))
        content.addView(label(context, "ADVANCED AI ENGINE • OFFLINE TEST", 18f, cyan, true))
        content.addView(label(context, "हे मॉड्यूल AdvancedAiEngine ला Testing Lab मधून जोडते. खालील candle data DEMO आहे; ते live market data नाही. त्यामुळे निकाल learning / integration test म्हणूनच पाहा.", 12f, muted))
        content.addView(label(context, "Verified NIFTY spot price (manual)", 12f, white, true))
        val spotInput = EditText(context).apply {
            hint = "उदा. 25250.50"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(white); setHintTextColor(muted); textSize = 14f
            setPadding(12, 8, 12, 8)
            setBackgroundColor(surface)
        }
        content.addView(spotInput, LinearLayout.LayoutParams(-1, 52))
        content.addView(space(context, 6))
        content.addView(label(context, "Option-chain snapshot age in minutes (तुझ्या Excel मधील actual वेळेनुसार)", 12f, white, true))
        val ageInput = EditText(context).apply {
            hint = "उदा. 2"
            setText("0")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(white); setHintTextColor(muted); textSize = 14f
            setPadding(12, 8, 12, 8)
            setBackgroundColor(surface)
        }
        content.addView(ageInput, LinearLayout.LayoutParams(-1, 52))
        content.addView(space(context, 8))
        val aiResult = label(context, "AI test अजून चालवलेली नाही. आधी Excel Option Chain import कर.", 12f, muted)
        val aiCard = card(context)
        aiCard.addView(label(context, "ADVANCED AI RESULT", 14f, cyan, true))
        aiCard.addView(aiResult)
        content.addView(aiCard)
        content.addView(space(context, 8))
        val runAdvanced = Button(context).apply {
            text = "RUN ADVANCED AI TEST"
            setTextColor(Color.BLACK); setBackgroundColor(cyan)
            setOnClickListener {
                val chain = latestOptionChainAnalysis
                val spot = spotInput.text.toString().toDoubleOrNull()
                val ageMinutes = ageInput.text.toString().toLongOrNull()
                if (chain == null) {
                    aiResult.text = "WAIT • आधी NIFTY Option Chain XLSX import कर."
                    return@setOnClickListener
                }
                if (spot == null || !spot.isFinite() || spot <= 0.0 || ageMinutes == null || ageMinutes < 0L) {
                    aiResult.text = "WAIT • योग्य NIFTY spot price आणि snapshot age भरा."
                    return@setOnClickListener
                }
                val now = System.currentTimeMillis()
                val capturedAt = now - ageMinutes * 60_000L
                val optionSnapshot = OptionChainSnapshot(chain, capturedAt, spot)
                val five = sampleCandles(30, 300_000L)
                val one = sampleCandles(5, 60_000L)
                val result = AdvancedAiEngine.evaluate(five, one, optionSnapshot, now)
                aiResult.text = buildString {
                    append("TEST MODE: DEMO CANDLES + IMPORTED OPTION CHAIN\n")
                    append("SIGNAL: ${result.bias.name} • ${result.confidenceLabel}\n")
                    append("TREND: ${result.trend}\n")
                    append("Bullish score: ${result.bullishScore} / 100\n")
                    append("Bearish score: ${result.bearishScore} / 100\n")
                    append("PCR: ${result.optionChainPcr?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "N/A"}\n")
                    append("Support: ${result.support?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—"}\n")
                    append("Resistance: ${result.resistance?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—"}\n\n")
                    append("REASONS\n"); result.reasons.forEach { append("• $it\n") }
                    if (result.warnings.isNotEmpty()) { append("\nWARNINGS\n"); result.warnings.forEach { append("• $it\n") } }
                    append("\nमहत्त्वाचे: हा live NIFTY signal नाही. 5m/1m candles कृत्रिम DEMO आहेत. वास्तविक signal साठी timestamped वास्तविक candles आवश्यक आहेत.")
                }
                aiResult.setTextColor(white)
            }
        }
        content.addView(runAdvanced)

        // Manual completed-trade metrics are displayed here, not on the live Reports page.
        content.addView(space(context, 16))
        content.addView(label(context, "HISTORICAL / PAPER BACKTEST METRICS", 16f, white, true))
        content.addView(label(context, "इथे manually completed practice trades टाकून win rate, net P&L, profit factor आणि drawdown तपासता येईल. हे live किंवा imported historical backtest नाही.", 12f, muted))
        val sidePicker = android.widget.Spinner(context).apply {
            adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("BUY_CE", "BUY_PE"))
        }
        content.addView(sidePicker, LinearLayout.LayoutParams(-1, 48))
        fun numberField(hintText: String, decimal: Boolean): EditText = EditText(context).apply {
            hint = hintText
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or if (decimal) android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED else 0
            setTextColor(white); setHintTextColor(muted); textSize = 13f
            setPadding(12, 6, 12, 6); setBackgroundColor(surface)
        }
        val entryInput = numberField("Entry premium", true)
        val exitInput = numberField("Exit premium", true)
        val qtyInput = numberField("Quantity (units)", false)
        val chargesInput = numberField("Brokerage + taxes + slippage (₹)", true).apply { setText("0") }
        content.addView(entryInput, LinearLayout.LayoutParams(-1, 48))
        content.addView(exitInput, LinearLayout.LayoutParams(-1, 48))
        content.addView(qtyInput, LinearLayout.LayoutParams(-1, 48))
        content.addView(chargesInput, LinearLayout.LayoutParams(-1, 48))
        val backtestTrades = mutableListOf<BacktestTrade>()
        val metricsText = label(context, "No completed test trades added.", 12f, muted)
        val metricsCard = card(context)
        metricsCard.addView(label(context, "BACKTEST SUMMARY", 14f, cyan, true))
        metricsCard.addView(metricsText)
        content.addView(metricsCard)
        val addTrade = Button(context).apply {
            text = "ADD CLOSED TEST TRADE & CALCULATE"
            setTextColor(Color.BLACK); setBackgroundColor(cyan)
            setOnClickListener {
                val entry = entryInput.text.toString().toDoubleOrNull()
                val exit = exitInput.text.toString().toDoubleOrNull()
                val qty = qtyInput.text.toString().toIntOrNull()
                val charges = chargesInput.text.toString().toDoubleOrNull()
                if (entry == null || exit == null || qty == null || charges == null ||
                    !entry.isFinite() || !exit.isFinite() || !charges.isFinite() ||
                    entry <= 0.0 || exit <= 0.0 || qty <= 0 || charges < 0.0) {
                    metricsText.text = "योग्य Entry, Exit, Quantity आणि charges भरा."
                    return@setOnClickListener
                }
                val closedAt = System.currentTimeMillis()
                backtestTrades += BacktestTrade(
                    side = if (sidePicker.selectedItemPosition == 0) TradeBias.BUY_CE else TradeBias.BUY_PE,
                    entryPrice = entry, exitPrice = exit, quantity = qty,
                    entryTimeMillis = closedAt - 1_000L, exitTimeMillis = closedAt,
                    feesAndSlippage = charges
                )
                val report = HistoricalBacktestMetrics.calculate(backtestTrades)
                metricsText.text = buildString {
                    append("Trades: ${report.tradeCount} • Wins: ${report.winningTrades} • Losses: ${report.losingTrades}\n")
                    append("Win rate: ${String.format(java.util.Locale.US, "%.1f", report.winRatePercent)}%\n")
                    append("Net P&L: ₹${String.format(java.util.Locale.US, "%.2f", report.netPnl)}\n")
                    append("Profit factor: ${report.profitFactor?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "Undefined"}\n")
                    append("Max drawdown: ₹${String.format(java.util.Locale.US, "%.2f", report.maximumDrawdown)}\n")
                    append("Average P&L / trade: ₹${String.format(java.util.Locale.US, "%.2f", report.averagePnlPerTrade)}\n")
                    report.warnings.forEach { append("• $it\n") }
                }
                metricsText.setTextColor(white)
                entryInput.text.clear(); exitInput.text.clear(); qtyInput.text.clear()
            }
        }
        content.addView(addTrade)
        content.addView(label(context, "TESTING LAB SAFETY", 14f, amber, true))
        content.addView(label(context, "हा विभाग offline आहे. DEMO candles वरून मिळणारा CALL/PUT/WAIT हा वास्तविक बाजाराचा signal नाही. Excel मधील timestamp/spot स्वतः पडताळा. कोणतीही broker order, live dashboard update किंवा report upload होत नाही. Rule score हा calibrated probability किंवा सिद्ध win rate नाही.", 12f, muted))

        content.addView(space(context, 14))
        content.addView(label(context, "TESTING LAB SAFETY", 14f, amber, true))
        content.addView(label(context, "Excel analysis offline आणि या विभागापुरतेच आहे. डेटा Live Dashboard, Live AI Signals किंवा Reports मध्ये पाठवला जात नाही. Snapshot मधील OI levels हे हमखास support/resistance किंवा trade signal नाहीत. Accuracy मोजण्यासाठी अनेक timestamped snapshots आणि पुढील NIFTY price data आवश्यक आहे.", 12f, muted))
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
