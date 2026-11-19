package com.niftysignal.ai

import android.app.Activity
import android.content.Context
import android.graphics.Color
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

/**
 * Read-only NIFTY option-chain screen. All broker credentials remain on the Cloudflare Worker.
 */
object OptionChainScreen {
    private const val BASE = "https://mgrow-upstox-readonly.vikaskalburgi130.workers.dev"
    private val bg = Color.rgb(8, 14, 25)
    private val panel = Color.rgb(18, 29, 46)
    private val white = Color.WHITE
    private val muted = Color.rgb(158, 174, 194)
    private val green = Color.rgb(57, 235, 128)
    private val cyan = Color.rgb(0, 213, 255)
    private var expiries: List<String> = emptyList()
    private var selectedExpiry: String? = null
    private var requestId = 0

    fun create(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            setBackgroundColor(bg)
        }
        val title = label(context, "NIFTY OPTION CHAIN", 18f, white, true)
        root.addView(title)
        root.addView(label(context, "Read-only market data • No order execution", 11f, muted))
        val spot = label(context, "Spot: --     PCR: --", 13f, cyan, true)
        root.addView(spot)
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val expirySpinner = Spinner(context)
        controls.addView(expirySpinner, LinearLayout.LayoutParams(0, -2, 1f))
        val refresh = Button(context).apply { text = "Refresh" }
        controls.addView(refresh)
        root.addView(controls)
        val status = label(context, "Loading expiry dates…", 12f, muted)
        root.addView(status)
        val tableScroll = HorizontalScrollView(context)
        val table = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        tableScroll.addView(table)
        val vertical = ScrollView(context)
        vertical.addView(tableScroll)
        root.addView(vertical, LinearLayout.LayoutParams(-1, 0, 1f))

        fun loadChain(expiry: String) {
            val thisRequest = ++requestId
            status.text = "Loading option chain…"
            Thread {
                try {
                    val response = getJson("$BASE/v1/nifty/option-chain?expiry_date=" + URLEncoder.encode(expiry, "UTF-8"))
                    val rows = response.optJSONArray("data") ?: JSONArray()
                    val receivedAt = response.optString("receivedAt")
                    (context as? Activity)?.runOnUiThread {
                        if (thisRequest != requestId) return@runOnUiThread
                        table.removeAllViews()
                        val first = rows.optJSONObject(0)
                        val pcr = first?.optDouble("pcr", Double.NaN) ?: Double.NaN
                        val spotPrice = first?.optDouble("underlyingSpotPrice", Double.NaN) ?: Double.NaN
                        spot.text = "Spot: ${number(spotPrice)}     PCR: ${number(pcr)}"
                        addTableRow(context, table, listOf("CE OI", "CE ΔOI", "CE Vol", "CE IV", "CE LTP", "STRIKE", "PE LTP", "PE IV", "PE Vol", "PE ΔOI", "PE OI"), true)
                        for (i in 0 until rows.length()) {
                            val row = rows.optJSONObject(i) ?: continue
                            val call = row.optJSONObject("call") ?: JSONObject()
                            val put = row.optJSONObject("put") ?: JSONObject()
                            addTableRow(context, table, listOf(
                                number(call.optDouble("oi", Double.NaN)),
                                number(call.optDouble("changeOi", Double.NaN)),
                                number(call.optDouble("volume", Double.NaN)),
                                number(call.optDouble("iv", Double.NaN)),
                                number(call.optDouble("ltp", Double.NaN)),
                                number(row.optDouble("strikePrice", Double.NaN)),
                                number(put.optDouble("ltp", Double.NaN)),
                                number(put.optDouble("iv", Double.NaN)),
                                number(put.optDouble("volume", Double.NaN)),
                                number(put.optDouble("changeOi", Double.NaN)),
                                number(put.optDouble("oi", Double.NaN))
                            ), false)
                        }
                        status.text = if (rows.length() > 0) "Expiry $expiry • ${rows.length()} strikes • Received $receivedAt" else "No option-chain rows returned."
                    }
                } catch (error: Exception) {
                    (context as? Activity)?.runOnUiThread {
                        if (thisRequest == requestId) status.text = "Option Chain unavailable: ${error.message ?: "network error"}"
                    }
                }
            }.start()
        }

        fun loadExpiries() {
            status.text = "Loading expiry dates…"
            Thread {
                try {
                    val response = getJson("$BASE/v1/nifty/option-expiries")
                    val data = response.optJSONArray("data") ?: JSONArray()
                    val found = (0 until data.length()).mapNotNull { data.optString(it).takeIf(String::isNotBlank) }
                    (context as? Activity)?.runOnUiThread {
                        expiries = found
                        if (found.isEmpty()) {
                            status.text = "No NIFTY expiries available."
                        } else {
                            expirySpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, found)
                            val target = selectedExpiry?.takeIf { it in found } ?: found.first()
                            selectedExpiry = target
                            expirySpinner.setSelection(found.indexOf(target))
                            loadChain(target)
                        }
                    }
                } catch (error: Exception) {
                    (context as? Activity)?.runOnUiThread { status.text = "Expiry list unavailable: ${error.message ?: "network error"}" }
                }
            }.start()
        }

        expirySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val expiry = expiries.getOrNull(position) ?: return
                if (expiry != selectedExpiry || table.childCount == 0) {
                    selectedExpiry = expiry
                    loadChain(expiry)
                }
            }
        }
        refresh.setOnClickListener {
            if (selectedExpiry == null) loadExpiries() else loadChain(selectedExpiry!!)
        }
        loadExpiries()
        return root
    }

    private fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun addTableRow(context: Context, table: LinearLayout, values: List<String>, header: Boolean) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(if (header) Color.rgb(28, 48, 68) else panel)
        }
        values.forEachIndexed { index, value ->
            val color = when {
                index == 5 -> cyan
                index < 5 -> green
                else -> Color.rgb(255, 116, 135)
            }
            row.addView(label(context, value, 10f, if (header) white else color, header).apply {
                gravity = Gravity.CENTER
                setPadding(7, 9, 7, 9)
                minWidth = 66
            })
        }
        table.addView(row)
    }

    private fun label(context: Context, value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(2, 5, 2, 5)
        }

    private fun number(value: Double): String =
        if (!value.isFinite()) "--" else if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", value)
}
