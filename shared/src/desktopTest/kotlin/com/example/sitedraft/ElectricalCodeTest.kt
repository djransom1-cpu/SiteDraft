package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.ElectricalCode
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.Mep
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.RoomElectrical
import com.example.sitedraft.ui.canvas.inchesToPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Electrical to code in one press (Dan, Nov 27): the spacing rules, room types, and a laid-out bedroom and kitchen. */
class ElectricalCodeTest {
    private val base = CanvasState(projectName = "Elec")
    private val inch = base.inchesToPx(1f)

    private fun wall(id: String, l: Float, t: Float, r: Float, b: Float) = DrawingElement.Symbol(
        id = id, symbolName = FloorPlan.INTERIOR_WALL, category = PlanSymbols.WALLS, color = Color.White,
        center = Offset((l + r) / 2f * inch, (t + b) / 2f * inch), size = Size((r - l) * inch, (b - t) * inch)
    )
    private fun label(text: String, x: Float, y: Float) =
        DrawingElement.Text(text = text, topLeft = Offset(x * inch, y * inch), fontSize = 14f, color = Color.White)

    /** A room 139 1/2" x 168" inside (walls 4 1/2"), a 3' door in its south wall swinging in, 30" from the west corner. */
    private fun box(name: String, extra: List<DrawingElement> = emptyList()): CanvasState {
        val walls = listOf(wall("n", 0f, 0f, 148.5f, 4.5f), wall("s", 0f, 172.5f, 148.5f, 177f),
            wall("w", 0f, 0f, 4.5f, 177f), wall("e", 144f, 0f, 148.5f, 177f))
        val door = DrawingElement.Symbol(id = "door", symbolName = "Door 3'-0\"", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
            center = Offset((4.5f + 30f + 18f) * inch, (174.75f - 18f) * inch), size = Size(36f * inch, 36f * inch))
        return base.copy(elements = walls + door + label(name, 40f, 60f) + extra)
    }

    private fun syms(s: CanvasState) = s.elements.filterIsInstance<DrawingElement.Symbol>().filter { it.category == Mep.ELECTRICAL }

    @Test
    fun `fill leaves no point further than the reach`() {
        // A 25' wall: 3 plugs (2 would leave a gap at 12'). A 100" wall: 1, in the middle.
        assertEquals(3, ElectricalCode.fill(0f, 300f, emptyList(), 72f).size)
        assertEquals(listOf(50f), ElectricalCode.fill(0f, 100f, emptyList(), 72f))
        // 100" of counter: 3 counter plugs, the first and last within 24" of the ends, none more than 48" apart.
        val c = ElectricalCode.fill(0f, 100f, emptyList(), 24f)
        assertEquals(3, c.size)
        assertTrue(c.first() <= 24f && 100f - c.last() <= 24f)
        assertTrue(c.zipWithNext().all { (a, b) -> b - a <= 48f })
        // A plug already at 72" on a 144" wall covers it.
        assertTrue(ElectricalCode.fill(0f, 144f, listOf(72f), 72f).isEmpty())
    }

    @Test
    fun `room types come from the room name`() {
        assertEquals(ElectricalCode.RoomType.BEDROOM, ElectricalCode.typeOf("MASTER BEDROOM"))
        assertEquals(ElectricalCode.RoomType.BATH, ElectricalCode.typeOf("Hall Bath"))
        assertEquals(ElectricalCode.RoomType.CLOSET, ElectricalCode.typeOf("MASTER CLOSET"))
        assertEquals(ElectricalCode.RoomType.KITCHEN, ElectricalCode.typeOf("Kitchen"))
        assertEquals(ElectricalCode.RoomType.LAUNDRY, ElectricalCode.typeOf("LAUNDRY"))
        assertEquals(ElectricalCode.RoomType.OTHER, ElectricalCode.typeOf("Room 12' x 10'"))
    }

    @Test
    fun `a bedroom gets wall plugs to the 6 foot rule, a switched light at the door and a smoke alarm`() {
        val st = box("BEDROOM 2")
        val out = ElectricalCode.apply(st)
        val e = syms(out)
        val plugs = e.filter { it.symbolName == Mep.DUPLEX }
        assertTrue("plugs: ${plugs.size}", plugs.size >= 4)
        // Walk the floor line inch by inch outside the door: every point within 6' (plus the box half) of a plug.
        val r = androidx.compose.ui.geometry.Rect(4.5f * inch, 4.5f * inch, 144f * inch, 172.5f * inch)
        val samples = buildList {
            var x = r.left; while (x <= r.right) { add(Offset(x, r.top)); add(Offset(x, r.bottom)); x += 6f * inch }
            var y = r.top; while (y <= r.bottom) { add(Offset(r.left, y)); add(Offset(r.right, y)); y += 6f * inch }
        }.filter { p -> !(abs(p.y - r.bottom) < inch && p.x / inch in 30f..76f) } // the door
        for (p in samples) {
            // Measured along the walls; a straight line is never longer, so this is a fair floor for the test.
            val near = plugs.minOf { (it.center - p).getDistance() } / inch
            assertTrue("point ${p / inch} is ${near}\" from a plug", near <= 78f)
        }
        // Each plug has its back to a wall (within 8" of a face) for the elevations.
        for (pl in plugs) {
            val c = pl.center / inch
            val off = minOf(abs(c.x - 4.5f), abs(c.x - 144f), abs(c.y - 4.5f), abs(c.y - 172.5f))
            assertTrue("plug off the wall: $off", off <= 8f)
        }
        val light = e.single { it.symbolName == Mep.CEILING_LIGHT }
        // The door here leads outside, so there is also the porch light's switch beside it; the room's switch runs the light.
        val sw = e.filter { Mep.isSwitch(it.symbolName) }.first { s -> out.switchLegs.any { it.switchId == s.id && it.deviceId == light.id } }
        // The switch is on the south wall by the door's latch side, inside the room.
        assertTrue("switch y ${sw.center.y / inch}", abs(sw.center.y / inch - 168.5f) < 6f)
        // The bedroom's smoke alarm is just inside its door (Dan): within 3' of the door's middle.
        val smoke = e.single { it.symbolName == Mep.SMOKE }
        assertTrue("smoke at ${smoke.center / inch}", (smoke.center / inch - Offset(52.5f, 172.5f)).getDistance() <= 36f)
    }

