package com.example.sitedraft

import com.example.sitedraft.ui.canvas.splitFeetInches
import org.junit.Assert.assertEquals
import org.junit.Test

class FeetInchesTest {

    private val hundredth = 0.01f
    private val eighth = 1f / 8f

    @Test
    fun `value a hair under a whole foot carries into feet at hundredths`() {
        val (feet, inches) = splitFeetInches(2.9999f, hundredth)
        assertEquals(3, feet)
        assertEquals(0f, inches, 0.0001f)
    }

    @Test
    fun `value a hair under a whole foot carries into feet at eighths`() {
        // 2.999' = 35.988", within 1/16" of 36" - rounds up to 3' at eighth resolution.
        val (feet, inches) = splitFeetInches(2.999f, eighth)
        assertEquals(3, feet)
        assertEquals(0f, inches, 0.0001f)
    }

    @Test
    fun `eleven point nine nine inches stays below twelve at hundredths`() {
        // 1' 11.99" exactly - must not round up to 12.00
        val (feet, inches) = splitFeetInches(1f + 11.99f / 12f, hundredth)
        assertEquals(1, feet)
        assertEquals(11.99f, inches, 0.001f)
    }

    @Test
    fun `plain split is unchanged`() {
        val (feet, inches) = splitFeetInches(2.5f, hundredth)
        assertEquals(2, feet)
        assertEquals(6f, inches, 0.0001f)
    }

    @Test
    fun `eighths resolution snaps to the nearest eighth`() {
        // 1' 11 1/4" = 1.9375'
        val (feet, inches) = splitFeetInches(1.9375f, eighth)
        assertEquals(1, feet)
        assertEquals(11.25f, inches, 0.0001f)

        // 0.3' = 3.6" -> nearest eighth is 3 5/8" (3.625)
        val (f2, i2) = splitFeetInches(0.3f, eighth)
        assertEquals(0, f2)
        assertEquals(3.625f, i2, 0.0001f)
    }

    @Test
    fun `zero is zero`() {
        val (feet, inches) = splitFeetInches(0f, hundredth)
        assertEquals(0, feet)
        assertEquals(0f, inches, 0.0001f)
    }
}
