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
        if (candles5m.size < 22 || candles1m.size < 2 ||
            !validSeries(candles5m) || !validSeries(candles1m) ||
            now < candles5m.last().timestampMillis || now - candles5m.last().timestampMillis > 10 * 60_000L ||
            now < candles1m.last().timestampMillis || now - candles1m.last().timestampMillis > 2 * 60_000L
        ) return unavailable(now, "Fresh, chronological 5m and 1m candles are required.")

        val c = candles5m
        val last = c.last()
        val prior = c.dropLast(1).takeLast(20)
        val resistance = prior.maxOf { it.high }
        val support = prior.minOf { it.low }
        val range = (resistance - support).coerceAtLeast(0.01)
        val closes = c.map { it.close }
        val rsi = TechnicalAnalysis.rsi(closes, 14)
        val ema9 = TechnicalAnalysis.ema(closes, 9)
        val ema21 = TechnicalAnalysis.ema(closes, 21)
        val detections = mutableListOf<AiDetection>()

        val patternScore = when {
            last.close > resistance -> 82
            last.close < support -> 82
            abs(last.high - resistance) / last.close < 0.0015 || abs(last.low - support) / last.close < 0.0015 -> 68
            else -> 45
        }
        detections += AiDetection("Chart pattern detection", patternScore, "Recent 20-candle range and breakout structure; advanced named patterns need more history.")

        val srDistance = min(abs(last.close - support), abs(last.close - resistance)) / last.close
        detections += AiDetection("Auto support & resistance", (100 - (srDistance * 20_000).toInt()).coerceIn(0, 100), "Support ₹" + fmt(support) + " • Resistance ₹" + fmt(resistance))

        val volumes = c.dropLast(1).takeLast(20).map { it.volume }
        val avgVolume = volumes.average()
        if (avgVolume <= 0.0 || last.volume <= 0.0) detections += AiDetection("Smart money / volume spike", null, "Volume is zero or unavailable in the NIFTY index feed.")
        else {
            val ratio = last.volume / avgVolume
            detections += AiDetection("Smart money / volume spike", (ratio * 45).toInt().coerceIn(0, 100), "Latest volume is " + String.format(java.util.Locale.US, "%.2f", ratio) + "× its 20-candle average; volume is only a proxy, not proof of institutional flow.")
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
        val fakeout = (last.high > resistance && last.close <= resistance) || (last.low < support && last.close >= support)
        detections += AiDetection("Fakeout detection", if (fakeout) 85 else if (last.close > resistance || last.close < support) 70 else 35, if (fakeout) "Wick broke the recent range but candle closed back inside." else "No clear failed breakout on the latest candle.")

        val divergence = detectRsiDivergence(c, rsi)
        detections += AiDetection("RSI divergence", divergence, if (divergence == null) "RSI or swing history is unavailable." else if (divergence >= 60) "Possible price/RSI divergence detected." else "No strong RSI divergence detected.")

        detections += AiDetection("Market sentiment & news", null, "No verified live news/sentiment source is connected; not scored.")

        val atr = c.takeLast(14).map { it.high - it.low }.average()
        val volatilityPct = atr / last.close * 100.0
        val riskScore = (100.0 - volatilityPct * 120.0).toInt().coerceIn(0, 100)
        detections += AiDetection("AI risk & volatility", riskScore, "14-candle average range " + String.format(java.util.Locale.US, "%.2f", volatilityPct) + "% of price; higher range lowers this stability score.")

        val body = abs(last.close - last.open)
        val candleRange = (last.high - last.low).coerceAtLeast(0.01)
        val bodyRatio = body / candleRange
        val aligned1m = candles1m.last().close >= candles1m.last().open && last.close >= last.open ||
            candles1m.last().close < candles1m.last().open && last.close < last.open
        val scalpScore = ((bodyRatio * 65).toInt() + if (aligned1m) 35 else 0).coerceIn(0, 100)
        detections += AiDetection("Candlestick & scalping setup", scalpScore, "Candle body strength and latest 1m/5m direction alignment.")

        val trendScore = when {
            ema9 != null && ema21 != null && rsi != null && ema9 > ema21 && rsi >= 50 -> 85
            ema9 != null && ema21 != null && rsi != null && ema9 < ema21 && rsi <= 50 -> 85
            ema9 != null && ema21 != null && rsi != null -> 45
            else ->  null
        }
        detections += AiDetection("AI master signal & market bias", trendScore, when (trendScore) {
            null -> "EMA/RSI inputs unavailable."
            85 -> "EMA 9/21 and RSI agree on directional bias; this is analysis only, not an order."
            else -> "EMA/RSI do not align; wait for confirmation."
        })
        return AiPerformanceReport(now, detections)
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
