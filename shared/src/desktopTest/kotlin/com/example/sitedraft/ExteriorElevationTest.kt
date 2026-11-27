package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.Building
import com.example.sitedraft.ui.canvas.BuildingSettings
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.ExteriorElevation
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.RoofEnd
import com.example.sitedraft.ui.canvas.RoofSettings
import com.example.sitedraft.ui.canvas.SheetLayout
import com.example.sitedraft.ui.canvas.SheetView
import com.example.sitedraft.ui.canvas.SheetViewKind
import com.example.sitedraft.ui.canvas.Sheets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The exterior elevations (ExteriorElevation) and sheet numbers by discipline. 1' = 50px. */
class ExteriorElevationTest {
    private val inch = 50f / 12f

    /** A 40' x 28' box of 2x6 exterior walls, outside faces on (0,0) to (480", 336"). */
    private fun box(): List<DrawingElement> {
        val t = 5.5f
        fun wall(id: String, cx: Float, cy: Float, w: Float, h: Float) = DrawingElement.Symbol(id = id, symbolName = FloorPlan.EXTERIOR_WALL,
            category = PlanSymbols.WALLS, color = Color.White, center = Offset(cx * inch, cy * inch), size = Size(w * inch, h * inch))
        return listOf(
            wall("n", 240f, t / 2f, 480f, t), wall("s", 240f, 336f - t / 2f, 480f, t),
            wall("w", t / 2f, 168f, t, 336f), wall("e", 480f - t / 2f, 168f, t, 336f)
        )
    }

    private fun state(roof: RoofSettings? = RoofSettings(pitch = 6f, heelIn = 4f, overhangIn = 12f, rakeIn = 12f)) =
        CanvasState(elements = box(), building = BuildingSettings(roof = roof, gradeExposureIn = 8f))

    @Test
    fun `no walls, no elevation`() {
        assertNull(ExteriorElevation.geometry(CanvasState(), ExteriorElevation.Face.SOUTH))
    }

    @Test
    fun `ridge is plate plus heel plus half the span times the pitch`() {
        val s = state()
        val g = ExteriorElevation.geometry(s, ExteriorElevation.Face.SOUTH)!!
        val plate = ExteriorElevation.plateIn(s.building)
        assertEquals(1200f + 97.125f, plate, 0.01f)
        val ridge = g.marks.first { it.name == "RIDGE" }.elevationIn
        assertEquals(plate + 4f + 168f * 0.5f, ridge, 0.01f) // 28' span: 14' run at 6/12 = 7'
    }

    @Test
    fun `grade is the exposure below the top of the foundation`() {
        val s = state()
        val g = ExteriorElevation.geometry(s, ExteriorElevation.Face.EAST)!!
        val grade = g.marks.first { it.name == "GRADE" }.elevationIn
        assertEquals(Building.foundationTopIn(s.building) - 8f, grade, 0.01f)
    }

    @Test
    fun `the eave side runs the length plus the rakes, the gable end the span plus the overhangs`() {
        val s = state()
        val south = ExteriorElevation.geometry(s, ExteriorElevation.Face.SOUTH)!!
        val east = ExteriorElevation.geometry(s, ExteriorElevation.Face.EAST)!!
        // The ridge line on the south face: from rake to rake, 40' + 2 x 12".
        val ridgeY = -south.marks.first { it.name == "RIDGE" }.elevationIn
        val ridge = south.segs.filter { it.a.y == ridgeY && it.b.y == ridgeY }.maxByOrNull { kotlin.math.abs(it.b.x - it.a.x) }!!
        assertEquals(480f + 24f, kotlin.math.abs(ridge.b.x - ridge.a.x), 0.01f)
        // The gable end peaks at the middle of the 28' wall.
        assertTrue(east.segs.any { it.b.y == ridgeY && kotlin.math.abs(it.b.x - 168f) < 0.01f })
    }

