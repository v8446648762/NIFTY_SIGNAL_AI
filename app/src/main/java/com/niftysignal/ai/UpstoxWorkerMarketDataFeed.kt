package com.niftysignal.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Read-only adapter for the MGrow Cloudflare Worker.
 * Broker credentials stay on Cloudflare; the Android app only calls the HTTPS proxy.
 */
class UpstoxWorkerMarketDataFeed : MarketDataFeed {
    companion object {
        private const val ENDPOINT =
            "https://mgrow-upstox-readonly.vikaskalburgi130.workers.dev/v1/nifty/candles"
        private const val POLL_SECONDS = 20L
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 10000
    }

    @Volatile private var listener: MarketDataFeed.Listener? = null
    @Volatile private var executor: ScheduledExecutorService? = null

    override fun connect(listener: MarketDataFeed.Listener) {
        this.listener = listener
        if (executor != null && executor?.isShutdown == false) return
        listener.onStatusChanged(FeedConnectionState.CONNECTING, "Connecting securely to MGrow market data")
        val service = Executors.newSingleThreadScheduledExecutor()
        executor = service
        service.execute { pollOnce() }
        service.scheduleWithFixedDelay(
            { pollOnce() },
            POLL_SECONDS,
            POLL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    override fun disconnect() {
        executor?.shutdownNow()
        executor = null
        listener = null
    }

    private fun pollOnce() {
        val target = listener ?: return
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Market data server returned HTTP $code")
            }
            val root = JSONObject(body)
            if (!root.optString("provider").equals("upstox", ignoreCase = true)) {
                throw IllegalStateException("Unexpected market data provider")
            }
            val data = root.optJSONObject("data")
                ?: throw IllegalStateException("Candle data is missing from server response")
            val now = System.currentTimeMillis()
            val oneMinute = parseCandles(data.optJSONArray("oneMinuteCandles"), 60_000L, now)
            val fiveMinute = parseCandles(data.optJSONArray("fiveMinuteCandles"), 300_000L, now)
            if (oneMinute.isEmpty() || fiveMinute.isEmpty()) {
                throw IllegalStateException("No completed 1-minute or 5-minute candles were returned")
            }
            target.onSnapshot(
                MarketDataSnapshot(
                    fiveMinuteCandles = fiveMinute,
                    oneMinuteCandles = oneMinute,
                    receivedAtMillis = now
                )
            )
            target.onStatusChanged(FeedConnectionState.LIVE, null)
        } catch (error: Exception) {
            val message = when (error) {
                is java.net.SocketTimeoutException -> "Market data request timed out"
                is java.net.UnknownHostException -> "Cannot reach MGrow market data server"
                else -> error.message ?: "Unable to read live market data"
            }
            target.onStatusChanged(FeedConnectionState.ERROR, message)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseCandles(array: JSONArray?, intervalMillis: Long, nowMillis: Long): List<MarketCandle> {
        if (array == null) return emptyList()
        val result = ArrayList<MarketCandle>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val timestamp = parseIsoTimestamp(item.optString("timestamp")) ?: continue
            // Do not calculate indicators or signals from a candle that is still forming.
            if (timestamp + intervalMillis > nowMillis) continue
            val open = item.optDouble("open", Double.NaN)
            val high = item.optDouble("high", Double.NaN)
            val low = item.optDouble("low", Double.NaN)
            val close = item.optDouble("close", Double.NaN)
            val volume = item.optDouble("volume", 0.0)
            val candle = MarketCandle(timestamp, open, high, low, close, volume)
            if (valid(candle)) result.add(candle)
        }
        return result.sortedBy { it.timestampMillis }.distinctBy { it.timestampMillis }.takeLast(180)
    }

    /**
     * Parses ISO-8601 timestamps without relying on java.time, preserving minSdk 23 support.
     */
    private fun parseIsoTimestamp(value: String): Long? {
        val match = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:\\d{2})$").matchEntire(value)
            ?: return null
        return try {
            val millisText = (match.groupValues[2] + "000").take(3)
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val base = parser.parse(match.groupValues[1] + "." + millisText)?.time ?: return null
            val zone = match.groupValues[3]
            if (zone == "Z") base else {
                val sign = if (zone[0] == '-') -1 else 1
                val hours = zone.substring(1, 3).toInt()
                val minutes = zone.substring(4, 6).toInt()
                base - sign * (hours * 60L + minutes) * 60_000L
            }
        } catch (_: ParseException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun valid(candle: MarketCandle): Boolean {
        val values = listOf(candle.open, candle.high, candle.low, candle.close, candle.volume)
        return values.all { it.isFinite() } &&
            candle.open > 0.0 && candle.high > 0.0 && candle.low > 0.0 &&
            candle.close > 0.0 && candle.volume >= 0.0 &&
            candle.high >= maxOf(candle.open, candle.close, candle.low) &&
            candle.low <= minOf(candle.open, candle.close, candle.high)
    }
}
