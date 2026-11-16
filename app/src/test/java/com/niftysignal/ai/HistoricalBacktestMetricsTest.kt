package com.niftysignal.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalBacktestMetricsTest {
    @Test fun calculatesNetPnlWinRateAndDrawdown() {
        val trades = listOf(
            BacktestTrade(TradeBias.BUY_CE, 100.0, 110.0, 2, 1L, 2L, 2.0),
            BacktestTrade(TradeBias.BUY_PE, 100.0, 105.0, 1, 3L, 4L, 1.0),
            BacktestTrade(TradeBias.BUY_CE, 100.0, 97.0, 1, 5L, 6L, 1.0)
        )
        val report = HistoricalBacktestMetrics.calculate(trades)
        assertEquals(3, report.tradeCount)
        assertEquals(2, report.winningTrades)
        assertEquals(1, report.losingTrades)
        assertEquals(66.6666667, report.winRatePercent, 0.0001)
        assertEquals(18.0, report.netPnl, 0.0001)
        assertEquals(4.0, report.maximumDrawdown, 0.0001)
        assertEquals(22.0 / 4.0, report.profitFactor!!, 0.0001)
    }

    @Test fun emptyInputDoesNotClaimAccuracy() {
        val report = HistoricalBacktestMetrics.calculate(emptyList())
        assertEquals(0, report.tradeCount)
        assertEquals(0.0, report.winRatePercent, 0.0)
        assertNull(report.profitFactor)
        assertTrue(report.warnings.isNotEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidOrChronologicallyImpossibleTrade() {
        HistoricalBacktestMetrics.calculate(
            listOf(BacktestTrade(TradeBias.BUY_CE, 100.0, 110.0, 1, 5L, 4L))
        )
    }
}
