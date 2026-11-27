package com.niftysignal.ai

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class MarketIndexQuote(
    val symbol: String,
    val lastPrice: Double,
    val previousClose: Double,
    val lastTradeTimeMillis: Long,
    val isFresh: Boolean
) {
    val change: Double get() = lastPrice - previousClose
    val changePercent: Double get() = if (previousClose > 0.0) change / previousClose * 100.0 else 0.0
}

data class MarketIndicesSnapshot(
    val receivedAtMillis: Long,
    val quotes: Map<String, MarketIndexQuote>
)

class MarketIndicesFeed {
    interface Listener {
        fun onIndices(snapshot: MarketIndicesSnapshot)
        fun onError(message: String)
    }

    companion object {
        private const val ENDPOINT = "https://mgrow-upstox-readonly.vikaskalburgi130.workers.dev/v1/indices"
        private const val POLL_SECONDS = 30L
        private const val MAX_RECEIPT_AGE_MS = 90_000L
        private const val MAX_TRADE_AGE_MS = 90_000L
    }

    @Volatile private var listener: Listener? = null
    @Volatile private var executor: ScheduledExecutorService? = null

    fun connect(listener: Listener) {
        this.listener = listener
        if (executor != null && executor?.isShutdown == false) return
        val service = Executors.newSingleThreadScheduledExecutor()
        executor = service
        service.execute { pollOnce() }
        service.scheduleWithFixedDelay({ pollOnce() }, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS)
    }

    fun disconnect() {
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
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Index server returned HTTP $code")
            val root = JSONObject(body)
            if (!root.optString("provider").equals("upstox", ignoreCase = true)) {
                throw IllegalStateException("Unexpected index data provider")
            }
            val receivedAt = root.optLong("receivedAtEpochMs", 0L)
            val now = System.currentTimeMillis()
            if (receivedAt <= 0L || receivedAt > now + 5_000L || now - receivedAt > MAX_RECEIPT_AGE_MS) {
                throw IllegalStateException("Index quote response is stale")
            }
            val data = root.optJSONObject("data") ?: throw IllegalStateException("Index quotes are unavailable")
            val quotes = linkedMapOf<String, MarketIndexQuote>()
            listOf("BANKNIFTY", "FINNIFTY").forEach { symbol ->
                val item = data.optJSONObject(symbol) ?: return@forEach
                val price = item.optDouble("lastPrice", Double.NaN)
                val previous = item.optDouble("previousClose", Double.NaN)
                val tradeTime = item.optLong("lastTradeTimeMs", 0L)
                if (!price.isFinite() || price <= 0.0 || !previous.isFinite() || previous <= 0.0 || tradeTime <= 0L) {
                    return@forEach
                }
                val tradeAge = now - tradeTime
                val fresh = item.optBoolean("isFresh", false) &&
                    tradeAge in 0..MAX_TRADE_AGE_MS
                quotes[symbol] = MarketIndexQuote(symbol, price, previous, tradeTime, fresh)
            }
            if (quotes.isEmpty()) throw IllegalStateException("BANKNIFTY / FINNIFTY quotes are unavailable")
            target.onIndices(MarketIndicesSnapshot(receivedAt, quotes))
        } catch (error: Exception) {
            target.onError(error.message ?: "Unable to fetch index quotes")
        } finally {
            connection?.disconnect()
        }
    }
}
