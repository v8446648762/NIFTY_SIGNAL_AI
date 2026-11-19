package com.niftysignal.ai

import kotlin.math.abs

enum class NextCandleDirection { GREEN, RED, WAIT }

data class NextCandlePrediction(
    val direction: NextCandleDirection,
    val heuristicConfidence: Int?,
    val bullishScore: Int,
    val bearishScore: Int,
    val reasons: List<String>
)

/** Read-only heuristic estimate; not a calibrated probability or a trading instruction. */
object NextFiveMinuteCandleEngine {
    fun evaluate(
        fiveMinuteCandles: List<MarketCandle>,
        oneMinuteCandles: List<MarketCandle>,
        intelligence: MarketIntelligenceSnapshot? = null,
        stale: Boolean = false
    ): NextCandlePrediction {
        fun wait(reason: String) = NextCandlePrediction(NextCandleDirection.WAIT, null, 0, 0, listOf(reason))
        if (stale) return wait("Live feed is stale; prediction paused.")
        if (fiveMinuteCandles.size < 22) return wait("Need at least 22 validated 5-minute candles.")
        if (oneMinuteCandles.size < 5) return wait("Waiting for at least 5 validated 1-minute candles for retest confirmation.")
        if (!fiveMinuteCandles.all(::valid) || !oneMinuteCandles.all(::valid) ||
            !chronological(fiveMinuteCandles) || !chronological(oneMinuteCandles)
        ) return wait("Candle data is invalid or out of order.")

        val closes = fiveMinuteCandles.map { it.close }
        val ema9 = TechnicalAnalysis.ema(closes, 9) ?: return wait("EMA 9 is not ready.")
        val ema21 = TechnicalAnalysis.ema(closes, 21) ?: return wait("EMA 21 is not ready.")
        val rsi = TechnicalAnalysis.rsi(closes, 14) ?: return wait("RSI is not ready.")
        val latest = fiveMinuteCandles.last()
        val previous = fiveMinuteCandles[fiveMinuteCandles.lastIndex - 1]
        val vwap = TechnicalAnalysis.sessionVwap(fiveMinuteCandles)
        val support = fiveMinuteCandles.takeLast(20).minOf { it.low }
        val resistance = fiveMinuteCandles.takeLast(20).maxOf { it.high }
        val oneRecent = oneMinuteCandles.takeLast(5)
        val oneLast = oneRecent.last()
        val onePrev = oneRecent[oneRecent.lastIndex - 1]

        var bull = 0
        var bear = 0
        val reasons = mutableListOf<String>()

        when {
            ema9 > ema21 && rsi >= 52.0 -> { bull += 30; reasons += "5M EMA/RSI bullish" }
            ema9 < ema21 && rsi <= 48.0 -> { bear += 30; reasons += "5M EMA/RSI bearish" }
            ema9 > ema21 -> { bull += 15; reasons += "5M EMA trend mildly bullish" }
            ema9 < ema21 -> { bear += 15; reasons += "5M EMA trend mildly bearish" }
        }
        if (vwap != null) {
            if (latest.close > vwap) bull += 10
            if (latest.close < vwap) bear += 10
        }
        when (intelligence?.bias) {
            "BULLISH" -> { bull += 15; reasons += "Market intelligence bullish" }
            "BEARISH" -> { bear += 15; reasons += "Market intelligence bearish" }
        }
        when (intelligence?.supertrend) {
            "BULLISH" -> bull += 5
            "BEARISH" -> bear += 5
        }
        when (intelligence?.macd) {
            "BULLISH" -> bull += 5
            "BEARISH" -> bear += 5
        }
        when {
            latest.close > previous.high && latest.close > latest.open -> { bull += 15; reasons += "5M bullish breakout candle" }
            latest.close < previous.low && latest.close < latest.open -> { bear += 15; reasons += "5M bearish breakdown candle" }
            latest.close > latest.open -> bull += 5
            latest.close < latest.open -> bear += 5
        }
        val range = (resistance - support).coerceAtLeast(latest.close * 0.0001)
        if ((resistance - latest.close) <= range * 0.12) { bear += 8; reasons += "Price near resistance" }
        if ((latest.close - support) <= range * 0.12) { bull += 8; reasons += "Price near support" }

        val bullishRetest = oneLast.close > onePrev.high && oneLast.close > oneLast.open
        val bearishRetest = oneLast.close < onePrev.low && oneLast.close < oneLast.open
        when {
            bullishRetest -> { bull += 20; reasons += "1M bullish retest/rejection confirmed" }
            bearishRetest -> { bear += 20; reasons += "1M bearish retest/rejection confirmed" }
            else -> reasons += "1M retest/rejection not confirmed"
        }

        val direction = when {
            bull >= 65 && bull - bear >= 15 -> NextCandleDirection.GREEN
            bear >= 65 && bear - bull >= 15 -> NextCandleDirection.RED
            else -> NextCandleDirection.WAIT
        }
        val confidence = if (direction == NextCandleDirection.WAIT) null else maxOf(bull, bear).coerceIn(0, 100)
        if (direction == NextCandleDirection.WAIT) reasons += "Signals are mixed or below the confirmation threshold."
        return NextCandlePrediction(direction, confidence, bull, bear, reasons.distinct())
    }

    private fun chronological(candles: List<MarketCandle>) =
        candles.zipWithNext().all { (a, b) -> b.timestampMillis > a.timestampMillis }

    private fun valid(c: MarketCandle): Boolean {
        val values = listOf(c.open, c.high, c.low, c.close, c.volume)
        return values.all { it.isFinite() } && c.open > 0.0 && c.high > 0.0 &&
            c.low > 0.0 && c.close > 0.0 && c.volume >= 0.0 &&
            c.high >= maxOf(c.open, c.close, c.low) &&
            c.low <= minOf(c.open, c.close, c.high)
    }
}
