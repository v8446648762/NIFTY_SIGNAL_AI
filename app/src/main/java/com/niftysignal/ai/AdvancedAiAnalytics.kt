package com.niftysignal.ai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class AiDetection(val name: String, val score: Int?, val detail: String) {
    val passed: Boolean get() = score != null && score >= 60
    val failed: Boolean get() = score != null && score < 60
}

data class AiPerformanceReport(
    val capturedAtMillis: Long,
    val detections: List<AiDetection>
) {
    val availableCount: Int get() = detections.count { it.score != null }
    val passedCount: Int get() = detections.count { it.passed }
    val failedCount: Int get() = detections.count { it.failed }
    val passPercent: Int get() = if (availableCount == 0) 0 else passedCount * 100 / availableCount
}

/**
 * Explainable local NIFTY analysis only. No network, broker, credentials or order APIs.
 * Scores are heuristic setup-strength scores, not probabilities or proven win rates.
 */
object AdvancedAiAnalytics {
    private val detectionNames = listOf(
        "Chart pattern detection",
        "Auto support & resistance",
        "Smart money / volume spike",
        "Option chain & OI analysis",
        "Fakeout detection",
        "RSI divergence",
        "Market sentiment & news",
        "AI risk & volatility",
        "Candlestick & scalping setup",
        "AI master signal & market bias"
    )

    fun unavailable(now: Long, reason: String): AiPerformanceReport =
        AiPerformanceReport(now, detectionNames.map { AiDetection(it, null, reason) })

