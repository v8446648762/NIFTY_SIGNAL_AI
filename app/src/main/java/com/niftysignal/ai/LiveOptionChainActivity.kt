package com.niftysignal.ai

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Read-only live NIFTY option chain and transparent rule-based analysis. */
class LiveOptionChainActivity : Activity() {
    companion object {
        private const val WORKER = "https://mgrow-upstox-readonly.vikaskalburgi130.workers.dev"
        private const val REFRESH_MS = 20_000L
    }
    private data class Side(
        val ltp: Double?, val volume: Double?, val oi: Double?, val prevOi: Double?,
        val oiChange: Double?, val iv: Double?, val delta: Double?, val gamma: Double?,
        val theta: Double?, val vega: Double?, val bid: Double?, val ask: Double?
    )
    private data class StrikeRow(val strike: Double, val call: Side, val put: Side)
    private data class Snapshot(
        val expiry: String, val receivedAt: String, val spot: Double?, val pcr: Double?,
        val totalCallOi: Double, val totalPutOi: Double, val rows: List<StrikeRow>
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val requestInFlight = AtomicBoolean(false)
    private var expiries: List<String> = emptyList()
    private var selectedExpiry: String? = null
    private lateinit var statusView: TextView
    private lateinit var summaryView: TextView
    private lateinit var analyzerView: TextView
    private lateinit var rowsContainer: LinearLayout
    private lateinit var expirySpinner: Spinner
    private lateinit var countView: TextView
    private val navy = Color.rgb(9, 16, 29)
    private val panel = Color.rgb(20, 31, 49)
    private val panel2 = Color.rgb(27, 42, 64)
    private val cyan = Color.rgb(0, 213, 255)
    private val green = Color.rgb(0, 220, 145)
    private val red = Color.rgb(255, 91, 116)
    private val white = Color.WHITE
    private val muted = Color.rgb(185, 201, 221)

    private val refreshRunnable = object : Runnable {
        override fun run() {
            selectedExpiry?.let(::loadChain)
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        loadExpiries()
    }
    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, REFRESH_MS)
    }
    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
            setPadding(dp(14), dp(14), dp(14), dp(8))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "NIFTY OPTION CHAIN"; textSize = 19f; setTextColor(white); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply { text = "BACK"; setTextColor(cyan); setOnClickListener { finish() } })
        root.addView(header)
        statusView = label("Connecting to secure Upstox option-chain feed…", 12f, cyan)
        root.addView(statusView)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        expirySpinner = Spinner(this)
        controls.addView(expirySpinner, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(Button(this).apply {
            text = "REFRESH"; setOnClickListener { selectedExpiry?.let(::loadChain) ?: loadExpiries() }
        })
        root.addView(controls)
        summaryView = label("Spot: --     PCR: --\nCE OI: --     PE OI: --", 13f, white).apply {
            background = rounded(panel, cyan); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(summaryView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        analyzerView = label("SMART AI OPTION CHAIN ANALYSER\nWaiting for verified live option-chain data.", 13f, white).apply {
            background = rounded(panel2, green); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(analyzerView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        countView = label("All available strikes", 12f, cyan)
        root.addView(countView)
        val scroll = ScrollView(this).apply { clipToPadding = false }
        rowsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rowsContainer)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun loadExpiries() {
        if (!requestInFlight.compareAndSet(false, true)) return
        status("Loading available NIFTY expiries…", cyan)
        executor.execute {
            try {
                val root = JSONObject(httpGet("$WORKER/v1/nifty/option-expiries"))
                require(root.optString("provider") == "upstox") { "Unexpected data provider" }
                val array = root.optJSONArray("expiries") ?: JSONArray()
                val found = (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } }
                if (found.isEmpty()) throw IllegalStateException("No NIFTY option expiry is available from Upstox.")
                runOnUiThread {
                    expiries = found
                    expirySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, found).apply {
                        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                    expirySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                            val expiry = expiries.getOrNull(position) ?: return
                            if (expiry != selectedExpiry) { selectedExpiry = expiry; loadChain(expiry) }
                        }
                    }
                    val initial = found.indexOf(selectedExpiry).takeIf { it >= 0 } ?: 0
                    expirySpinner.setSelection(initial)
                    selectedExpiry = found[initial]
                    loadChain(found[initial])
                }
            } catch (error: Exception) {
                runOnUiThread { clearData(); status("OPTION CHAIN ERROR • ${error.message ?: "Unable to load expiries"}", red) }
            } finally { requestInFlight.set(false) }
        }
    }

    private fun loadChain(expiry: String) {
        if (!requestInFlight.compareAndSet(false, true)) return
        status("Refreshing live option chain • $expiry", cyan)
        executor.execute {
            try {
                val encoded = java.net.URLEncoder.encode(expiry, "UTF-8")
                val root = JSONObject(httpGet("$WORKER/v1/nifty/option-chain?expiry_date=$encoded"))
                require(root.optString("provider") == "upstox") { "Unexpected data provider" }
                require(root.optString("expiry") == expiry) { "Expiry mismatch in server response" }
                val array = root.optJSONArray("rows") ?: throw IllegalStateException("Option-chain rows are missing")
                val rows = (0 until array.length()).mapNotNull { index ->
                    val row = array.optJSONObject(index) ?: return@mapNotNull null
                    val strike = row.optDouble("strikePrice", Double.NaN)
                    if (!strike.isFinite()) return@mapNotNull null
                    StrikeRow(strike, parseSide(row.optJSONObject("call")), parseSide(row.optJSONObject("put")))
                }.sortedBy { it.strike }
                if (rows.isEmpty()) throw IllegalStateException("Upstox returned no valid strikes")
                val snapshot = Snapshot(expiry, root.optString("receivedAt"), nullableNumber(root, "underlyingSpotPrice"),
                    nullableNumber(root, "pcr"), root.optDouble("totalCallOi", 0.0), root.optDouble("totalPutOi", 0.0), rows)
                runOnUiThread { render(snapshot) }
            } catch (error: Exception) {
                runOnUiThread { clearData(); status("OPTION CHAIN ERROR • ${error.message ?: "Refresh failed"}", red) }
            } finally { requestInFlight.set(false) }
        }
    }

    private fun parseSide(obj: JSONObject?): Side {
        val item = obj ?: JSONObject()
        return Side(nullableNumber(item, "ltp"), nullableNumber(item, "volume"), nullableNumber(item, "oi"),
            nullableNumber(item, "prevOi"), nullableNumber(item, "oiChange"), nullableNumber(item, "iv"),
            nullableNumber(item, "delta"), nullableNumber(item, "gamma"), nullableNumber(item, "theta"),
            nullableNumber(item, "vega"), nullableNumber(item, "bidPrice"), nullableNumber(item, "askPrice"))
    }

    private fun render(data: Snapshot) {
        status("● LIVE OPTION CHAIN • Updated ${parseTime(data.receivedAt)} • Auto refresh 20s", green)
        summaryView.text = "NIFTY SPOT: ${fmt(data.spot)}     PCR: ${fmt(data.pcr, 3)}\n" +
            "TOTAL CE OI: ${fmt(data.totalCallOi, 0)}     TOTAL PE OI: ${fmt(data.totalPutOi, 0)}"
        countView.text = "ALL AVAILABLE STRIKES: ${data.rows.size}   •   EXPIRY: ${data.expiry}"
        analyzerView.text = analyze(data)
        rowsContainer.removeAllViews()
        data.rows.forEach { row ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(11), dp(10), dp(11), dp(10))
                background = rounded(panel, Color.rgb(55, 82, 112))
            }
            card.addView(label("STRIKE  ${fmt(row.strike, 0)}", 15f, cyan).apply { typeface = Typeface.DEFAULT_BOLD })
            addSide(card, "CALL (CE)", row.call, green)
            addSide(card, "PUT (PE)", row.put, red)
            rowsContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5); bottomMargin = dp(5) })
        }
    }

    private fun addSide(card: LinearLayout, name: String, side: Side, color: Int) {
        card.addView(label("$name  •  LTP ${fmt(side.ltp)}  •  OI ${fmt(side.oi, 0)}  •  ΔOI ${signed(side.oiChange)}", 12f, color))
        card.addView(label("Volume ${fmt(side.volume, 0)}  •  IV ${fmt(side.iv)}  •  Bid ${fmt(side.bid)} / Ask ${fmt(side.ask)}", 11f, white))
        card.addView(label("Delta ${fmt(side.delta, 3)}  Gamma ${fmt(side.gamma, 4)}  Theta ${fmt(side.theta, 3)}  Vega ${fmt(side.vega, 3)}", 10f, muted))
    }

    private fun analyze(data: Snapshot): String {
        val pcr = data.pcr
        val callBuild = data.rows.sumOf { max(0.0, it.call.oiChange ?: 0.0) }
        val putBuild = data.rows.sumOf { max(0.0, it.put.oiChange ?: 0.0) }
        val maxCall = data.rows.filter { it.call.oi != null }.maxByOrNull { it.call.oi ?: 0.0 }?.strike
        val maxPut = data.rows.filter { it.put.oi != null }.maxByOrNull { it.put.oi ?: 0.0 }?.strike
        val pcrScore = when { pcr == null -> 0; pcr >= 1.15 -> 1; pcr <= 0.85 -> -1; else -> 0 }
        val buildScore = when {
            callBuild == 0.0 && putBuild == 0.0 -> 0
            putBuild > callBuild * 1.10 -> 1
            callBuild > putBuild * 1.10 -> -1
            else -> 0
        }
        val score = 50 + pcrScore * 25 + buildScore * 25
        val bias = when { score >= 75 -> "BULLISH"; score <= 25 -> "BEARISH"; else -> "NEUTRAL / WAIT" }
        val pcrNote = when {
            pcr == null -> "PCR unavailable; no PCR bias assigned."
            pcr >= 1.15 -> "PCR above 1.15: put OI is relatively stronger."
            pcr <= 0.85 -> "PCR below 0.85: call OI is relatively stronger."
            else -> "PCR is in the neutral band (0.85–1.15)."
        }
        val oiNote = when {
            callBuild == 0.0 && putBuild == 0.0 -> "No positive OI-change data available for buildup comparison."
            putBuild > callBuild * 1.10 -> "Positive PE OI addition exceeds CE addition."
            callBuild > putBuild * 1.10 -> "Positive CE OI addition exceeds PE addition."
            else -> "CE and PE positive OI additions are broadly balanced."
        }
        return "SMART AI OPTION CHAIN ANALYSER\n$bias  •  Rule score $score/100 (not a probability)\n" +
            "Potential support (highest PE OI): ${fmt(maxPut, 0)}\nPotential resistance (highest CE OI): ${fmt(maxCall, 0)}\n" +
            "Positive CE OI addition: ${fmt(callBuild, 0)}  •  PE addition: ${fmt(putBuild, 0)}\n" +
            "• $pcrNote\n• $oiNote\n\nOI levels are potential reaction zones, not guaranteed support/resistance. " +
            "This is transparent rule-based analysis, not a validated predictive model or trade instruction."
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 8_000; readTimeout = 10_000
            setRequestProperty("Accept", "application/json"); setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try { JSONObject(body).optString("error") } catch (_: Exception) { "" }
                throw IllegalStateException("HTTP $code ${detail.ifBlank { "from secure data server" }}")
            }
            return body
        } finally { connection.disconnect() }
    }

    private fun clearData() {
        summaryView.text = "Spot: --     PCR: --\nCE OI: --     PE OI: --"
        analyzerView.text = "SMART AI OPTION CHAIN ANALYSER\nAnalysis paused: verified live data is unavailable."
        countView.text = "No verified option-chain rows"
        rowsContainer.removeAllViews()
    }
    private fun status(message: String, color: Int) {
        if (::statusView.isInitialized) { statusView.text = message; statusView.setTextColor(color) }
    }
    private fun nullableNumber(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.optDouble(key, Double.NaN).takeIf { it.isFinite() }
    }
    private fun fmt(value: Double?, decimals: Int = 2): String =
        value?.let { String.format(Locale.US, "%,.\${decimals}f", it) } ?: "--"
    private fun signed(value: Double?): String = value?.let { (if (it >= 0) "+" else "") + fmt(it, 0) } ?: "--"
    private fun parseTime(value: String): String = try {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val date = parser.parse(value) ?: return value
        SimpleDateFormat("HH:mm:ss", Locale.US).format(date)
    } catch (_: Exception) { value }
    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(4))
    }
    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(14).toFloat(); setStroke(dp(1), stroke)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
