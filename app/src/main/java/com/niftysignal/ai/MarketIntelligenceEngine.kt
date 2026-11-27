package com.niftysignal.ai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Read-only 5-minute market intelligence. Every output is derived from validated
 * OHLCV candles; volume-dependent confirmation remains unavailable for zero-volume index feeds.
 */
data class MarketIntelligenceSnapshot(
    val bias: String,
    val supertrend: String,
    val macd: String,
    val adx: Double?,
    val volumeStatus: String,
    val breakoutStatus: String,
    val riskInputs: RiskInputs
)

object MarketIntelligenceEngine {
    fun evaluate(candles: List<MarketCandle>): MarketIntelligenceSnapshot? {
        // Produce independent partial studies as soon as their own input history
        // exists. Do not block EMA/RSI/Supertrend behind MACD/ADX warm-up.
        if (candles.size < 15 || !candles.all(::valid) ||
            candles.zipWithNext().any { (a, b) -> b.timestampMillis <= a.timestampMillis }
        ) return null

        val closes = candles.map { it.close }
        val ema9 = TechnicalAnalysis.ema(closes, 9) ?: return null
        val ema21 = TechnicalAnalysis.ema(closes, 21)
        val rsi = TechnicalAnalysis.rsi(closes, 14) ?: return null
        val macd = macdState(closes)
        val adx = adx(candles)
        val supertrend = supertrend(candles)
        val latest = candles.last()
        val bias = when {
            ema21 != null && ema9 > ema21 && rsi >= 50.0 -> "BULLISH"
            ema21 != null && ema9 < ema21 && rsi <= 50.0 -> "BEARISH"
            ema21 == null && latestCloseAboveEma(closes.last(), ema9) && rsi >= 50.0 -> "BULLISH (EARLY)"
            ema21 == null && closes.last() < ema9 && rsi <= 50.0 -> "BEARISH (EARLY)"
            else -> "SIDEWAYS / MIXED"
        }
        val volumeAverage = candles.takeLast(21).dropLast(1).map { it.volume }.average()
        val volumeStatus = if (volumeAverage <= 0.0 || latest.volume <= 0.0) {
            "N/A (index volume 0)"
        } else if (candles.size < 21) {
            "WARMING UP (<21 candles)"
        } else if (latest.volume > volumeAverage) {
            "CONFIRMED"
        } else {
            "WEAK"
        }
        val breakout = if (candles.size < 21) {
            "WARMING UP (<21 candles)"
        } else {
            breakoutStatus(candles)
        }
        val macdText = macd?.let { if (it.first >= it.second) "BULLISH" else "BEARISH" }
            ?: "WARMING UP (<34 candles)"
        val supertrendText = supertrend?.let { if (it) "BULLISH" else "BEARISH" }
            ?: "WARMING UP (<11 candles)"

        val indicatorRisk = (20.0 + max(0.0, abs(rsi - 50.0) - 15.0) * 2.5)
            .coerceIn(0.0, 100.0)
        val emaSpreadPct = if (ema21 == null) 0.0 else abs(ema9 - ema21) / latest.close * 100.0
        val trendRisk = (75.0 - emaSpreadPct * 250.0).coerceIn(25.0, 85.0)
        val averageRangePct = candles.takeLast(14).map { it.high - it.low }.average() /
            latest.close * 100.0
        val priceActionRisk = (averageRangePct * 300.0).coerceIn(10.0, 100.0)
        val momentumAligned = macdText != "WARMING UP (<34 candles)" &&
            ((bias == "BULLISH" && macdText == "BULLISH") ||
                (bias == "BEARISH" && macdText == "BEARISH"))
        val sentimentRisk = when {
            bias == "SIDEWAYS / MIXED" -> 70.0
            momentumAligned -> 30.0
            macd == null -> 50.0
            else -> 80.0
        }
        return MarketIntelligenceSnapshot(
            bias = bias,
            supertrend = supertrendText,
            macd = macdText,
            adx = adx,
            volumeStatus = volumeStatus,
            breakoutStatus = breakout,
            riskInputs = RiskInputs(indicatorRisk, trendRisk, priceActionRisk, sentimentRisk)
        )
    }

