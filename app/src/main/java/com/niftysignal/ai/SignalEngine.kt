package com.niftysignal.ai

/**
 * Strategy calculations are deliberately independent from any broker/feed.
 * A future Upstox adapter can supply validated candles without changing this engine.
 */
data class IndicatorSnapshot(
    val vwap: Double?,
    val ema9: Double?,
    val ema21: Double?,
    val rsi14: Double?
)

data class SignalDecision(
    val bias: TradeBias,
    val trend: String,
    val reasons: List<String>,
    val indicators: IndicatorSnapshot,
    val setupScore: Int
)

object SignalEngine {
    fun calculate(candles: List<MarketCandle>): IndicatorSnapshot {
        if (candles.isEmpty()) return IndicatorSnapshot(null, null, null, null)
        val totalVolume = candles.sumOf { it.volume.coerceAtLeast(0.0) }
        val vwap = if (totalVolume > 0.0) {
            candles.sumOf { ((it.high + it.low + it.close) / 3.0) * it.volume.coerceAtLeast(0.0) } / totalVolume
        } else null
        return IndicatorSnapshot(
            vwap = vwap,
            ema9 = ema(candles.map { it.close }, 9),
            ema21 = ema(candles.map { it.close }, 21),
            rsi14 = rsi(candles.map { it.close }, 14)
        )
    }

    fun evaluate(
        fiveMinuteCandles: List<MarketCandle>,
        oneMinuteCandles: List<MarketCandle>
    ): SignalDecision {
        val indicators = calculate(fiveMinuteCandles)
        val latest = fiveMinuteCandles.lastOrNull()
        val confirm = oneMinuteCandles.takeLast(3)
        if (latest == null || indicators.vwap == null || indicators.ema9 == null ||
            indicators.ema21 == null || indicators.rsi14 == null || confirm.size < 3
        ) {
            return SignalDecision(
                TradeBias.WAIT, "INSUFFICIENT DATA",
                listOf("Need sufficient 5-minute history and 1-minute confirmation candles."),
                indicators, 0
            )
        }

        val priceAboveVwap = latest.close > indicators.vwap
        val priceBelowVwap = latest.close < indicators.vwap
        val bullishEma = indicators.ema9 > indicators.ema21
        val bearishEma = indicators.ema9 < indicators.ema21
        val bullishRsi = indicators.rsi14 > 55.0
        val bearishRsi = indicators.rsi14 < 45.0
        val oneMinBullish = confirm.last().close > confirm.last().open &&
            confirm.last().close > confirm.first().close
        val oneMinBearish = confirm.last().close < confirm.last().open &&
            confirm.last().close < confirm.first().close

        val bullChecks = listOf(priceAboveVwap, bullishEma, bullishRsi, oneMinBullish)
        val bearChecks = listOf(priceBelowVwap, bearishEma, bearishRsi, oneMinBearish)
        val bullScore = bullChecks.count { it } * 25
        val bearScore = bearChecks.count { it } * 25

        val reasons = mutableListOf<String>()
        val bias = when {
            bullChecks.all { it } -> {
                reasons += listOf("Price above VWAP", "EMA 9 above EMA 21", "RSI above 55", "1-minute bullish confirmation")
                TradeBias.BUY_CE
            }
            bearChecks.all { it } -> {
                reasons += listOf("Price below VWAP", "EMA 9 below EMA 21", "RSI below 45", "1-minute bearish confirmation")
                TradeBias.BUY_PE
            }
            else -> {
                if (priceAboveVwap) reasons += "Price above VWAP"
                if (priceBelowVwap) reasons += "Price below VWAP"
                if (bullishEma) reasons += "EMA 9 above EMA 21"
                if (bearishEma) reasons += "EMA 9 below EMA 21"
                if (bullishRsi) reasons += "RSI above 55"
                if (bearishRsi) reasons += "RSI below 45"
                reasons += "Conditions are mixed; wait for confirmation"
                TradeBias.WAIT
            }
        }
        return SignalDecision(
            bias,
            when { bullishEma && priceAboveVwap -> "BULLISH"; bearishEma && priceBelowVwap -> "BEARISH"; else -> "SIDEWAYS / MIXED" },
            reasons,
            indicators,
            maxOf(bullScore, bearScore)
        )
    }

    private fun ema(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        val multiplier = 2.0 / (period + 1.0)
        var result = values.take(period).average()
        for (value in values.drop(period)) result = (value * multiplier) + (result * (1.0 - multiplier))
        return result
    }

    private fun rsi(values: List<Double>, period: Int): Double? {
        if (values.size <= period) return null
        var gains = 0.0
        var losses = 0.0
        for (i in 1..period) {
            val change = values[i] - values[i - 1]
            if (change >= 0) gains += change else losses -= change
        }
        var avgGain = gains / period
        var avgLoss = losses / period
        for (i in (period + 1) until values.size) {
            val change = values[i] - values[i - 1]
            avgGain = (avgGain * (period - 1) + maxOf(change, 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + maxOf(-change, 0.0)) / period
        }
        if (avgLoss == 0.0) return if (avgGain == 0.0) 50.0 else 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }
}

/** Manual, offline paper-trade record. Quantity is user-entered units, never auto-sized. */
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
        require(entryPremium > 0.0)
        require(quantity > 0)
        require(exitPremium == null || exitPremium > 0.0)
    }

    val isOpen: Boolean get() = exitPremium == null
    val pointsPnl: Double? get() = exitPremium?.minus(entryPremium)
    val grossPnl: Double? get() = pointsPnl?.times(quantity)
}
