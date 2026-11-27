package com.example.sitedraft

import com.example.sitedraft.ui.canvas.Trim
import org.junit.Assert.assertEquals
import org.junit.Test

/** Windows side by side trimmed as one unit (Dan, Nov 25: the three bedroom windows). */
class WindowGangTest {
    private val inch = 50f / 12f
    private fun w(from: Float, to: Float, sill: Float = 20f, head: Float = 80f) = floatArrayOf(from * inch, to * inch, sill, head)

    @Test
    fun `three windows four and a half inches apart are one unit`() {
        // Practice Run 1's bedroom: 36" windows at 31.5, 72 and 112.5 along the wall.
        val g = Trim.gangs(listOf(w(31.5f, 67.5f), w(72f, 108f), w(112.5f, 148.5f)), inch)
        assertEquals(listOf(listOf(0, 1, 2)), g)
    }

    @Test
    fun `windows far apart or at another height are trimmed on their own`() {
        val g = Trim.gangs(listOf(w(0f, 36f), w(60f, 96f), w(100f, 136f, sill = 30f)), inch)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), g)
        // Given out of order, still grouped along the wall.
        assertEquals(listOf(listOf(1, 0)), Trim.gangs(listOf(w(40f, 76f), w(0f, 36f)), inch))
    }
}