    @Test
    fun `a hip end stops the ridge half the span in`() {
        val s = state(RoofSettings(pitch = 6f, endA = RoofEnd.HIP, endB = RoofEnd.HIP))
        val south = ExteriorElevation.geometry(s, ExteriorElevation.Face.SOUTH)!!
        val ridgeY = -south.marks.first { it.name == "RIDGE" }.elevationIn
        val ridge = south.segs.first { it.a.y == ridgeY && it.b.y == ridgeY }
        assertEquals(168f, minOf(ridge.a.x, ridge.b.x), 0.01f)
        assertEquals(480f - 168f, maxOf(ridge.a.x, ridge.b.x), 0.01f)
    }

    @Test
    fun `a window in the south wall shows on the south elevation only`() {
        val win = DrawingElement.Symbol(id = "w1", symbolName = "Window 36", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
            center = Offset(120f * inch, (336f - 2.75f) * inch), size = Size(36f * inch, 5.5f * inch),
            properties = mapOf(PlanSymbols.PROP_SILL_IN to 36f, PlanSymbols.PROP_HEIGHT_IN to 48f))
        val s = state().let { it.copy(elements = it.elements + win) }
        val south = ExteriorElevation.geometry(s, ExteriorElevation.Face.SOUTH)!!
        val north = ExteriorElevation.geometry(s, ExteriorElevation.Face.NORTH)!!
        val headY = -(1200f + 84f)
        assertTrue(south.segs.any { it.a.y == headY && it.b.y == headY })
        assertTrue(north.segs.none { it.a.y == headY && it.b.y == headY })
    }

    @Test
    fun `sheets number by discipline`() {
        val s = CanvasState(elements = box())
        val sheets = listOf(SheetLayout(number = "A-1"), SheetLayout(number = "A-2"), SheetLayout(number = "S-1"))
        val section = SheetView(kind = SheetViewKind.SECTION, title = "Wall Section")
        val foundation = SheetView(kind = SheetViewKind.PLAN, title = "Foundation plan", layerIds = listOf(Building.FOUNDATION))
        val roof = SheetView(kind = SheetViewKind.PLAN, title = "Roof framing plan", layerIds = listOf("roof-structural"))
        val plan = SheetView(kind = SheetViewKind.PLAN, title = "First floor plan", layerIds = listOf("floor1-plan", "floor1-cabinets"))
        val all = SheetView(kind = SheetViewKind.PLAN, title = "Floor Plan", layerIds = null)
        val schedule = SheetView(kind = SheetViewKind.WINDOW_SCHEDULE, title = "Window schedule")
        assertEquals("S", Sheets.disciplineOf(section, s))
        assertEquals("S", Sheets.disciplineOf(foundation, s))
        assertEquals("S", Sheets.disciplineOf(roof, s))
        assertEquals("A", Sheets.disciplineOf(plan, s))
        assertEquals("A", Sheets.disciplineOf(all, s))
        assertEquals("A", Sheets.disciplineOf(schedule, s))
        assertEquals("S-2", Sheets.nextNumber("S", sheets))
        assertEquals("A-3", Sheets.nextNumber("A", sheets))
        assertEquals("S-1", Sheets.nextNumber("S", emptyList()))
    }

    @Test
    fun `the set keeps Dan's order and renumbers around a sheet added late`() {
        val s = CanvasState(elements = box())
        fun plan(title: String, vararg ids: String) = SheetView(kind = SheetViewKind.PLAN, title = title, layerIds = ids.toList())
        val roof = SheetLayout(number = "S-1", title = "Roof framing plan", views = listOf(plan("Roof framing plan", "roof-structural")))
        val floor = SheetLayout(number = "A-1", title = "First floor plan", views = listOf(plan("First floor plan", "floor1-plan", "floor1-cabinets")))
        val elev = SheetLayout(number = "A-2", title = "Window schedule", views = listOf(SheetView(kind = SheetViewKind.WINDOW_SCHEDULE, title = "Window schedule")))
        val section = SheetLayout(number = "A-3", title = "Wall Section", views = listOf(SheetView(kind = SheetViewKind.SECTION, title = "Wall Section")))
        val foundation = SheetLayout(number = "S-2", title = "Foundation plan", views = listOf(plan("Foundation plan", Building.FOUNDATION)))
        val framing = SheetLayout(number = "S-3", title = "First floor framing plan", views = listOf(plan("First floor framing plan", "floor1-structural")))
        val site = SheetLayout(number = "A-9", title = "Site plan", views = listOf(plan("Site plan", "site")))
        val cover = SheetLayout(number = "", title = "Cover")
        val out = Sheets.renumbered(listOf(roof, floor, elev, section, foundation, framing, site, cover), s)
        assertEquals(
            listOf("T-1 Cover", "C-1 Site plan", "S-1 Foundation plan", "S-2 First floor framing plan", "S-3 Roof framing plan",
                "S-4 Wall Section", "A-1 First floor plan", "A-2 Window schedule"),
            out.map { "${it.number} ${it.title}" }
        )
    }

