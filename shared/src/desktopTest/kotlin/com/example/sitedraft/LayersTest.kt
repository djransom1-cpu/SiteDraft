package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.Building
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.DxfExport
import com.example.sitedraft.ui.canvas.FoundationType
import com.example.sitedraft.ui.canvas.LayerEdits
import com.example.sitedraft.ui.canvas.LayerTrade
import com.example.sitedraft.ui.canvas.Layers
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.SheetView
import com.example.sitedraft.ui.canvas.SheetViewKind
import com.example.sitedraft.ui.canvas.Sheets
import com.example.sitedraft.ui.canvas.UpperFloor
import com.example.sitedraft.ui.canvas.activeLayer
import com.example.sitedraft.ui.canvas.effectiveLayers
import com.example.sitedraft.ui.canvas.levels
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Levels and layers (Building.kt), phase 1 of foundation to roof. */
class LayersTest {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private fun wall(id: String = "w1") = DrawingElement.Symbol(id = id, symbolName = "2x4 Wall", category = PlanSymbols.WALLS,
        center = Offset(100f, 100f), size = Size(200f, 20f), color = Color.White)
    private fun cabinet(id: String = "c1") = DrawingElement.Symbol(id = id, symbolName = "Base 24\"", category = PlanSymbols.CABINETS,
        center = Offset(100f, 130f), size = Size(100f, 100f), color = Color.White)
    private fun sofa(id: String = "f1") = DrawingElement.Symbol(id = id, symbolName = "Sofa", category = PlanSymbols.FURNITURE,
        center = Offset(300f, 300f), size = Size(140f, 70f), color = Color.White)

    @Test
    fun `levels come from Dan's defaults`() {
        val s = BuildingSettings()
        val byId = Building.levels(s).associateBy { it.id }
        // 100'-0" top of subfloor; 3/4 + 9 1/4 + 1 1/2 = 11 1/2" stack; 11 block of 8" = 7'-4"; 8" footing.
        assertEquals(1200f, byId.getValue(Building.FLOOR1).elevationIn!!, 0.001f)
        assertEquals(1190f, byId.getValue(Building.framingId(1)).elevationIn!!, 0.001f)
        assertEquals(1188.5f, byId.getValue(Building.FOUNDATION).elevationIn!!, 0.001f) // 99'-0 1/2"
        assertEquals(1100.5f, byId.getValue(Building.FOOTING).elevationIn!!, 0.001f) // 91'-8 1/2"
        assertEquals(1092.5f, Building.footingBottomIn(s), 0.001f) // 91'-0 1/2"
        assertNull(byId.getValue(Building.ROOF).elevationIn)
    }

    @Test
    fun `changing one input moves the levels that depend on it`() {
        val s = BuildingSettings(blockCourses = 12)
        assertEquals(1188.5f - 96f, Building.footingTopIn(s), 0.001f)
        assertEquals(1188.5f, Building.foundationTopIn(s), 0.001f)
        val crawl = Building.levels(BuildingSettings(foundation = FoundationType.CRAWL)).first { it.id == Building.CRAWL_FLOOR }
        assertEquals(1190f - 24f, crawl.elevationIn!!, 0.001f) // 24" below the bottom of the joists
        val slab = Building.levels(BuildingSettings(foundation = FoundationType.SLAB))
        assertTrue(slab.none { it.id == Building.FOOTING || it.id == Building.FOUNDATION })
    }

    @Test
    fun `Dan's layer list, one structural plan cabinets and furniture layer per floor`() {
        val layers = Building.defaultLayers(BuildingSettings(upperFloors = listOf(UpperFloor("Second floor", 1310f))))
        val trades = layers.map { it.trade }
        assertTrue(LayerTrade.FOUNDATION in trades)
        assertEquals(2, layers.count { it.trade == LayerTrade.FLOOR_STRUCTURAL })
        assertEquals(2, layers.count { it.trade == LayerTrade.FLOOR_PLAN })
        assertEquals(2, layers.count { it.trade == LayerTrade.CABINETS })
        assertEquals(2, layers.count { it.trade == LayerTrade.FURNITURE })
        assertTrue(LayerTrade.ROOF_STRUCTURAL in trades && LayerTrade.EXTERIOR_ELEVATION in trades && LayerTrade.SITE_PLAN in trades)
        assertEquals("Second floor structural", layers.first { it.id == "floor2-structural" }.name)
    }

