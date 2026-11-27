package com.niftysignal.ai

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Read-only selected-index candle feed. Broker credentials remain server-side. */
class IndexMarketDataFeed(private val symbol: String) : MarketDataFeed {
    companion object {
        private const val BASE_ENDPOINT = "https://mgrow-upstox-readonly.vikaskalburgi130.workers.dev/v1/index/candles"
        private const val POLL_SECONDS = 20L
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_RECEIPT_AGE_MS = 90_000L
    }
    @Volatile private var listener: MarketDataFeed.Listener? = null
    @Volatile private var executor: ScheduledExecutorService? = null

    override fun connect(listener: MarketDataFeed.Listener) {
        require(symbol in setOf("NIFTY", "BANKNIFTY", "FINNIFTY"))
        this.listener = listener
        if (executor != null && executor?.isShutdown == false) return
        listener.onStatusChanged(FeedConnectionState.CONNECTING, "Connecting to $symbol read-only candles")
        val service = Executors.newSingleThreadScheduledExecutor()
        executor = service
        service.execute { pollOnce() }
        service.scheduleWithFixedDelay({ pollOnce() }, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS)
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
            val encoded = URLEncoder.encode(symbol, "UTF-8")
            connection = (URL("$BASE_ENDPOINT?symbol=$encoded").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Selected-index server returned HTTP $code")
            val root = JSONObject(body)
            if (!root.optString("provider").equals("upstox", true) || !root.optString("symbol").equals(symbol, true))
                throw IllegalStateException("Selected-index feed identity mismatch")
            val receivedAt = root.optLong("receivedAtEpochMs", 0L)
            val now = System.currentTimeMillis()
            if (receivedAt <= 0L || receivedAt > now + 5_000L || now - receivedAt > MAX_RECEIPT_AGE_MS)
                throw IllegalStateException("$symbol feed receipt is stale or invalid")
            val data = root.optJSONObject("data") ?: throw IllegalStateException("$symbol candle data is missing")
            val fiveMinute = parseCandles(data.optJSONArray("fiveMinuteCandles"), 300_000L, now)
            val oneMinute = parseCandles(data.optJSONArray("oneMinuteCandles"), 60_000L, now)
            if (fiveMinute.isEmpty()) throw IllegalStateException("$symbol has no completed 5-minute candles")
            val fiveAge = now - (fiveMinute.last().timestampMillis + 300_000L)
            val oneAge = oneMinute.lastOrNull()?.let { now - (it.timestampMillis + 60_000L) }
            val stale = fiveAge > 600_000L || (oneAge != null && oneAge > 180_000L)
            val staleReason = when {
                fiveAge > 600_000L -> "Last completed $symbol 5-minute candle is stale; AI signals paused."
                oneAge != null && oneAge > 180_000L -> "$symbol 1-minute confirmation is stale; AI signals paused."
                oneMinute.isEmpty() -> "$symbol 1-minute confirmation unavailable; directional AI signal waits."
                else -> null
            }
            target.onSnapshot(MarketDataSnapshot(
                fiveMinuteCandles = fiveMinute, oneMinuteCandles = oneMinute,
                receivedAtMillis = receivedAt, isStale = stale, staleReason = staleReason,
                previousClose = data.optDouble("previousClose", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
                dayHigh = data.optDouble("dayHigh", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
                dayLow = data.optDouble("dayLow", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
            ))
            target.onStatusChanged(if (stale) FeedConnectionState.STALE else FeedConnectionState.LIVE, staleReason)
        } catch (error: Exception) {
            target.onStatusChanged(FeedConnectionState.ERROR, error.message ?: "$symbol feed unavailable")
        } finally { connection?.disconnect() }
    }
    private fun parseCandles(array: org.json.JSONArray?, intervalMillis: Long, nowMillis: Long): List<MarketCandle> {
        if (array == null) return emptyList()
        val result = ArrayList<MarketCandle>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val timestamp = parseIsoTimestamp(item.optString("timestamp")) ?: continue
            if (timestamp + intervalMillis > nowMillis) continue
            val candle = MarketCandle(timestamp, item.optDouble("open", Double.NaN), item.optDouble("high", Double.NaN),
                item.optDouble("low", Double.NaN), item.optDouble("close", Double.NaN), item.optDouble("volume", 0.0))
            if (valid(candle)) result.add(candle)
        }
        return result.sortedBy { it.timestampMillis }.distinctBy { it.timestampMillis }.takeLast(180)
    }
    private fun parseIsoTimestamp(value: String): Long? {
        val match = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:\\d{2})$").matchEntire(value) ?: return null
        return try {
            val millisText = (match.groupValues[2] + "000").take(3)
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
            val base = parser.parse(match.groupValues[1] + "." + millisText)?.time ?: return null
            val zone = match.groupValues[3]
            if (zone == "Z") base else {
                val sign = if (zone[0] == '-') -1 else 1
                val hours = zone.substring(1, 3).toInt()
                val minutes = zone.substring(4, 6).toInt()
                base - sign * (hours * 60L + minutes) * 60_000L
            }
        } catch (_: ParseException) { null } catch (_: NumberFormatException) { null }
    }
    private fun valid(candle: MarketCandle): Boolean =
        listOf(candle.open, candle.high, candle.low, candle.close, candle.volume).all { it.isFinite() } &&
            candle.open > 0.0 && candle.high > 0.0 && candle.low > 0.0 && candle.close > 0.0 && candle.volume >= 0.0 &&
            candle.high >= maxOf(candle.open, candle.close, candle.low) && candle.low <= minOf(candle.open, candle.close, candle.high)
}