    @Test
    fun `a number typed by hand stays and the rest number around it`() {
        val s = CanvasState(elements = box())
        fun plan(vararg ids: String) = SheetView(kind = SheetViewKind.PLAN, title = "x", layerIds = ids.toList())
        val roof = SheetLayout(number = "S-1", title = "Roof", views = listOf(plan("roof-structural")), numberManual = true)
        val foundation = SheetLayout(number = "", title = "Foundation", views = listOf(plan(Building.FOUNDATION)))
        val out = Sheets.renumbered(listOf(roof, foundation), s)
        assertEquals(listOf("S-2 Foundation", "S-1 Roof"), out.map { "${it.number} ${it.title}" })
    }

    @Test
    fun `a hip end gets its own jacks from the end eave to the hip`() {
        val s = CanvasState(elements = box())
        val rafters = com.example.sitedraft.ui.canvas.RoofFraming.RAFTERS
        val gable = com.example.sitedraft.ui.canvas.RoofPlan.geometry(s, RoofSettings(framing = rafters, ocIn = 24f))!!
        val hip = com.example.sitedraft.ui.canvas.RoofPlan.geometry(s, RoofSettings(framing = rafters, ocIn = 24f, endA = RoofEnd.HIP))!!
        // Members running the long way (along the ridge) are only in a hip end.
        fun endJacks(g: com.example.sitedraft.ui.canvas.RoofPlan.Geometry) =
            g.lines.filter { it.kind == com.example.sitedraft.ui.canvas.RoofPlan.Kind.MEMBER && kotlin.math.abs(it.a.y - it.b.y) < 0.01f }
        assertEquals(0, endJacks(gable).size)
        val jacks = endJacks(hip)
        // 28' span at 24" from the middle: the king common and 7 each side.
        assertTrue(jacks.size in 13..15)
        val king = jacks.maxByOrNull { kotlin.math.abs(it.b.x - it.a.x) }!!
        assertEquals(hip.ridgeStart.x, maxOf(king.a.x, king.b.x), 0.1f) // the king common reaches the end of the ridge
    }

    @Test
    fun `a truss hip end is a step-down hip with a girder where the truss is 5 feet tall`() {
        val s = CanvasState(elements = box())
        val k = inch
        val r = RoofSettings(ocIn = 24f, pitch = 6f, heelIn = 4f, endA = RoofEnd.HIP)
        // 6/12, 4" heel: 5' tall at 112" in, on the 24" layout 120" (10'), under half the 28' span.
        assertEquals(120f, com.example.sitedraft.ui.canvas.RoofPlan.girderSetbackIn(r, 336f)!!, 0.01f)
        val g = com.example.sitedraft.ui.canvas.RoofPlan.geometry(s, r)!!
        assertEquals(1, g.girders.size)
        val girderX = g.footprint.left + 120f * k
        val members = g.lines.filter { it.kind == com.example.sitedraft.ui.canvas.RoofPlan.Kind.MEMBER }
        // End jacks (running the long way) stop at the girder, not the ridge.
        val endJacks = members.filter { kotlin.math.abs(it.a.y - it.b.y) < 0.01f }
        assertEquals(girderX, endJacks.maxOf { maxOf(it.a.x, it.b.x) }, 0.1f)
        // Step-downs: between the girder and the commons the trusses run eave to eave.
        val full = members.filter { kotlin.math.abs(it.a.x - it.b.x) < 0.01f && kotlin.math.abs(it.a.y - it.b.y) >= g.eave.height - 0.1f }
        assertTrue(full.any { it.a.x > girderX + 0.1f && it.a.x < g.ridgeStart.x - 0.1f })
        // Rafters: no girder.
        assertEquals(0, com.example.sitedraft.ui.canvas.RoofPlan.geometry(s, r.copy(framing = com.example.sitedraft.ui.canvas.RoofFraming.RAFTERS))!!.girders.size)
    }

