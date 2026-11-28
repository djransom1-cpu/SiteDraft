package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import kotlin.math.min

/**
 * Every room needs a name (Dan, Nov 28): schedules, the finish schedule and the electrical rules read rooms by name,
 * and an unnamed one prints as "Room 9' x 3'". Each room on the floor plan with no name gets a red "Name this room"
 * tag, on screen only (never on a sheet or a PDF); tapping it opens a box to type the name, which goes on the room's
 * label.
 */
object RoomNames {
    const val TAG_TEXT = "Name this room"

    /** Rooms on the active level with no name: no label with words in them, no finish-schedule name. Slivers left out. */
    fun unnamed(state: CanvasState): List<Rect> {
        if (state.activeLayer.trade != LayerTrade.FLOOR_PLAN) return emptyList()
        val inch = state.inchesToPx(1f)
        return ElectricalCode.rooms(state).filter { it.name.startsWith("Room ") }.map { it.rect }
            .filter { min(it.width, it.height) >= 18f * inch && it.width * it.height >= 6f * 144f * inch * inch }
    }

    /** Screen-constant tag size, world px, at [zoom]. */
    private fun fontPx(zoom: Float) = 13f / zoom.coerceAtLeast(0.01f)

    /** Where a room's tag sits: centred a little below the middle, clear of a size-only label. */
    fun tagRect(room: Rect, zoom: Float): Rect {
        val f = fontPx(zoom)
        val w = f * 0.6f * TAG_TEXT.length + f
        val h = f * 1.8f
        val c = room.center + Offset(0f, f * 2.2f)
        return Rect(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f)
    }

    /** The unnamed room whose tag is at [p], if any. */
    fun tagAt(p: Offset, state: CanvasState): Rect? = unnamed(state).firstOrNull { tagRect(it, state.zoom).inflate(4f / state.zoom).contains(p) }

    fun DrawScope.drawTags(state: CanvasState) {
        val f = fontPx(state.zoom)
        for (room in unnamed(state)) {
            val r = tagRect(room, state.zoom)
            drawRoundRect(color = Color(0xFFD32F2F), topLeft = r.topLeft, size = Size(r.width, r.height), cornerRadius = CornerRadius(r.height / 2f))
            drawLabel(TAG_TEXT, r.center, f, Color.White, bold = true)
        }
    }

    /**
     * The room's name onto its label: a size-only label in the room becomes "Name" over the size; with no label, a new
     * one ("Name" over the inside size) goes in the middle. Returns the new element list.
     */
    fun named(room: Rect, name: String, state: CanvasState): List<DrawingElement> {
        val dims = FloorPlan.formatDims(room.width / state.scale.ratio, room.height / state.scale.ratio, state.unitSystem)
        val sizeOnly = Regex("""^[\d\s.'"x×m/-]+$""")
        val existing = Layers.onActiveLevel(state).filterIsInstance<DrawingElement.Text>().firstOrNull { t ->
            room.contains(t.topLeft + Offset(1f, 1f)) && t.text.isNotBlank() &&
                sizeOnly.matches(t.text.lineSequence().first { it.isNotBlank() }.trim())
        }
        if (existing != null) {
            val text = name.trim() + "\n" + existing.text.trim()
            val fs = existing.fontSize
            return state.elements.map { if (it.id == existing.id) existing.copy(text = text, topLeft = existing.topLeft - Offset(0f, fs * 0.6f)) else it }
        }
        val fontSize = state.inchesToPx(6f).coerceIn(10f, 36f)
        val text = name.trim() + "\n" + dims
        val longest = text.lines().maxOf { it.length }
        val width = longest * fontSize * 0.55f
        val label = DrawingElement.Text(text = text, topLeft = Offset(room.center.x - width / 2f, room.center.y - fontSize * 1.2f),
            fontSize = fontSize, color = state.currentColor)
        return state.elements + label
    }
}
