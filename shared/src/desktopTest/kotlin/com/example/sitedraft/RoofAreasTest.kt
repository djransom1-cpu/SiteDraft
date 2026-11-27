package com.example.sitedraft

import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.RoofArea
import com.example.sitedraft.ui.canvas.RoofAreas
import com.example.sitedraft.ui.canvas.RoofEdge
import com.example.sitedraft.ui.canvas.RoofFraming
import com.example.sitedraft.ui.canvas.RoofSettings
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The traced roof (RoofAreas, Dan Nov 26), on the Ransom House's areas. Plan inches. */
class RoofAreasTest {
    private val E = RoofEdge.EAVE
    private val G = RoofEdge.GABLE
    private val O = RoofEdge.OPEN

    private fun area(name: String, pts: List<Pair<Float, Float>>, edges: List<RoofEdge>, pitch: Float? = null, wall: Float? = null, flatOf: String? = null) =
        RoofArea(id = name.lowercase(), name = name, xs = pts.map { it.first }, ys = pts.map { it.second }, edges = edges, pitch = pitch, wallHeightIn = wall, flatAtRidgeOf = flatOf)

    private val main = area("Main", listOf(164f to -82f, 360f to -82f, 360f to 253f, 164f to 253f), listOf(G, E, G, E), 12f)
    private val west = area("West gable", listOf(-5f to -4f, 262f to -4f, 262f to 169f, -5f to 169f), listOf(E, O, E, G), 12f)
    private val foyer = area("Foyer hip", listOf(-5f to 82.5f, 262f to 82.5f, 262f to 253f, -5f to 253f), listOf(O, O, E, E), 12f, flatOf = "West gable")
    private val wing = area("East wing", listOf(360f to -19f, 498f to -19f, 498f to 254f, 360f to 254f), listOf(E, G, E, O), 7f, wall = 85.5f)

    private fun state(vararg a: RoofArea) = CanvasState(building = BuildingSettings(roof = RoofSettings(framing = RoofFraming.RAFTERS, pitch = 12f, ocIn = 16f, areas = a.toList())))

    @Test
    fun `one gable area, the ridge runs down the middle between the eaves`() {
        val b = RoofAreas.built(state(main))!!
        val ridges = b.lines.filter { it.what == RoofAreas.What.RIDGE }
        assertTrue("a ridge: ${b.lines.map { it.what }.toSet()}", ridges.isNotEmpty())
        assertTrue(ridges.all { abs(it.a.x - 262f) < 0.5f && abs(it.b.x - 262f) < 0.5f })
        assertTrue(b.lines.any { it.what == RoofAreas.What.MEMBER })
    }

    @Test
    fun `the Ransom House, valleys, the flat at the west gable's ridge, and the wing against the wall`() {
        val st = state(main, west, foyer, wing)
        val b = RoofAreas.built(st)!!
        RoofAreas.summary(st).forEach { println(it) }
        b.lines.filter { it.what != RoofAreas.What.MEMBER }.forEach { println("${it.what} (${it.a.x.toInt()},${it.a.y.toInt()}) -> (${it.b.x.toInt()},${it.b.y.toInt()})") }
        val kinds = b.lines.map { it.what }.toSet()
        assertTrue("valleys: $kinds", RoofAreas.What.VALLEY in kinds)
        assertTrue("flat edge: $kinds", RoofAreas.What.FLAT_EDGE in kinds)
        assertTrue("the wing stops against the wall: $kinds", RoofAreas.What.STEP in kinds)
        // The west gable's ridge, at y 82.5, runs east from the front gable.
        assertTrue(b.lines.any { it.what == RoofAreas.What.RIDGE && abs(it.a.y - 82.5f) < 1f && abs(it.b.y - 82.5f) < 1f })
        assertTrue(b.labels.any { it.first == "FLAT" })
    }
}
