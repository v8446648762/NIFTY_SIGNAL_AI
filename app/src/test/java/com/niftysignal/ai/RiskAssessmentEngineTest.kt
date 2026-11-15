package com.niftysignal.ai

import org.junit.Assert.*
import org.junit.Test

class RiskAssessmentEngineTest {
    @Test fun missingComponentDoesNotInventScore() {
        assertNull(RiskAssessmentEngine.evaluate(RiskInputs(20.0, 30.0, 40.0, null)))
    }

    @Test fun weightedScoreAndBandAreCalculated() {
        val result = RiskAssessmentEngine.evaluate(RiskInputs(100.0, 0.0, 0.0, 0.0))
        assertNotNull(result)
        assertEquals(30, result!!.score)
        assertEquals(RiskBand.LOW, result.band)
    }

    @Test fun valuesAreClampedToRange() {
        val result = RiskAssessmentEngine.evaluate(RiskInputs(200.0, -10.0, 50.0, 50.0))
        assertEquals(52, result!!.score)
        assertEquals(RiskBand.MODERATE, result.band)
    }

    @Test fun nonFiniteInputDoesNotProduceScore() {
        assertNull(RiskAssessmentEngine.evaluate(RiskInputs(Double.NaN, 10.0, 10.0, 10.0)))
    }
}
