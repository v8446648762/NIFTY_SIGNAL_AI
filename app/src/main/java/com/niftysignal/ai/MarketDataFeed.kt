package com.niftysignal.ai

/**
 * Feed boundary for the dashboard.
 *
 * A future Upstox adapter should implement this interface and publish validated
 * session candles. This module intentionally contains no broker SDK, credentials,
 * network calls, or order-placement methods.
 */
enum class FeedConnectionState {
    OFFLINE,
    CONNECTING,
    LIVE,
    STALE,
    ERROR
}

/**
 * One feed update. fiveMinuteCandles and oneMinuteCandles should contain
 * chronological, session-scoped OHLCV candles. receivedAtMillis is the local
 * receipt time, not the timestamp of the last candle.
 */
data class MarketDataSnapshot(
    val fiveMinuteCandles: List<MarketCandle>,
    val oneMinuteCandles: List<MarketCandle>,
    val receivedAtMillis: Long,
    val previousClose: Double? = null,
    val dayHigh: Double? = null,
    val dayLow: Double? = null,
    val lastPrice: Double? = null
)

/**
 * Implement this boundary with a read-only market-data adapter.
 * Keep broker authentication server-side; the dashboard never handles broker secrets.
 */
interface MarketDataFeed {
    fun connect(listener: Listener)
    fun disconnect()

    interface Listener {
        fun onStatusChanged(status: FeedConnectionState, message: String? = null)
        fun onSnapshot(snapshot: MarketDataSnapshot)
    }
}