    @Test
    fun `an old project opens with everything on its layer and nothing moved`() {
        val old = CanvasState(elements = listOf(wall(), cabinet(), sofa()))
        val text = json.encodeToString(CanvasState.serializer(), old)
        // A project that never used layers is written exactly as before: no new keys.
        assertFalse(text.contains("layerId") || text.contains("\"layers\"") || text.contains("\"building\""))
        val opened = json.decodeFromString(CanvasState.serializer(), text)
        assertEquals(old.elements, opened.elements)
        assertEquals(Building.FLOOR1_PLAN, Layers.layerOf(opened.elements[0], opened).id)
        assertEquals(Building.FLOOR1_CABINETS, Layers.layerOf(opened.elements[1], opened).id)
        assertEquals(Building.FLOOR1_FURNITURE, Layers.layerOf(opened.elements[2], opened).id)
        assertEquals(Building.FLOOR1_PLAN, opened.activeLayer.id)
    }

    @Test
    fun `layers, tags and inputs survive saving and opening`() {
        var s = CanvasState(elements = listOf(wall().copy(layerId = Building.FOUNDATION)))
        s = LayerEdits.setBuilding(s, BuildingSettings(blockCourses = 9)).state
        s = LayerEdits.setVisible(s, "site", false).state
        s = LayerEdits.setActive(s, Building.FOUNDATION).state
        val back = json.decodeFromString(CanvasState.serializer(), json.encodeToString(CanvasState.serializer(), s))
        assertEquals(Building.FOUNDATION, back.elements.single().layerId)
        assertEquals(9, back.building.blockCourses)
        assertFalse(back.effectiveLayers.first { it.id == "site" }.visible)
        assertEquals(Building.FOUNDATION, back.activeLayer.id)
    }

    @Test
    fun `hidden layers are not drawn picked or printed, and the active one can't be hidden`() {
        var s = CanvasState(elements = listOf(wall(), cabinet()), selectedElementId = "c1")
        val r = LayerEdits.setVisible(s, Building.FLOOR1_PLAN, false)
        assertNotNull(r.refused) // the active layer
        s = LayerEdits.setVisible(s, Building.FLOOR1_CABINETS, false).state
        assertEquals(listOf("w1"), Layers.visibleElements(s).map { it.id })
        assertNull(s.selectedElementId) // nothing hidden stays selected
        // Making a hidden layer active shows it again.
        s = LayerEdits.setActive(s, Building.FLOOR1_CABINETS).state
        assertTrue(s.effectiveLayers.first { it.id == Building.FLOOR1_CABINETS }.visible)
    }

    @Test
    fun `working-layer mode - active layer full, floor plan faint under it, the rest hidden until turned on`() {
        val s = CanvasState()
        val d = Layers.displayMap(s)
        // On the first-floor plan: its cabinets and furniture with it, nothing else.
        assertEquals(Layers.Display.FULL, d[Building.FLOOR1_PLAN])
        assertEquals(Layers.Display.FULL, d[Building.FLOOR1_CABINETS])
        assertEquals(Layers.Display.FULL, d[Building.FLOOR1_FURNITURE])
        assertEquals(Layers.Display.HIDDEN, d[Building.FOUNDATION])
        assertEquals(Layers.Display.HIDDEN, d["floor1-structural"])
        assertEquals(Layers.Display.HIDDEN, d["site"])

        // On the foundation: it full, the first-floor plan faint, cabinets hidden.
        val onFoundation = LayerEdits.setActive(s, Building.FOUNDATION).state
        val f = Layers.displayMap(onFoundation)
        assertEquals(Layers.Display.FULL, f[Building.FOUNDATION])
        assertEquals(Layers.Display.FAINT, f[Building.FLOOR1_PLAN])
        assertEquals(Layers.Display.HIDDEN, f[Building.FLOOR1_CABINETS])

        // A floor's framing gets that floor's plan underneath.
        val two = LayerEdits.setBuilding(s, BuildingSettings(upperFloors = listOf(UpperFloor("Second floor", 1310f)))).state
        assertEquals("floor2-plan", Layers.referencePlanId(LayerEdits.setActive(two, "floor2-structural").state))

        // Turning a layer on shows it faint; turning it off hides it again.
        val withFraming = LayerEdits.toggleShown(onFoundation, "floor1-structural").state
        assertEquals(Layers.Display.FAINT, Layers.displayMap(withFraming)["floor1-structural"])
        assertEquals(Layers.Display.HIDDEN, Layers.displayMap(LayerEdits.toggleShown(withFraming, "floor1-structural").state)["floor1-structural"])

        // The floor plan under the active layer switches off with the eye; the active layer can't.
        val planOff = LayerEdits.toggleShown(onFoundation, Building.FLOOR1_PLAN).state
        assertEquals(Layers.Display.HIDDEN, Layers.displayMap(planOff)[Building.FLOOR1_PLAN])
        assertNotNull(LayerEdits.toggleShown(onFoundation, Building.FOUNDATION).refused)
    }