    @Test
    fun `the roofing shows as courses or seams on the roof and is named over it`() {
        fun roofLines(c: com.example.sitedraft.ui.canvas.RoofCovering): Pair<Int, Int> {
            val s = state(RoofSettings(pitch = 6f, covering = c))
            val g = ExteriorElevation.geometry(s, ExteriorElevation.Face.SOUTH)!!
            val ridgeY = -g.marks.first { it.name == "RIDGE" }.elevationIn
            val plateY = -g.marks.first { it.name == "TOP OF PLATE" }.elevationIn
            val onRoof = g.segs.filter { it.pen == ExteriorElevation.Pen.THIN && minOf(it.a.y, it.b.y) > ridgeY - 0.01f && maxOf(it.a.y, it.b.y) < plateY + 12f && maxOf(it.a.x, it.b.x) < 500f }
            assertTrue(g.labels.any { it.text == c.label.uppercase() })
            return onRoof.count { it.a.y == it.b.y } to onRoof.count { it.a.x == it.b.x }
        }
        val (shingleRows, shingleSeams) = roofLines(com.example.sitedraft.ui.canvas.RoofCovering.ARCH_SHINGLES)
        assertTrue(shingleRows > 20)   // 14' run up the slope at 5 5/8" is about 33 courses
        assertEquals(0, shingleSeams)
        val (seamRows, seams) = roofLines(com.example.sitedraft.ui.canvas.RoofCovering.STANDING_SEAM)
        assertEquals(0, seamRows)
        assertTrue(seams > 25)         // 42' at 16" panels
    }

    @Test
    fun `the roof plan is its own layer and view, on the A sheets`() {
        val s = CanvasState(elements = box())
        assertTrue(s.effectiveLayersHasRoofPlan())
        val choices = Sheets.planChoices(s).toMap()
        assertEquals(listOf("roof-structural"), choices["Roof framing plan"])
        assertEquals(listOf("roof-plan"), choices["Roof plan"])
        val roofPlan = SheetView(kind = SheetViewKind.PLAN, title = "Roof plan", layerIds = listOf("roof-plan"))
        assertEquals(Sheets.Slot("A", 200), Sheets.slotOf(roofPlan, s))
    }

    private fun CanvasState.effectiveLayersHasRoofPlan() =
        com.example.sitedraft.ui.canvas.Building.defaultLayers(building).any { it.id == "roof-plan" && it.trade == com.example.sitedraft.ui.canvas.LayerTrade.ROOF_PLAN }

