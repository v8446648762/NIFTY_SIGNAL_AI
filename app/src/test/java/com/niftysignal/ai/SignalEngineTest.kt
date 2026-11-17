package com.niftysignal.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalEngineTest {
    private fun risingCandles(count: Int): List<MarketCandle> =
        (0 until count).map { index ->
            val close = 100.0 + index
            MarketCandle(
                timestampMillis = index * 300_000L,
                open = close - 0.4,
                high = close + 0.1,
                low = close - 0.6,
                close = close,
                volume = 100.0
            )
        }

    private fun fallingCandles(count: Int): List<MarketCandle> =
        (0 until count).map { index ->
            val close = 200.0 - index
            MarketCandle(
                timestampMillis = index * 300_000L,
                open = close + 0.4,
                high = close + 0.6,
                low = close - 0.1,
                close = close,
                volume = 100.0
            )
        }

    @Test
    fun insufficientHistoryReturnsWaitWithoutIndicators() {
        val result = SignalEngine.evaluate(risingCandles(8), risingCandles(2))
        assertEquals(TradeBias.WAIT, result.bias)
        assertEquals("INSUFFICIENT DATA", result.trend)
        assertNull(result.indicators.ema21)
        assertFalse(result.confirmed)
    }

    @Test
    fun alignedBullishCandlesReturnBuyCe() {
        val result = SignalEngine.evaluate(risingCandles(30), risingCandles(3))
        assertEquals(TradeBias.BUY_CE, result.bias)
        assertEquals("BULLISH", result.trend)
        assertTrue(result.confirmed)
        assertTrue(result.indicators.vwap!! > 0.0)
    }

    @Test
    fun alignedBearishCandlesReturnBuyPe() {
        val result = SignalEngine.evaluate(fallingCandles(30), fallingCandles(3))
        assertEquals(TradeBias.BUY_PE, result.bias)
        assertEquals("BEARISH", result.trend)
        assertTrue(result.confirmed)
    }

    @Test
    fun zeroIndexVolumeKeepsEmaRsiAvailableButDoesNotInventVwap() {
        val primary = risingCandles(30).map { it.copy(volume = 0.0) }
        val confirmation = risingCandles(3).map { it.copy(volume = 0.0) }
        val result = SignalEngine.evaluate(primary, confirmation)
        assertEquals(TradeBias.BUY_CE, result.bias)
        assertTrue(result.trend.startsWith("BULLISH"))
        assertTrue(result.indicators.ema9!! > 0.0)
        assertTrue(result.indicators.ema21!! > 0.0)
        assertTrue(result.indicators.rsi14!! > 0.0)
        assertNull(result.indicators.vwap)
    }

    @Test
    fun invalidOhlcvNeverProducesTradeSignal() {
        val invalid = risingCandles(30).toMutableList()
        invalid[10] = invalid[10].copy(volume = -1.0)
        val result = SignalEngine.evaluate(invalid, risingCandles(3))
        assertEquals(TradeBias.WAIT, result.bias)
        assertFalse(result.confirmed)
    }

    @Test
    fun paperTradeCalculatesGrossPnlFromManualQuantity() {
        val closed = PaperTrade(
            side = TradeBias.BUY_CE,
            entryPremium = 120.50,
            quantity = 50,
            openedAtMillis = 1L,
            exitPremium = 135.00,
            closedAtMillis = 2L
        )
        assertEquals(725.0, closed.grossPnl!!, 0.0001)
        assertFalse(closed.isOpen)
    }
}
