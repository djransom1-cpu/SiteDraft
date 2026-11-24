package com.example.sitedraft.ui.canvas

import com.example.sitedraft.platform.randomId
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.serialization.Serializable

/**
 * Remodel phases (Dan, Nov 24): every wall is Existing, Demo or New. Measured walls go in as
 * Existing, the ones coming out (or part of one) are marked Demo, additions are New. One drawing
 * then gives the existing, demo and proposed plans. Stored as a number in the wall's properties
 * ([Remodel.PROP_STATUS]); a wall without it is New, so drawings made before this are unchanged.
 */
@Serializable
enum class WallStatus(val label: String, val code: Float) {
    NEW("New", 0f),
    EXISTING("Existing", 1f),
    DEMO("Demo", 2f);

    companion object {
        fun of(code: Float?): WallStatus = entries.firstOrNull { it.code == code } ?: NEW
    }
}

/** Which plan the walls are shown for: the canvas toggle, and each sheet view's own. */
@Serializable
enum class RemodelPhase(val label: String) {
    /** Everything, each wall in its own style: for working on the drawing. */
    ALL("All"),
    /** The house as it stands: existing walls and the ones coming out, all drawn as existing. */
    EXISTING("Existing"),
    /** Existing walls, the ones coming out dashed; no new work. */
    DEMO("Demo"),
    /** Existing plus new; the demo walls are gone. */
    PROPOSED("Proposed"),
}

object Remodel {
    const val PROP_STATUS = "remodelStatus"

    fun isWall(e: DrawingElement): Boolean = e is DrawingElement.Symbol && e.category == PlanSymbols.WALLS

    fun status(e: DrawingElement): WallStatus =
        if (isWall(e)) WallStatus.of((e as DrawingElement.Symbol).properties[PROP_STATUS]) else WallStatus.NEW

    /** Walls and framing (joist bays, beams, posts, ladders) carry a status; framing drawn Existing reads like an existing-conditions plan. */
    fun canHaveStatus(e: DrawingElement): Boolean =
        e is DrawingElement.Symbol && (e.category == PlanSymbols.WALLS || e.category in Structure.categories)

    /** Any element's status (New when it has none). */
    fun statusOf(e: DrawingElement): WallStatus =
        (e as? DrawingElement.Symbol)?.let { WallStatus.of(it.properties[PROP_STATUS]) } ?: WallStatus.NEW

    fun withStatus(s: DrawingElement.Symbol, status: WallStatus): DrawingElement.Symbol =
        s.copy(properties = s.properties + (PROP_STATUS to status.code))

    /** True once any wall is marked Existing or Demo: the drawing is a remodel. */
    fun isRemodel(state: CanvasState): Boolean = state.elements.any { isWall(it) && status(it) != WallStatus.NEW }

    /** Whether [e] shows in [phase]. Only walls have a phase; everything else always shows. */
    fun shows(e: DrawingElement, phase: RemodelPhase): Boolean {
        if (!isWall(e)) return true
        val s = status(e)
        return when (phase) {
            RemodelPhase.ALL -> true
            RemodelPhase.EXISTING, RemodelPhase.DEMO -> s != WallStatus.NEW
            RemodelPhase.PROPOSED -> s != WallStatus.DEMO
        }
    }

    /** How a wall draws in [phase]: on the existing plan the walls coming out are still just existing walls. */
    fun drawStyle(e: DrawingElement, phase: RemodelPhase): WallStatus {
        val s = status(e)
        return if (phase == RemodelPhase.EXISTING && s == WallStatus.DEMO) WallStatus.EXISTING else s
    }