    @Test
    fun `lap siding runs at its exposure and stops at the window casing, a stone wainscot under it`() {
        val win = DrawingElement.Symbol(id = "w1", symbolName = "Window 36", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
            center = Offset(120f * inch, (336f - 2.75f) * inch), size = Size(36f * inch, 5.5f * inch),
            properties = mapOf(PlanSymbols.PROP_SILL_IN to 36f, PlanSymbols.PROP_HEIGHT_IN to 48f))
        val fin = com.example.sitedraft.ui.canvas.ExteriorFinish(
            main = com.example.sitedraft.ui.canvas.WallFinish.LAP, mainIn = 7f,
            wainscot = com.example.sitedraft.ui.canvas.WallFinish.STONE, wainscotHeightIn = 24f)
        val base = state()
        val s = base.copy(elements = base.elements + win, building = base.building.copy(exterior = fin))
        val g = ExteriorElevation.geometry(s, ExteriorElevation.Face.SOUTH)!!
        val fTop = ExteriorElevation.foundationTopIn(s.building)
        // A course through the window (sill 1236", head 1284"): split around the casing, nothing between 98.5" and 141.5".
        val inWindow = 1200f + 60f
        val e = (generateSequence(fTop + 24f + 2f + 7f) { it + 7f }.take(100).first { it > inWindow - 3.6f && it < inWindow + 3.6f })
        val course = g.segs.filter { it.pen == ExteriorElevation.Pen.THIN && it.a.y == -e && it.b.y == -e && maxOf(it.a.x, it.b.x) < 480f }
        assertEquals(2, course.size)
        assertTrue(course.none { minOf(it.a.x, it.b.x) < 141f && maxOf(it.a.x, it.b.x) > 99f })
        // The wainscot cap across the wall at 24" up.
        assertTrue(g.segs.any { it.pen == ExteriorElevation.Pen.MEDIUM && it.a.y == -(fTop + 24f) && it.b.y == -(fTop + 24f) })
    }

    @Test
    fun `the wainscot cap stops at a door`() {
        val door = DrawingElement.Symbol(id = "d1", symbolName = "Door 36", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
            center = Offset(240f * inch, (336f - 2.75f - 18f) * inch), size = Size(36f * inch, 36f * inch))
        val fin = com.example.sitedraft.ui.canvas.ExteriorFinish(wainscot = com.example.sitedraft.ui.canvas.WallFinish.STONE, wainscotHeightIn = 36f)
        val base = state()
        val s = base.copy(elements = base.elements + door, building = base.building.copy(exterior = fin))
        val g = ExteriorElevation.geometry(s, ExteriorElevation.Face.SOUTH)!!
        val capY = -(ExteriorElevation.foundationTopIn(s.building) + 36f)
        val cap = g.segs.filter { it.pen == ExteriorElevation.Pen.MEDIUM && it.a.y == capY && it.b.y == capY }
        assertEquals(2, cap.size) // split around the door and its casing
        assertTrue(cap.none { minOf(it.a.x, it.b.x) < 240f && maxOf(it.a.x, it.b.x) > 240f })
    }

    @Test
    fun `a double door shows its two leaves, a single door its knob`() {
        fun door(name: String, w: Float) = DrawingElement.Symbol(id = "d1", symbolName = name, category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
            center = Offset(240f * inch, (336f - 2.75f - w / 2f) * inch), size = Size(w * inch, w * inch))
        val base = state()
        fun centreLines(name: String, w: Float): Int {
            val g = ExteriorElevation.geometry(base.copy(elements = base.elements + door(name, w)), ExteriorElevation.Face.SOUTH)!!
            // Full-height verticals within an inch of the door's centre.
            return g.segs.count { it.a.x == it.b.x && kotlin.math.abs(it.a.x - 240f) < 1f && kotlin.math.abs(it.a.y - it.b.y) > 70f }
        }
        assertEquals(2, centreLines("Door 6'-0\" Double", 72f))
        assertEquals(0, centreLines("Door 3'-0\"", 36f))
    }

    @Test
    fun `survey bearings read the way surveys write them`() {
        val az = com.example.sitedraft.ui.canvas.SiteLot::azimuthOf
        assertEquals(45.5, az("N 45°30'00\" E")!!, 1e-6)
        assertEquals(45.5, az("N45-30-00E")!!, 1e-6)
        assertEquals(180.0 - 12.0, az("S 12 E")!!, 1e-6)
        assertEquals(270.0, az("N 90 W")!!, 1e-6)
        assertEquals(180.0 + 30.25, az("S 30.25 W")!!, 1e-6)
        assertNull(az("north-ish"))
    }

