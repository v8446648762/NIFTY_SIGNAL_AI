package com.niftysignal.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketDataFreshnessTest {
    @Test
    fun acceptsRecentQuoteAndCandlesWithinTheirOwnWindows() {
        val now = 1_800_000_000_000L
        assertTrue(MarketDataFreshness.isFresh(now - 30_000L, now, MarketDataFreshness.MAX_QUOTE_AGE_MS))
        assertTrue(MarketDataFreshness.isFresh(now - 90_000L, now, MarketDataFreshness.MAX_1M_AGE_MS))
        assertTrue(MarketDataFreshness.isFresh(now - 8 * 60_000L, now, MarketDataFreshness.MAX_5M_AGE_MS))
    }

    @Test
    fun rejectsOldMissingAndFarFutureTimestamps() {
        val now = 1_800_000_000_000L
        assertFalse(MarketDataFreshness.isFresh(now - 61_000L, now, MarketDataFreshness.MAX_QUOTE_AGE_MS))
        assertFalse(MarketDataFreshness.isFresh(now - 3 * 60_000L, now, MarketDataFreshness.MAX_1M_AGE_MS))
        assertFalse(MarketDataFreshness.isFresh(now - 11 * 60_000L, now, MarketDataFreshness.MAX_5M_AGE_MS))
        assertFalse(MarketDataFreshness.isFresh(0L, now, MarketDataFreshness.MAX_1M_AGE_MS))
        assertFalse(MarketDataFreshness.isFresh(now + 6_000L, now, MarketDataFreshness.MAX_5M_AGE_MS))
    }
}
