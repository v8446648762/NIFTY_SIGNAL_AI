package com.niftysignal.ai

/**
 * Independent Option Chain calculation models.
 * This engine is read-only and intentionally does not depend on TechnicalAnalysis
 * or SignalEngine.
 */
data class OptionChainContract(
    val strike: Double,
    val callOi: Double?,
    val callOiChange: Double?,
    val callVolume: Double?,
    val callLtp: Double?,
    val callIv: Double? = null,
    val callDelta: Double? = null,
    val callBid: Double? = null,
    val callAsk: Double? = null,
    val putOi: Double?,
    val putOiChange: Double?,
    val putVolume: Double?,
    val putLtp: Double?,
    val putIv: Double? = null,
    val putDelta: Double? = null,
    val putBid: Double? = null,
    val putAsk: Double? = null
)

data class OptionChainMetrics(
    val totalCallOi: Double,
    val totalPutOi: Double,
    val pcr: Double?,
    val highestCallOiStrike: Double?,
    val highestPutOiStrike: Double?,
    val strongestCallOiAdditionStrike: Double?,
    val strongestPutOiAdditionStrike: Double?,
    val callWritingPressure: Double,
    val putWritingPressure: Double,
    val supportCandidates: List<Double>,
    val resistanceCandidates: List<Double>,
    val validStrikeCount: Int,
    val warnings: List<String>
)

object OptionChainCalculationEngine {
    fun calculate(rows: List<OptionChainContract>, spot: Double? = null): OptionChainMetrics {
        val valid = rows.filter { it.strike.isFinite() && it.strike > 0.0 }
            .distinctBy { it.strike }
            .sortedBy { it.strike }
        require(valid.isNotEmpty()) { "Option chain has no valid strike rows." }

        val callOi = valid.sumOf { it.callOi.nonNegativeOrZero() }
        val putOi = valid.sumOf { it.putOi.nonNegativeOrZero() }
        val callAdd = valid.filter { (it.callOiChange ?: 0.0) > 0.0 }
            .maxByOrNull { it.callOiChange ?: 0.0 }?.strike
        val putAdd = valid.filter { (it.putOiChange ?: 0.0) > 0.0 }
            .maxByOrNull { it.putOiChange ?: 0.0 }?.strike

        val near = if (spot != null && spot.isFinite() && spot > 0.0) {
            valid.sortedBy { kotlin.math.abs(it.strike - spot) }.take(12)
        } else valid
        val callPressure = near.sumOf { it.callOiChange.positiveOrZero() }
        val putPressure = near.sumOf { it.putOiChange.positiveOrZero() }
        val warnings = buildList {
            if (valid.any { it.callOi == null || it.putOi == null }) add("Some OI values are missing; totals include available values only.")
            if (valid.any { it.callOiChange == null || it.putOiChange == null }) add("Some change-in-OI values are missing.")
            if (spot == null || !spot.isFinite() || spot <= 0.0) add("Spot price unavailable; support/resistance candidates use all strikes.")
            if (valid.any { listOfNotNull(it.callIv, it.putIv).any { v -> !v.isFinite() || v < 0.0 } }) add("Invalid IV values were ignored by downstream display.")
        }
        val supports = valid.filter { (spot == null || it.strike <= spot) && (it.putOi ?: 0.0) > 0.0 }
            .sortedByDescending { it.putOi ?: 0.0 }.take(3).map { it.strike }
        val resistances = valid.filter { (spot == null || it.strike >= spot) && (it.callOi ?: 0.0) > 0.0 }
            .sortedByDescending { it.callOi ?: 0.0 }.take(3).map { it.strike }

        return OptionChainMetrics(
            totalCallOi = callOi,
            totalPutOi = putOi,
            pcr = if (callOi > 0.0) putOi / callOi else null,
            highestCallOiStrike = valid.filter { (it.callOi ?: 0.0) > 0.0 }.maxByOrNull { it.callOi ?: 0.0 }?.strike,
            highestPutOiStrike = valid.filter { (it.putOi ?: 0.0) > 0.0 }.maxByOrNull { it.putOi ?: 0.0 }?.strike,
            strongestCallOiAdditionStrike = callAdd,
            strongestPutOiAdditionStrike = putAdd,
            callWritingPressure = callPressure,
            putWritingPressure = putPressure,
            supportCandidates = supports,
            resistanceCandidates = resistances,
            validStrikeCount = valid.size,
            warnings = warnings
        )
    }

    private fun Double?.nonNegativeOrZero(): Double =
        this?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0

    private fun Double?.positiveOrZero(): Double =
        this?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
}
