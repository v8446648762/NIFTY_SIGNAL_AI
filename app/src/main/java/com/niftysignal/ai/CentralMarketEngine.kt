package com.niftysignal.ai

/**
 * The single in-app calculation hub. It accepts only the existing read-only
 * Upstox feed snapshot; it never opens a connection or handles broker credentials.
 * Every live-dependent panel should consume this immutable result.
 */
data class CentralChartIndicators(
    val ema9Series: List<Double?>,
    val ema21Series: List<Double?>,
    val vwap: Double?
)

data class CentralVolumeLevel(val price: Double, val volume: Double)

data class CentralVolumeProfile(
    val pointOfControl: Double?,
    val levels: List<CentralVolumeLevel>,
    val available: Boolean
)

data class CentralMarketState(
    val snapshot: MarketDataSnapshot,
    val signalDecision: SignalDecision,
    val marketIntelligence: MarketIntelligenceSnapshot?,
    val nextCandlePrediction: NextCandlePrediction,
    val aiPerformanceReport: AiPerformanceReport,
    val advancedSignalResult: AdvancedSignalResult,
    val riskAssessment: RiskAssessment?,
    val fiveMinuteChartIndicators: CentralChartIndicators,
    val oneMinuteChartIndicators: CentralChartIndicators,
    val stochasticRsi: Double?,
    val volumeProfile: CentralVolumeProfile,
    val averageRange14: Double?
) {
    val isLive: Boolean get() = !snapshot.isStale
    val staleReason: String? get() = snapshot.staleReason
}

object CentralMarketEngine {
    private const val MAX_RECEIPT_AGE_MS = 90_000L
    private const val MAX_5M_CANDLE_AGE_MS = 10 * 60_000L
    private const val MAX_1M_CANDLE_AGE_MS = 3 * 60_000L

    fun evaluate(snapshot: MarketDataSnapshot, nowMillis: Long): CentralMarketState? {
        val candles5m = snapshot.fiveMinuteCandles
        val candles1m = snapshot.oneMinuteCandles
        if (candles5m.isEmpty() || !validSeries(candles5m) ||
            (candles1m.isNotEmpty() && !validSeries(candles1m))) return null

        val receiptAge = nowMillis - snapshot.receivedAtMillis
        val fiveMinuteEnd = candles5m.last().timestampMillis + 300_000L
        val oneMinuteEnd = candles1m.lastOrNull()?.timestampMillis?.plus(60_000L)
        val staleReason = when {
            snapshot.isStale && !snapshot.staleReason.isNullOrBlank() -> snapshot.staleReason
            snapshot.isStale -> "Upstox feed marked this snapshot stale; live analysis paused."
            receiptAge < -5_000L -> "Market snapshot timestamp is ahead of device time."
            receiptAge > MAX_RECEIPT_AGE_MS -> "Last feed receipt is older than 90 seconds."
            nowMillis - fiveMinuteEnd > MAX_5M_CANDLE_AGE_MS -> "Latest completed 5-minute candle is stale."
            oneMinuteEnd != null && nowMillis - oneMinuteEnd > MAX_1M_CANDLE_AGE_MS ->
                "Latest completed 1-minute candle is stale; live confirmation paused."
            else -> null
        }
        val canonicalSnapshot = snapshot.copy(
            isStale = staleReason != null,
            staleReason = staleReason
        )
        val technicalDecision = SignalEngine.evaluate(candles5m, candles1m)
        val decision = if (canonicalSnapshot.isStale) {
            technicalDecision.copy(
                bias = TradeBias.WAIT,
                trend = "STALE / PAUSED",
                reasons = listOf(staleReason ?: "Waiting for fresh validated market data."),
                confirmed = false
            )
        } else technicalDecision

        val intelligence = if (canonicalSnapshot.isStale) null
            else MarketIntelligenceEngine.evaluate(candles5m)
        val nextCandle = NextFiveMinuteCandleEngine.evaluate(
            candles5m, candles1m, intelligence, canonicalSnapshot.isStale
        )
        val report = if (canonicalSnapshot.isStale) {
            AdvancedAiAnalytics.unavailable(
                nowMillis,
                staleReason ?: "Fresh validated Upstox candles are required."
            )
        } else {
            AdvancedAiAnalytics.evaluate(candles5m, candles1m, nowMillis)
        }
        // Advanced AI v1 deliberately remains WAIT until a verified, fresh option-chain
        // snapshot exists. No synthetic PCR/OI values are supplied by this central hub.
        val advancedSignal = AdvancedAiEngine.evaluate(
            candles5m, candles1m, optionChain = null, nowMillis = nowMillis
        )
        val risk = if (canonicalSnapshot.isStale) null
            else intelligence?.riskInputs?.let(RiskAssessmentEngine::evaluate)

        fun chartIndicators(candles: List<MarketCandle>) = CentralChartIndicators(
            ema9Series = TechnicalAnalysis.emaSeries(candles.map { it.close }, 9),
            ema21Series = TechnicalAnalysis.emaSeries(candles.map { it.close }, 21),
            vwap = TechnicalAnalysis.sessionVwap(candles)
        )

        return CentralMarketState(
            snapshot = canonicalSnapshot,
            signalDecision = decision,
            marketIntelligence = intelligence,
            nextCandlePrediction = nextCandle,
            aiPerformanceReport = report,
            advancedSignalResult = advancedSignal,
            riskAssessment = risk,
            fiveMinuteChartIndicators = chartIndicators(candles5m),
            oneMinuteChartIndicators = chartIndicators(candles1m),
            stochasticRsi = stochasticRsi(candles5m),
            volumeProfile = volumeProfile(candles5m),
            averageRange14 = candles5m.takeLast(14).map { it.high - it.low }
                .average().takeIf { it.isFinite() && it > 0.0 }
        )
    }

