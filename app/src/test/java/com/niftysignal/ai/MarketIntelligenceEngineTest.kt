package com.niftysignal.ai

import org.junit.Assert.*
import org.junit.Test

class MarketIntelligenceEngineTest {
    private fun risingCandles(count: Int = 40) = (0 until count).map { index ->
        val close = 22000.0 + index * 8.0
        MarketCandle(index * 300_000L, close - 2.0, close + 3.0, close - 4.0, close, 0.0)
    }

    @Test fun calculatesTrendMomentumAndRiskFromValidatedCandles() {
        val result = MarketIntelligenceEngine.evaluate(risingCandles())
        assertNotNull(result)
        assertEquals("BULLISH", result!!.bias)
        assertEquals("BULLISH", result.supertrend)
        assertEquals("BULLISH", result.macd)
        assertNotNull(result.adx)
        assertEquals("N/A (index volume 0)", result.volumeStatus)
        assertNotNull(RiskAssessmentEngine.evaluate(result.riskInputs))
    }

    @Test fun keepsIndependentStudiesAvailableWhileLongerIndicatorsWarmUp() {
        val result = MarketIntelligenceEngine.evaluate(risingCandles(18))
        assertNotNull(result)
        assertEquals("BULLISH (EARLY)", result!!.bias)
        assertEquals("BULLISH", result.supertrend)
        assertEquals("WARMING UP (<34 candles)", result.macd)
        assertEquals("WARMING UP (<21 candles)", result.breakoutStatus)
        assertNotNull(RiskAssessmentEngine.evaluate(result.riskInputs))
    }

    @Test fun refusesOnlyInsufficientBaseHistoryOrInvalidCandles() {
        assertNull(MarketIntelligenceEngine.evaluate(risingCandles(14)))
        assertNotNull(MarketIntelligenceEngine.evaluate(risingCandles(30)))
        val invalid = risingCandles().toMutableList()
        invalid[10] = invalid[10].copy(high = invalid[10].low - 1.0)
        assertNull(MarketIntelligenceEngine.evaluate(invalid))
    }
}
