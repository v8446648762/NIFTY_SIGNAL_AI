package com.niftysignal.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextFiveMinuteCandleEngineTest {
    private fun candles(count: Int, step: Long): List<MarketCandle> =
        (0 until count).map { i ->
            val close = 22000.0 + i * 5.0
            MarketCandle(i * step, close - 1.0, close + 2.0, close - 2.0, close, 0.0)
        }

    @Test fun staleFeedAlwaysReturnsWait() {
        val result = NextFiveMinuteCandleEngine.evaluate(candles(30, 300_000L), candles(6, 60_000L), stale = true)
        assertEquals(NextCandleDirection.WAIT, result.direction)
        assertNull(result.heuristicConfidence)
    }

    @Test fun insufficientFiveMinuteHistoryReturnsWait() {
        val result = NextFiveMinuteCandleEngine.evaluate(candles(10, 300_000L), candles(6, 60_000L))
        assertEquals(NextCandleDirection.WAIT, result.direction)
    }

    @Test fun insufficientOneMinuteConfirmationReturnsWait() {
        val result = NextFiveMinuteCandleEngine.evaluate(candles(30, 300_000L), candles(3, 60_000L))
        assertEquals(NextCandleDirection.WAIT, result.direction)
    }
}