    private fun stochasticRsi(candles: List<MarketCandle>): Double? {
        val closes = candles.map { it.close }
        if (closes.size < 28) return null
        val rsiValues = mutableListOf<Double>()
        for (end in 14 until closes.size) {
            var gains = 0.0
            var losses = 0.0
            for (index in end - 13..end) {
                val change = closes[index] - closes[index - 1]
                if (change > 0.0) gains += change else losses -= change
            }
            val averageGain = gains / 14.0
            val averageLoss = losses / 14.0
            rsiValues += if (averageLoss == 0.0) 100.0
                else 100.0 - 100.0 / (1.0 + averageGain / averageLoss)
        }
        if (rsiValues.size < 14) return null
        val window = rsiValues.takeLast(14)
        val low = window.minOrNull() ?: return null
        val high = window.maxOrNull() ?: return null
        return if (high > low) (window.last() - low) / (high - low) * 100.0 else 50.0
    }

    private fun volumeProfile(candles: List<MarketCandle>): CentralVolumeProfile {
        val totalVolume = candles.sumOf { it.volume }
        if (candles.isEmpty() || !totalVolume.isFinite() || totalVolume <= 0.0) {
            return CentralVolumeProfile(null, emptyList(), false)
        }
        val low = candles.minOf { it.low }
        val high = candles.maxOf { it.high }
        val step = ((high - low) / 12.0).takeIf { it > 0.0 } ?: 1.0
        val bins = linkedMapOf<Int, Double>()
        candles.forEach { candle ->
            val key = ((candle.close - low) / step).toInt().coerceIn(0, 11)
            bins[key] = (bins[key] ?: 0.0) + candle.volume
        }
        val pocKey = bins.maxByOrNull { it.value }?.key
        val poc = pocKey?.let { low + (it + 0.5) * step }
        val levels = bins.entries.sortedByDescending { it.value }.take(4).map { entry ->
            CentralVolumeLevel(low + (entry.key + 0.5) * step, entry.value)
        }
        return CentralVolumeProfile(poc, levels, true)
    }

    private fun validSeries(candles: List<MarketCandle>): Boolean =
        candles.size >= 2 &&
            candles.zipWithNext().all { (a, b) -> b.timestampMillis > a.timestampMillis } &&
            candles.all { candle ->
                val values = listOf(candle.open, candle.high, candle.low, candle.close, candle.volume)
                values.all { it.isFinite() } &&
                    candle.timestampMillis > 0L &&
                    candle.open > 0.0 && candle.high > 0.0 &&
                    candle.low > 0.0 && candle.close > 0.0 &&
                    candle.volume >= 0.0 &&
                    candle.high >= maxOf(candle.open, candle.close, candle.low) &&
                    candle.low <= minOf(candle.open, candle.close, candle.high)
            }
}