    /**
     * New walls get the wall tool's "Draw as" status (state.wallDrawStatus). Only walls the user
     * just drew: ones that already carry a status (a split, a copy, the connector) and walls the
     * layer builder made (DerivedLayers) are left alone. Called where new elements are tagged
     * with their layer (LayerTaggingState), so every way of drawing a wall picks it up.
     */
    fun tagNew(before: Set<String>, state: CanvasState): CanvasState {
        if (state.wallDrawStatus == WallStatus.NEW) return state
        var changed = false
        val out = state.elements.map { e ->
            if (e.id in before || e !is DrawingElement.Symbol || e.category != PlanSymbols.WALLS ||
                PROP_STATUS in e.properties || DerivedLayers.isGenerated(e)) e
            else { changed = true; withStatus(e, state.wallDrawStatus) }
        }
        return if (changed) state.copy(elements = out) else state
    }

    /** A straight wall's run: its two ends along its length (world px), and whether it runs across the page. */
    private data class Run(val horizontal: Boolean, val a: Float, val b: Float)

    private fun run(w: DrawingElement.Symbol): Run {
        val horizontal = w.size.width >= w.size.height
        val half = (if (horizontal) w.size.width else w.size.height) / 2f
        val c = if (horizontal) w.center.x else w.center.y
        return Run(horizontal, c - half, c + half)
    }

    /**
     * Demo part of a wall (Dan: "sometimes we only demo part of a wall"). [fromIn] is where the
     * demo starts, inches from the wall's start end: [fromStart] true = the left end of an
     * east-west wall or the top (north) end of a north-south one, false = the other end.
     * [lengthIn] is how much comes out. The wall becomes up to three walls, the piece coming out
     * marked Demo and the rest keeping the wall's status (Existing for a measured wall). The first
     * piece keeps the wall's id. Returns the new walls, or an error for the toast.
     */
    fun splitForDemo(
        wall: DrawingElement.Symbol, fromIn: Float, lengthIn: Float, fromStart: Boolean, state: CanvasState
    ): Result<List<DrawingElement.Symbol>> {
        if (wall.category != PlanSymbols.WALLS) return Result.failure(IllegalArgumentException("Pick a wall."))
        if (wall.rotation % 90f != 0f) return Result.failure(IllegalArgumentException("Demo part works on square (east-west or north-south) walls."))
        val inch = state.inchesToPx(1f)
        val r = run(wall)
        val totalIn = (r.b - r.a) / inch
        if (lengthIn <= 0f) return Result.failure(IllegalArgumentException("The demo length must be more than zero."))
        if (fromIn < 0f || fromIn + lengthIn > totalIn + 0.01f) {
            return Result.failure(IllegalArgumentException("The demo runs past the end of the wall (the wall is ${formatIn(totalIn)})."))
        }
        // Where the demo starts and ends along the wall, from its left/top end.
        val d0 = if (fromStart) fromIn else totalIn - fromIn - lengthIn
        val d1 = d0 + lengthIn
        val keep = if (status(wall) == WallStatus.DEMO) WallStatus.EXISTING else status(wall)
        val pieces = listOf(0f to d0 to keep, d0 to d1 to WallStatus.DEMO, d1 to totalIn to keep)
            .filter { (span, _) -> span.second - span.first > 0.01f }
        var first = true
        return Result.success(pieces.map { (span, st) ->
            val a = r.a + span.first * inch
            val b = r.a + span.second * inch
            val len = b - a
            val mid = (a + b) / 2f
            val piece = wall.copy(
                id = if (first) wall.id else randomId(),
                center = if (r.horizontal) Offset(mid, wall.center.y) else Offset(wall.center.x, mid),
                // Properties stay as they were: a wall keeps its default length there, its size is the real one.
                size = if (r.horizontal) Size(len, wall.size.height) else Size(wall.size.width, len)
            )
            first = false
            withStatus(piece, st)
        })
    }

    private fun formatIn(inches: Float): String {
        val total = kotlin.math.round(inches * 16f) / 16f
        val ft = (total / 12f).toInt()
        val rem = total - ft * 12f
        val remText = if (rem == rem.toInt().toFloat()) rem.toInt().toString() else ((kotlin.math.round(rem * 16f)) / 16f).toString()
        return if (ft > 0) "$ft' $remText\"" else "$remText\""
    }
}
