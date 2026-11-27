package com.example.sitedraft

import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Stairs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bifold, double and barn doors (Dan, Nov 27) and stairs coming in bare. */
class DoorStylesTest {
    private val styles = listOf(
        "Door 2'-0\" Bifold", "Door 2'-6\" Bifold", "Door 3'-0\" Bifold", "Door 4'-0\" Bifold", "Door 5'-0\" Bifold", "Door 6'-0\" Bifold",
        "Door 5'-0\" Double", "Door 5'-4\" Double", "Door 6'-0\" Double",
        "Door 3'-0\" Barn", "Door 3'-6\" Barn", "Door 4'-0\" Barn", "Door 6'-0\" Barn Bi-parting"
    )

    @Test
    fun `the new doors are in the library, are doors, and have a footprint`() {
        assertTrue(PlanSymbols.doorsWindows.containsAll(styles))
        for (n in styles) assertTrue(n, PlanSymbols.isDoor(n))
        assertTrue(styles.filter { it.endsWith("Bifold") }.all { PlanSymbols.isBifold(it) })
        assertTrue(styles.filter { it.endsWith("Double") }.all { PlanSymbols.isDoubleDoor(it) })
        assertTrue(styles.filter { it.contains("Barn") }.all { PlanSymbols.isBarnDoor(it) })
        assertTrue(PlanSymbols.isBiParting("Door 6'-0\" Barn Bi-parting"))
        assertFalse(PlanSymbols.isBiParting("Door 3'-0\" Barn"))
        // A plain door is none of them.
        assertFalse(PlanSymbols.isBifold("Door 3'-0\"") || PlanSymbols.isDoubleDoor("Door 3'-0\"") || PlanSymbols.isBarnDoor("Door 3'-0\""))
    }

    @Test
    fun `a bifold over 3 feet is a pair`() {
        assertFalse(PlanSymbols.isBifoldPair("Door 3'-0\" Bifold", 36f))
        assertTrue(PlanSymbols.isBifoldPair("Door 4'-0\" Bifold", 48f))
    }

    @Test
    fun `stairs come in bare, a deck stair keeps its guards`() {
        for (n in listOf(Stairs.STRAIGHT, Stairs.L_STAIR, Stairs.U_STAIR, Stairs.SPIRAL, Stairs.CURVED)) {
            val d = Stairs.defaults(n)
            assertEquals(n, Stairs.RAIL_NONE.toFloat(), d[Stairs.RAIL_LEFT]!!, 0f)
            assertEquals(n, Stairs.RAIL_NONE.toFloat(), d[Stairs.RAIL_RIGHT]!!, 0f)
        }
        assertEquals(Stairs.RAIL_GUARD.toFloat(), Stairs.defaults(Stairs.DECK)[Stairs.RAIL_LEFT]!!, 0f)
    }

    @Test
    fun `an L stair with 2 winders, as the Ransom House foyer stair`() {
        // 117 1/4" floor to floor, 16 risers, 9 before the corner (the 9th riser lands on the first winder), 2 winders, 5 treads after.
        val props = Stairs.defaults(Stairs.L_STAIR) + mapOf(Stairs.RISE to 117.25f, Stairs.RISE_SET to 1f, Stairs.FLIGHT1 to 9f,
            Stairs.WINDERS to 1f, Stairs.TREAD to 9f, Stairs.WIDTH to 39f)
        val s = com.example.sitedraft.ui.canvas.DrawingElement.Symbol(symbolName = Stairs.L_STAIR, category = PlanSymbols.STAIRS,
            center = androidx.compose.ui.geometry.Offset.Zero, size = androidx.compose.ui.geometry.Size.Zero,
            color = androidx.compose.ui.graphics.Color.White, properties = props)
        val l = Stairs.layout(s)
        assertEquals(16, l.risers)
        assertEquals(2, l.corner)
        assertEquals(8, l.treads1)
        assertEquals(5, l.treads2)
        val (w, d) = Stairs.footprintIn(s)
        assertEquals(39f + 5 * 9f, w, 0.01f)
        assertEquals(8 * 9f + 39f, d, 0.01f)
        // With a landing instead: 6 treads after it.
        assertEquals(6, Stairs.layout(s.copy(properties = props + (Stairs.WINDERS to 0f))).treads2)
    }
}
