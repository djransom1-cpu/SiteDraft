package com.example.sitedraft

import com.example.sitedraft.ui.canvas.*
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/** Prints each fixture's outside walls and the roof footprint (Nov 28: Benjeman's roof came out square). Always passes. */
class RoofOutlineProbe {
    @Test
    fun `print outlines`() {
        for (f in listOf("benjeman_full.json", "benjeman.json", "practice_run1.json")) {
            val st: CanvasState = Json { ignoreUnknownKeys = true }.decodeFromString(CanvasState.serializer(), File("src/desktopTest/fixtures/$f").readText())
            val k = st.inchesToPx(1f)
            fun r(x: androidx.compose.ui.geometry.Rect) = "(${(x.left / k).toInt()},${(x.top / k).toInt()})-(${(x.right / k).toInt()},${(x.bottom / k).toInt()})"
            val plan = HouseOutline.planWalls(st)
            val ext = plan.filter { HouseOutline.isExterior(it, st) }
            println("ROOF $f roof=${st.building.roof != null} wings=${Wings.wings(st).size} planWalls=${plan.size} exterior=${ext.size} footprint=${RoofPlan.footprint(st)?.let { r(it) }}")
            ext.forEach { println("ROOF   ext ${it.symbolName} ${r(FloorPlan.bounds(it))}") }
        }
    }
}