    @Test
    fun `what the screen shows is what can be picked, and a layer turned off drops out of the selection`() {
        val footing = wall("ft").copy(layerId = Building.FOUNDATION)
        var s = CanvasState(elements = listOf(wall(), cabinet(), footing), selectedElementIds = setOf("ft", "w1"))
        // On the plan the foundation is hidden.
        assertEquals(listOf("w1", "c1"), Layers.shownElements(s).map { it.id })
        // Printing and DXF still go by the eye: everything is visible.
        assertEquals(3, Layers.visibleElements(s).size)
        s = LayerEdits.toggleShown(s, Building.FOUNDATION).state
        assertEquals(setOf("w1", "c1", "ft"), Layers.shownElements(s).map { it.id }.toSet())
        s = LayerEdits.toggleShown(s, Building.FOUNDATION).state
        assertEquals(setOf("w1"), s.selectedElementIds)
    }

    @Test
    fun `layers draw in stacking order, foundation first and the upper layer on top`() {
        val footing = wall("ft").copy(layerId = Building.FOUNDATION)
        val joist = wall("j").copy(layerId = "floor1-structural")
        val roof = wall("r").copy(layerId = "roof-structural")
        // Drawn in the "wrong" order: roof, plan wall, cabinet, footing, joist.
        val s = CanvasState(elements = listOf(roof, wall(), cabinet(), footing, joist))
        assertEquals(listOf("ft", "j", "w1", "c1", "r"), Layers.inStackOrder(s.elements, s).map { it.id })
    }

    @Test
    fun `new drawing goes on the active layer, cabinets on the floor's cabinets layer`() {
        val vm = DrawingManager().also { it.onEvent(DrawingEvent.ToggleSnap(false)) }
        vm.onEvent(DrawingEvent.SetActiveLayer(Building.FOUNDATION))
        vm.onEvent(DrawingEvent.SelectSymbol("2x4 Wall", PlanSymbols.WALLS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(500f, 0f)))
        assertEquals(Building.FOUNDATION, vm.state.value.elements.last().layerId)

        vm.onEvent(DrawingEvent.SetBuilding(BuildingSettings(upperFloors = listOf(UpperFloor("Second floor", 1310f)))))
        vm.onEvent(DrawingEvent.SetActiveLayer("floor2-plan"))
        vm.onEvent(DrawingEvent.SelectSymbol("Base 24\"", PlanSymbols.CABINETS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(800f, 400f)))
        assertEquals("floor2-cabinets", vm.state.value.elements.last().layerId)

        // On the first-floor plan (the default) a new wall stays untagged: the file is as before.
        vm.onEvent(DrawingEvent.SetActiveLayer(Building.FLOOR1_PLAN))
        vm.onEvent(DrawingEvent.SelectSymbol("2x4 Wall", PlanSymbols.WALLS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(1200f, 0f)))
        assertNull(vm.state.value.elements.last().layerId)
    }

    @Test
    fun `undo puts a deleted element back on its old layer, not the active one`() {
        val vm = DrawingManager().also { it.onEvent(DrawingEvent.ToggleSnap(false)) }
        vm.onEvent(DrawingEvent.SelectSymbol("2x4 Wall", PlanSymbols.WALLS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(500f, 0f)))
        val w = vm.state.value.elements.last()
        assertNull(w.layerId)
        vm.onEvent(DrawingEvent.SetActiveLayer("site"))
        vm.onEvent(DrawingEvent.SelectElements(setOf(w.id)))
        vm.onEvent(DrawingEvent.DeleteSelectedElement)
        assertTrue(vm.state.value.elements.none { it.id == w.id })
        vm.onEvent(DrawingEvent.Undo)
        assertNull(vm.state.value.elements.single { it.id == w.id }.layerId)
    }

