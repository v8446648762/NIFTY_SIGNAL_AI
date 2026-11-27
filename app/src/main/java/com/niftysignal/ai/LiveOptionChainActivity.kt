package com.niftysignal.ai

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Isolated read-only NIFTY option-chain screen.
 * It uses a dedicated Worker route and never changes the main quote/candle feed.
 */
class LiveOptionChainActivity : Activity() {
    private data class StrikeRow(
        val strike: Double,
        val callOi: Double?,
        val callOiChange: Double?,
        val callLtp: Double?,
        val putLtp: Double?,
        val putOiChange: Double?,
        val putOi: Double?
    )

    private data class ChainSnapshot(
        val expiry: String,
        val spot: Double,
        val receivedAtMillis: Long,
        val rows: List<StrikeRow>
    )

    companion object {
        private const val ENDPOINT =
            "https://mgrow-upstox-readonly.vikaskalburgi130.workers.dev/v1/nifty/option-chain"
        private const val POLL_SECONDS = 30L
        private const val MAX_SNAPSHOT_AGE_MS = 60_000L
        private val BG = Color.rgb(5, 14, 13)
        private val SURFACE = Color.rgb(13, 29, 26)
        private val WHITE = Color.WHITE
        private val MUTED = Color.rgb(169, 194, 185)
        private val CYAN = Color.rgb(0, 213, 255)
        private val GREEN = Color.rgb(0, 235, 145)
        private val AMBER = Color.rgb(255, 190, 70)
        private val RED = Color.rgb(255, 105, 105)
    }

