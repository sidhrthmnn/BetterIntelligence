package com.example

import com.example.engine.BackendValidation
import org.junit.Assert.*
import org.junit.Test

class BackendValidationTest {
    @Test fun acceptsCorrectResultsAcrossCpuGpuWordAndSymbolDifferences() {
        val multiply = BackendValidation.probes.first()
        assertTrue(BackendValidation.correct(multiply, "102"))
        assertTrue(BackendValidation.correct(multiply, "17 multiplied by 6 is 102. So, the answer is 102."))
        assertTrue(BackendValidation.correct(multiply, "17 × 6 = 102. So, the answer is 102."))
        assertTrue(BackendValidation.correct(multiply, "The answer is 102.0."))
        assertTrue(BackendValidation.correct(BackendValidation.probes[1], "23 + 19 = 42. So, the answer is 42."))
        assertTrue(BackendValidation.correct(BackendValidation.probes[2], "9 multiplied by 7 is 63. So the answer is 63."))
    }
    @Test fun rejectsWrongNumbersContradictionsUncertaintyBlankAndDegenerateOutput() {
        val probe = BackendValidation.probes.first()
        for (text in listOf("103", "17 × 6 = 103", "17 divided by 6 is 102", "102 or 103", "It is not 102", "Maybe 102", "102 is wrong", "", "<unused15>102", "102 102 102 102", "17 × 6"))
            assertFalse(text, BackendValidation.correct(probe, text))
    }
}
