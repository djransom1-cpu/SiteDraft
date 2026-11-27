package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.TubShower
import com.example.sitedraft.ui.canvas.ViewerSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tub/shower and end-on cabinets in the elevations (Dan, Nov 25, the J&J bath). */
class TubShowerTest {
    private fun sym(name: String, cat: String, rotation: Float, props: Map<String, Float> = emptyMap()) = DrawingElement.Symbol(
        id = name, symbolName = name, category = cat, color = Color.White, center = Offset.Zero, size = Size(100f, 100f),
        rotation = rotation, properties = props)

    @Test
    fun `the J&J tub's valve is on its north end by default`() {
        // Tub facing west (90): standing in front of it, its left end is north.
        val tub = sym("Bathtub", PlanSymbols.FIXTURES, 90f)
        assertTrue(TubShower.isOn(tub))
        assertTrue(TubShower.valveHere(tub, ViewerSide.BELOW))   // the north wall, seen looking north
        assertFalse(TubShower.valveHere(tub, ViewerSide.ABOVE))  // the south wall
        assertFalse(TubShower.valveHere(tub, ViewerSide.LEFT))   // the back wall (east), seen looking east
        val right = tub.copy(properties = mapOf(TubShower.VALVE_END to 1f))
        assertTrue(TubShower.valveHere(right, ViewerSide.ABOVE))
        // Facing south: the left end is west.
        assertTrue(TubShower.valveHere(sym("Bathtub", PlanSymbols.FIXTURES, 0f), ViewerSide.RIGHT))
        assertFalse(TubShower.isOn(sym(PlanSymbols.CLAWFOOT_TUB, PlanSymbols.FIXTURES, 0f)))
        assertEquals(84f, TubShower.prop(tub, TubShower.TILE_H), 0.01f)
    }

    @Test
    fun `a vanity facing across the view is seen end-on`() {
        // The J&J vanity faces east (270); on the north wall's elevation (looking north) it is end-on, front on the right.
        val vanity = sym("Vanity 60\" Double", PlanSymbols.CABINETS, 270f)
        assertTrue(Elevation.endOn(vanity, ViewerSide.BELOW))
        assertTrue(Elevation.frontOnRight(vanity, ViewerSide.BELOW))
        assertFalse(Elevation.endOn(vanity, ViewerSide.RIGHT)) // the west wall seen looking west: it faces the viewer
        assertFalse(Elevation.endOn(sym("Base 36\"", PlanSymbols.CABINETS, 0f), ViewerSide.BELOW))
        assertFalse(Elevation.endOn(sym("Corner Base 36\"", PlanSymbols.CABINETS, 90f), ViewerSide.BELOW))
        assertFalse(Elevation.endOn(sym("Toilet", PlanSymbols.FIXTURES, 90f), ViewerSide.BELOW))
    }
}
