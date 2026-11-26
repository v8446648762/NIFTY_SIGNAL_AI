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
}
