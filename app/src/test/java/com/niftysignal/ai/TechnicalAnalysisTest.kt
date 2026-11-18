package com.niftysignal.ai

import org.junit.Assert.*
import org.junit.Test

class TechnicalAnalysisTest {
    private fun candle(index: Int, close: Double, volume: Double = 100.0) =
        MarketCandle(index * 60_000L, close - 0.5, close + 1.0, close - 1.0, close, volume)

    @Test fun emaReturnsNullWhenHistoryIsTooShort() {
        assertNull(TechnicalAnalysis.ema(listOf(1.0, 2.0), 3))
    }

    @Test fun emaCalculatesSeedAverageAndNextValue() {
        val result = TechnicalAnalysis.ema(listOf(1.0, 2.0, 3.0, 4.0), 3)
        assertEquals(3.0, result!!, 0.000001)
    }

    @Test fun rsiReturnsNeutralForFlatSeries() {
        assertEquals(50.0, TechnicalAnalysis.rsi(List(16) { 100.0 })!!, 0.000001)
    }

    @Test fun vwapIsVolumeWeighted() {
        val candles = listOf(
            MarketCandle(1, 9.0, 11.0, 9.0, 10.0, 1.0),
            MarketCandle(2, 19.0, 21.0, 19.0, 20.0, 3.0)
        )
        assertEquals(17.5, TechnicalAnalysis.sessionVwap(candles)!!, 0.000001)
    }

    @Test fun indicatorsAppearIndependentlyBeforeFullSignalHistoryIsReady() {
        val fiveMinute = (1..15).map { candle(it, 100.0 + it) }
        val oneMinute = listOf(candle(20, 120.0), candle(21, 121.0))
        val result = TechnicalAnalysis.analyze(fiveMinute, oneMinute)

        assertNotNull("EMA 9 should be available after 9 candles", result.ema9)
        assertNull("EMA 21 needs 21 candles", result.ema21)
        assertNotNull("RSI 14 should be available after 15 closes", result.rsi14)
        assertNotNull("Support should be available after 5 candles", result.support)
        assertNotNull("Resistance should be available after 5 candles", result.resistance)
        assertEquals(TradeBias.WAIT, result.bias)
    }

    @Test fun ema21AppearsAfter21CandlesWhileSignalsWaitFor22() {
        val fiveMinute = (1..21).map { candle(it, 100.0 + it) }
        val oneMinute = listOf(candle(22, 122.0), candle(23, 123.0))
        val result = TechnicalAnalysis.analyze(fiveMinute, oneMinute)

        assertNotNull(result.ema9)
        assertNotNull(result.ema21)
        assertNotNull(result.rsi14)
        assertEquals(TradeBias.WAIT, result.bias)
        assertTrue(result.reason.contains("22 completed 5m candles"))
    }

    @Test fun invalidCandleCausesWaitWithoutPublishingIndicators() {
        val primary = (1..22).map { candle(it, 100.0 + it) }.toMutableList()
        primary[5] = MarketCandle(5, 10.0, 8.0, 9.0, 10.0, 100.0)
        val confirmation = listOf(candle(23, 123.0), candle(24, 124.0))
        val result = TechnicalAnalysis.analyze(primary, confirmation)
        assertEquals(TradeBias.WAIT, result.bias)
        assertTrue(result.reason.contains("Invalid"))
        assertNull(result.ema9)
        assertNull(result.support)
    }
}
