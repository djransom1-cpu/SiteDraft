package com.example.sitedraft

import com.example.sitedraft.ui.canvas.*
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The outline cut into roof boxes (Nov 28): Benjeman is two offset boxes, Practice Run 1 one rectangle. */
class RoofBoxesTest {
    private fun load(f: String): CanvasState = Json { ignoreUnknownKeys = true }.decodeFromString(CanvasState.serializer(), File("src/desktopTest/fixtures/$f").readText())

    @Test fun `benjeman is two boxes`() {
        val st = load("benjeman_full.json"); val k = st.inchesToPx(1f)
        val b = RoofBoxes.of(st).map { listOf(it.left / k, it.top / k, it.right / k, it.bottom / k).map { v -> Math.round(v) } }
        println("BOXES benjeman $b")
        assertEquals(2, b.size)
        // Together they cover the house and not the empty corners (the old square was 435" x 422").
        val area = RoofBoxes.of(st).sumOf { (it.width / k * it.height / k).toDouble() }
        assertTrue("area $area", area < 435.0 * 422.0 * 0.9)
    }

    @Test fun `a plain rectangle is one box`() {
        val st = load("practice_run1.json"); val k = st.inchesToPx(1f)
        val b = RoofBoxes.of(st)
        println("BOXES run1 ${b.map { listOf(it.left / k, it.top / k, it.right / k, it.bottom / k) }}")
        assertEquals(1, b.size)
    }
}
