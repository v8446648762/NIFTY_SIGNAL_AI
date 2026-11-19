package com.niftysignal.ai

import kotlin.math.abs

/**
 * Isolated, read-only estimate for the direction of the NEXT completed 5-minute candle.
 *
 * This is a transparent heuristic score, NOT a trained/validated AI model and NOT
 * a statistically calibrated probability. It never fetches data or changes other
 * signal engines. Callers must pass only validated live candles and a freshness flag.
 */
enum class NextCandleDirection { GREEN, RED, INSUFFICIENT_DATA, NO_EDGE }

data class NextCandleEstimate(
    val direction: NextCandleDirection,
    val greenProbability: Int?,
    val redProbability: Int?,
    val confidence: Int?,
    val reasons: List<String>
)

object NextFiveMinuteCandleEngine {
    private const val MIN_5M = 22
    private const val MIN_1M = 5
    private const val MIN_EDGE = 12
    private const val MIN_PROBABILITY = 60

    fun evaluate(
        candles5m: List<MarketCandle>,
        candles1m: List<MarketCandle>,
        feedFresh: Boolean,
        nowMillis: Long
    ): NextCandleEstimate {
        if (!feedFresh) return insufficient("Live feed stale or disconnected.")
        if (candles5m.size < MIN_5M || candles1m.size < MIN_1M) {
            return insufficient("Need at least 22 five-minute and 5 one-minute candles.")
        }
        if (!candles5m.all(::valid) || !candles1m.all(::valid)) {
            return insufficient("Invalid candle data.")
        }
        val latest5 = candles5m.last()
        val latest1 = candles1m.last()
        if (latest5.timestampMillis <= 0L || latest1.timestampMillis <= 0L ||
            latest5.timestampMillis > nowMillis || latest1.timestampMillis > nowMillis ||
            nowMillis - latest5.timestampMillis > 10 * 60_000L ||
            nowMillis - latest1.timestampMillis > 2 * 60_000L
        ) return insufficient("Latest candle timestamps are stale or invalid.")

        val closes = candles5m.map { it.close }
        val ema9 = TechnicalAnalysis.ema(closes, 9) ?: return insufficient("EMA data unavailable.")
        val ema21 = TechnicalAnalysis.ema(closes, 21) ?: return insufficient("EMA data unavailable.")
        val rsi = TechnicalAnalysis.rsi(closes, 14) ?: return insufficient("RSI data unavailable.")
        val vwap = TechnicalAnalysis.sessionVwap(candles5m)
            ?: return insufficient("VWAP data unavailable.")

        var score = 0
        val reasons = mutableListOf<String>()
        fun add(points: Int, reason: String) {
            score += points
            reasons += reason
        }

        if (ema9 > ema21) add(18, "5m EMA9 above EMA21") else if (ema9 < ema21) add(-18, "5m EMA9 below EMA21")
        if (latest5.close > vwap) add(14, "NIFTY above 5m VWAP") else if (latest5.close < vwap) add(-14, "NIFTY below 5m VWAP")
        if (rsi >= 55.0) add(12, "5m RSI bullish") else if (rsi <= 45.0) add(-12, "5m RSI bearish")
        if (latest5.close > latest5.open) add(8, "Latest 5m candle body green") else if (latest5.close < latest5.open) add(-8, "Latest 5m candle body red")

        val oneMinuteMove = latest1.close - candles1m[candles1m.lastIndex - 1].close
        if (oneMinuteMove > 0) add(10, "1m momentum positive") else if (oneMinuteMove < 0) add(-10, "1m momentum negative")

        val recent1 = candles1m.takeLast(5)
        val upBars = recent1.count { it.close > it.open }
        val downBars = recent1.count { it.close < it.open }
        if (upBars >= 4) add(8, "4+ of last 5 one-minute candles green")
        else if (downBars >= 4) add(-8, "4+ of last 5 one-minute candles red")

        val recent5 = candles5m.takeLast(20)
        val support = recent5.minOf { it.low }
        val resistance = recent5.maxOf { it.high }
        val range = (resistance - support).coerceAtLeast(0.01)
        val supportDistance = (latest5.close - support) / range
        val resistanceDistance = (resistance - latest5.close) / range
        if (supportDistance < 0.08) add(-6, "Price near recent support: upside/downside reaction risk")
        if (resistanceDistance < 0.08) add(-6, "Price near recent resistance: rejection risk")

        val green = (50 + score / 2).coerceIn(5, 95)
        val red = 100 - green
        val direction = when {
            green >= MIN_PROBABILITY && green - red >= MIN_EDGE -> NextCandleDirection.GREEN
            red >= MIN_PROBABILITY && red - green >= MIN_EDGE -> NextCandleDirection.RED
            else -> NextCandleDirection.NO_EDGE
        }
        return NextCandleEstimate(
            direction = direction,
            greenProbability = green,
            redProbability = red,
            confidence = abs(green - red),
            reasons = reasons.toList()
        )
    }

    private fun valid(c: MarketCandle): Boolean =
        c.timestampMillis > 0L &&
            listOf(c.open, c.high, c.low, c.close, c.volume).all { it.isFinite() } &&
            c.open > 0.0 && c.high >= maxOf(c.open, c.close, c.low) &&
            c.low > 0.0 && c.low <= minOf(c.open, c.close, c.high) && c.volume >= 0.0

    private fun insufficient(reason: String) = NextCandleEstimate(
        NextCandleDirection.INSUFFICIENT_DATA, null, null, null, listOf(reason)
    )
}
