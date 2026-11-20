package com.niftysignal.ai

import org.junit.Assert.*
import org.junit.Test

class SmartAiOptionChainAnalyserTest {
    private fun risingCandles(count: Int, stepMillis: Long, start: Double = 25000.0): List<SmartAnalyserCandle> =
        (0 until count).map { i ->
            val close = start + i * 5.0
            SmartAnalyserCandle(
                timestampMillis = i * stepMillis,
                open = close - 1.0,
                high = close + 0.5,
                low = close - 1.5,
                close = close,
                volume = 100.0
            )
        }

    @Test fun insufficientCandlesReturnWait() {
        val result = SmartAiOptionChainAnalyser.analyse(risingCandles(10, 300_000), risingCandles(2, 60_000))
        assertEquals(SmartAnalyserBias.WAIT, result.bias)
        assertEquals(0, result.confidencePercent)
    }

    @Test fun alignedFiveMinuteTrendAndOneMinuteBreakoutAreBullish() {
        val five = risingCandles(24, 300_000).toMutableList()
        val prev = five[five.lastIndex]
        five += SmartAnalyserCandle(prev.timestampMillis + 300_000, prev.close, prev.close + 10.0, prev.close - 1.0, prev.close + 8.0, 150.0)
        val one = risingCandles(3, 60_000).toMutableList()
        val prev1 = one.last()
        one += SmartAnalyserCandle(prev1.timestampMillis + 60_000, prev1.close, prev1.close + 10.0, prev1.close - 1.0, prev1.close + 8.0, 30.0)

        val result = SmartAiOptionChainAnalyser.analyse(five, one)
        assertEquals(SmartAnalyserBias.BULLISH, result.bias)
        assertNotNull(result.ema9)
        assertNotNull(result.ema21)
        assertNotNull(result.rsi14)
        assertNotNull(result.vwap)
        assertTrue(result.oneMinuteConfirmation.contains("bullish"))
    }

    @Test fun invalidTimestampOrderReturnsWait() {
        val five = risingCandles(22, 300_000).toMutableList()
        five[21] = five[21].copy(timestampMillis = five[20].timestampMillis)
        val result = SmartAiOptionChainAnalyser.analyse(five, risingCandles(2, 60_000))
        assertEquals(SmartAnalyserBias.WAIT, result.bias)
    }
}
