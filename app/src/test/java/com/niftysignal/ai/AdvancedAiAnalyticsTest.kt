package com.niftysignal.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedAiAnalyticsTest {
    @Test fun sixtyPercentIsPassedAndBelowSixtyIsFailed() {
        assertTrue(AiDetection("threshold", 60, "").passed)
        assertFalse(AiDetection("threshold", 60, "").failed)
        assertTrue(AiDetection("threshold", 59, "").failed)
        assertFalse(AiDetection("threshold", 59, "").passed)
    }

    @Test fun unavailableDataIsNotCountedAsPassedOrFailed() {
        val item = AiDetection("news", null, "not connected")
        assertFalse(item.passed)
        assertFalse(item.failed)
    }

    @Test fun reportSeparatesUnavailableFromPassRateDenominator() {
        val report = AiPerformanceReport(
            1L,
            listOf(
                AiDetection("a", 60, ""),
                AiDetection("b", 40, ""),
                AiDetection("c", null, "")
            )
        )
        assertEquals(2, report.availableCount)
        assertEquals(1, report.passedCount)
        assertEquals(1, report.failedCount)
        assertEquals(50, report.passPercent)
    }
    @Test fun shortHistoryKeepsIndependentlyReadyAiToolsAvailable() {
        val start = 1_700_000_000_000L
        val candles = (0 until 5).map { index ->
            val close = 25_000.0 + index * 10.0
            MarketCandle(
                timestampMillis = start + index * 5 * 60_000L,
                open = close - 2.0,
                high = close + 5.0,
                low = close - 5.0,
                close = close,
                volume = 0.0
            )
        }
        val report = AdvancedAiAnalytics.evaluate(
            candles5m = candles,
            candles1m = emptyList(),
            now = candles.last().timestampMillis + 30_000L
        )
        val levels = report.detections.first { it.name == "Auto support & resistance" }
        val pattern = report.detections.first { it.name == "Chart pattern detection" }
        val volatility = report.detections.first { it.name == "AI risk & volatility" }
        val master = report.detections.first { it.name == "AI master signal & market bias" }

        assertTrue("Support/resistance should not wait for 22 candles", levels.score != null)
        assertTrue("Preliminary pattern analysis should be available", pattern.score != null)
        assertTrue("14-candle risk calculation must wait for enough history", volatility.score == null)
        assertTrue("Master signal retains its 22-candle requirement", master.score == null)
    }

}
