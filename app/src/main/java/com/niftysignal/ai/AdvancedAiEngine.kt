package com.niftysignal.ai

/**
 * Explainable, deterministic Advanced AI v1.
 *
 * This is a rule-based scoring engine, not a trained machine-learning model.
 * It consumes caller-supplied, validated market snapshots only; it has no
 * network, broker, credential, or order-placement capability.
 */
data class OptionChainSnapshot(
    val analysis: OptionChainAnalysis,
    val capturedAtMillis: Long,
    val underlyingPrice: Double
)

data class AdvancedSignalResult(
    val bias: TradeBias,
    val bullishScore: Int,
    val bearishScore: Int,
    val confidenceLabel: String,
    val trend: String,
    val reasons: List<String>,
    val warnings: List<String>,
    val support: Double?,
    val resistance: Double?,
    val optionChainPcr: Double?
)

object AdvancedAiEngine {
    private const val MIN_5M_CANDLES = 22
    private const val MIN_1M_CANDLES = 2
    private const val MAX_5M_AGE_MS = 10 * 60 * 1000L
    private const val MAX_1M_AGE_MS = 2 * 60 * 1000L
    private const val MAX_OPTION_CHAIN_AGE_MS = 5 * 60 * 1000L

    fun evaluate(
        candles5m: List<MarketCandle>,
        candles1m: List<MarketCandle>,
        optionChain: OptionChainSnapshot?,
        nowMillis: Long
    ): AdvancedSignalResult {
        val warnings = mutableListOf<String>()
        if (candles5m.size < MIN_5M_CANDLES || candles1m.size < MIN_1M_CANDLES) {
            return waitResult("Insufficient candle history.", listOf("Need at least 22 five-minute and 2 one-minute candles."))
        }
        if (!candles5m.all(::validCandle) || !candles1m.all(::validCandle) ||
            !strictlyIncreasing(candles5m) || !strictlyIncreasing(candles1m)
        ) {
            return waitResult("Invalid or out-of-order candle data.", listOf("OHLCV values and candle timestamps must be valid and chronological."))
        }
        if (!fresh(candles5m.last().timestampMillis, nowMillis, MAX_5M_AGE_MS) ||
            !fresh(candles1m.last().timestampMillis, nowMillis, MAX_1M_AGE_MS)
        ) {
            return waitResult("Market candles are stale.", listOf("Latest 5m or 1m candle is outside the allowed freshness window."))
        }

        val technical = TechnicalAnalysis.analyze(candles5m, candles1m)
        val ema9 = technical.ema9 ?: return waitResult("Indicators are not ready.", listOf(technical.reason))
        val ema21 = technical.ema21 ?: return waitResult("Indicators are not ready.", listOf(technical.reason))
        val rsi = technical.rsi14 ?: return waitResult("RSI is not ready.", listOf(technical.reason))
        val vwap = technical.vwap ?: return waitResult("VWAP is not ready.", listOf(technical.reason))

        if (optionChain == null) {
            return waitResult(
                "Option-chain confirmation is required for Advanced AI v1.",
                listOf("Import a timestamped option-chain snapshot with a verified underlying price.")
            ).copy(support = technical.support, resistance = technical.resistance)
        }
        val pcr = optionChain.analysis.pcr
        if (!optionChain.underlyingPrice.isFinite() || optionChain.underlyingPrice <= 0.0 ||
            pcr == null || !pcr.isFinite() || pcr <= 0.0 ||
            !fresh(optionChain.capturedAtMillis, nowMillis, MAX_OPTION_CHAIN_AGE_MS)
        ) {
            return waitResult("Option-chain snapshot is invalid or stale.", listOf("A recent timestamp, valid PCR and verified underlying price are required."))
                .copy(support = technical.support, resistance = technical.resistance)
        }

        val last5 = candles5m.last()
        val previous5 = candles5m[candles5m.lastIndex - 1]
        val last1 = candles1m.last()
        val previous1 = candles1m[candles1m.lastIndex - 1]

        val bullishTrend = last5.close > vwap && ema9 > ema21
        val bearishTrend = last5.close < vwap && ema9 < ema21
        val bullishMomentum = rsi in 55.0..70.0
        val bearishMomentum = rsi in 30.0..45.0
        val bullishStructure = last5.close > previous5.high && last1.close > previous1.high
        val bearishStructure = last5.close < previous5.low && last1.close < previous1.low
        val bullishOi = pcr >= 1.10
        val bearishOi = pcr <= 0.90

        var bull = 0
        var bear = 0
        if (bullishTrend) bull += 35
        if (bearishTrend) bear += 35
        if (bullishMomentum) bull += 20
        if (bearishMomentum) bear += 20
        if (bullishStructure) bull += 25
        if (bearishStructure) bear += 25
        if (bullishOi) bull += 20
        if (bearishOi) bear += 20
        if (!bullishOi && !bearishOi) {
            bull += 10
            bear += 10
            warnings += "PCR is neutral; option-chain direction is not confirmed."
        }

        val bias = when {
            bull >= 70 && bull - bear >= 15 -> TradeBias.BUY_CE
            bear >= 70 && bear - bull >= 15 -> TradeBias.BUY_PE
            else -> TradeBias.WAIT
        }
        val reasons = mutableListOf<String>()
        reasons += when {
            bullishTrend -> "5m price is above VWAP and EMA 9 is above EMA 21."
            bearishTrend -> "5m price is below VWAP and EMA 9 is below EMA 21."
            else -> "Trend indicators are mixed."
        }
        reasons += when {
            bullishMomentum -> "RSI supports bullish momentum without an overbought reading."
            bearishMomentum -> "RSI supports bearish momentum without an oversold reading."
            else -> "RSI momentum confirmation is absent."
        }
        reasons += when {
            bullishStructure -> "5m breakout and 1m confirmation are aligned bullish."
            bearishStructure -> "5m breakdown and 1m confirmation are aligned bearish."
            else -> "5m breakout/breakdown and 1m confirmation are not aligned."
        }
        reasons += when {
            bullishOi -> "PCR is at or above the bullish confirmation threshold."
            bearishOi -> "PCR is at or below the bearish confirmation threshold."
            else -> "PCR is in the neutral range."
        }
        if (bias == TradeBias.WAIT) reasons += "Required score and directional separation are not met; no trade signal."
        val selected = maxOf(bull, bear)
        val confidence = when {
            bias == TradeBias.WAIT -> "NO SIGNAL"
            selected >= 85 -> "HIGH SCORE"
            else -> "QUALIFIED SCORE"
        }
        val trend = when {
            bullishTrend -> "BULLISH"
            bearishTrend -> "BEARISH"
            else -> "SIDEWAYS / MIXED"
        }
        return AdvancedSignalResult(
            bias, bull, bear, confidence, trend, reasons, warnings,
            technical.support, technical.resistance, pcr
        )
    }

    private fun waitResult(reason: String, warnings: List<String>) = AdvancedSignalResult(
        TradeBias.WAIT, 0, 0, "NO SIGNAL", "INSUFFICIENT / UNVERIFIED DATA",
        listOf(reason), warnings, null, null, null
    )

    private fun fresh(timestamp: Long, now: Long, maxAge: Long): Boolean =
        timestamp > 0L && timestamp <= now && now - timestamp <= maxAge

    private fun strictlyIncreasing(candles: List<MarketCandle>): Boolean =
        candles.zipWithNext().all { (a, b) -> b.timestampMillis > a.timestampMillis }

    private fun validCandle(c: MarketCandle): Boolean {
        val values = listOf(c.open, c.high, c.low, c.close, c.volume)
        return c.timestampMillis > 0L && values.all { it.isFinite() } &&
            c.open > 0.0 && c.high > 0.0 && c.low > 0.0 && c.close > 0.0 &&
            c.volume >= 0.0 && c.high >= maxOf(c.open, c.close, c.low) &&
            c.low <= minOf(c.open, c.close, c.high)
    }
}