    private lateinit var statusView: TextView
    private lateinit var summaryView: TextView
    private lateinit var rowsContainer: LinearLayout
    private lateinit var expiryPicker: Spinner
    private var selectedExpiry = "current_week"
    private var latestSnapshot: ChainSnapshot? = null
    private var executor: ScheduledExecutorService? = null
    @Volatile private var requestInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        loadLastAvailableSnapshot()
        executor = Executors.newSingleThreadScheduledExecutor()
        executor?.scheduleWithFixedDelay({ fetchSnapshot() }, 0L, POLL_SECONDS, TimeUnit.SECONDS)
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(14))
            setBackgroundColor(BG)
        }
        page.addView(label("NIFTY LIVE OPTION CHAIN", 21f, WHITE, true))
        page.addView(label("UPSTOX • READ-ONLY SNAPSHOT", 10f, CYAN, true))
        page.addView(space(10))
        statusView = label("CONNECTING TO OPTION CHAIN…", 12f, AMBER, true)
        page.addView(statusView)
        page.addView(space(10))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        expiryPicker = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@LiveOptionChainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Current Week", "Next Week", "Far Week", "Current Month", "Next Month", "Far Month")
            )
            setSelection(0)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val values = listOf("current_week", "next_week", "far_week", "current_month", "next_month", "far_month")
                    val next = values[position]
                    if (next != selectedExpiry) {
                        selectedExpiry = next
                        executor?.execute { fetchSnapshot() }
                    }
                }
            }
        }
        controls.addView(expiryPicker, LinearLayout.LayoutParams(0, dp(48), 1f))
        val refresh = Button(this).apply {
            text = "REFRESH"
            setTextColor(Color.BLACK)
            setBackgroundColor(CYAN)
            setOnClickListener { executor?.execute { fetchSnapshot() } }
        }
        controls.addView(refresh, LinearLayout.LayoutParams(dp(112), dp(48)))
        page.addView(controls)
        page.addView(space(10))

        val summaryCard = card()
        summaryCard.addView(label("OPTION CHAIN INTELLIGENCE", 13f, CYAN, true))
        summaryView = label("Waiting for a validated NIFTY option-chain snapshot.", 12f, MUTED)
        summaryCard.addView(summaryView)
        page.addView(summaryCard)
        page.addView(space(10))
        page.addView(label("STRIKE-WISE OPEN INTEREST", 13f, WHITE, true))
        page.addView(label("CE = Call • PE = Put • ΔOI = current OI minus previous OI", 10f, MUTED))
        page.addView(space(6))

        val horizontal = HorizontalScrollView(this).apply {
            isFillViewport = false
            isHorizontalScrollBarEnabled = true
        }
        rowsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        horizontal.addView(rowsContainer)
        val tableScroll = ScrollView(this).apply { addView(horizontal) }
        page.addView(tableScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(space(8))
        page.addView(label(
            "DATA NOTE: Upstox REST returns an option-chain snapshot, not a tick-by-tick stream. The API response does not expose an exchange timestamp for every strike. “Recent” means the Worker fetched a response recently; it does not prove every quote is exchange-fresh. OI concentrations are observations, not guaranteed support/resistance or trade signals.",
            10f, MUTED
        ))
        page.addView(space(6))
        page.addView(label("No orders • No trading permissions • No broker order APIs", 10f, GREEN, true))
        setContentView(page)
    }

    private fun fetchSnapshot() {
        if (requestInFlight) return
        requestInFlight = true
        var connection: HttpURLConnection? = null
        try {
            val expiry = URLEncoder.encode(selectedExpiry, "UTF-8")
            connection = (URL("$ENDPOINT?expiry_date=$expiry").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(body) }.getOrNull()
                val upstreamStatus = detail?.optInt("upstreamStatus", code) ?: code
                val upstreamCode = detail?.optString("upstreamCode").orEmpty()
                val upstreamMessage = detail?.optString("upstreamMessage").orEmpty()
                val reason = when {
                    upstreamCode.isNotBlank() && upstreamMessage.isNotBlank() -> "$upstreamCode: $upstreamMessage"
                    upstreamCode.isNotBlank() -> upstreamCode
                    upstreamMessage.isNotBlank() -> upstreamMessage
                    else -> "Upstream HTTP $upstreamStatus"
                }
                throw IllegalStateException("Option-chain unavailable • $reason")
            }
            val root = JSONObject(body)
            if (!root.optString("provider").equals("upstox", true)) {
                throw IllegalStateException("Unexpected option-chain data provider")
            }
            val receivedAt = root.optLong("receivedAtEpochMs", 0L)
            val now = System.currentTimeMillis()
            if (receivedAt <= 0L || receivedAt > now + 5_000L || now - receivedAt > MAX_SNAPSHOT_AGE_MS) {
                throw IllegalStateException("Option-chain response timestamp is stale or invalid")
            }
            val spot = root.optDouble("spotPrice", Double.NaN)
            if (!spot.isFinite() || spot <= 0.0) throw IllegalStateException("NIFTY spot price is missing")
            val array = root.optJSONArray("strikes") ?: throw IllegalStateException("Option-chain strikes are missing")
            val rows = parseRows(array)
            if (rows.isEmpty()) throw IllegalStateException("No valid option-chain strike rows received")
            val snapshot = ChainSnapshot(root.optString("expiryDate"), spot, receivedAt, rows)
            latestSnapshot = snapshot
            getSharedPreferences("mgrow_option_chain", MODE_PRIVATE).edit()
                .putString("last_snapshot", root.toString())
                .putString("last_expiry", selectedExpiry)
                .apply()
            publishToCentralEngine(snapshot)
            runOnUiThread { renderSnapshot(snapshot, cached = false) }
        } catch (error: Exception) {
            val message = error.message ?: "Unable to refresh option chain"
            runOnUiThread {
                val saved = latestSnapshot
                if (saved != null) {
                    // Keep the last validated rows visible when refresh fails.
                    renderSnapshot(saved, cached = true)
                    statusView.text = "LAST AVAILABLE • NOT LIVE • ${formatSnapshotTime(saved.receivedAtMillis)}\\nRefresh failed: $message"
                    statusView.setTextColor(AMBER)
                } else {
                    statusView.text = "NO SAVED OPTION-CHAIN DATA • NOT LIVE\\nRefresh failed: $message"
                    statusView.setTextColor(RED)
                }
            }
        } finally {
            connection?.disconnect()
            requestInFlight = false
        }
    }

    private fun loadLastAvailableSnapshot() {
        val prefs = getSharedPreferences("mgrow_option_chain", MODE_PRIVATE)
        val raw = prefs.getString("last_snapshot", null) ?: return
        val savedExpiry = prefs.getString("last_expiry", "current_week") ?: "current_week"
        selectedExpiry = savedExpiry
        val position = listOf("current_week", "next_week", "far_week", "current_month", "next_month", "far_month")
            .indexOf(savedExpiry).coerceAtLeast(0)
        if (::expiryPicker.isInitialized) expiryPicker.setSelection(position)
        runCatching {
            val root = JSONObject(raw)
            require(root.optString("provider").equals("upstox", true))
            val spot = root.optDouble("spotPrice", Double.NaN)
            require(spot.isFinite() && spot > 0.0)
            val rows = parseRows(root.optJSONArray("strikes") ?: JSONArray())
            require(rows.isNotEmpty())
            val receivedAt = root.optLong("receivedAtEpochMs", 0L)
            require(receivedAt > 0L && receivedAt <= System.currentTimeMillis() + 5_000L)
            ChainSnapshot(root.optString("expiryDate"), spot, receivedAt, rows)
        }.onSuccess { cached ->
            latestSnapshot = cached
            renderSnapshot(cached, cached = true)
        }.onFailure {
            statusView.text = "NO VALID CACHED OPTION-CHAIN SNAPSHOT • FETCHING…"
            statusView.setTextColor(AMBER)
        }
    }

    private fun publishToCentralEngine(snapshot: ChainSnapshot) {
        val totalCallOi = snapshot.rows.sumOf { it.callOi ?: 0.0 }
        val totalPutOi = snapshot.rows.sumOf { it.putOi ?: 0.0 }
        val maxCallOi = snapshot.rows.filter { it.callOi != null }.maxByOrNull { it.callOi!! }
        val maxPutOi = snapshot.rows.filter { it.putOi != null }.maxByOrNull { it.putOi!! }
        val maxCallAddition = snapshot.rows.filter { (it.callOiChange ?: 0.0) > 0.0 }
            .maxByOrNull { it.callOiChange!! }
        val maxPutAddition = snapshot.rows.filter { (it.putOiChange ?: 0.0) > 0.0 }
            .maxByOrNull { it.putOiChange!! }
        val analysis = OptionChainAnalysis(
            strikes = snapshot.rows.map { row ->
                OptionChainStrike(
                    strike = row.strike,
                    callOi = row.callOi,
                    callOiChange = row.callOiChange,
                    callVolume = null,
                    callLtp = row.callLtp,
                    putOi = row.putOi,
                    putOiChange = row.putOiChange,
                    putVolume = null,
                    putLtp = row.putLtp
                )
            },
            totalCallOi = totalCallOi,
            totalPutOi = totalPutOi,
            pcr = if (totalCallOi > 0.0) totalPutOi / totalCallOi else null,
            maxCallOiStrike = maxCallOi?.strike,
            maxPutOiStrike = maxPutOi?.strike,
            maxCallAdditionStrike = maxCallAddition?.strike,
            maxPutAdditionStrike = maxPutAddition?.strike,
            totalRows = snapshot.rows.size,
            warnings = listOf(
                "Upstox Worker fetch timestamp is recent; per-strike exchange timestamps are not exposed."
            )
        )
        CentralMarketEngine.publishOptionChainSnapshot(
            OptionChainSnapshot(analysis, snapshot.receivedAtMillis, snapshot.spot)
        )
    }

    private fun parseRows(array: JSONArray): List<StrikeRow> {
        val result = mutableListOf<StrikeRow>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val call = item.optJSONObject("call") ?: JSONObject()
            val put = item.optJSONObject("put") ?: JSONObject()
            val strike = item.optDouble("strike", Double.NaN)
            if (!strike.isFinite() || strike <= 0.0) continue
            result += StrikeRow(
                strike,
                numberOrNull(call, "oi"),
                signedNumberOrNull(call, "oiChange"),
                numberOrNull(call, "ltp"),
                numberOrNull(put, "ltp"),
                signedNumberOrNull(put, "oiChange"),
                numberOrNull(put, "oi")
            )
        }
        return result.sortedBy { it.strike }
    }

    private fun renderSnapshot(snapshot: ChainSnapshot, cached: Boolean) {
        val age = (System.currentTimeMillis() - snapshot.receivedAtMillis).coerceAtLeast(0L)
        val lastAvailable = cached || age > MAX_SNAPSHOT_AGE_MS
        statusView.text = if (lastAvailable) {
            "LAST AVAILABLE SNAPSHOT • ${snapshot.expiry} • NOT LIVE • ${formatSnapshotTime(snapshot.receivedAtMillis)}"
        } else {
            "RECENT UPSTOX API SNAPSHOT • ${snapshot.expiry} • NOT TICK-VERIFIED • ${formatSnapshotTime(snapshot.receivedAtMillis)}"
        }
        statusView.setTextColor(if (lastAvailable) AMBER else GREEN)

        val totalCall = snapshot.rows.sumOf { it.callOi ?: 0.0 }
        val totalPut = snapshot.rows.sumOf { it.putOi ?: 0.0 }
        val pcr = if (totalCall > 0.0) totalPut / totalCall else null
        val maxCall = snapshot.rows.filter { it.callOi != null }.maxByOrNull { it.callOi!! }
        val maxPut = snapshot.rows.filter { it.putOi != null }.maxByOrNull { it.putOi!! }
        val maxCallAddition = snapshot.rows.filter { (it.callOiChange ?: 0.0) > 0.0 }.maxByOrNull { it.callOiChange!! }
        val maxPutAddition = snapshot.rows.filter { (it.putOiChange ?: 0.0) > 0.0 }.maxByOrNull { it.putOiChange!! }
        val maxPain = calculateMaxPain(snapshot.rows)
        summaryView.text = buildString {
            append("NIFTY SPOT: ${fmt(snapshot.spot)}    PCR: ${pcr?.let(::fmt) ?: "—"}\n")
            append("TOTAL CE OI: ${fmtOi(totalCall)}    TOTAL PE OI: ${fmtOi(totalPut)}\n")
            append("HIGHEST CE OI: ${maxCall?.let { fmt(it.strike) } ?: "—"}    HIGHEST PE OI: ${maxPut?.let { fmt(it.strike) } ?: "—"}\n")
            append("TOP CE OI ADDITION: ${maxCallAddition?.let { fmt(it.strike) } ?: "—"}    TOP PE OI ADDITION: ${maxPutAddition?.let { fmt(it.strike) } ?: "—"}\n")
            append("ESTIMATED MAX PAIN: ${maxPain?.let(::fmt) ?: "—"}\n")
            append("OI zones are concentration levels only; they are not guaranteed support/resistance.")
        }
        renderTable(snapshot.rows, snapshot.spot)
    }

    private fun formatSnapshotTime(epochMillis: Long): String =
        java.text.SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
            .format(java.util.Date(epochMillis))

    private fun calculateMaxPain(rows: List<StrikeRow>): Double? {
        val candidates = rows.map { it.strike }
        if (candidates.isEmpty() || rows.none { it.callOi != null || it.putOi != null }) return null
        return candidates.minByOrNull { settlement ->
            rows.sumOf { row ->
                (row.callOi ?: 0.0) * (settlement - row.strike).coerceAtLeast(0.0) +
                    (row.putOi ?: 0.0) * (row.strike - settlement).coerceAtLeast(0.0)
            }
        }
    }

    private fun renderTable(rows: List<StrikeRow>, spot: Double) {
        rowsContainer.removeAllViews()
        val header = listOf("CE OI", "CE ΔOI", "CE LTP", "STRIKE", "PE LTP", "PE ΔOI", "PE OI")
        addTableRow(header, true, null)
        rows.forEach { row ->
            val values = listOf(
                fmtOi(row.callOi), fmtChange(row.callOiChange), fmt(row.callLtp),
                fmt(row.strike), fmt(row.putLtp), fmtChange(row.putOiChange), fmtOi(row.putOi)
            )
            addTableRow(values, false, row.strike.compareTo(spot))
        }
    }

    private fun addTableRow(values: List<String>, header: Boolean, spotCompare: Int?) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (header) rounded(CYAN, CYAN) else rounded(SURFACE, Color.rgb(38, 65, 58))
        }
        values.forEachIndexed { index, value ->
            val centerStrike = index == 3
            val color = when {
                header -> Color.BLACK
                centerStrike && spotCompare != null && spotCompare == 0 -> CYAN
                centerStrike -> WHITE
                else -> MUTED
            }
            val cell = label(value, if (header) 10f else 10f, color, header || centerStrike).apply {
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(9), dp(3), dp(9))
                maxLines = 1
            }
            line.addView(cell, LinearLayout.LayoutParams(dp(82), -2))
        }
        val params = LinearLayout.LayoutParams(-2, -2)
        params.bottomMargin = dp(3)
        rowsContainer.addView(line, params)
    }

    private fun numberOrNull(obj: JSONObject, key: String): Double? =
        obj.optDouble(key, Double.NaN).takeIf { it.isFinite() && it >= 0.0 }

    private fun signedNumberOrNull(obj: JSONObject, key: String): Double? =
        obj.optDouble(key, Double.NaN).takeIf { it.isFinite() }

    private fun fmt(value: Double?): String =
        value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.2f", it) } ?: "—"

    private fun fmtOi(value: Double?): String =
        value?.takeIf { it.isFinite() }?.let { NumberFormat.getIntegerInstance(Locale.US).format(it) } ?: "—"

    private fun fmtChange(value: Double?): String =
        value?.takeIf { it.isFinite() }?.let {
            (if (it > 0) "+" else "") + NumberFormat.getIntegerInstance(Locale.US).format(it)
        } ?: "—"

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = rounded(SURFACE, Color.rgb(38, 65, 58))
    }

    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor?.shutdownNow()
        executor = null
        super.onDestroy()
    }
}
