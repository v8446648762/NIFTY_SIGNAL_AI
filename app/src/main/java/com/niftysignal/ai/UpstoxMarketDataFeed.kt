package com.niftysignal.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

/** Read-only MGrow Worker poller. The Upstox token remains server-side. */
class UpstoxMarketDataFeed(workerBaseUrl: String) : MarketDataFeed {
    private val baseUrl = workerBaseUrl.trim().trimEnd('/')
    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null

    override fun connect(listener: MarketDataFeed.Listener) {
        stopPolling()
        if (baseUrl.isBlank() || !baseUrl.startsWith("https://")) {
            listener.onStatusChanged(FeedConnectionState.ERROR, "Secure MGrow Worker URL is not configured.")
            return
        }
        val feedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = feedScope
        pollJob = feedScope.launch {
            listener.onStatusChanged(FeedConnectionState.CONNECTING, "Connecting securely to MGrow live data...")
            var lastCandleFetchAt = 0L
            var oneMinute = emptyList<MarketCandle>()
            var fiveMinute = emptyList<MarketCandle>()
            while (isActive) {
                try {
                    // Quotes refresh frequently; candles refresh once per minute because
                    // 1m/5m candle endpoints do not need to be polled every 15 seconds.
                    val quoteResponse = getJson("/v1/nifty/quote")
                    val now = System.currentTimeMillis()
                    if (oneMinute.isEmpty() || fiveMinute.isEmpty() ||
                        now - lastCandleFetchAt >= CANDLE_REFRESH_INTERVAL_MS) {
                        val candleResponse = getJson("/v1/nifty/candles")
                        val candleData = candleResponse.optJSONObject("data")
                            ?: throw IllegalStateException("NIFTY candles are missing")
                        val newOneMinute = parseCandles(candleData, "oneMinuteCandles")
                        val newFiveMinute = parseCandles(candleData, "fiveMinuteCandles")
                        if (newOneMinute.isEmpty() || newFiveMinute.isEmpty()) {
                            throw IllegalStateException("Upstox returned no intraday candles")
                        }
                        oneMinute = newOneMinute
                        fiveMinute = newFiveMinute
                        lastCandleFetchAt = now
                    }
                    val quote = quoteResponse.optJSONObject("data")
                        ?: throw IllegalStateException("NIFTY quote is missing")
                    val ohlc = quote.optJSONObject("ohlc")
                    val lastPrice = quote.optDouble("last_price", Double.NaN)
                    if (!lastPrice.isFinite() || lastPrice <= 0.0) {
                        throw IllegalStateException("NIFTY quote price is invalid")
                    }
                    val previousClose = quote.optDouble("prev_close_price", Double.NaN)
                        ?.takeIf { it.isFinite() && it > 0.0 }
                    val dayHigh = ohlc?.optDouble("high", Double.NaN)
                        ?.takeIf { it.isFinite() && it > 0.0 }
                    val dayLow = ohlc?.optDouble("low", Double.NaN)
                        ?.takeIf { it.isFinite() && it > 0.0 }
                    listener.onSnapshot(
                        MarketDataSnapshot(
                            fiveMinuteCandles = fiveMinute,
                            oneMinuteCandles = oneMinute,
                            receivedAtMillis = System.currentTimeMillis(),
                            previousClose = previousClose,
                            dayHigh = dayHigh,
                            dayLow = dayLow,
                            lastPrice = lastPrice
                        )
                    )
                    listener.onStatusChanged(FeedConnectionState.LIVE, "NIFTY live data received.")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val reason = when (error) {
                        is MarketDataHttpException -> buildString {
                            append("Worker HTTP ${error.statusCode}: ${error.errorCode}")
                            error.upstreamStatus?.let { append(" (Upstox HTTP $it") }
                            error.interval?.let { append(", ${it}m") }
                            if (error.upstreamStatus != null) append(")")
                        }
                        is java.net.SocketTimeoutException -> "Live data server timed out."
                        is java.io.IOException -> "Network connection failed."
                        else -> "Live data unavailable (${error.javaClass.simpleName})."
                    }
                    listener.onStatusChanged(FeedConnectionState.ERROR, reason)
                }
                // A 429 is a rate-limit response: wait longer before retrying.
                val waitMs = if (lastStatusWasRateLimited) RATE_LIMIT_BACKOFF_MS else POLL_INTERVAL_MS
                lastStatusWasRateLimited = false
                delay(waitMs)
            }
        }
    }

    @Volatile private var lastStatusWasRateLimited = false

    override fun disconnect() {
        stopPolling()
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        scope?.cancel()
        scope = null
        lastStatusWasRateLimited = false
    }

    private fun getJson(path: String): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            useCaches = false
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val errorBody = runCatching { JSONObject(body) }.getOrNull()
                val errorCode = errorBody?.optString("error", "http_error") ?: "http_error"
                val upstreamStatus = errorBody?.optInt("upstreamStatus")?.takeIf { it > 0 }
                val interval = errorBody?.optString("interval")?.takeIf { it == "1" || it == "5" }
                if (upstreamStatus == 429 || status == 429) lastStatusWasRateLimited = true
                throw MarketDataHttpException(status, errorCode, upstreamStatus, interval)
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCandles(data: JSONObject, field: String): List<MarketCandle> {
        val rows = data.optJSONArray(field) ?: throw IllegalStateException("Missing $field")
        val candles = ArrayList<MarketCandle>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: throw IllegalStateException("Malformed candle row")
            val timestamp = row.optString("timestamp")
            val candle = MarketCandle(
                timestampMillis = OffsetDateTime.parse(timestamp).toInstant().toEpochMilli(),
                open = row.getDouble("open"),
                high = row.getDouble("high"),
                low = row.getDouble("low"),
                close = row.getDouble("close"),
                volume = row.getDouble("volume")
            )
            if (!isValidCandle(candle)) throw IllegalStateException("Invalid OHLCV candle")
            candles.add(candle)
        }
        return candles.sortedBy { it.timestampMillis }
    }

    private fun isValidCandle(candle: MarketCandle): Boolean =
        candle.timestampMillis > 0L &&
            listOf(candle.open, candle.high, candle.low, candle.close, candle.volume).all { it.isFinite() } &&
            candle.open > 0.0 && candle.high > 0.0 && candle.low > 0.0 && candle.close > 0.0 &&
            candle.volume >= 0.0 &&
            candle.high >= maxOf(candle.open, candle.close, candle.low) &&
            candle.low <= minOf(candle.open, candle.close, candle.high)

    private companion object {
        const val POLL_INTERVAL_MS = 15_000L
        const val CANDLE_REFRESH_INTERVAL_MS = 60_000L
        const val RATE_LIMIT_BACKOFF_MS = 60_000L
        const val REQUEST_TIMEOUT_MS = 5_000
    }
}


/** Safe, structured HTTP error returned by the MGrow Worker. */
private class MarketDataHttpException(
    val statusCode: Int,
    val errorCode: String,
    val upstreamStatus: Int? = null,
    val interval: String? = null
) : java.io.IOException("MGrow Worker HTTP $statusCode: $errorCode")
