package com.niftysignal.ai

import org.junit.Assert.*
import org.junit.Test

class MarketIntelligenceEngineTest {
    private fun risingCandles(count: Int): List<MarketCandle> =
        (0 until count).map { i ->
            val close = 100.0 + i
            MarketCandle(i * 300_000L, close - 0.4, close + 0.1, close - 0.6, close, 100.0)
        }

    @Test
    fun liveCandleAnalysisProducesTransparentIntelligenceAndRiskInputs() {
        val candles = risingCandles(30)
        val decision = SignalEngine.evaluate(candles, risingCandles(3))
        val result = MarketIntelligenceEngine.evaluate(candles, decision)

        assertNotNull(result)
        assertEquals("BULLISH • UPSIDE TREND", result!!.marketBias)
        assertTrue(result.volumeConfirmed)
        assertEquals("BULLISH", result.priceVolumeBias)
        assertNotNull(RiskAssessmentEngine.evaluate(result.riskInputs))
    }

    @Test
    fun insufficientOrInvalidCandlesDoNotProduceIntelligence() {
        val insufficient = risingCandles(10)
        val shortDecision = SignalEngine.evaluate(insufficient, risingCandles(3))
        assertNull(MarketIntelligenceEngine.evaluate(insufficient, shortDecision))

        val invalid = risingCandles(30).toMutableList()
        invalid[5] = invalid[5].copy(volume = -1.0)
        val invalidDecision = SignalEngine.evaluate(invalid, risingCandles(3))
        assertNull(MarketIntelligenceEngine.evaluate(invalid, invalidDecision))
    }
}