    private fun macdState(closes: List<Double>): Pair<Double, Double>? {
        val line = ArrayList<Double>()
        for (i in closes.indices) {
            if (i < 25) continue
            val fast = TechnicalAnalysis.ema(closes.take(i + 1), 12) ?: continue
            val slow = TechnicalAnalysis.ema(closes.take(i + 1), 26) ?: continue
            line.add(fast - slow)
        }
        if (line.size < 9) return null
        val signal = TechnicalAnalysis.ema(line, 9) ?: return null
        return Pair(line.last(), signal)
    }

    private fun adx(candles: List<MarketCandle>, period: Int = 14): Double? {
        if (candles.size < period * 2 + 1) return null
        val tr = ArrayList<Double>()
        val plus = ArrayList<Double>()
        val minus = ArrayList<Double>()
        for (i in 1 until candles.size) {
            val current = candles[i]
            val previous = candles[i - 1]
            tr.add(max(current.high - current.low, max(abs(current.high - previous.close), abs(current.low - previous.close))))
            val up = current.high - previous.high
            val down = previous.low - current.low
            plus.add(if (up > down && up > 0.0) up else 0.0)
            minus.add(if (down > up && down > 0.0) down else 0.0)
        }
        val dx = ArrayList<Double>()
        for (end in period - 1 until tr.size) {
            val start = end - period + 1
            val trSum = tr.subList(start, end + 1).sum()
            if (trSum <= 0.0) continue
            val pdi = plus.subList(start, end + 1).sum() / trSum * 100.0
            val mdi = minus.subList(start, end + 1).sum() / trSum * 100.0
            val denominator = pdi + mdi
            dx.add(if (denominator <= 0.0) 0.0 else abs(pdi - mdi) / denominator * 100.0)
        }
        return if (dx.size < period) null else dx.takeLast(period).average()
    }

    private fun supertrend(candles: List<MarketCandle>, period: Int = 10, multiplier: Double = 3.0): Boolean? {
        if (candles.size <= period) return null
        val ranges = ArrayList<Double>()
        for (i in 1 until candles.size) {
            val c = candles[i]
            val prevClose = candles[i - 1].close
            ranges.add(max(c.high - c.low, max(abs(c.high - prevClose), abs(c.low - prevClose))))
        }
        var finalUpper = 0.0
        var finalLower = 0.0
        var bullish = true
        for (i in period - 1 until ranges.size) {
            val atr = ranges.subList(i - period + 1, i + 1).average()
            val candle = candles[i + 1]
            val midpoint = (candle.high + candle.low) / 2.0
            val basicUpper = midpoint + multiplier * atr
            val basicLower = midpoint - multiplier * atr
            if (i == period - 1) {
                finalUpper = basicUpper
                finalLower = basicLower
                bullish = candle.close >= midpoint
            } else {
                val previousClose = candles[i].close
                finalUpper = if (basicUpper < finalUpper || previousClose > finalUpper) basicUpper else finalUpper
                finalLower = if (basicLower > finalLower || previousClose < finalLower) basicLower else finalLower
                bullish = if (bullish) candle.close >= finalLower else candle.close > finalUpper
            }
        }
        return bullish
    }

    private fun breakoutStatus(candles: List<MarketCandle>): String {
        val previous = candles.dropLast(1).takeLast(20)
        if (previous.isEmpty()) return "N/A"
        val last = candles.last()
        val high = previous.maxOf { it.high }
        val low = previous.minOf { it.low }
        return when {
            last.high > high && last.close <= high -> "POSSIBLE FALSE UPSIDE"
            last.low < low && last.close >= low -> "POSSIBLE FALSE DOWNSIDE"
            last.close > high -> "BULLISH BREAKOUT"
            last.close < low -> "BEARISH BREAKDOWN"
            else -> "NO BREAKOUT"
        }
    }

    private fun valid(c: MarketCandle): Boolean {
        val values = listOf(c.open, c.high, c.low, c.close, c.volume)
        return values.all { it.isFinite() } && c.open > 0.0 && c.high > 0.0 &&
            c.low > 0.0 && c.close > 0.0 && c.volume >= 0.0 &&
            c.high >= max(c.open, max(c.close, c.low)) &&
            c.low <= min(c.open, min(c.close, c.high))
    }
}