    @Test
    fun `a 100 by 150 lot closes, its setbacks sit inside, and the house's distances are measured`() {
        val calls = listOf(
            com.example.sitedraft.ui.canvas.LotCall("N 90 E", 100f, setbackFt = 10f),
            com.example.sitedraft.ui.canvas.LotCall("S 0 E", 150f, setbackFt = 10f),
            com.example.sitedraft.ui.canvas.LotCall("N 90 W", 100f, setbackFt = 30f),
            com.example.sitedraft.ui.canvas.LotCall("N 0 E", 150f, setbackFt = 10f)
        )
        val site = com.example.sitedraft.ui.canvas.SitePlan(calls)
        assertNull(com.example.sitedraft.ui.canvas.SiteLot.closure(site)!!.second)
        val s = CanvasState(elements = box(), building = BuildingSettings(site = site))
        val g = com.example.sitedraft.ui.canvas.SiteLot.geometry(s)!!
        assertTrue(g.closes)
        val foot = 50f
        // The lot is centred on the 40' x 28' house: 30' to each side, 61' front and back.
        assertEquals(100f * foot, g.corners.maxOf { it.x } - g.corners.minOf { it.x }, 0.5f)
        val sb = g.setback!!
        assertEquals(80f * foot, sb.maxOf { it.x } - sb.minOf { it.x }, 0.5f)   // 10' each side
        assertEquals(110f * foot, sb.maxOf { it.y } - sb.minOf { it.y }, 0.5f)  // 10' + 30'
        val d = com.example.sitedraft.ui.canvas.SiteLot.houseDistances(g).map { (c, n) -> (c - n).getDistance() / foot }
        assertEquals(4, d.size)
        assertEquals(30f, d[0 + 1], 0.05f) // east line: (100 - 40) / 2
        assertEquals(61f, d[0], 0.05f)     // north line: (150 - 28) / 2
    }

    @Test
    fun `the suite's steel engine gives the textbook W8x15 numbers`() {
        val w8 = com.example.sitedraft.ui.canvas.SteelEngine.shape("W8x15")!!
        // 20' simple span, 1 klf direct, no self weight: M = wL^2/8 = 50 kip-ft; Mn/1.67 = 50 x 13.6 / 12 / 1.67 = 33.9 kip-ft.
        val r = com.example.sitedraft.ui.canvas.SteelEngine.analyzeSteelBeam(w8, 20.0, directLoads = true, w_dl_plf_in = 1000.0, includeSelfWeight = false)
        assertEquals(50.0, r.M_max_kipft, 0.05)
        assertEquals(50.0 / (50.0 * 13.6 / 12.0 / 1.67), r.stressRatio, 0.001)
        assertTrue(!r.isPass)
    }

    @Test
    fun `a 40 by 28 basement gets one center beam, columns no more than 12 feet apart, sized`() {
        val s = CanvasState(elements = box())
        val d = com.example.sitedraft.ui.canvas.StructuralDesign.design(s)!!
        assertEquals(1, d.beams.size)
        assertEquals(14f, d.joistSpanFt, 0.01f)
        val b = d.beams.first()
        assertEquals(4, b.spans)                       // 38'-9" between the walls, columns no more than 12' apart: 4 spans of 9'-8"
        assertTrue(b.spanFt <= 12f)
        assertEquals("W8x15", b.shape!!.name)          // Dan's smallest passes, under his 1/4" total deflection too
        assertTrue(b.result!!.isPass && b.result!!.delta_total_in <= 0.25)
        assertEquals(26f, b.padIn, 0.01f)              // about 8.3 kips over 2,000 psf, rounded up to 2"
        assertTrue(b.post != null && b.postResult!!.isPass)
        assertTrue(com.example.sitedraft.ui.canvas.StructuralDesign.rows(s).any { it.first == "Beam" && it.second.startsWith("W8x15") })
    }

    /** A 60' x 30' box of 2x6 exterior walls. */
    private fun bigBox(): List<DrawingElement> {
        val t = 5.5f
        fun wall(id: String, cx: Float, cy: Float, w: Float, h: Float) = DrawingElement.Symbol(id = id, symbolName = FloorPlan.EXTERIOR_WALL,
            category = PlanSymbols.WALLS, color = Color.White, center = Offset(cx * inch, cy * inch), size = Size(w * inch, h * inch))
        return listOf(
            wall("n", 360f, t / 2f, 720f, t), wall("s", 360f, 360f - t / 2f, 720f, t),
            wall("w", t / 2f, 180f, t, 360f), wall("e", 720f - t / 2f, 180f, t, 360f)
        )
    }

