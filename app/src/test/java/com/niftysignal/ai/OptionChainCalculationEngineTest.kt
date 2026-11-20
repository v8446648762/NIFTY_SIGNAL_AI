package com.niftysignal.ai

import org.junit.Assert.*
import org.junit.Test

class OptionChainCalculationEngineTest {
    private fun row(
        strike: Double, callOi: Double?, callChange: Double?,
        putOi: Double?, putChange: Double?
    ) = OptionChainContract(
        strike = strike,
        callOi = callOi, callOiChange = callChange, callVolume = 10.0, callLtp = 100.0,
        putOi = putOi, putOiChange = putChange, putVolume = 12.0, putLtp = 105.0
    )

    @Test fun calculatesTotalsPcrAndOiWalls() {
        val result = OptionChainCalculationEngine.calculate(
            listOf(
                row(25000.0, 100.0, 10.0, 300.0, 30.0),
                row(25050.0, 400.0, 40.0, 200.0, 20.0),
                row(25100.0, 200.0, 20.0, 500.0, 50.0)
            ),
            spot = 25050.0
        )
        assertEquals(700.0, result.totalCallOi, 0.0001)
        assertEquals(1000.0, result.totalPutOi, 0.0001)
        assertEquals(1000.0 / 700.0, result.pcr!!, 0.0001)
        assertEquals(25050.0, result.highestCallOiStrike!!, 0.0001)
        assertEquals(25100.0, result.highestPutOiStrike!!, 0.0001)
        assertTrue(result.supportCandidates.contains(25000.0))
        assertTrue(result.resistanceCandidates.contains(25100.0))
    }

    @Test fun missingOiIsIgnoredAndZeroCallOiMakesPcrUnavailable() {
        val result = OptionChainCalculationEngine.calculate(
            listOf(row(25000.0, null, null, 150.0, 5.0)),
            spot = 25000.0
        )
        assertEquals(0.0, result.totalCallOi, 0.0001)
        assertEquals(150.0, result.totalPutOi, 0.0001)
        assertNull(result.pcr)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test fun invalidStrikesAreIgnoredAndEmptyValidChainIsRejected() {
        try {
            OptionChainCalculationEngine.calculate(
                listOf(row(Double.NaN, 1.0, 1.0, 1.0, 1.0), row(-1.0, 1.0, 1.0, 1.0, 1.0))
            )
            fail("Expected an empty valid chain to be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected: invalid strike rows must not produce misleading metrics.
        }
    }
}
