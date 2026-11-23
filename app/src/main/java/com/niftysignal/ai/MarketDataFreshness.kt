package com.niftysignal.ai

/** Freshness limits shared by the live feed adapter and its tests. */
internal object MarketDataFreshness {
    const val MAX_QUOTE_AGE_MS = 60_000L
    const val MAX_1M_AGE_MS = 2 * 60_000L
    const val MAX_5M_AGE_MS = 10 * 60_000L
    private const val MAX_FUTURE_SKEW_MS = 5_000L

    fun isFresh(timestampMillis: Long, nowMillis: Long, maxAgeMillis: Long): Boolean {
        if (timestampMillis <= 0L || maxAgeMillis < 0L) return false
        val age = nowMillis - timestampMillis
        return age >= -MAX_FUTURE_SKEW_MS && age <= maxAgeMillis
    }
}
