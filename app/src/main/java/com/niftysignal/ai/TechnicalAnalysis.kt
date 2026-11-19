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
    private const val MIN_PRIMARY_CANDLES = 22
    private const val MIN_CONFIRM_CANDLES = 2

    fun analyze(
        candles5m: List<MarketCandle>,
        candles1m: List<MarketCandle>
    ): TechnicalSnapshot {
        if (!candles5m.all(::isValid) || !isChronological(candles5m) ||
            (candles1m.isNotEmpty() &&
                (!candles1m.all(::isValid) || !isChronological(candles1m)))
        ) {
            return waitForData("Invalid or out-of-order OHLCV candle data.")
        }
        if (candles5m.isEmpty()) {
            return waitForData("Waiting for five-minute candles.")
        }

        val closes5m = candles5m.map { it.close }
        val ema9 = ema(closes5m, 9)
        val ema21 = ema(closes5m, 21)
        val rsi14 = rsi(closes5m, 14)
        val vwap = sessionVwap(candles5m)
        val recent = candles5m.takeLast(20)
        if (candles5m.size < MIN_PRIMARY_CANDLES) {
            return TechnicalSnapshot(
                bias = TradeBias.WAIT,
                reason = "Indicators update as candles become available; a trade setup needs at least 22 five-minute candles.",
                ema9 = ema9,
                ema21 = ema21,
                rsi14 = rsi14,
                vwap = vwap,
                support = recent.minOfOrNull { it.low },
                resistance = recent.maxOfOrNull { it.high }
            )
        }
        if (ema9 == null || ema21 == null || rsi14 == null) {
            return waitForData("EMA / RSI indicator data is not ready.")
        }

        val last5 = candles5m.last()
        val previous5 = candles5m[candles5m.lastIndex - 1]
        // 5m indicators are calculated independently; 1m candles are only for confirmation.
        val bullish5m = ema9 > ema21 && rsi14 > 55.0 &&
            last5.close > previous5.high &&
            (vwap == null || last5.close > vwap)
        val bearish5m = ema9 < ema21 && rsi14 < 45.0 &&
            last5.close < previous5.low &&
            (vwap == null || last5.close < vwap)

        val confirmationAvailable = candles1m.size >= MIN_CONFIRM_CANDLES
        val bullish1mConfirm = confirmationAvailable &&
            candles1m.last().close > candles1m[candles1m.lastIndex - 1].high
        val bearish1mConfirm = confirmationAvailable &&
            candles1m.last().close < candles1m[candles1m.lastIndex - 1].low

        val bias = when {
            bullish5m && bullish1mConfirm -> TradeBias.BUY_CE
            bearish5m && bearish1mConfirm -> TradeBias.BUY_PE
            else -> TradeBias.WAIT
        }

        val reason = when {
            !confirmationAvailable ->
                "5m indicators are available; waiting for at least 2 completed 1m candles for confirmation."
            bias == TradeBias.BUY_CE && vwap == null ->
                "5m EMA/RSI bullish breakout and 1m confirmation. VWAP unavailable: index volume is zero."
            bias == TradeBias.BUY_CE -> "5m bullish conditions and 1m breakout confirmation."
            bias == TradeBias.BUY_PE && vwap == null ->
                "5m EMA/RSI bearish breakdown and 1m confirmation. VWAP unavailable: index volume is zero."
            bias == TradeBias.BUY_PE -> "5m bearish conditions and 1m breakdown confirmation."
            vwap == null ->
                "Waiting: 5m EMA/RSI price-action setup and 1m confirmation are not aligned. VWAP unavailable."
            else -> "Full 5m setup and 1m confirmation are not aligned."
        }

        return TechnicalSnapshot(
            bias = bias,
            reason = reason,
            ema9 = ema9,
            ema21 = ema21,
            rsi14 = rsi14,
            vwap = vwap,
            support = recent.minOfOrNull { it.low },
            resistance = recent.maxOfOrNull { it.high }
        )
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
