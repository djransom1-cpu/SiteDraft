package com.example.sitedraft

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.Elevation
import com.example.sitedraft.ui.canvas.ElevationSpec
import com.example.sitedraft.ui.canvas.Fill
import com.example.sitedraft.ui.canvas.FillPattern
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.canvas.Shower
import com.example.sitedraft.ui.canvas.ViewerSide
import com.example.sitedraft.ui.canvas.drawDocumentContents
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders showers (plan and every elevation) and a tiled kitchen wall to PNGs under
 * shared/build/previews, the same drawing code the canvas uses, so the look can be checked.
 */
class ShowerPreviewTest {
    private val inch = 50f / 12f
    private val ink = Color.Black

    private fun shower(name: String, cx: Float, cy: Float, props: Map<String, Float> = emptyMap(), rotation: Float = 0f): DrawingElement.Symbol {
        val (w, d) = Shower.footprints.getValue(name)
        return DrawingElement.Symbol(
            symbolName = name, category = PlanSymbols.SHOWERS, center = Offset(cx, cy), size = Size(w * inch, d * inch),
            color = ink, rotation = rotation, properties = Shower.defaults(name) + props
        )
    }

    private fun views(s: DrawingElement.Symbol, x0: Float, y0: Float, base: List<DrawingElement>): List<DrawingElement> {
        var x = x0
        return Shower.faces(Shower.type(s.symbolName)).map { f ->
            val spec = ElevationSpec(wallId = s.id, side = ViewerSide.BELOW, showerFace = f)
            val state = CanvasState(elements = base)
            val size = Elevation.sizePx(s, spec, state)
            val v = DrawingElement.ElevationView(spec = spec, origin = Offset(x, y0), size = size, color = ink)
            x += size.width + 30f * inch
            v
        }
    }

