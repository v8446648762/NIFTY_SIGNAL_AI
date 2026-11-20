package com.niftysignal.ai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Smart AI analyser: combines its own 5m/1m candle calculations with
 * validated live option-chain metrics supplied by the caller.
 */
data class SmartAnalyserCandle(
    val timestampMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0
)

enum class SmartAnalyserBias { BULLISH, BEARISH, SIDEWAYS, WAIT }

data class SmartAnalyserResult(
    val bias: SmartAnalyserBias,
    val confidencePercent: Int,
    val ema9: Double?,
    val ema21: Double?,
    val rsi14: Double?,
    val vwap: Double?,
    val support: Double?,
    val resistance: Double?,
    val oneMinuteConfirmation: String,
    val explanation: String,
    val warnings: List<String>
)

object SmartAiOptionChainAnalyser {
    fun analyse(
        fiveMinute: List<SmartAnalyserCandle>,
        oneMinute: List<SmartAnalyserCandle>,
        optionChain: OptionChainMetrics? = null
    ): SmartAnalyserResult {
        val warnings = mutableListOf<String>()
        if (fiveMinute.size < 22 || oneMinute.size < 2) {
            return waitResult("Insufficient candles: need at least 22 five-minute and 2 one-minute candles.")
        }
        if (!validSeries(fiveMinute) || !validSeries(oneMinute)) {
            return waitResult("Invalid or out-of-order OHLCV data.")
        }
        val closes = fiveMinute.map { it.close }
        val ema9 = ema(closes, 9) ?: return waitResult("EMA data is not ready.")
        val ema21 = ema(closes, 21) ?: return waitResult("EMA data is not ready.")
        val rsi = rsi(closes, 14) ?: return waitResult("RSI data is not ready.")
        val vwap = sessionVwap(fiveMinute)
        if (vwap == null) warnings += "VWAP unavailable because this feed has no usable volume."

        val last = fiveMinute.last()
        val prev = fiveMinute[fiveMinute.lastIndex - 1]
        val bull5 = ema9 > ema21 && rsi >= 55.0 && (vwap == null || last.close > vwap) && last.close > prev.high
        val bear5 = ema9 < ema21 && rsi <= 45.0 && (vwap == null || last.close < vwap) && last.close < prev.low
        val last1 = oneMinute.last()
        val prev1 = oneMinute[oneMinute.lastIndex - 1]
        val confirm = when {
            last1.close > prev1.high -> "1m bullish breakout confirmation"
            last1.close < prev1.low -> "1m bearish breakdown confirmation"
            else -> "No 1m breakout confirmation"
        }
        val pcr = optionChain?.pcr
        val bias = when {
            bull5 && last1.close > prev1.high && pcr != null && pcr >= 1.10 -> SmartAnalyserBias.BULLISH
            bear5 && last1.close < prev1.low && pcr != null && pcr <= 0.90 -> SmartAnalyserBias.BEARISH
            pcr == null -> SmartAnalyserBias.WAIT
            else -> SmartAnalyserBias.SIDEWAYS
        }
        if (optionChain == null || pcr == null) {
            warnings += "Live option-chain PCR is unavailable; directional confirmation is withheld."
        } else if (pcr in 0.90..1.10) {
            warnings += "PCR is neutral; option-chain direction is not confirmed."
        }
        val recent = fiveMinute.takeLast(20)
        val alignment = when (bias) {
            SmartAnalyserBias.BULLISH -> 4
            SmartAnalyserBias.BEARISH -> 4
            SmartAnalyserBias.SIDEWAYS -> 1
            SmartAnalyserBias.WAIT -> 0
        }
        val confidence = ((alignment * 20) + (abs(rsi - 50.0).coerceAtMost(20.0))).toInt().coerceIn(0, 95)
        val explanation = when (bias) {
            SmartAnalyserBias.BULLISH -> "5m bullish structure, 1m breakout and PCR bullish confirmation. Analysis only; not an order."
            SmartAnalyserBias.BEARISH -> "5m bearish structure, 1m breakdown and PCR bearish confirmation. Analysis only; not an order."
            SmartAnalyserBias.WAIT -> "Live option-chain confirmation is missing; no directional result is issued."
            SmartAnalyserBias.SIDEWAYS -> "5m conditions, 1m confirmation and option-chain PCR are not aligned; wait for a clearer setup."
        }
        return SmartAnalyserResult(
            bias, confidence, ema9, ema21, rsi, vwap,
            recent.minOfOrNull { it.low }, recent.maxOfOrNull { it.high },
            confirm, explanation, warnings
        )
    }

    private fun validSeries(candles: List<SmartAnalyserCandle>): Boolean =
        candles.all { c ->
            val values = listOf(c.open, c.high, c.low, c.close, c.volume)
            values.all { it.isFinite() } && c.open > 0.0 && c.low > 0.0 &&
                c.high >= max(c.open, max(c.close, c.low)) &&
                c.low <= min(c.open, min(c.close, c.high)) && c.volume >= 0.0
        } && candles.zipWithNext().all { (a, b) -> b.timestampMillis > a.timestampMillis }

    private fun ema(values: List<Double>, period: Int): Double? {
        if (period <= 0 || values.size < period) return null
        val alpha = 2.0 / (period + 1.0)
        var result = values.take(period).average()
        for (value in values.drop(period)) result += alpha * (value - result)
        return result.takeIf { it.isFinite() }
    }

    private fun rsi(values: List<Double>, period: Int): Double? {
        if (values.size <= period) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val change = values[i] - values[i - 1]
            if (change > 0) gain += change else loss -= change
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        for (i in period + 1 until values.size) {
            val change = values[i] - values[i - 1]
            avgGain = (avgGain * (period - 1) + change.coerceAtLeast(0.0)) / period
            avgLoss = (avgLoss * (period - 1) + (-change).coerceAtLeast(0.0)) / period
        }
        return when {
            avgGain == 0.0 && avgLoss == 0.0 -> 50.0
            avgLoss == 0.0 -> 100.0
            avgGain == 0.0 -> 0.0
            else -> 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
        }
    }

    private fun sessionVwap(candles: List<SmartAnalyserCandle>): Double? {
        val volume = candles.sumOf { it.volume }
        if (!volume.isFinite() || volume <= 0.0) return null
        return candles.sumOf { ((it.high + it.low + it.close) / 3.0) * it.volume } / volume
    }

    private fun waitResult(message: String) = SmartAnalyserResult(
        SmartAnalyserBias.WAIT, 0, null, null, null, null, null, null,
        "Not evaluated", message, listOf(message)
    )
}
