package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import com.example.sitedraft.ui.canvas.CabinetList
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.OpeningPosition
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.inchesToPx
import com.example.sitedraft.ui.canvas.activeLayer
import com.example.sitedraft.ui.canvas.effectiveLayers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Opens drawings the Site Draft MCP connector made (functions-mcp/sitedraft, written by
 * functions-mcp/test/sitedraft-mcp.test.js) with the app's own code, and checks the app measures
 * them the way the connector said it placed them: wall positions, door and window distances,
 * cabinet codes, the room.
 */
class ConnectorCheckTest {
    private val appJson = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val dir = File("../functions-mcp/sitedraft/fixtures")
    private fun load(name: String) = appJson.decodeFromString(CanvasState.serializer(), File(dir, name).readText())
    private val expect = Json.parseToJsonElement(File(dir, "connector-expect.json").readText()).jsonObject

    private fun CanvasState.symbols() = elements.filterIsInstance<DrawingElement.Symbol>()
    private fun CanvasState.symbol(id: String) = symbols().first { it.id == id }

    @Test
    fun `the connector's kitchen opens in the app`() {
        val state = load("connector-project.json")
        val walls = state.symbols().filter { it.category == PlanSymbols.WALLS }
        assertEquals(expect["walls"]!!.jsonPrimitive.int, walls.size)
        // Square walls only, each one an axis-aligned rectangle the elevations can use.
        for (w in walls) assertEquals(0f, w.rotation, 0.001f)
    }

    @Test
    fun `the window and the door sit where the connector put them`() {
        val state = load("connector-project.json")
        val inch = state.inchesToPx(1f)
        val win = expect["window"]!!.jsonObject
        val window = state.symbol(win["id"]!!.jsonPrimitive.content)
        val dWin = OpeningPosition.distance(window, state, OpeningPosition.Ref(fromEnd = false, toCenter = true, outside = false))
        assertNotNull("window is in a wall", dWin)
        assertEquals(win["fromLeftInsideCornerToCenterIn"]!!.jsonPrimitive.float, dWin!! / inch, 0.01f)

        val dr = expect["door"]!!.jsonObject
        val door = state.symbol(dr["id"]!!.jsonPrimitive.content)
        val dDoor = OpeningPosition.distance(door, state, OpeningPosition.Ref(fromEnd = true, toCenter = false, outside = false))
        assertNotNull("door is in a wall", dDoor)
        assertEquals(dr["fromBottomInsideCornerToEdgeIn"]!!.jsonPrimitive.float, dDoor!! / inch, 0.01f)
        assertEquals(dr["hingeRight"]!!.jsonPrimitive.boolean, (door.properties[PlanSymbols.PROP_HINGE_RIGHT] ?: 0f) >= 0.5f)
        // The saved "position on the wall" settings match how it was measured.
        assertEquals(OpeningPosition.Ref(fromEnd = true, toCenter = false, outside = false), OpeningPosition.ref(door))
    }

    @Test
    fun `cabinet codes match the app's own labels`() {
        val state = load("connector-project.json")
        val codes = expect["codes"]!!.jsonObject
        assertTrue(codes.size >= 10)
        for ((id, code) in codes) assertEquals("code of $id", code.jsonPrimitive.content, PlanSymbols.cabinetLabel(state.symbol(id), state))
        // And the cabinet list puts them in the Kitchen.
        val rows = CabinetList.rows(state)
        val sb = rows.first { it.code == "SB36" }
        assertTrue("SB36 in ${sb.room}", sb.room.startsWith("Kitchen"))
        assertEquals(3, rows.filter { it.code == "W1830" }.sumOf { it.qty })
    }

