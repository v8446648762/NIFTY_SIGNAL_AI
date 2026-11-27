package com.niftysignal.ai

import org.junit.Assert.*
import org.junit.Test

class CentralMarketEngineTest {
    private fun candles(count: Int, interval: Long, now: Long): List<MarketCandle> =
        (0 until count).map { index ->
            val close = 22000.0 + index * 5.0
            MarketCandle(
                timestampMillis = now - (count - index) * interval,
                open = close - 2.0,
                high = close + 3.0,
                low = close - 4.0,
                close = close,
                volume = 0.0
            )
        }

    @Test fun aggregatesAllLiveCalculationsFromOneFreshSnapshot() {
        val now = 1_800_000_000_000L
        val snapshot = MarketDataSnapshot(
            fiveMinuteCandles = candles(40, 300_000L, now),
            oneMinuteCandles = candles(8, 60_000L, now),
            receivedAtMillis = now - 1_000L
        )

        val state = CentralMarketEngine.evaluate(snapshot, now)

        assertNotNull(state)
        assertTrue(state!!.isLive)
        assertNotNull(state.signalDecision)
        assertNotNull(state.marketIntelligence)
        assertNotNull(state.nextCandlePrediction)
        assertEquals(10, state.aiPerformanceReport.detections.size)
        assertNotNull(state.riskAssessment)
        assertEquals(40, state.fiveMinuteChartIndicators.ema9Series.size)
        assertEquals(8, state.oneMinuteChartIndicators.ema21Series.size)
        assertEquals(TradeBias.WAIT, state.advancedSignalResult.bias)
        assertFalse(state.volumeProfile.available)
    }

    @Test fun staleSnapshotCannotProduceLiveSignalOrRisk() {
        val now = 1_800_000_000_000L
        val snapshot = MarketDataSnapshot(
            fiveMinuteCandles = candles(40, 300_000L, now),
            oneMinuteCandles = candles(8, 60_000L, now),
            receivedAtMillis = now - 1_000L,
            isStale = true,
            staleReason = "Test stale feed"
        )

        val state = CentralMarketEngine.evaluate(snapshot, now)

        assertNotNull(state)
        assertFalse(state!!.isLive)
        assertEquals(TradeBias.WAIT, state.signalDecision.bias)
        assertFalse(state.signalDecision.confirmed)
        assertEquals(TradeBias.WAIT, state.advancedSignalResult.bias)
        assertEquals("STALE / PAUSED", state.advancedSignalResult.trend)
        assertNull(state.riskAssessment)
        assertTrue(state.aiPerformanceReport.detections.all { it.score == null })
    }

    @Test fun recentUpstoxOptionChainFlowsIntoCentralAiDetections() {
        val now = System.currentTimeMillis()
        val analysis = OptionChainAnalysis(
            strikes = listOf(
                OptionChainStrike(
                    strike = 22000.0,
                    callOi = 100.0,
                    callOiChange = 10.0,
                    callVolume = null,
                    callLtp = 120.0,
                    putOi = 120.0,
                    putOiChange = 15.0,
                    putVolume = null,
                    putLtp = 110.0
                )
            ),
            totalCallOi = 100.0,
            totalPutOi = 120.0,
            pcr = 1.20,
            maxCallOiStrike = 22000.0,
            maxPutOiStrike = 22000.0,
            maxCallAdditionStrike = 22000.0,
            maxPutAdditionStrike = 22000.0,
            totalRows = 1,
            warnings = listOf("Per-strike exchange timestamps are not exposed.")
        )
        CentralMarketEngine.publishOptionChainSnapshot(
            OptionChainSnapshot(analysis, now, 22000.0)
        )
        val snapshot = MarketDataSnapshot(
            fiveMinuteCandles = candles(40, 300_000L, now),
            oneMinuteCandles = candles(8, 60_000L, now),
            receivedAtMillis = now - 1_000L
        )

        val state = CentralMarketEngine.evaluate(snapshot, now)
        val oiDetection = state?.aiPerformanceReport?.detections?.firstOrNull {
            it.name == "Option chain & OI analysis"
        }

        assertNotNull(oiDetection)
        assertNotNull(oiDetection!!.score)
        assertTrue(oiDetection.detail.contains("Upstox API snapshot"))
        CentralMarketEngine.publishOptionChainSnapshot(null)
    }

    @Test fun rejectsInvalidOrOutOfOrderSnapshots() {
        val now = 1_800_000_000_000L
        val valid = candles(40, 300_000L, now)
        val outOfOrder = valid.toMutableList().also {
            it[10] = it[10].copy(timestampMillis = it[9].timestampMillis)
        }
        val snapshot = MarketDataSnapshot(
            fiveMinuteCandles = outOfOrder,
            oneMinuteCandles = candles(8, 60_000L, now),
            receivedAtMillis = now
        )
        assertNull(CentralMarketEngine.evaluate(snapshot, now))
    }
}