    @Test
    fun `the three questions - deflection, column spacing, W8 or best - pick the beam`() {
        fun beam(spec: com.example.sitedraft.ui.canvas.StructureSpecs) =
            com.example.sitedraft.ui.canvas.StructuralDesign.design(CanvasState(elements = bigBox(), building = BuildingSettings(structure = spec)))!!.beams.first()
        val w8 = beam(com.example.sitedraft.ui.canvas.StructureSpecs())         // 12' columns, 1/4", W8 only (a 7'-5 1/2" basement)
        assertEquals(5, w8.spans)                                               // 58'-9": 5 spans of 11'-9"
        assertTrue(w8.shape!!.name.startsWith("W8x") && w8.result!!.delta_total_in <= 0.25)
        val best = beam(com.example.sitedraft.ui.canvas.StructureSpecs(w8Only = false))
        assertTrue(best.shape!!.weight <= w8.shape!!.weight)                    // the lightest of any depth is never heavier
        val standard = beam(com.example.sitedraft.ui.canvas.StructureSpecs(standardPosts = true))
        assertEquals(8, standard.spans)                                         // the standard 8': 8 spans of 7'-4"
        assertEquals("W8x15", standard.shape!!.name)
        val code = beam(com.example.sitedraft.ui.canvas.StructureSpecs(maxTotalDeflectionIn = 0f))
        assertTrue(code.shape!!.weight <= w8.shape!!.weight)                    // code limits only: never stiffer than with the 1/4" limit
    }

    @Test
    fun `a beam picked by hand spaces the columns to what it carries`() {
        val d = com.example.sitedraft.ui.canvas.StructuralDesign.design(CanvasState(elements = bigBox(),
            building = BuildingSettings(structure = com.example.sitedraft.ui.canvas.StructureSpecs(beamShape = "W8x15"))))!!.beams.first()
        assertEquals("W8x15", d.shape!!.name)
        assertTrue(d.beamPicked)
        assertTrue(d.spans > 5 && d.result!!.delta_total_in <= 0.25)   // closer than the 5 spans of 11'-9" a W8x18 needs
        val tooSmall = com.example.sitedraft.ui.canvas.StructuralDesign.design(CanvasState(elements = bigBox(),
            building = BuildingSettings(structure = com.example.sitedraft.ui.canvas.StructureSpecs(beamShape = "W8x15", standardPosts = false, postSpacingFt = 30f))))!!.beams.first()
        assertTrue(tooSmall.shape != null)
    }

    @Test
    fun `a post moved by hand sets the spans, and the beam and pads follow`() {
        var s = com.example.sitedraft.ui.canvas.DerivedLayers.sync(CanvasState(elements = box(), building = BuildingSettings(autoLayers = true)))
        val id = com.example.sitedraft.ui.canvas.DerivedLayers.GEN_PREFIX + "post:b0:1"
        assertTrue(s.elements.any { it.id == id })
        // Move the first post 3' along the beam (the beam runs left to right on this 40' x 28' box).
        s = s.copy(elements = s.elements.map { if (it.id == id) (it as DrawingElement.Symbol).copy(center = it.center + Offset(36f * inch, 0f)) else it })
        val b = com.example.sitedraft.ui.canvas.StructuralDesign.design(s)!!.beams.first()
        assertTrue(b.postsByHand)
        assertEquals(4, b.spans)
        assertTrue(b.spanFt > 12f)                       // 9'-8" + 3'
        assertTrue(b.posts.map { it.padIn }.distinct().size > 1 || b.posts.maxOf { it.loadKips } > b.posts.minOf { it.loadKips })
        val s2 = com.example.sitedraft.ui.canvas.DerivedLayers.sync(s)
        val pad = s2.elements.first { it.id == com.example.sitedraft.ui.canvas.DerivedLayers.GEN_PREFIX + "pad:b0:1" } as DrawingElement.Symbol
        val post = s2.elements.first { it.id == id } as DrawingElement.Symbol
        assertEquals(post.center.x, pad.center.x, 0.5f)   // the pad followed the post
    }

