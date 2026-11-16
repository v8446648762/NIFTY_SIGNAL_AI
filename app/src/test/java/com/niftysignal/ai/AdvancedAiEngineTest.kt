package com.niftysignal.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedAiEngineTest {
    private val now = 1_800_000_000_000L

    private fun rising(count: Int, interval: Long): List<MarketCandle> =
        (0 until count).map { i ->
            val close = 100.0 + i
            MarketCandle(now - (count - 1 - i) * interval, close - 0.4, close + 0.1, close - 0.6, close, 100.0)
        }

    private fun falling(count: Int, interval: Long): List<MarketCandle> =
        (0 until count).map { i ->
            val close = 200.0 - i
            MarketCandle(now - (count - 1 - i) * interval, close + 0.4, close + 0.6, close - 0.1, close, 100.0)
        }

    private fun chain(pcr: Double) = OptionChainSnapshot(
        OptionChainAnalysis(emptyList(), 100.0, pcr * 100.0, pcr, null, null, null, null, 0, emptyList()),
        now, 25000.0
    )

    @Test fun missingOptionChainAlwaysWaits() {
        val result = AdvancedAiEngine.evaluate(rising(30, 300_000), rising(3, 60_000), null, now)
        assertEquals(TradeBias.WAIT, result.bias)
    }

    @Test fun staleCandlesAlwaysWait() {
        val old = rising(30, 300_000).map { it.copy(timestampMillis = it.timestampMillis - 60 * 60 * 1000L) }
        val result = AdvancedAiEngine.evaluate(old, rising(3, 60_000), chain(1.2), now)
        assertEquals(TradeBias.WAIT, result.bias)
        assertTrue(result.reasons.first().contains("stale"))
    }

    @Test fun invalidOptionChainAlwaysWaits() {
        val result = AdvancedAiEngine.evaluate(rising(30, 300_000), rising(3, 60_000), chain(Double.NaN), now)
        assertEquals(TradeBias.WAIT, result.bias)
    }

    @Test fun validInputsProduceExplainableResult() {
        val result = AdvancedAiEngine.evaluate(rising(30, 300_000), rising(3, 60_000), chain(1.2), now)
        assertTrue(result.reasons.isNotEmpty())
        assertTrue(result.bullishScore in 0..100)
        assertTrue(result.bearishScore in 0..100)
    }

    @Test fun outOfOrderCandlesAlwaysWait() {
        val candles = rising(30, 300_000).toMutableList()
        val tmp = candles[5]
        candles[5] = candles[6]
        candles[6] = tmp
        val result = AdvancedAiEngine.evaluate(candles, rising(3, 60_000), chain(1.2), now)
        assertEquals(TradeBias.WAIT, result.bias)
    }
}
