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

data class CentralTradeReferenceLevels(
    val direction: TradeBias,
    val entry: Double,
    val stop: Double,
    val target: Double,
    val riskReward: Double
)

data class UnifiedAiDecision(
    val bias: TradeBias,
    val status: String,
    val alignmentScore: Int?,
    val reasons: List<String>,
    val warnings: List<String>,
    val optionChainPcr: Double?,
    val referenceLevels: CentralTradeReferenceLevels?
)

data class CentralMarketState(
    val snapshot: MarketDataSnapshot,
    val signalDecision: SignalDecision,
    val marketIntelligence: MarketIntelligenceSnapshot?,
    val nextCandlePrediction: NextCandlePrediction,
    val aiPerformanceReport: AiPerformanceReport,
    val advancedSignalResult: AdvancedSignalResult,
    val riskAssessment: RiskAssessment?,
    val unifiedAiDecision: UnifiedAiDecision,
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
    private const val MAX_OPTION_CHAIN_AGE_MS = 60_000L

    @Volatile private var latestOptionChainSnapshot: OptionChainSnapshot? = null

    /** Receives only a recently fetched, structurally valid Upstox option-chain snapshot. */
    fun publishOptionChainSnapshot(snapshot: OptionChainSnapshot?) {
        val now = System.currentTimeMillis()
        latestOptionChainSnapshot = snapshot?.takeIf {
            it.capturedAtMillis > 0L &&
                it.capturedAtMillis <= now + 5_000L &&
                now - it.capturedAtMillis in 0..MAX_OPTION_CHAIN_AGE_MS &&
                it.underlyingPrice.isFinite() && it.underlyingPrice > 0.0 &&
                it.analysis.pcr?.let { pcr -> pcr.isFinite() && pcr > 0.0 } == true
        }
    }

    fun evaluate(snapshot: MarketDataSnapshot, nowMillis: Long): CentralMarketState? {
        val coreState = CoreMarketDataEngine.validate(snapshot, nowMillis) ?: return null
        val canonicalSnapshot = coreState.snapshot
        val candles5m = canonicalSnapshot.fiveMinuteCandles
        val candles1m = canonicalSnapshot.oneMinuteCandles
        val staleReason = coreState.staleReason
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
        val optionChain = latestOptionChainSnapshot?.takeIf {
            it.capturedAtMillis > 0L &&
                it.capturedAtMillis <= nowMillis &&
                nowMillis - it.capturedAtMillis <= MAX_OPTION_CHAIN_AGE_MS &&
                it.underlyingPrice.isFinite() && it.underlyingPrice > 0.0 &&
                it.analysis.pcr?.let { pcr -> pcr.isFinite() && pcr > 0.0 } == true
        }
        if (optionChain == null) latestOptionChainSnapshot = null
        val report = if (canonicalSnapshot.isStale) {
            AdvancedAiAnalytics.unavailable(
                nowMillis,
                staleReason ?: "Fresh validated Upstox candles are required."
            )
        } else {
            AdvancedAiAnalytics.evaluate(candles5m, candles1m, nowMillis, optionChain)
        }
        // OI/PCR is used only when a recent Upstox option-chain snapshot is present.
        // No synthetic option-chain values are supplied by this central hub.
        val advancedSignal = if (canonicalSnapshot.isStale) {
            AdvancedSignalResult(
                bias = TradeBias.WAIT,
                bullishScore = 0,
                bearishScore = 0,
                confidenceLabel = "NO SIGNAL",
                trend = "STALE / PAUSED",
                reasons = listOf(staleReason ?: "Fresh validated candles are required."),
                warnings = listOf("Advanced AI is paused until the central feed is fresh."),
                support = null,
                resistance = null,
                optionChainPcr = null
            )
        } else {
            AdvancedAiEngine.evaluate(candles5m, candles1m, optionChain, nowMillis)
        }
        val risk = if (canonicalSnapshot.isStale) null
            else intelligence?.riskInputs?.let(RiskAssessmentEngine::evaluate)
        val averageRange14 = candles5m.takeLast(14).map { it.high - it.low }
            .average().takeIf { it.isFinite() && it > 0.0 }
        val unifiedDecision = unifyDecision(
            canonicalSnapshot.isStale,
            decision,
            nextCandle,
            advancedSignal,
            averageRange14,
            candles5m.lastOrNull()?.close
        )

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
            unifiedAiDecision = unifiedDecision,
            fiveMinuteChartIndicators = chartIndicators(candles5m),
            oneMinuteChartIndicators = chartIndicators(candles1m),
            stochasticRsi = stochasticRsi(candles5m),
            volumeProfile = volumeProfile(candles5m),
            averageRange14 = averageRange14
        )
    }

    private fun unifyDecision(
        stale: Boolean,
        technical: SignalDecision,
        nextCandle: NextCandlePrediction,
        advanced: AdvancedSignalResult,
        averageRange: Double?,
        latestPrice: Double?
    ): UnifiedAiDecision {
        if (stale) {
            return UnifiedAiDecision(
                TradeBias.WAIT, "PAUSED • NOT LIVE", null,
                listOf("Central market data is stale; all AI decisions are paused."),
                listOf("No signal, entry, stop or target is available until fresh validated data arrives."),
                null, null
            )
        }
        if (!technical.confirmed || technical.bias == TradeBias.WAIT) {
            return UnifiedAiDecision(
                TradeBias.WAIT, "WAIT • PRIMARY SETUP NOT CONFIRMED", null,
                technical.reasons,
                listOf("Supporting AI modules cannot create a trade direction without the primary technical setup."),
                advanced.optionChainPcr, null
            )
        }

        val candleBias = when (nextCandle.direction) {
            NextCandleDirection.GREEN -> TradeBias.BUY_CE
            NextCandleDirection.RED -> TradeBias.BUY_PE
            NextCandleDirection.WAIT -> TradeBias.WAIT
        }
        val conflicts = mutableListOf<String>()
        if (candleBias != TradeBias.WAIT && candleBias != technical.bias) {
            conflicts += "Next-candle model conflicts with the primary technical setup."
        }
        if (advanced.bias != TradeBias.WAIT && advanced.bias != technical.bias) {
            conflicts += "Options-aware advanced model conflicts with the primary technical setup."
        }
        if (conflicts.isNotEmpty()) {
            return UnifiedAiDecision(
                TradeBias.WAIT, "CONFLICT • WAIT", 0,
                technical.reasons + conflicts,
                listOf("Conflicting model outputs are never shown as separate BUY CE / BUY PE recommendations."),
                advanced.optionChainPcr, null
            )
        }

        val confirmations = listOf(
            candleBias == technical.bias,
            advanced.bias == technical.bias
        ).count { it }
        val alignment = 60 + confirmations * 20
        val status = when (confirmations) {
            2 -> "ALL MODULES ALIGNED"
            1 -> "PARTIAL CONFIRMATION"
            else -> "TECHNICAL SETUP • CONFIRMATION WAITING"
        }
        val reasons = technical.reasons +
            (if (candleBias == technical.bias) listOf("Next 5-minute candle model agrees.") else emptyList()) +
            (if (advanced.bias == technical.bias) listOf("Options-aware advanced model agrees.") else emptyList())
        val warnings = buildList {
            if (candleBias == TradeBias.WAIT) add("Next-candle confirmation is not ready; this is not full AI confluence.")
            if (advanced.optionChainPcr == null) add("Recent verified option-chain data is unavailable; OI/PCR is excluded.")
            else if (advanced.bias == TradeBias.WAIT) add("Option-chain data is present but does not confirm a directional setup.")
            add("Alignment score is rule-based confluence, not a probability of profit.")
        }
        val levels = if (averageRange != null && latestPrice != null &&
            averageRange.isFinite() && averageRange > 0.0 && latestPrice.isFinite() && latestPrice > 0.0
        ) {
            val bullish = technical.bias == TradeBias.BUY_CE
            val stop = if (bullish) latestPrice - averageRange else latestPrice + averageRange
            val target = if (bullish) latestPrice + averageRange * 1.5 else latestPrice - averageRange * 1.5
            if (stop > 0.0 && target > 0.0) {
                CentralTradeReferenceLevels(technical.bias, latestPrice, stop, target, 1.5)
            } else null
        } else null
        return UnifiedAiDecision(
            technical.bias, status, alignment, reasons, warnings,
            advanced.optionChainPcr, levels
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
        candles.isNotEmpty() &&
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