    @Test
    fun `the overhang detail draws from the roof settings and sorts with the A sheet wall details`() {
        val truss = CanvasState(elements = box(), building = BuildingSettings(roof = RoofSettings(pitch = 8f, heelIn = 4f, overhangIn = 12f)))
        val g = com.example.sitedraft.ui.canvas.OverhangDetail.geometry(truss)
        assertTrue(g.parts.size > 10 && g.extent.width > 0f)
        assertTrue(g.notes.any { it.text.startsWith("TRUSSES @ 24") })
        assertTrue(g.notes.any { it.text.startsWith("ICE & WATER SHIELD, 2 ROLLS") })
        assertTrue(g.notes.any { it.text == "VENTED SOFFIT" })
        val rafters = truss.copy(building = truss.building.copy(roof = RoofSettings(framing = com.example.sitedraft.ui.canvas.RoofFraming.RAFTERS, rafterSize = "2x10",
            soffit = com.example.sitedraft.ui.canvas.SoffitType.OPEN, attic = com.example.sitedraft.ui.canvas.AtticInsulation.SPRAY_FOAM)))
        val gr = com.example.sitedraft.ui.canvas.OverhangDetail.geometry(rafters)
        assertTrue(gr.notes.any { it.text.startsWith("2x10 RAFTERS") } && gr.notes.any { it.text == "EXPOSED RAFTER TAILS" })
        assertTrue(gr.notes.none { it.text.startsWith("RAFTER VENT") })
        val sym = com.example.sitedraft.ui.canvas.DetailKit.newSymbol(truss, com.example.sitedraft.ui.canvas.DetailKit.Detail.OVERHANG, Offset.Zero)
        val s = truss.copy(elements = truss.elements + sym)
        val view = SheetView(kind = SheetViewKind.ELEVATION, elementId = sym.id, title = "Overhang Detail")
        assertEquals(Sheets.Slot("A", 500), Sheets.slotOf(view, s))
    }

    @Test
    fun `the beam pocket and column details draw from the structure and sort with the S sheet details`() {
        val st = CanvasState(elements = box(), building = BuildingSettings(roof = RoofSettings(pitch = 8f, heelIn = 4f, overhangIn = 12f)))
        val kit = com.example.sitedraft.ui.canvas.DetailKit
        val pocket = kit.geometry(st, com.example.sitedraft.ui.canvas.DetailKit.Detail.BEAM_POCKET)
        assertTrue(pocket.parts.size > 10 && pocket.extent.width > 0f && pocket.extent.height > 0f)
        assertTrue(pocket.notes.any { it.text.endsWith("STEEL BEAM") })
        assertTrue(pocket.notes.any { it.text.startsWith("1/2\" AIR SPACE") })
        assertTrue(pocket.notes.any { it.text.contains("FLOOR JOISTS @") })
        val column = kit.geometry(st, com.example.sitedraft.ui.canvas.DetailKit.Detail.COLUMN)
        assertTrue(column.parts.size > 10 && column.extent.width > 0f)
        assertTrue(column.notes.any { it.text.contains("BASE PLATE ON PAD, NO ANCHOR BOLTS") })
        assertTrue(column.notes.any { it.text.contains("CAP PLATE") })
        assertTrue(column.notes.any { it.text.contains("CONCRETE PAD FOOTING") })
        for (kind in listOf(com.example.sitedraft.ui.canvas.DetailKit.Detail.BEAM_POCKET, com.example.sitedraft.ui.canvas.DetailKit.Detail.COLUMN)) {
            val sym = kit.newSymbol(st, kind, Offset.Zero)
            val s = st.copy(elements = st.elements + sym)
            val view = SheetView(kind = SheetViewKind.ELEVATION, elementId = sym.id, title = kind.title)
            assertEquals("S", Sheets.slotOf(view, s)?.series)
        }
    }
}
