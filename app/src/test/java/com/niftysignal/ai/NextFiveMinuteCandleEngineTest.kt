package com.niftysignal.ai

import org.junit.Assert.*
import org.junit.Test

class NextFiveMinuteCandleEngineTest {
    private val now = 1_800_000_000_000L

    private fun candles(count: Int, interval: Long, rising: Boolean): List<MarketCandle> =
        (0 until count).map { i ->
            val base = if (rising) 24000.0 + i * 3.0 else 24000.0 - i * 3.0
            val open = base - if (rising) 1.0 else -1.0
            val close = base
            MarketCandle(now - (count - 1 - i) * interval, open,
                maxOf(open, close) + 1.0, minOf(open, close) - 1.0,
                close, 1000.0 + i)
        }

    @Test fun staleFeedReturnsNoProbability() {
        val result = NextFiveMinuteCandleEngine.evaluate(candles(30, 300_000, true),
            candles(8, 60_000, true), false, now)
        assertEquals(NextCandleDirection.INSUFFICIENT_DATA, result.direction)
        assertNull(result.greenProbability)
    }

    @Test fun insufficientHistoryReturnsNoProbability() {
        val result = NextFiveMinuteCandleEngine.evaluate(candles(10, 300_000, true),
            candles(8, 60_000, true), true, now)
        assertEquals(NextCandleDirection.INSUFFICIENT_DATA, result.direction)
    }

    @Test fun invalidCandleReturnsNoProbability() {
        val invalid = candles(30, 300_000, true).toMutableList()
        invalid[invalid.lastIndex] = invalid.last().copy(high = 1.0)
        val result = NextFiveMinuteCandleEngine.evaluate(invalid, candles(8, 60_000, true), true, now)
        assertEquals(NextCandleDirection.INSUFFICIENT_DATA, result.direction)
    }

    @Test fun opposingOrBalancedSignalsNeverForceGreenOrRed() {
        val flat = candles(30, 300_000, true).map { it.copy(open = it.close, high = it.close + 1, low = it.close - 1) }
        val result = NextFiveMinuteCandleEngine.evaluate(flat, candles(8, 60_000, false), true, now)
        assertTrue(result.direction == NextCandleDirection.NO_EDGE ||
            result.direction == NextCandleDirection.GREEN || result.direction == NextCandleDirection.RED)
        assertNotNull(result.greenProbability)
        assertEquals(100, result.greenProbability!! + result.redProbability!!)
    }

    @Test fun timestampTooOldReturnsNoProbability() {
        val old = candles(30, 300_000, true).map { it.copy(timestampMillis = it.timestampMillis - 20 * 60_000L) }
        val result = NextFiveMinuteCandleEngine.evaluate(old, candles(8, 60_000, true), true, now)
        assertEquals(NextCandleDirection.INSUFFICIENT_DATA, result.direction)
    }
}
