package com.niftysignal.ai

/**
 * Transparent risk aggregation. Every component must be supplied from validated
 * market calculations; missing inputs produce no score rather than a guessed value.
 * This is an adverse-condition score, not a probability of profit.
 */
data class RiskInputs(
    val indicatorRisk: Double?,
    val trendRisk: Double?,
    val priceActionRisk: Double?,
    val sentimentRisk: Double?
)

data class RiskAssessment(val score: Int, val band: RiskBand)

enum class RiskBand { LOW, MODERATE, HIGH }

object RiskAssessmentEngine {
    fun evaluate(inputs: RiskInputs): RiskAssessment? {
        val values = listOf(
            inputs.indicatorRisk, inputs.trendRisk,
            inputs.priceActionRisk, inputs.sentimentRisk
        )
        if (values.any { it == null || !it.isFinite() }) return null
        val score = (
            inputs.indicatorRisk!! .coerceIn(0.0, 100.0) * 0.30 +
            inputs.trendRisk!! .coerceIn(0.0, 100.0) * 0.25 +
            inputs.priceActionRisk!! .coerceIn(0.0, 100.0) * 0.25 +
            inputs.sentimentRisk!! .coerceIn(0.0, 100.0) * 0.20
        ).toInt().coerceIn(0, 100)
        val band = when {
            score <= 30 -> RiskBand.LOW
            score <= 60 -> RiskBand.MODERATE
            else -> RiskBand.HIGH
        }
        return RiskAssessment(score, band)
    }
}