    fun evaluate(
        candles5m: List<MarketCandle>,
        candles1m: List<MarketCandle>,
        now: Long,
        optionChain: OptionChainSnapshot? = null
    ): AiPerformanceReport {
        val fiveValid = candles5m.isNotEmpty() &&
            candles5m.all(::validCandle) &&
            candles5m.zipWithNext().all { (a, b) -> b.timestampMillis > a.timestampMillis }
        val oneValid = candles1m.isEmpty() ||
            (candles1m.all(::validCandle) &&
                candles1m.zipWithNext().all { (a, b) -> b.timestampMillis > a.timestampMillis })
        val fiveFresh = fiveValid && candles5m.last().timestampMillis <= now &&
            now - candles5m.last().timestampMillis <= 10 * 60_000L
        val oneFresh = candles1m.isNotEmpty() && oneValid &&
            candles1m.last().timestampMillis <= now &&
            now - candles1m.last().timestampMillis <= 2 * 60_000L

        val detections = mutableListOf<AiDetection>()
        if (!fiveFresh) {
            val reason = if (!fiveValid) "Validated 5m candle data is not available."
                else "Latest completed 5m candle is outside the freshness window."
            detectionNames.filterNot { it == "Option chain & OI analysis" || it == "Market sentiment & news" }
                .forEach { detections += AiDetection(it, null, reason) }
        } else {
            val c = candles5m
            val last = c.last()
            val closes = c.map { it.close }

            // Pattern and levels can provide preliminary analysis from a smaller
            // history window; the master signal keeps its stricter 22-candle rule.
            if (c.size >= 5) {
                val prior = c.dropLast(1).takeLast(20)
                val resistance = prior.maxOf { it.high }
                val support = prior.minOf { it.low }
                val patternScore = when {
                    last.close > resistance -> 82
                    last.close < support -> 82
                    abs(last.high - resistance) / last.close < 0.0015 ||
                        abs(last.low - support) / last.close < 0.0015 -> 68
                    else -> 45
                }
                detections += AiDetection(
                    "Chart pattern detection", patternScore,
                    "Preliminary 5m range/breakout structure from ${c.size} candles; broader pattern history is still building."
                )
            } else {
                detections += AiDetection("Chart pattern detection", null, "Need at least 5 validated 5m candles for preliminary pattern analysis.")
            }

            if (c.size >= 2) {
                val recent = c.takeLast(20)
                val support = recent.minOf { it.low }
                val resistance = recent.maxOf { it.high }
                val srDistance = min(abs(last.close - support), abs(last.close - resistance)) / last.close
                detections += AiDetection(
                    "Auto support & resistance",
                    (100 - (srDistance * 20_000).toInt()).coerceIn(0, 100),
                    "Support ₹" + fmt(support) + " • Resistance ₹" + fmt(resistance) +
                        if (c.size < 20) " • Preliminary levels from ${c.size} candles." else " • Recent 20-candle levels."
                )
            } else {
                detections += AiDetection("Auto support & resistance", null, "Need at least 2 validated 5m candles.")
            }

            if (c.size >= 2) {
                val volumes = c.dropLast(1).takeLast(20).map { it.volume }
                val avgVolume = volumes.average()
                if (avgVolume <= 0.0 || last.volume <= 0.0) {
                    detections += AiDetection("Smart money / volume spike", null, "Volume is zero or unavailable in the NIFTY index feed.")
                } else {
                    val ratio = last.volume / avgVolume
                    detections += AiDetection(
                        "Smart money / volume spike", (ratio * 45).toInt().coerceIn(0, 100),
                        "Latest volume is " + String.format(java.util.Locale.US, "%.2f", ratio) +
                            "× its available-candle average; volume is only a proxy, not proof of institutional flow."
                    )
                }
            } else {
                detections += AiDetection("Smart money / volume spike", null, "Need at least 2 validated 5m candles.")
            }

            if (c.size >= 21) {
                val prior = c.dropLast(1).takeLast(20)
                val resistance = prior.maxOf { it.high }
                val support = prior.minOf { it.low }
                val fakeout = (last.high > resistance && last.close <= resistance) ||
                    (last.low < support && last.close >= support)
                detections += AiDetection(
                    "Fakeout detection",
                    if (fakeout) 85 else if (last.close > resistance || last.close < support) 70 else 35,
                    if (fakeout) "Wick broke the recent 20-candle range but candle closed back inside."
                    else "No clear failed breakout on the latest candle."
                )
            } else {
                detections += AiDetection("Fakeout detection", null, "Need 21 validated 5m candles to compare the latest candle with the prior 20.")
            }

            val rsi = TechnicalAnalysis.rsi(closes, 14)
            val divergence = if (c.size >= 30) detectRsiDivergence(c, rsi) else null
            detections += AiDetection(
                "RSI divergence", divergence,
                if (c.size < 30) "Need 30 validated 5m candles for the current swing-comparison rule."
                else if (divergence == null) "RSI or swing history is unavailable."
                else if (divergence >= 60) "Possible price/RSI divergence detected."
                else "No strong RSI divergence detected."
            )

            if (c.size >= 15) {
                val atr = c.takeLast(14).map { it.high - it.low }.average()
                val volatilityPct = atr / last.close * 100.0
                val riskScore = (100.0 - volatilityPct * 120.0).toInt().coerceIn(0, 100)
                detections += AiDetection(
                    "AI risk & volatility", riskScore,
                    "14-candle average range " + String.format(java.util.Locale.US, "%.2f", volatilityPct) +
                        "% of price; higher range lowers this stability score."
                )
            } else {
                detections += AiDetection("AI risk & volatility", null, "Need 15 validated 5m candles for the 14-candle range calculation.")
            }

            if (c.size >= 22 && oneFresh && candles1m.size >= 2) {
                val body = abs(last.close - last.open)
                val candleRange = (last.high - last.low).coerceAtLeast(0.01)
                val bodyRatio = body / candleRange
                val oneLast = candles1m.last()
                val aligned1m = (oneLast.close >= oneLast.open && last.close >= last.open) ||
                    (oneLast.close < oneLast.open && last.close < last.open)
                val scalpScore = ((bodyRatio * 65).toInt() + if (aligned1m) 35 else 0).coerceIn(0, 100)
                detections += AiDetection(
                    "Candlestick & scalping setup", scalpScore,
                    "5m candle body strength and latest fresh 1m/5m direction alignment."
                )
            } else {
                detections += AiDetection(
                    "Candlestick & scalping setup", null,
                    if (c.size < 22) "Need at least 22 validated 5m candles for the scalping rule."
                    else "Need at least 2 fresh, chronological 1m candles for confirmation."
                )
            }

            if (c.size >= 22 && oneFresh && candles1m.size >= 2) {
                val ema9 = TechnicalAnalysis.ema(closes, 9)
                val ema21 = TechnicalAnalysis.ema(closes, 21)
                val masterRsi = TechnicalAnalysis.rsi(closes, 14)
                val trendScore = when {
                    ema9 != null && ema21 != null && masterRsi != null && ema9 > ema21 && masterRsi >= 50 -> 85
                    ema9 != null && ema21 != null && masterRsi != null && ema9 < ema21 && masterRsi <= 50 -> 85
                    ema9 != null && ema21 != null && masterRsi != null -> 45
                    else -> null
                }
                detections += AiDetection(
                    "AI master signal & market bias", trendScore,
                    when (trendScore) {
                        null -> "EMA/RSI inputs unavailable."
                        85 -> "EMA 9/21 and RSI agree on directional bias; this is analysis only, not an order."
                        else -> "EMA/RSI do not align; wait for confirmation."
                    }
                )
            } else {
                detections += AiDetection(
                    "AI master signal & market bias", null,
                    "Need at least 22 validated 5m candles and 2 fresh 1m candles for the full master-signal rule."
                )
            }
        }

        val freshOptionChain = optionChain?.takeIf {
            it.capturedAtMillis > 0L && it.capturedAtMillis <= now &&
                now - it.capturedAtMillis <= 5 * 60_000L &&
                it.underlyingPrice.isFinite() && it.underlyingPrice > 0.0 &&
                it.analysis.pcr?.let { pcr -> pcr.isFinite() && pcr > 0.0 } == true
        }
        val optionPcr = freshOptionChain?.analysis?.pcr
        val optionScore = optionPcr?.let { if (it >= 1.10 || it <= 0.90) 75 else 50 }
        detections += AiDetection(
            "Option chain & OI analysis",
            optionScore,
            if (optionPcr == null) {
                "Recent verified Upstox option-chain PCR is unavailable; no OI score is fabricated."
            } else {
                "Recent Upstox API snapshot • PCR " + String.format(java.util.Locale.US, "%.3f", optionPcr) +
                    ". Per-strike exchange timestamps are not exposed; treat OI as a recent API observation, not tick-verified data."
            }
        )
        detections += AiDetection(
            "Market sentiment & news", null,
            "No verified live news/sentiment source is connected; not scored."
        )
        return AiPerformanceReport(now, detections)
    }

