package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.sitedraft.platform.randomId
import kotlinx.serialization.Serializable
import kotlin.math.max

/**
 * One room in the finish schedule (Dan, Nov 26): found by a tap inside it ([x], [y], world px), named once, with
 * its finishes. Floor and walls fill in from the drawing (the floor hatch; paint, wallpaper and wainscot on its
 * walls) unless typed; ceiling, trim (paint or stain) and notes are typed.
 */
@Serializable
data class RoomFinish(
    val id: String = randomId(),
    val name: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val floor: String = "",
    val walls: String = "",
    val ceiling: String = "",
    /** The trim's paint or stain (the trim schedule's PAINT / STAIN column). */
    val trim: String = "",
    val notes: String = "",
    // Extra lines under the room, printed only when filled (kitchen and bath, Nov 26).
    val backsplash: String = "",
    val showerWalls: String = "",
    val showerFloor: String = "",
    // The trim schedule (TrimSchedule, Nov 26): typed over what the elevations show.
    val trimBase: String = "",
    val trimCasing: String = "",
    val trimWindows: String = "",
    val trimCrown: String = "",
    val trimWainscot: String = "",
    val trimNotes: String = "",
)

object FinishSchedule {
    val fields = listOf("name", "floor", "walls", "ceiling", "notes", "backsplash", "showerWalls", "showerFloor")

    /** The extra lines and their labels on the table. */
    val extras = listOf("backsplash" to "BACKSPLASH", "showerWalls" to "SHOWER WALLS", "showerFloor" to "SHOWER FLOOR")

    fun point(f: RoomFinish) = Offset(f.x, f.y)

    /**
     * The finishes on the room's walls from the drawing (paint colours, wallpaper, wainscot); "" when none. All walls
     * alike: just the finish. Walls that differ (an accent wall, Dan Nov 26): by wall, "North: Wallpaper; East, South,
     * West: SW 7029 Agreeable Gray".
     */
    fun wallsFromDrawing(f: RoomFinish, state: CanvasState): String {
        val byWall = RoomElevations.faces(point(f), state).map { it.direction to faceFinish(it, state) }
        val groups = byWall.groupBy({ it.second }, { it.first })
        if (groups.keys.all { it.isBlank() }) return ""
        if (groups.size == 1) return groups.keys.first()
        return groups.entries.joinToString("; ") { (finish, dirs) ->
            dirs.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } } + ": " + finish.ifBlank { "unfinished" }
        }
    }

    /** One wall's finishes in this room: its paint, wallpaper and wainscot, joined. */
    private fun faceFinish(face: RoomElevations.Face, state: CanvasState): String {
        val out = LinkedHashSet<String>()
        run {
            val spans by lazy { Wainscot.roomSpans(face.wall, face.side, state) }
            fun covers(rooms: Int) = rooms == 0 || spans.withIndex().any { (i, s) ->
                (rooms shr i) and 1 == 1 && minOf(s.second, face.toIn) - maxOf(s.first, face.fromIn) > 2f
            }
            val decor = WallDecorations.on(state, face.wall.id, face.side)
            decor.filter { it.kind == WallDecor.PAINT && covers(it.rooms) }.forEach { out += it.paintLabel }
            if (decor.any { it.kind == WallDecor.WALLPAPER }) out += "Wallpaper"
            Wainscot.specOf(face.wall, face.side)?.let { ws ->
                if (spans.withIndex().any { (i, s) -> ws.inRoom(i) && minOf(s.second, face.toIn) - maxOf(s.first, face.fromIn) > 2f })
                    out += "${Wainscot.styleLabels.getOrElse(ws.style) { "Wainscot" }} wainscot ${formatInches(ws.heightIn)}\""
            }
        }
        return out.joinToString(", ")
    }

    /** The floor from a floor hatch over the room ("Tile 12\""); "" when there's none. */
    fun floorFromDrawing(f: RoomFinish, state: CanvasState): String {
        val p = point(f)
        val hatch = state.elements.filterIsInstance<DrawingElement.Rectangle>().lastOrNull {
            it.fill.pattern != FillPattern.NONE && it.fill.pattern != FillPattern.SOLID &&
                p.x >= it.topLeft.x && p.x <= it.topLeft.x + it.size.width && p.y >= it.topLeft.y && p.y <= it.topLeft.y + it.size.height
        } ?: return ""
        val size = hatch.fill.spacing / state.inchesToPx(1f)
        return hatch.fill.pattern.label + if (size >= 1f) " ${formatInches(size)}\"" else ""
    }

    fun floor(f: RoomFinish, state: CanvasState) = f.floor.ifBlank { floorFromDrawing(f, state) }
    fun walls(f: RoomFinish, state: CanvasState) = f.walls.ifBlank { wallsFromDrawing(f, state) }

    /** The rooms in the schedule, in the order they were added: ROOM, FLOOR, WALLS, CEILING, NOTES. */
    fun rows(state: CanvasState): List<List<String>> = state.roomFinishes.mapIndexed { i, f ->
        listOf(f.name.ifBlank { "Room ${i + 1}" }, floor(f, state), walls(f, state), f.ceiling, f.notes)
    }

    /** The table's rows: each room, then its filled extra lines (backsplash, shower walls, shower floor). */
    fun tableRows(state: CanvasState): List<ScheduleTable.Row> = state.roomFinishes.zip(rows(state)).flatMap { (f, r) ->
        listOf(ScheduleTable.Row(r)) + listOf(f.backsplash, f.showerWalls, f.showerFloor).zip(extras)
            .filter { it.first.isNotBlank() }.map { (v, e) -> ScheduleTable.Row(line = "${e.second}: $v") }
    }

    fun withField(f: RoomFinish, field: String, value: String): RoomFinish? = when (field) {
        "name" -> f.copy(name = value.trim())
        "floor" -> f.copy(floor = value.trim())
        "walls" -> f.copy(walls = value.trim())
        "ceiling" -> f.copy(ceiling = value.trim())
        "trim" -> f.copy(trim = value.trim())
        "notes" -> f.copy(notes = value.trim())
        "backsplash" -> f.copy(backsplash = value.trim())
        "showerWalls" -> f.copy(showerWalls = value.trim())
        "showerFloor" -> f.copy(showerFloor = value.trim())
        "trimBase" -> f.copy(trimBase = value.trim())
        "trimCasing" -> f.copy(trimCasing = value.trim())
        "trimWindows" -> f.copy(trimWindows = value.trim())
        "trimCrown" -> f.copy(trimCrown = value.trim())
        "trimWainscot" -> f.copy(trimWainscot = value.trim())
        "trimNotes" -> f.copy(trimNotes = value.trim())
        else -> null
    }

    // ---- The table on the A sheets ----

    private val COLS = listOf("ROOM" to 1.9f, "FLOOR" to 3.8f, "WALLS" to 5.6f, "CEILING" to 3.0f, "NOTES" to 4.2f)

    fun tableSizeIn(state: CanvasState, size: Float = 1f): Size = ScheduleTable.sizeIn(COLS, tableRows(state).size, size)

    fun DrawScope.drawFinishTable(state: CanvasState, topLeft: Offset, dpi: Float, size: Float = 1f) = with(ScheduleTable) {
        drawTable("FINISH SCHEDULE", COLS, tableRows(state), "Add rooms: Floor plan > Finish schedule, then tap inside each room", topLeft, dpi, size)
    }
}