    @Test
    fun `redo keeps what was moved and does not double it`() {
        val first = ElectricalCode.apply(box("BEDROOM 2"))
        val light = syms(first).single { it.symbolName == Mep.CEILING_LIGHT }
        val moved = first.copy(elements = first.elements.map { if (it.id == light.id) light.copy(center = light.center + Offset(20f * inch, 0f)) else it })
        val again = ElectricalCode.apply(moved)
        val lights = syms(again).filter { it.symbolName == Mep.CEILING_LIGHT }
        assertEquals(1, lights.size)
        assertEquals(light.id, lights.single().id)
        // The kept light still has a switch.
        assertTrue(again.switchLegs.any { it.deviceId == light.id && syms(again).any { s -> s.id == it.switchId } })
        // The plugs were laid out fresh: the same count, no pile-up.
        assertEquals(syms(first).count { it.symbolName == Mep.DUPLEX }, syms(again).count { it.symbolName == Mep.DUPLEX })
    }

    @Test
    fun `kitchen counters get counter plugs no more than 4 feet apart`() {
        // 96" of base cabinets along the north wall, from the west corner.
        val cabs = (0 until 4).map { i ->
            DrawingElement.Symbol(id = "b$i", symbolName = "Base 24\"", category = PlanSymbols.CABINETS, color = Color.White,
                center = Offset((4.5f + 12f + 24f * i) * inch, (4.5f + 12f) * inch), size = Size(24f * inch, 24f * inch))
        }
        val out = ElectricalCode.apply(box("KITCHEN", cabs))
        val counter = syms(out).filter { it.symbolName == Mep.COUNTER }.map { it.center.x / inch - 4.5f }.sorted()
        assertTrue("counter plugs: $counter", counter.size >= 2)
        assertTrue(counter.first() <= 24.5f && 96f - counter.last() <= 24.5f)
        assertTrue(counter.zipWithNext().all { (a, b) -> b - a <= 48.5f })
        // No wall plug behind the counter.
        assertFalse(syms(out).any { it.symbolName == Mep.DUPLEX && it.center.y / inch < 12f && it.center.x / inch < 100f })
        assertTrue(syms(out).count { it.symbolName == Mep.CAN_6 } >= 4)
    }

    @Test
    fun `a kitchen with an island gets pendants over it by default`() {
        val island = DrawingElement.Symbol(id = "isl", symbolName = "Island 3'x6'", category = PlanSymbols.CABINETS, color = Color.White,
            center = Offset(74f * inch, 90f * inch), size = Size(72f * inch, 36f * inch))
        val out = ElectricalCode.apply(box("KITCHEN", listOf(island)))
        assertTrue(syms(out).count { it.symbolName == Mep.PENDANT } >= 2)
    }

    @Test
    fun `an open room with no door gets its switch beside the opening`() {
        // The south wall stops 30" short of the east wall: a 30" opening, no door.
        val walls = listOf(wall("n", 0f, 0f, 148.5f, 4.5f), wall("s", 0f, 172.5f, 114f, 177f),
            wall("w", 0f, 0f, 4.5f, 177f), wall("e", 144f, 0f, 148.5f, 177f))
        val st = base.copy(elements = walls + label("KITCHEN", 40f, 60f))
        val out = ElectricalCode.apply(st)
        val sw = syms(out).filter { Mep.isSwitch(it.symbolName) }
        assertTrue("switches: ${sw.map { it.center / inch }}", sw.any { it.center.y / inch > 160f && it.center.x / inch in 100f..114f })
    }

    @Test
    fun `a room with two doors gets a 3-way switch at each`() {
        // A second 3' door in the north wall, swinging into the room.
        val north = DrawingElement.Symbol(id = "door2", symbolName = "Door 3'-0\"", category = PlanSymbols.DOORS_WINDOWS, color = Color.White,
            center = Offset((144f - 30f - 18f) * inch, (2.25f + 18f) * inch), size = Size(36f * inch, 36f * inch), rotation = 180f)
        val out = ElectricalCode.apply(box("BATH", listOf(north)))
        val threeWays = syms(out).filter { it.symbolName == Mep.SWITCH_3 }
        assertEquals(2, threeWays.size)
        assertTrue(threeWays.any { it.center.y / inch < 20f } && threeWays.any { it.center.y / inch > 160f })
    }

    @Test
    fun `the room card's choices change the light`() {
        val st = box("BEDROOM 2")
        val center = androidx.compose.ui.geometry.Rect(4.5f * inch, 4.5f * inch, 144f * inch, 172.5f * inch).center
        val withFan = st.copy(roomElectrical = listOf(RoomElectrical(x = center.x, y = center.y, light = ElectricalCode.LightKind.FAN.name, extraPlugs = 2)))
        val out = ElectricalCode.apply(withFan)
        assertTrue(syms(out).any { it.symbolName.startsWith("Ceiling Fan") })
        assertEquals(syms(ElectricalCode.apply(st)).count { it.symbolName == Mep.DUPLEX } + 2, syms(out).count { it.symbolName == Mep.DUPLEX })
    }
}
