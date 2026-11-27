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

    @Test fun invalidCandleCausesWaitInsteadOfSignal() {
        val primary = (1..22).map { candle(it, 100.0 + it) }.toMutableList()
        primary[5] = MarketCandle(5, 10.0, 8.0, 9.0, 10.0, 100.0)
        val confirmation = listOf(candle(23, 123.0), candle(24, 124.0))
        val result = TechnicalAnalysis.analyze(primary, confirmation)
        assertEquals(TradeBias.WAIT, result.bias)
        assertTrue(result.reason.contains("Invalid"))
    }
    @Test fun fiveMinuteIndicatorsRemainAvailableWithoutOneMinuteConfirmation() {
        val candles = (1..40).map { index ->
            val close = 22000.0 + index * 2.0
            MarketCandle(index * 300_000L, close - 1.0, close + 1.0, close - 2.0, close, 0.0)
        }
        val result = TechnicalAnalysis.analyze(candles, emptyList())
        assertNotNull(result.ema9)
        assertNotNull(result.ema21)
        assertNotNull(result.rsi14)
        assertEquals(TradeBias.WAIT, result.bias)
        assertTrue(result.reason.contains("1m"))
    }

    @Test fun emaAndRsiRemainVisibleBeforeTwentyTwoCandleSignalThreshold() {
        val candles = (1..21).map { index ->
            val close = 22000.0 + index * 2.0
            MarketCandle(index * 300_000L, close - 1.0, close + 1.0, close - 2.0, close, 0.0)
        }
        val result = TechnicalAnalysis.analyze(candles, emptyList())
        assertNotNull(result.ema9)
        assertNotNull(result.ema21)
        assertNotNull(result.rsi14)
        assertEquals(TradeBias.WAIT, result.bias)
        assertTrue(result.reason.contains("22 completed 5m candles"))
    }

}