    private fun validCandle(c: MarketCandle): Boolean {
        val values = listOf(c.open, c.high, c.low, c.close, c.volume)
        return c.timestampMillis > 0L && values.all { it.isFinite() } &&
            c.open > 0.0 && c.high > 0.0 && c.low > 0.0 && c.close > 0.0 &&
            c.volume >= 0.0 && c.high >= max(c.open, max(c.close, c.low)) &&
            c.low <= min(c.open, min(c.close, c.high))
    }

    private fun detectRsiDivergence(c: List<MarketCandle>, rsi: Double?): Int? {
        if (rsi == null || c.size < 30) return null
        val recent = c.takeLast(10)
        val previous = c.dropLast(10).takeLast(10)
        val priceHigher = recent.maxOf { it.high } > previous.maxOf { it.high } * 1.001
        val priceLower = recent.minOf { it.low } < previous.minOf { it.low } * 0.999
        val oldRsi = TechnicalAnalysis.rsi(c.dropLast(10).map { it.close }, 14) ?: return null
        return when {
            priceHigher && rsi < oldRsi - 3 -> 75
            priceLower && rsi > oldRsi + 3 -> 75
            else -> 35
        }
    }

    private fun validSeries(c: List<MarketCandle>): Boolean = c.size >= 2 &&
        c.all { x ->
            val v = listOf(x.open, x.high, x.low, x.close, x.volume)
            x.timestampMillis > 0 && v.all { it.isFinite() } && x.open > 0 && x.high >= max(x.open, max(x.close, x.low)) &&
                x.low > 0 && x.low <= min(x.open, min(x.close, x.high)) && x.volume >= 0
        } && c.zipWithNext().all { (a, b) -> b.timestampMillis > a.timestampMillis }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
}
