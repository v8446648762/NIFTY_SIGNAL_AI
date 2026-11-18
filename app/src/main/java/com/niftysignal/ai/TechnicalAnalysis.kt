package com.niftysignal.ai

/**
 * Pure technical-analysis calculations for NIFTY candles.
 * Volume-derived VWAP is intentionally unavailable when the index feed reports zero volume.
 */
data class MarketCandle(
    val timestampMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

enum class TradeBias { BUY_CE, BUY_PE, WAIT }

data class TechnicalSnapshot(
    val bias: TradeBias,
    val reason: String,
    val ema9: Double?,
    val ema21: Double?,
    val rsi14: Double?,
    val vwap: Double?,
    val support: Double?,
    val resistance: Double?
)

object TechnicalAnalysis {
    // Full signal confirmation remains deliberately stricter than displaying individual indicators.
    private const val MIN_SIGNAL_CANDLES = 22
    private const val MIN_CONFIRM_CANDLES = 2
    private const val MIN_LEVEL_CANDLES = 5

    fun analyze(
        candles5m: List<MarketCandle>,
        candles1m: List<MarketCandle>
    ): TechnicalSnapshot {
        if (candles5m.isEmpty() || candles1m.isEmpty() ||
            !candles5m.all(::isValid) || !candles1m.all(::isValid) ||
            !isChronological(candles5m) || !isChronological(candles1m)
        ) {
            return waitForData("Invalid, empty, or out-of-order OHLCV candle data.")
        }

        // Calculate each display metric as soon as its own history requirement is met.
        // Do not hide EMA 9 / RSI 14 merely because the stricter signal history is incomplete.
        val closes5m = candles5m.map { it.close }
        val ema9 = ema(closes5m, 9)
        val ema21 = ema(closes5m, 21)
        val rsi14 = rsi(closes5m, 14)
        val vwap = sessionVwap(candles5m)
        val recent = candles5m.takeLast(20)
        val support = recent.minOfOrNull { it.low }.takeIf { candles5m.size >= MIN_LEVEL_CANDLES }
        val resistance = recent.maxOfOrNull { it.high }.takeIf { candles5m.size >= MIN_LEVEL_CANDLES }

        fun snapshot(bias: TradeBias, reason: String) = TechnicalSnapshot(
            bias = bias,
            reason = reason,
            ema9 = ema9,
            ema21 = ema21,
            rsi14 = rsi14,
            vwap = vwap,
            support = support,
            resistance = resistance
        )

        if (candles5m.size < MIN_SIGNAL_CANDLES || candles1m.size < MIN_CONFIRM_CANDLES ||
            ema9 == null || ema21 == null || rsi14 == null
        ) {
            val missing = buildList {
                if (ema9 == null) add("EMA 9 needs 9 completed 5m candles")
                if (rsi14 == null) add("RSI 14 needs 15 completed 5m candles")
                if (ema21 == null) add("EMA 21 needs 21 completed 5m candles")
                if (candles5m.size < MIN_SIGNAL_CANDLES) add("signal confirmation needs 22 completed 5m candles")
                if (candles1m.size < MIN_CONFIRM_CANDLES) add("signal confirmation needs 2 completed 1m candles")
            }
            return snapshot(TradeBias.WAIT, "Indicators are calculated independently. " + missing.joinToString("; ") + ".")
        }

        val last5 = candles5m.last()
        val previous5 = candles5m[candles5m.lastIndex - 1]
        val last1 = candles1m.last()
        val previous1 = candles1m[candles1m.lastIndex - 1]

        // When volume is absent (as in the current NIFTY index response), VWAP is
        // shown as unavailable and is not fabricated. EMA, RSI and price action remain usable.
        val bullish5m = ema9 > ema21 && rsi14 > 55.0 &&
            last5.close > previous5.high &&
            (vwap == null || last5.close > vwap)
        val bearish5m = ema9 < ema21 && rsi14 < 45.0 &&
            last5.close < previous5.low &&
            (vwap == null || last5.close < vwap)

        val bullish1mConfirm = last1.close > previous1.high
        val bearish1mConfirm = last1.close < previous1.low

        val bias = when {
            bullish5m && bullish1mConfirm -> TradeBias.BUY_CE
            bearish5m && bearish1mConfirm -> TradeBias.BUY_PE
            else -> TradeBias.WAIT
        }

        val reason = when (bias) {
            TradeBias.BUY_CE -> if (vwap == null)
                "5m EMA/RSI bullish breakout and 1m confirmation. VWAP unavailable: index volume is zero."
            else "5m bullish conditions and 1m breakout confirmation."
            TradeBias.BUY_PE -> if (vwap == null)
                "5m EMA/RSI bearish breakdown and 1m confirmation. VWAP unavailable: index volume is zero."
            else "5m bearish conditions and 1m breakdown confirmation."
            TradeBias.WAIT -> if (vwap == null)
                "Waiting: 5m EMA/RSI price-action setup and 1m confirmation are not aligned. VWAP unavailable."
            else "Full 5m setup and 1m confirmation are not aligned."
        }

        return snapshot(bias, reason)
    }

    fun ema(values: List<Double>, period: Int): Double? {
        if (period <= 0 || values.size < period || values.any { !it.isFinite() }) return null
        val multiplier = 2.0 / (period + 1.0)
        var result = values.take(period).average()
        for (value in values.drop(period)) result = (value - result) * multiplier + result
        return result
    }

    fun emaSeries(values: List<Double>, period: Int): List<Double?> {
        if (period <= 0) return emptyList()
        return values.indices.map { index ->
            if (index + 1 < period) null else ema(values.take(index + 1), period)
        }
    }

    fun rsi(closes: List<Double>, period: Int = 14): Double? {
        if (period <= 0 || closes.size <= period || closes.any { !it.isFinite() }) return null
        var gains = 0.0
        var losses = 0.0
        for (i in 1..period) {
            val change = closes[i] - closes[i - 1]
            if (change > 0) gains += change else losses -= change
        }
        var averageGain = gains / period
        var averageLoss = losses / period
        for (i in (period + 1) until closes.size) {
            val change = closes[i] - closes[i - 1]
            averageGain = (averageGain * (period - 1) + change.coerceAtLeast(0.0)) / period
            averageLoss = (averageLoss * (period - 1) + (-change).coerceAtLeast(0.0)) / period
        }
        if (averageGain == 0.0 && averageLoss == 0.0) return 50.0
        if (averageLoss == 0.0) return 100.0
        if (averageGain == 0.0) return 0.0
        val rs = averageGain / averageLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    fun sessionVwap(candles: List<MarketCandle>): Double? {
        if (candles.isEmpty() || candles.any { !it.volume.isFinite() || it.volume < 0.0 }) return null
        val totalVolume = candles.sumOf { it.volume }
        if (totalVolume <= 0.0) return null
        val totalPriceVolume = candles.sumOf {
            ((it.high + it.low + it.close) / 3.0) * it.volume
        }
        return totalPriceVolume / totalVolume
    }

    private fun isChronological(candles: List<MarketCandle>): Boolean =
        candles.zipWithNext().all { (a, b) -> b.timestampMillis > a.timestampMillis }

    private fun isValid(candle: MarketCandle): Boolean {
        val values = listOf(candle.open, candle.high, candle.low, candle.close, candle.volume)
        return values.all { it.isFinite() } &&
            candle.open > 0.0 && candle.high > 0.0 &&
            candle.low > 0.0 && candle.close > 0.0 &&
            candle.volume >= 0.0 &&
            candle.high >= maxOf(candle.open, candle.close, candle.low) &&
            candle.low <= minOf(candle.open, candle.close, candle.high)
    }

    private fun waitForData(message: String) = TechnicalSnapshot(
        bias = TradeBias.WAIT,
        reason = message,
        ema9 = null,
        ema21 = null,
        rsi14 = null,
        vwap = null,
        support = null,
        resistance = null
    )
}
