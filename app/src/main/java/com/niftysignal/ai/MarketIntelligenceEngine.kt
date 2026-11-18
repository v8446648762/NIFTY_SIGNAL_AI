package com.niftysignal.ai

/**
 * Transparent intelligence derived only from validated 5-minute OHLCV candles.
 * Price/volume bias is a technical proxy, not news or external market sentiment.
 */
data class MarketIntelligenceSnapshot(
    val marketBias: String,
    val volumeConfirmed: Boolean,
    val falseBreakout: Boolean,
    val trendStrengthLabel: String,
    val priceVolumeBias: String,
    val riskInputs: RiskInputs
)

object MarketIntelligenceEngine {
    fun evaluate(
        candles: List<MarketCandle>,
        decision: SignalDecision
    ): MarketIntelligenceSnapshot? {
        if (candles.size < 22 || candles.any { !valid(it) }) return null
        val indicators = decision.indicators
        val rsi = indicators.rsi14 ?: return null
        val ema9 = indicators.ema9 ?: return null
        val ema21 = indicators.ema21 ?: return null
        val vwap = indicators.vwap ?: return null
        val last = candles.last()
        val prior = candles[candles.lastIndex - 1]
        val previousWindow = candles.takeLast(21).dropLast(1)
        val averageVolume = previousWindow.map { it.volume }.average()
        val volumeConfirmed = averageVolume > 0.0 && last.volume >= averageVolume
        val falseBreakout =
            (last.high > prior.high && last.close <= prior.high) ||
            (last.low < prior.low && last.close >= prior.low)

        val spreadBps = kotlin.math.abs(ema9 - ema21) / last.close * 10_000.0
        val strength = when {
            spreadBps < 3.0 -> "WEAK"
            spreadBps < 8.0 -> "MODERATE"
            else -> "STRONG"
        }
        val signedVolume = candles.takeLast(10).sumOf { candle ->
            val range = candle.high - candle.low
            if (range <= 0.0) 0.0
            else ((candle.close - candle.open) / range).coerceIn(-1.0, 1.0) * candle.volume
        }
        val totalVolume = candles.takeLast(10).sumOf { it.volume }
        if (totalVolume <= 0.0) return null
        val pressure = (signedVolume / totalVolume).coerceIn(-1.0, 1.0)
        val priceVolumeBias = when {
            pressure > 0.20 -> "BULLISH"
            pressure < -0.20 -> "BEARISH"
            else -> "NEUTRAL"
        }
        val marketBias = when (decision.trend) {
            "BULLISH" -> "BULLISH • UPSIDE TREND"
            "BEARISH" -> "BEARISH • DOWNSIDE TREND"
            "SIDEWAYS / MIXED" -> "SIDEWAYS / MIXED"
            else -> "INSUFFICIENT INDICATOR DATA"
        }

        val indicatorRisk = (30.0 + kotlin.math.abs(rsi - 50.0) * 1.2)
            .coerceIn(0.0, 100.0)
        val trendAligned = (last.close > vwap && ema9 > ema21) ||
            (last.close < vwap && ema9 < ema21)
        val trendRisk = when {
            !trendAligned -> 75.0
            strength == "STRONG" -> 20.0
            strength == "MODERATE" -> 35.0
            else -> 55.0
        }
        val candleRange = (last.high - last.low).coerceAtLeast(0.000001)
        val bodyRatio = (kotlin.math.abs(last.close - last.open) / candleRange)
            .coerceIn(0.0, 1.0)
        val priceActionRisk = (
            65.0 - bodyRatio * 35.0 +
                (if (falseBreakout) 25.0 else 0.0) +
                (if (!volumeConfirmed) 10.0 else 0.0)
            ).coerceIn(0.0, 100.0)
        val sentimentRisk = (50.0 - kotlin.math.abs(pressure) * 30.0)
            .coerceIn(0.0, 100.0)

        return MarketIntelligenceSnapshot(
            marketBias = marketBias,
            volumeConfirmed = volumeConfirmed,
            falseBreakout = falseBreakout,
            trendStrengthLabel = strength,
            priceVolumeBias = priceVolumeBias,
            riskInputs = RiskInputs(indicatorRisk, trendRisk, priceActionRisk, sentimentRisk)
        )
    }

    private fun valid(candle: MarketCandle): Boolean {
        val values = listOf(candle.open, candle.high, candle.low, candle.close, candle.volume)
        return values.all { it.isFinite() } &&
            candle.open > 0.0 && candle.high >= maxOf(candle.open, candle.close, candle.low) &&
            candle.low <= minOf(candle.open, candle.close, candle.high) && candle.volume >= 0.0
    }
}
