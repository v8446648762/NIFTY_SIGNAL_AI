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

data class CentralMarketState(
    val snapshot: MarketDataSnapshot,
    val signalDecision: SignalDecision,
    val marketIntelligence: MarketIntelligenceSnapshot?,
    val nextCandlePrediction: NextCandlePrediction,
    val aiPerformanceReport: AiPerformanceReport,
    val advancedSignalResult: AdvancedSignalResult,
    val riskAssessment: RiskAssessment?,
    val fiveMinuteChartIndicators: CentralChartIndicators,
    val oneMinuteChartIndicators: CentralChartIndicators
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
            oneMinuteChartIndicators = chartIndicators(candles1m)
        )
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
