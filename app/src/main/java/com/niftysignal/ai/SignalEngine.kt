package com.niftysignal.ai

/**
 * Dashboard-facing signal engine. It has no dependency on a broker or network.
 * All decisions reuse the validated candle rules in TechnicalAnalysis.
 */
data class IndicatorSnapshot(
    val vwap: Double?,
    val ema9: Double?,
    val ema21: Double?,
    val rsi14: Double?,
    val support: Double?,
    val resistance: Double?
)

data class SignalDecision(
    val bias: TradeBias,
    val trend: String,
    val reasons: List<String>,
    val indicators: IndicatorSnapshot,
    val confirmed: Boolean
)

object SignalEngine {
    fun calculate(candles: List<MarketCandle>): IndicatorSnapshot {
        if (candles.isEmpty() || candles.any { !isValid(it) }) {
            return IndicatorSnapshot(null, null, null, null, null, null)
        }
        val closes = candles.map { it.close }
        val recent = candles.takeLast(20)
        return IndicatorSnapshot(
            vwap = TechnicalAnalysis.sessionVwap(candles),
            ema9 = TechnicalAnalysis.ema(closes, 9),
            ema21 = TechnicalAnalysis.ema(closes, 21),
            rsi14 = TechnicalAnalysis.rsi(closes, 14),
            support = recent.minOfOrNull { it.low },
            resistance = recent.maxOfOrNull { it.high }
        )
    }

    fun evaluate(
        fiveMinuteCandles: List<MarketCandle>,
        oneMinuteCandles: List<MarketCandle>
    ): SignalDecision {
        val technical = TechnicalAnalysis.analyze(fiveMinuteCandles, oneMinuteCandles)
        val indicators = IndicatorSnapshot(
            vwap = technical.vwap,
            ema9 = technical.ema9,
            ema21 = technical.ema21,
            rsi14 = technical.rsi14,
            support = technical.support,
            resistance = technical.resistance
        )
        val latest = fiveMinuteCandles.lastOrNull()
        val trendBase = when {
            latest == null || indicators.ema9 == null ||
                indicators.ema21 == null || indicators.rsi14 == null -> "INSUFFICIENT DATA"
            indicators.ema9 > indicators.ema21 &&
                (indicators.vwap == null || latest.close > indicators.vwap) -> "BULLISH"
            indicators.ema9 < indicators.ema21 &&
                (indicators.vwap == null || latest.close < indicators.vwap) -> "BEARISH"
            else -> "SIDEWAYS / MIXED"
        }
        val trend = if (trendBase != "INSUFFICIENT DATA" && indicators.vwap == null) {
            "$trendBase • VWAP N/A"
        } else trendBase
        return SignalDecision(
            bias = technical.bias,
            trend = trend,
            reasons = listOf(technical.reason),
            indicators = indicators,
            confirmed = technical.bias != TradeBias.WAIT
        )
    }

    private fun isValid(candle: MarketCandle): Boolean {
        val values = listOf(
            candle.open, candle.high, candle.low, candle.close, candle.volume
        )
        return values.all { it.isFinite() } &&
            candle.open > 0.0 && candle.high > 0.0 &&
            candle.low > 0.0 && candle.close > 0.0 &&
            candle.volume >= 0.0 &&
            candle.high >= maxOf(candle.open, candle.close, candle.low) &&
            candle.low <= minOf(candle.open, candle.close, candle.high)
    }
}

/** Manual, offline paper-trade record. Quantity is user-entered units only. */
data class PaperTrade(
    val side: TradeBias,
    val entryPremium: Double,
    val quantity: Int,
    val openedAtMillis: Long,
    val exitPremium: Double? = null,
    val closedAtMillis: Long? = null
) {
    init {
        require(side == TradeBias.BUY_CE || side == TradeBias.BUY_PE)
        require(entryPremium.isFinite() && entryPremium > 0.0)
        require(quantity > 0)
        require(exitPremium == null || (exitPremium.isFinite() && exitPremium > 0.0))
    }

    val isOpen: Boolean get() = exitPremium == null
    val pointsPnl: Double? get() = exitPremium?.minus(entryPremium)
    val grossPnl: Double? get() = pointsPnl?.times(quantity)
}