    private fun render(file: String, elements: List<DrawingElement>, width: Int, height: Int, zoom: Float = 1f) {
        val state = CanvasState(elements = elements, backgroundColor = Color.White, zoom = zoom)
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
            drawRect(Color.White)
            withTransform({ scale(zoom, zoom, Offset.Zero) }) { drawDocumentContents(state) }
        }
        val dir = File("build/previews").apply { mkdirs() }
        val out = File(dir, file)
        ImageIO.write(bitmap.toAwtImage(), "png", out)
        assertTrue(out.exists())
    }

    @Test
    fun `render shower previews`() {
        val alcove = shower("Alcove Shower 60x32", 250f, 200f, mapOf(Shower.BENCH_WALL to Shower.RIGHT.toFloat()))
        val walls = Shower.wallsFor(alcove, CanvasState(elements = listOf(alcove)))
        val corner = shower("Corner Shower 48x36", 700f, 200f, mapOf(Shower.TILE_PAT to 1f, Shower.TILE_IN to 3f, Shower.TILE_LEN to 6f))
        val neo = shower("Neo-Angle Shower 42x42", 1100f, 200f, mapOf(Shower.TILE_PAT to 2f, Shower.TILE_IN to 2f, Shower.TILE_LEN to 8f, Shower.FLOOR_TILE to 6f, Shower.FLOOR_TILE_LEN to 24f))
        val walkIn = shower("Walk-In Shower 72x42", 1550f, 200f, mapOf(Shower.TILE_PAT to 3f, Shower.TILE_IN to 6f))
        val plan = listOf(alcove, corner, neo, walkIn) + walls
        val all = plan +
            views(alcove, 50f, 900f, plan) +
            views(corner, 50f, 1500f, plan) +
            views(neo, 50f, 2100f, plan) +
            views(walkIn, 50f, 2700f, plan)
        render("showers.png", all, 1900, 2950)
        // Sliding and door-only fronts, curtain, curbless door + panel.
        val sliding = shower("Alcove Shower 60x32", 250f, 200f, mapOf(Shower.GLASS to 2f))
        val doorOnly = shower("Alcove Shower 36x36", 650f, 200f, mapOf(Shower.GLASS to 1f, Shower.DOOR_SIDE to 0f))
        val curtain = shower("Alcove Shower 60x36", 1050f, 200f, mapOf(Shower.GLASS to 4f))
        val curbless = shower("Alcove Shower 48x36", 1500f, 200f, mapOf(Shower.CURB_H to 0f, Shower.DOOR_SIDE to 0f, Shower.DRAIN to 1f))
        val fronts = listOf(sliding, doorOnly, curtain, curbless)
        render("glass.png", fronts + fronts.flatMapIndexed { i, s ->
            val spec = ElevationSpec(wallId = s.id, side = ViewerSide.BELOW, showerFace = Shower.FRONT)
            listOf(DrawingElement.ElevationView(spec = spec, origin = Offset(80f + i * 450f, 900f), size = Elevation.sizePx(s, spec, CanvasState(elements = fronts)), color = ink))
        }, 1900, 1050)
    }

    @Test
    fun `render a backsplash and a hatched room`() {
        val t = 4.5f * inch
        fun wall(cx: Float, cy: Float, w: Float, h: Float) = DrawingElement.Symbol(
            symbolName = "2x4 Wall", category = PlanSymbols.WALLS, center = Offset(cx, cy), size = Size(w, h), color = ink
        )
        val north = wall(500f, 100f, 800f + 2 * t, t)
        val south = wall(500f, 600f, 800f + 2 * t, t)
        val westW = wall(100f - t / 2f, 350f, t, 500f - t)
        val eastW = wall(900f + t / 2f, 350f, t, 500f - t)
        val face = 100f + t / 2f
        fun cab(name: String, cx: Float) = DrawingElement.Symbol(
            symbolName = name, category = PlanSymbols.CABINETS, color = ink,
            center = Offset(cx, face + (if (name.startsWith("Upper")) 6f else 12f) * inch),
            size = Size((name.filter { it.isDigit() }.toFloat()) * inch, (if (name.startsWith("Upper")) 12f else 24f) * inch)
        )
        val cabs = listOf(cab("Base 36\"", 300f), cab("Base 30\"", 300f + 33f * inch), cab("Upper 36\"", 300f), cab("Upper 30\"", 300f + 33f * inch))
        val sink = DrawingElement.Symbol(symbolName = "Kitchen Sink", category = PlanSymbols.FIXTURES, color = ink, center = Offset(300f, face + 11f * inch), size = Size(33f * inch, 22f * inch))
        val hatch = DrawingElement.Rectangle(topLeft = Offset(100f, 100f + t / 2f), size = Size(800f, 500f - t), color = ink, strokeWidth = 0f, fill = Fill(pattern = FillPattern.TILE, spacing = 12f * inch, length = 24f * inch))
        val plan = listOf(hatch, north, south, westW, eastW) + cabs + sink
        val spec = ElevationSpec(wallId = north.id, side = ViewerSide.BELOW, tileFromIn = 36f, tileToIn = 54f, tileSizeIn = 3f, tileLengthIn = 6f, tilePattern = 1)
        val view = DrawingElement.ElevationView(spec = spec, origin = Offset(100f, 1150f), size = Elevation.sizePx(north, spec, CanvasState(elements = plan)), color = ink)
        render("backsplash.png", plan + view, 1000, 1250)
    }

    @Test
    fun `render a hatched bathroom with a toilet and a door`() {
        val t = 4.5f * inch
        fun sym(name: String, cat: String, cx: Float, cy: Float, w: Float, h: Float, rot: Float = 0f) = DrawingElement.Symbol(
            symbolName = name, category = cat, center = Offset(cx, cy), size = Size(w, h), color = ink, rotation = rot
        )
        // 8' x 5' inside: x 100..500, y 100..350.
        val top = sym("2x4 Wall", PlanSymbols.WALLS, 300f, 100f - t / 2f, 400f + 2 * t, t)
        val bottom = sym("2x4 Wall", PlanSymbols.WALLS, 300f, 350f + t / 2f, 400f + 2 * t, t)
        val left = sym("2x4 Wall", PlanSymbols.WALLS, 100f - t / 2f, 225f, t, 250f)
        val right = sym("2x4 Wall", PlanSymbols.WALLS, 500f + t / 2f, 225f, t, 250f)
        val hatch = DrawingElement.Rectangle(topLeft = Offset(100f, 100f), size = Size(400f, 250f), color = ink, strokeWidth = 0f, fill = Fill(pattern = FillPattern.HERRINGBONE, spacing = 3f * inch, length = 12f * inch))
        // Toilet against the left wall, turned so its tank is on the wall.
        val toilet = sym("Toilet", PlanSymbols.FIXTURES, 100f + 14f * inch, 290f, 20f * inch, 28f * inch, rot = -90f)
        // Door in the bottom wall: threshold on the wall's centre line, swinging into the room.
        val dw = 32f * inch
        val door = sym("Door 2'-8\"", PlanSymbols.DOORS_WINDOWS, 330f, 350f + t / 2f - dw / 2f, dw, dw)
        val tub = sym("Bathtub", PlanSymbols.FIXTURES, 100f + 30f * inch, 100f + 15f * inch, 60f * inch, 30f * inch)
        render("bathroom-hatch.png", listOf(hatch, top, bottom, left, right, toilet, door, tub), 650, 480)
    }

    @Test
    fun `render stairs and cabinet door styles`() {
        fun stair(name: String, cx: Float, cy: Float, props: Map<String, Float> = emptyMap()): DrawingElement.Symbol {
            val s0 = DrawingElement.Symbol(symbolName = name, category = PlanSymbols.STAIRS, center = Offset(cx, cy), size = Size.Zero, color = ink, properties = com.example.sitedraft.ui.canvas.Stairs.defaults(name) + props)
            val (w, d) = com.example.sitedraft.ui.canvas.Stairs.footprintIn(s0)
            return s0.copy(size = Size(w * inch, d * inch))
        }
        val straight = stair(com.example.sitedraft.ui.canvas.Stairs.STRAIGHT, 150f, 330f)
        val l = stair(com.example.sitedraft.ui.canvas.Stairs.L_STAIR, 500f, 330f, mapOf(com.example.sitedraft.ui.canvas.Stairs.RAIL_LEFT to 2f))
        val u = stair(com.example.sitedraft.ui.canvas.Stairs.U_STAIR, 900f, 330f)
        val deck = stair(com.example.sitedraft.ui.canvas.Stairs.DECK, 1250f, 330f)
        val spiral = stair(com.example.sitedraft.ui.canvas.Stairs.SPIRAL, 1600f, 330f, mapOf(com.example.sitedraft.ui.canvas.Stairs.RAIL_RIGHT to 2f))
        val curved = stair(com.example.sitedraft.ui.canvas.Stairs.CURVED, 1200f, 1000f, mapOf(
            com.example.sitedraft.ui.canvas.Stairs.INNER_R to 48f, com.example.sitedraft.ui.canvas.Stairs.OUTER_R to 96f))
        val plan = listOf(straight, l, u, deck, spiral, curved)
        val views = mutableListOf<DrawingElement>()
        var y = 1500f
        for ((st, side) in plan.map { it to 0 } + listOf(curved to 1)) {
            val spec = ElevationSpec(wallId = st.id, side = ViewerSide.BELOW, stairSide = side)
            val size = Elevation.sizePx(st, spec, CanvasState(elements = plan))
            y += size.height
            views += DrawingElement.ElevationView(spec = spec, origin = Offset(60f, y), size = size, color = ink)
            y += 220f
        }
        render("stairs.png", plan + views, 1850, y.toInt())

        // Cabinet styles: slab, shaker with bar pulls, raised panel, glass uppers.
        val t = 4.5f * inch
        val wall = DrawingElement.Symbol(symbolName = "2x4 Wall", category = PlanSymbols.WALLS, center = Offset(500f, 100f), size = Size(1000f, t), color = ink)
        val face = 100f + t / 2f
        fun cab(name: String, x: Float, style: Int, hw: Int, front: Int? = null): DrawingElement.Symbol {
            val wIn = name.filter { it.isDigit() }.toFloat()
            val upper = name.startsWith("Upper")
            val props = mutableMapOf(PlanSymbols.PROP_STYLE to style.toFloat(), PlanSymbols.PROP_HARDWARE to hw.toFloat())
            if (front != null) props[PlanSymbols.PROP_FRONT] = front.toFloat()
            return DrawingElement.Symbol(symbolName = name, category = PlanSymbols.CABINETS, color = ink, properties = props,
                center = Offset(x + wIn * inch / 2f, face + (if (upper) 6f else 12f) * inch), size = Size(wIn * inch, (if (upper) 12f else 24f) * inch))
        }
        val cabs = listOf(
            cab("Base 30\"", 120f, 0, 0), cab("Upper 30\"", 120f, 0, 0),
            cab("Base 30\"", 120f + 30f * inch, 1, 1), cab("Upper 30\"", 120f + 30f * inch, 1, 1),
            cab("Base 24\"", 120f + 60f * inch, 2, 2, front = 2), cab("Upper 24\"", 120f + 60f * inch, 2, 2),
            cab("Base 30\"", 120f + 84f * inch, 1, 1, front = 3), cab("Upper 30\"", 120f + 84f * inch, 3, 1)
        )
        val cabState = listOf(wall) + cabs
        val spec = ElevationSpec(wallId = wall.id, side = ViewerSide.BELOW)
        val view = DrawingElement.ElevationView(spec = spec, origin = Offset(0f, 900f), size = Elevation.sizePx(wall, spec, CanvasState(elements = cabState)), color = ink)
        render("door-styles.png", cabState + view, 1050, 980)
    }
}