    @Test
    fun `move the selection to a layer, and back with undo`() {
        val vm = DrawingManager().also { it.onEvent(DrawingEvent.ToggleSnap(false)) }
        vm.onEvent(DrawingEvent.SelectSymbol("2x4 Wall", PlanSymbols.WALLS))
        vm.onEvent(DrawingEvent.PlaceAt(Offset(500f, 0f)))
        val w = vm.state.value.elements.last()
        vm.onEvent(DrawingEvent.SelectElements(setOf(w.id)))
        vm.onEvent(DrawingEvent.MoveSelectionToLayer(Building.FOUNDATION))
        val moved = vm.state.value.elements.single { it.id == w.id }
        assertEquals(Building.FOUNDATION, moved.layerId)
        assertEquals((w as DrawingElement.Symbol).center, (moved as DrawingElement.Symbol).center)
        vm.onEvent(DrawingEvent.Undo)
        assertNull(vm.state.value.elements.single { it.id == w.id }.layerId)
    }

    @Test
    fun `layers of your own, removed only when empty`() {
        var s = CanvasState()
        s = LayerEdits.add(s, "Electrical", Building.FLOOR1, id = "custom-e").state
        assertEquals(LayerTrade.CUSTOM, s.effectiveLayers.last().trade)
        assertNotNull(LayerEdits.add(s, "electrical").refused) // same name
        assertNotNull(LayerEdits.remove(s, Building.FOUNDATION).refused) // built-in
        val used = s.copy(elements = listOf(wall().copy(layerId = "custom-e")))
        assertNotNull(LayerEdits.remove(used, "custom-e").refused)
        assertTrue(LayerEdits.remove(s, "custom-e").state.effectiveLayers.none { it.id == "custom-e" })
    }

    @Test
    fun `adding and removing a floor keeps custom layers, visibility and anything drawn`() {
        var s = LayerEdits.setVisible(CanvasState(), "site", false).state
        s = LayerEdits.add(s, "Electrical", id = "custom-e").state
        s = LayerEdits.setBuilding(s, BuildingSettings(upperFloors = listOf(UpperFloor("Second floor", 1310f)))).state
        assertTrue(s.effectiveLayers.any { it.id == "floor2-plan" })
        assertTrue(s.effectiveLayers.any { it.id == "custom-e" })
        assertFalse(s.effectiveLayers.first { it.id == "site" }.visible)
        s = s.copy(elements = listOf(wall().copy(layerId = "floor2-plan")))
        s = LayerEdits.setBuilding(s, BuildingSettings()).state
        // The floor is gone from the inputs, but its plan layer stays while something is on it.
        assertTrue(s.effectiveLayers.any { it.id == "floor2-plan" })
        assertTrue(s.effectiveLayers.none { it.id == "floor2-cabinets" })
        assertNotNull(LayerEdits.setBuilding(s, BuildingSettings(upperFloors = listOf(UpperFloor("Second floor", 1100f)))).refused)
    }

    @Test
    fun `a sheet view shows only its layers`() {
        val s = CanvasState(elements = listOf(wall(), cabinet(), DrawingElement.Line(id = "f", start = Offset(0f, 0f), end = Offset(10f, 0f),
            color = Color.White, strokeWidth = 2f, layerId = Building.FOUNDATION)))
        val foundation = SheetView(kind = SheetViewKind.PLAN, title = "Foundation plan", layerIds = listOf(Building.FOUNDATION))
        assertEquals(listOf("f"), Sheets.viewElements(foundation, s).map { it.id })
        val all = SheetView(kind = SheetViewKind.PLAN, title = "Floor Plan")
        assertEquals(3, Sheets.viewElements(all, s).size)
        val choices = Sheets.planChoices(s).map { it.first }
        assertTrue("Foundation plan" in choices && "First floor plan" in choices && "First floor framing plan" in choices)
        // A plan sheet starts from the floor plan; "all layers" is last.
        assertEquals("First floor plan", choices.first())
        assertEquals("Floor plan (all layers)", choices.last())
    }

    @Test
    fun `DXF puts a layer's elements on its own copy of the AutoCAD layers, hidden ones left out`() {
        var s = CanvasState(elements = listOf(wall(), wall("w2").copy(layerId = Building.FOUNDATION), wall("w3").copy(layerId = "site")))
        s = LayerEdits.setVisible(s, "site", false).state
        val text = DxfExport.export(s)
        val layerNames = text.lines().map { it.trim() }.zipWithNext().filter { it.first == "8" }.map { it.second }.toSet()
        assertTrue("WALLS" in layerNames)
        assertTrue(layerNames.any { it.startsWith("FOUNDATION") && it.endsWith("-WALLS") })
        assertTrue(layerNames.none { it.startsWith("SITE") })
    }
}
