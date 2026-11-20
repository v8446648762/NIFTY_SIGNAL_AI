package com.niftysignal.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only Android adapter for the MGrow Cloudflare Worker.
 * Broker credentials remain on the server; this class contains no Upstox token.
 */
class UpstoxMarketDataFeed(
    baseUrl: String,
    private val refreshSeconds: Long = 30L
) : MarketDataFeed {
    private val root = baseUrl.trim().trimEnd('/')
    private val stopped = AtomicBoolean(true)
    private var executor: ScheduledExecutorService? = null
    @Volatile private var listener: MarketDataFeed.Listener? = null

    override fun connect(listener: MarketDataFeed.Listener) {
        this.listener = listener
        if (root.isBlank()) {
            listener.onStatusChanged(FeedConnectionState.ERROR, "Live data server URL is missing.")
            return
        }
        if (!stopped.compareAndSet(true, false)) return
        listener.onStatusChanged(FeedConnectionState.CONNECTING, "Connecting to Upstox live data")
        val service = Executors.newSingleThreadScheduledExecutor()
        executor = service
        service.scheduleWithFixedDelay({
            if (!stopped.get()) refresh()
        }, 0L, 30L.coerceAtLeast(refreshSeconds), TimeUnit.SECONDS)
    }

    private fun refresh() {
        val target = listener ?: return
        try {
            target.onStatusChanged(FeedConnectionState.CONNECTING, "Refreshing NIFTY candles and option chain")
            val candles = getJson("/v1/nifty/candles")
            val data = candles.getJSONObject("data")
            val five = parseCandles(data.optJSONArray("fiveMinuteCandles"))
            val one = parseCandles(data.optJSONArray("oneMinuteCandles"))

            val expiryResponse = getJson("/v1/nifty/expiries")
            val expiryArray = expiryResponse.optJSONArray("data") ?: JSONArray()
            val today = LocalDate.now()
            val expiry = (0 until expiryArray.length())
                .mapNotNull { expiryArray.optString(it).takeIf { value -> value.isNotBlank() } }
                .mapNotNull { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
                .filter { !it.isBefore(today) }
                .minOrNull()
                ?: throw IllegalStateException("No upcoming NIFTY option expiry returned by Upstox.")

            val chainResponse = getJson("/v1/nifty/option-chain?expiry_date=$expiry")
            val chainArray = chainResponse.optJSONArray("data") ?: JSONArray()
            val contracts = parseOptionChain(chainArray)
            if (five.isEmpty()) throw IllegalStateException("Upstox returned no valid 5-minute candles.")
            if (System.currentTimeMillis() - five.last().timestampMillis > 10 * 60 * 1000L) {
                throw IllegalStateException("stale: latest NIFTY 5-minute candle is older than 10 minutes.")
            }
            if (contracts.isEmpty()) throw IllegalStateException("Upstox returned no valid option-chain strikes.")

            target.onSnapshot(
                MarketDataSnapshot(
                    fiveMinuteCandles = five,
                    oneMinuteCandles = one,
                    receivedAtMillis = System.currentTimeMillis(),
                    optionChain = contracts
                )
            )
            target.onStatusChanged(FeedConnectionState.LIVE, "Upstox data updated • expiry $expiry")
        } catch (error: Exception) {
            val message = error.message ?: "Unable to refresh Upstox data."
            target.onStatusChanged(
                if (message.contains("stale", ignoreCase = true)) FeedConnectionState.STALE
                else FeedConnectionState.ERROR,
                message
            )
        }
    }

    private fun getJson(path: String): JSONObject {
        val connection = (URL(root + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("Accept", "application/json")
            useCaches = false
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val error = runCatching { JSONObject(body).optString("error") }.getOrNull()
                throw IllegalStateException("Live data server HTTP $status" + if (error.isNullOrBlank()) "" else ": $error")
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCandles(array: JSONArray?): List<MarketCandle> {
        if (array == null) return emptyList()
        val result = mutableListOf<MarketCandle>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val timestamp = runCatching { Instant.parse(row.optString("timestamp")).toEpochMilli() }.getOrNull() ?: continue
            val open = row.number("open") ?: continue
            val high = row.number("high") ?: continue
            val low = row.number("low") ?: continue
            val close = row.number("close") ?: continue
            val volume = row.number("volume") ?: 0.0
            val candle = MarketCandle(timestamp, open, high, low, close, volume)
            if (open > 0 && low > 0 && close > 0 && high >= maxOf(open, high, low, close) &&
                low <= minOf(open, high, low, close) && volume >= 0) result += candle
        }
        return result.sortedBy { it.timestampMillis }.distinctBy { it.timestampMillis }
    }

    private fun parseOptionChain(array: JSONArray): List<OptionChainContract> {
        val result = mutableListOf<OptionChainContract>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val strike = row.number("strike") ?: continue
            result += OptionChainContract(
                strike = strike,
                callOi = row.number("callOi"),
                callOiChange = row.number("callOiChange"),
                callVolume = row.number("callVolume"),
                callLtp = row.number("callLtp"),
                callIv = row.number("callIv"),
                callDelta = row.number("callDelta"),
                callBid = row.number("callBid"),
                callAsk = row.number("callAsk"),
                putOi = row.number("putOi"),
                putOiChange = row.number("putOiChange"),
                putVolume = row.number("putVolume"),
                putLtp = row.number("putLtp"),
                putIv = row.number("putIv"),
                putDelta = row.number("putDelta"),
                putBid = row.number("putBid"),
                putAsk = row.number("putAsk")
            )
        }
        return result.sortedBy { it.strike }
    }

    private fun JSONObject.number(key: String): Double? {
        if (isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return value.takeIf { it.isFinite() }
    }

    override fun disconnect() {
        stopped.set(true)
        executor?.shutdownNow()
        executor = null
        listener = null
    }
}
