package com.wata

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Basic unit test to verify test infrastructure works.
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun `string concatenation works`() {
        val result = "Hello" + " " + "Wata"
        assertEquals("Hello Wata", result)
    }
}
