package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.example.sitedraft.ui.canvas.Fill
import com.example.sitedraft.ui.canvas.FillPattern
import com.example.sitedraft.ui.canvas.HatchGeometry
import com.example.sitedraft.ui.canvas.herringbonePlanks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TileSizeTest {
    private val box = Rect(0f, 0f, 200f, 100f)

    @Test
    fun `a 12 x 24 grid steps 24 across and 12 down`() {
        val h = HatchGeometry.pattern(Fill(pattern = FillPattern.TILE, spacing = 12f, length = 24f), box)
        val xs = h.segments.filter { it.first.x == it.second.x }.map { it.first.x }.sorted()
        val ys = h.segments.filter { it.first.y == it.second.y }.map { it.first.y }.sorted()
        assertEquals(24f, xs[1] - xs[0], 0.001f)
        assertEquals(12f, ys[1] - ys[0], 0.001f)
    }

    @Test
    fun `subway with a length uses it for the brick, offset by half`() {
        val h = HatchGeometry.pattern(Fill(pattern = FillPattern.BRICK, spacing = 3f, length = 6f), box)
        val firstRow = h.segments.filter { it.first.x == it.second.x && it.first.y == 0f }.map { it.first.x }.sorted()
        assertEquals(6f, firstRow[1] - firstRow[0], 0.001f)
        val secondRow = h.segments.filter { it.first.x == it.second.x && it.first.y == 3f }.map { it.first.x }.sorted()
        assertEquals(3f, secondRow[0], 0.001f)
    }

    @Test
    fun `herringbone planks are the real size and do not overlap`() {
        val planks = herringbonePlanks(box, 3f, 12f)
        assertTrue(planks.size > 20)
        for (q in planks) {
            val sides = (0 until 4).map { (q[(it + 1) % 4] - q[it]).getDistance() }.sorted()
            assertEquals(3f, sides[0], 0.01f); assertEquals(12f, sides[3], 0.01f)
        }
        // No two plank centres closer than the plank width: none sit on top of another.
        val centres = planks.map { q -> Offset(q.map { it.x }.average().toFloat(), q.map { it.y }.average().toFloat()) }
        for (i in centres.indices) for (j in i + 1 until centres.size) {
            assertTrue((centres[i] - centres[j]).getDistance() > 2.9f)
        }
        // Coverage: the planks inside the box add up to about its area.
        val inside = centres.count { box.deflate(15f).contains(it) }
        val area = box.deflate(15f).width * box.deflate(15f).height
        assertTrue("inside $inside", abs(inside * 36f - area) / area < 0.25f)
    }
}
