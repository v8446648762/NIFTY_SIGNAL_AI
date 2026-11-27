package com.niftysignal.ai

/**
 * Core, feature-independent gate for all market-data consumers.
 *
 * This class does not connect to Upstox, refresh the feed, calculate indicators,
 * create signals, or place orders. It validates one existing read-only snapshot
 * and gives every future feature a single canonical snapshot and freshness state.
 */
data class CoreMarketDataState(
    val snapshot: MarketDataSnapshot,
    val receivedAgeMillis: Long,
    val latestFiveMinuteCandleEndMillis: Long,
    val latestOneMinuteCandleEndMillis: Long?
) {
    val isFresh: Boolean get() = !snapshot.isStale
    val staleReason: String? get() = snapshot.staleReason
}

object CoreMarketDataEngine {
    private const val MAX_FUTURE_SKEW_MS = 5_000L
    private const val MAX_RECEIPT_AGE_MS = 90_000L
    private const val MAX_5M_CANDLE_AGE_MS = 10 * 60_000L
    private const val MAX_1M_CANDLE_AGE_MS = 3 * 60_000L

    /**
     * Returns null only for malformed snapshots. Old but structurally valid data
     * is returned as stale, never promoted to fresh/live.
     */
    fun validate(snapshot: MarketDataSnapshot, nowMillis: Long): CoreMarketDataState? {
        val five = snapshot.fiveMinuteCandles
        val one = snapshot.oneMinuteCandles
        if (five.isEmpty() || !validSeries(five) ||
            (one.isNotEmpty() && !validSeries(one))) return null
        if (snapshot.receivedAtMillis <= 0L ||
            snapshot.receivedAtMillis > nowMillis + MAX_FUTURE_SKEW_MS) return null
        if (five.last().timestampMillis > nowMillis + MAX_FUTURE_SKEW_MS ||
            (one.lastOrNull()?.timestampMillis ?: 0L) > nowMillis + MAX_FUTURE_SKEW_MS) return null

        val receiptAge = nowMillis - snapshot.receivedAtMillis
        val fiveEnd = five.last().timestampMillis + 300_000L
        val oneEnd = one.lastOrNull()?.timestampMillis?.plus(60_000L)
        val reason = when {
            snapshot.isStale && !snapshot.staleReason.isNullOrBlank() -> snapshot.staleReason
            snapshot.isStale -> "Upstox feed marked this snapshot stale; live data paused."
            receiptAge > MAX_RECEIPT_AGE_MS -> "Last feed receipt is older than 90 seconds."
            nowMillis - fiveEnd > MAX_5M_CANDLE_AGE_MS -> "Latest completed 5-minute candle is stale."
            oneEnd != null && nowMillis - oneEnd > MAX_1M_CANDLE_AGE_MS ->
                "Latest completed 1-minute candle is stale."
            else -> null
        }
        val canonical = snapshot.copy(isStale = reason != null, staleReason = reason)
        return CoreMarketDataState(canonical, receiptAge, fiveEnd, oneEnd)
    }

    private fun validSeries(candles: List<MarketCandle>): Boolean =
        candles.isNotEmpty() &&
            candles.zipWithNext().all { (a, b) -> b.timestampMillis > a.timestampMillis } &&
            candles.all { candle ->
                val values = listOf(candle.open, candle.high, candle.low, candle.close, candle.volume)
                values.all { it.isFinite() } &&
                    candle.timestampMillis > 0L &&
                    candle.open > 0.0 && candle.high > 0.0 &&
                    candle.low > 0.0 && candle.close > 0.0 &&
                    candle.volume >= 0.0 &&
                    candle.high >= maxOf(candle.open, candle.close, candle.low) &&
                    candle.low <= minOf(candle.open, candle.close, candle.high)
            }
}