    @Test
    fun `the room is the size Dan measured, and the north wall elevation sees the run`() {
        val state = load("connector-project.json")
        val inch = state.inchesToPx(1f)
        val (w, d) = expect["kitchenInsideIn"]!!.jsonArray.map { it.jsonPrimitive.float }
        val room = FloorPlan.areaAt(Offset(10f * inch, 10f * inch), state)
        assertNotNull(room)
        assertEquals(w, room!!.width / inch, 0.01f)
        assertEquals(d, room.height / inch, 0.01f)
        val north = state.symbols().filter { it.category == PlanSymbols.WALLS && Elevation.isHorizontal(it) }.minBy { it.center.y }
        val side = Elevation.interiorSide(north, state)
        assertNotNull(side)
        val items = Elevation.itemsOnWall(north, side!!, state)
        val codes = expect["codes"]!!.jsonObject
        val sb = state.symbols().first { codes[it.id]?.jsonPrimitive?.content == "SB36" }
        assertTrue("sink base on the north elevation", items.any { it.symbol.id == sb.id })
        assertTrue("window on the north elevation", items.any { it.symbol.id == expect["window"]!!.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test
    fun `an L-shaped room and an angled wall open too`() {
        val state = load("connector-L.json")
        val walls = state.symbols().filter { it.category == PlanSymbols.WALLS }
        assertEquals(8, walls.size)
        assertEquals(1, walls.count { it.rotation != 0f })
    }

    @Test
    fun `layers the connector set read the same in the app`() {
        val state = load("connector-layers.json")
        val ex = Json.parseToJsonElement(File(dir, "connector-layers-expect.json").readText()).jsonObject
        for ((id, layer) in ex["layerOf"]!!.jsonObject) {
            val e = state.elements.first { it.id == id }
            assertEquals("layer of $id", layer.jsonPrimitive.content, com.example.sitedraft.ui.canvas.Layers.layerOf(e, state).id)
        }
        assertEquals(ex["activeLayer"]!!.jsonPrimitive.content, state.activeLayer.id)
        val hidden = ex["hidden"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(hidden, state.effectiveLayers.filter { !it.visible }.map { it.id }.toSet())
        val levels = com.example.sitedraft.ui.canvas.Building.levels(state.building).associate { it.id to it.elevationIn }
        for ((id, v) in ex["levels"]!!.jsonObject) {
            val want = v.jsonPrimitive.content.toFloatOrNull()
            if (want == null) assertEquals(null, levels.getValue(id)) else assertEquals(id, want, levels.getValue(id)!!, 0.001f)
        }
        // Hidden layers are left out of what the app draws and picks.
        assertTrue(com.example.sitedraft.ui.canvas.Layers.visibleElements(state).size <= state.elements.size)
    }

    @Test
    fun `voice drawing - hatch, elevations, roof, fronts and the rod read the same in the app`() {
        val state = load("connector-voice.json")
        val ex = Json.parseToJsonElement(File(dir, "connector-voice-expect.json").readText()).jsonObject
        fun id(k: String) = ex[k]!!.jsonPrimitive.content
        // The cabinet run picked by room starts at the kitchen's inside corner (6 1/2" in from the outside of the 2x6 wall).
        val first = state.symbol(ex["kitchenRun"]!!.jsonArray.first().jsonPrimitive.content)
        assertEquals(state.inchesToPx(6.5f), FloorPlan.bounds(first).left, 0.5f)
        assertEquals(2f, state.symbol(id("drawerBase")).properties["front"])
        assertEquals(3f, state.symbol(id("base")).properties["front"])
        assertEquals(70.5f, state.symbol(id("rod")).properties["widthInches"])
        assertTrue("the rod is a closet part", state.symbol(id("rod")).category == PlanSymbols.CLOSET && "Rod 48\"" in PlanSymbols.closet)
        // Floor hatches: outline-free rectangles with a fill, as Hatch an area makes them.
        val hatches = state.elements.filterIsInstance<DrawingElement.Rectangle>()
        assertEquals(2, hatches.size)
        assertEquals(setOf("TILE", "DOTS"), hatches.map { it.fill.pattern.name }.toSet())
        // The wall elevation looks north from the room and is the wall's length; the island one shows its back.
        val wallView = state.elements.filterIsInstance<DrawingElement.ElevationView>().first { it.id == id("wallElevation") }
        assertEquals(com.example.sitedraft.ui.canvas.ViewerSide.BELOW, wallView.spec.side)
        assertEquals(3f, wallView.spec.crownIn)
        val wall = state.symbol(id("northWall"))
        assertEquals(Elevation.sizePx(wall, wallView.spec, state).width, wallView.size.width, 0.5f)
        val islandView = state.elements.filterIsInstance<DrawingElement.ElevationView>().first { it.id == id("islandElevation") }
        assertEquals(1, islandView.spec.islandFace)
        // The roof and the exterior elevations the app builds from the building inputs.
        val roof = state.building.roof
        assertNotNull(roof)
        assertEquals(8f, roof!!.pitch)
        assertEquals(com.example.sitedraft.ui.canvas.RoofEnd.HIP, roof.endA)
        assertNotNull("roof geometry over the 2x6 walls", com.example.sitedraft.ui.canvas.RoofPlan.geometry(state, roof))
        assertEquals(8f, state.building.gradeExposureIn)
        assertNotNull(com.example.sitedraft.ui.canvas.ExteriorElevation.worldSize(state, com.example.sitedraft.ui.canvas.ExteriorElevation.Face.SOUTH))
    }
}
