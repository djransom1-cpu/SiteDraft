package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.example.sitedraft.platform.randomId
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One room's electrical choices (the room electrical card, Dan Nov 27). Found by the point (x, y) inside the room,
 * like a finish-schedule room, so it follows the room when walls move a little. Blank or -1 = automatic.
 */
@Serializable
data class RoomElectrical(
    val id: String = randomId(),
    val x: Float = 0f,
    val y: Float = 0f,
    /** A [ElectricalCode.RoomType] name; blank = worked out from the room's name. */
    val type: String = "",
    /** A [ElectricalCode.LightKind] name; blank = by room type. */
    val light: String = "",
    /** How many cans; 0 = spaced to fit the room. */
    val cans: Int = 0,
    /** Which of the room's doors gets the switch (index into [ElectricalCode.doorsOf]); -1 = automatic. */
    val switchDoor: Int = -1,
    /** 3-way switches at two doors: -1 automatic, 0 off, 1 on. */
    val threeWay: Int = -1,
    /** Plugs beyond what code asks for, set into the widest gaps. */
    val extraPlugs: Int = 0,
    val floorPlug: Boolean = false,
    val tvPlug: Boolean = false
)

/**
 * Electrical to code in one press (Dan, Nov 27): plugs, switches and lights, smoke and CO alarms, bath fans, room by
 * room on the level's electrical layer, from the rules of the job's code edition. Every edition the app offers
 * (2015 MRC / IRC 2015 to IRC 2024) carries the same rules used here, except the garage: one receptacle in each
 * garage under the 2015 and 2018 IRC, one per vehicle bay from the 2021 IRC.
 *
 *  - Wall plugs (IRC E3901.2): in habitable rooms no point along the floor line of a wall space 2' or wider is more
 *    than 6' from a receptacle. Doors and fixed cabinets break the wall space; it runs around corners.
 *  - Counters (E3901.4): kitchens, pantries, breakfast and dining rooms: a receptacle at each counter space 12" or
 *    wider, none of it more than 24" from one. A sink, range or refrigerator splits the counter. An island gets one.
 *  - Baths (E3901.6): one within 36" of each basin, GFCI. Laundry (E3901.8): one; set within 6' of the washer.
 *    Hallways 10' or longer (E3901.10): one. Garages and an unfinished basement (E3901.9): GFCI. HVAC equipment
 *    (E3901.11): one within 25' (set beside it). Outside (E3901.7): one at the front and one at the back.
 *  - Lights (E3903): a wall-switched light in every habitable room, bath, hall, stairway and garage, and at each
 *    outside door. Rooms with two doors far apart get 3-way switches.
 *  - Smoke alarms (R314.3): in each bedroom, outside each sleeping area by the bedrooms, and on every story; not
 *    within 3' of a bath door. CO (R315.3): outside each sleeping area by the bedrooms, required where there is a
 *    fuel-fired appliance or an attached garage; a combination smoke/CO alarm is placed there.
 *  - Bath fan (R303.3): one in each bath, switched with the light.
 *
 * What the button places is tagged ([GEN], with where it was put); laying a room out again clears those that were
 * not moved and keeps anything moved or placed by hand, using plugs already there toward the spacing.
 */
object ElectricalCode {
    const val GEN = "ecGen"
    const val GX = "ecX"
    const val GY = "ecY"

    enum class RoomType(val label: String) {
        KITCHEN("Kitchen"), DINING("Dining"), LIVING("Living room"), FAMILY("Family / rec room"), BEDROOM("Bedroom"),
        OFFICE("Office / den"), BATH("Bathroom"), HALL("Hallway"), FOYER("Foyer / mudroom"), CLOSET("Closet"),
        PANTRY("Pantry"), LAUNDRY("Laundry"), GARAGE("Garage"), UTILITY("Utility / mechanical"),
        BASEMENT("Unfinished basement"), OTHER("Other room")
    }

    enum class LightKind(val label: String) {
        CANS("Recessed cans"), CENTER("Center fixture"), FAN("Ceiling fan with light"), PENDANTS("Cans + island pendants"),
        VANITY("Vanity light"), NONE("No light")
    }

    /** The room type from its name ("Master Bedroom", "Hall Bath", "Kitchen"); an unnamed room is OTHER. */
    fun typeOf(name: String): RoomType {
        val n = name.lowercase()
        fun has(vararg w: String) = w.any { n.contains(it) }
        return when {
            has("bath", "powder", "lav", "toilet") || Regex("""\bwc\b""").containsMatchIn(n) -> RoomType.BATH
            has("closet", "w.i.c", "wic") -> RoomType.CLOSET
            has("pantry") -> RoomType.PANTRY
            has("kitchen") -> RoomType.KITCHEN
            has("dining", "breakfast", "nook") -> RoomType.DINING
            has("bed", "nursery", "bunk", "guest", "master suite") -> RoomType.BEDROOM
            has("laundry") -> RoomType.LAUNDRY
            has("garage") -> RoomType.GARAGE
            has("living", "great room", "parlor", "sitting") -> RoomType.LIVING
            has("family", "rec", "bonus", "media", "game", "sunroom", "sun room", "play") -> RoomType.FAMILY
            has("office", "study", "library", "den") -> RoomType.OFFICE
            has("hall", "corridor", "gallery") -> RoomType.HALL
            has("foyer", "entry", "vestibule", "mud") -> RoomType.FOYER
            has("unfinished", "basement") -> RoomType.BASEMENT
            has("mech", "furnace", "utility", "storage") -> RoomType.UTILITY
            else -> RoomType.OTHER
        }
    }

    fun defaultLight(t: RoomType): LightKind = when (t) {
        RoomType.KITCHEN, RoomType.LIVING, RoomType.FAMILY -> LightKind.CANS
        RoomType.BATH -> LightKind.VANITY
        else -> LightKind.CENTER
    }

    /** Rooms the 6'/12' wall rule covers (E3901.2: habitable rooms and similar areas). */
    private fun wallRule(t: RoomType) = t in setOf(RoomType.KITCHEN, RoomType.DINING, RoomType.LIVING, RoomType.FAMILY,
        RoomType.BEDROOM, RoomType.OFFICE, RoomType.OTHER)
    private fun counterRule(t: RoomType) = t in setOf(RoomType.KITCHEN, RoomType.DINING, RoomType.PANTRY)
    /** The room the tap at [p] is in, with its name and its choices. */
    data class Room(val rect: Rect, val name: String, val type: RoomType, val opts: RoomElectrical?)

    /**
     * The room [p] is in: one of [rooms] when it is inside one, else the walls around it. Using the rooms found once,
     * rather than the walls around a point, keeps a big room from reaching around a small one inside it and taking its
     * label (Practice House, Nov 27: the living room took the laundry's label and got no plugs).
     */
    fun roomAt(p: Offset, state: CanvasState): Room? {
        rooms(state).firstOrNull { it.rect.contains(p) }?.let { return it }
        val (rect, name) = CabinetList.roomAt(p, state) ?: return null
        return roomFor(rect, name, state)
    }

    private fun roomFor(rect: Rect, name: String, state: CanvasState): Room {
        val opts = optsFor(rect, state)
        val type = opts?.type?.takeIf { it.isNotBlank() }?.let { runCatching { RoomType.valueOf(it) }.getOrNull() } ?: typeOf(name, rect, state)
        return Room(rect, name, type, opts)
    }

    /** The name on a label inside [rect] (not a size), else its finish-schedule name, else its size. */
    private fun nameIn(rect: Rect, state: CanvasState): String {
        for (t in Layers.onActiveLevel(state).filterIsInstance<DrawingElement.Text>()) {
            if (!rect.contains(t.topLeft + Offset(1f, 1f)) || t.text.isBlank()) continue
            val first = t.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: continue
            if (first.any { it.isLetter() } && !Regex("""^[\d\s.'"x×m-]+$""").matches(first)) return first
        }
        state.roomFinishes.firstOrNull { it.name.isNotBlank() && rect.contains(FinishSchedule.point(it)) }?.let { return it.name }
        return "Room " + FloorPlan.formatDims(rect.width / state.scale.ratio, rect.height / state.scale.ratio, state.unitSystem)
    }

    /**
     * The type from the name, and an unnamed room 3' or less across (or under 25 sq ft) is a closet: a reach-in closet
     * walled off inside a bedroom usually has no label.
     */
    fun typeOf(name: String, rect: Rect, state: CanvasState): RoomType {
        val t = typeOf(name)
        if (t != RoomType.OTHER) return t
        // An unnamed room with a toilet, tub or shower in it is a bath (Practice House's master bath has no label).
        val wet = Layers.onActiveLevel(state).any { e -> e is DrawingElement.Symbol && rect.contains(e.center) &&
            (e.category == PlanSymbols.SHOWERS || e.symbolName == "Toilet" || e.symbolName.contains("Tub")) }
        if (wet) return RoomType.BATH
        if (!name.startsWith("Room ")) return t
        val inch = state.inchesToPx(1f)
        val w = rect.width / inch; val h = rect.height / inch
        return if (min(w, h) <= 36f || w * h / 144f < 25f) RoomType.CLOSET else t
    }

    fun optsFor(rect: Rect, state: CanvasState): RoomElectrical? =
        state.roomElectrical.firstOrNull { rect.contains(Offset(it.x, it.y)) }

    /** The room's light: the card's choice, else by type; a kitchen with an island gets pendants over it (Dan, Nov 27). */
    fun lightOf(room: Room, state: CanvasState): LightKind =
        room.opts?.light?.takeIf { it.isNotBlank() }?.let { runCatching { LightKind.valueOf(it) }.getOrNull() }
            ?: if (room.type == RoomType.KITCHEN && hasIsland(room.rect, state)) LightKind.PENDANTS else defaultLight(room.type)

    fun hasIsland(rect: Rect, state: CanvasState) =
        Layers.onActiveLevel(state).any { it is DrawingElement.Symbol && PlanSymbols.isIsland(it) && rect.contains(it.center) }

    /** Every room on the active level (the rooms auto-dimensioning finds). */
    fun rooms(state: CanvasState): List<Room> {
        cache?.let { (els, opts, layer, list) -> if (els === state.elements && opts === state.roomElectrical && layer == state.activeLayerId) return list }
        val walls = Layers.onActiveLevel(state).filterIsInstance<DrawingElement.Symbol>().filter { it.category == PlanSymbols.WALLS }.map { FloorPlan.bounds(it) }
        val list = if (walls.isEmpty()) emptyList() else {
            val outer = Rect(walls.minOf { it.left }, walls.minOf { it.top }, walls.maxOf { it.right }, walls.maxOf { it.bottom })
            AutoDimension.rooms(walls, outer, state).map { roomFor(it, nameIn(it, state), state) }
        }
        cache = Cache(state.elements, state.roomElectrical, state.activeLayerId, list)
        return list
    }
    private data class Cache(val els: List<DrawingElement>, val opts: List<RoomElectrical>, val layer: String?, val list: List<Room>)
    private var cache: Cache? = null

    /** The layer the button draws on: the active level's electrical layer, else the active layer. */
    fun layerId(state: CanvasState): String =
        state.effectiveLayers.firstOrNull { it.trade == LayerTrade.ELECTRICAL && it.levelId == state.activeLayer.levelId }?.id
            ?: state.effectiveLayers.firstOrNull { it.trade == LayerTrade.ELECTRICAL }?.id ?: state.activeLayer.id

    /** Why the button can't run with this layer active (a building-wide layer like Exterior or Site has no floor), or null. */
    fun refusal(state: CanvasState): String? =
        if (state.activeLayer.levelId == null) "Make a floor's plan or electrical layer active first: electrical is laid out one floor at a time." else null

    private fun isGen(s: DrawingElement.Symbol) = (s.properties[GEN] ?: 0f) >= 0.5f
    private fun moved(s: DrawingElement.Symbol) =
        abs(s.center.x - (s.properties[GX] ?: s.center.x)) > 1f || abs(s.center.y - (s.properties[GY] ?: s.center.y)) > 1f

    // ---------------------------------------------------------------- the room's outline, walked as one line

    /**
     * The room's sides walked clockwise from its top-left corner, as one line [len] inches long: north (west to east),
     * east (north to south), south (east to west), west (south to north). [at] gives the point on the wall face, the
     * way into the room and the turn a wall device takes (its back to the wall).
     */
    private class Outline(val r: Rect, val inch: Float) {
        val w = r.width / inch
        val h = r.height / inch
        val len = 2f * (w + h)
        val corners = listOf(0f, w, w + h, 2f * w + h)

        fun side(s: Float): Int { val t = ((s % len) + len) % len; return when { t < w -> 0; t < w + h -> 1; t < 2f * w + h -> 2; else -> 3 } }

        /** The face point, inward direction and rotation at [s] inches along. */
        fun at(s: Float): Triple<Offset, Offset, Float> {
            val t = ((s % len) + len) % len
            return when (side(t)) {
                0 -> Triple(Offset(r.left + t * inch, r.top), Offset(0f, 1f), 0f)
                1 -> Triple(Offset(r.right, r.top + (t - w) * inch), Offset(-1f, 0f), 90f)
                2 -> Triple(Offset(r.right - (t - w - h) * inch, r.bottom), Offset(0f, -1f), 180f)
                else -> Triple(Offset(r.left, r.bottom - (t - 2f * w - h) * inch), Offset(1f, 0f), 270f)
            }
        }

        /** How far along the outline a point near a face is (the nearest face). */
        fun along(p: Offset): Float {
            val dN = abs(p.y - r.top); val dE = abs(p.x - r.right); val dS = abs(p.y - r.bottom); val dW = abs(p.x - r.left)
            val m = minOf(dN, dE, dS, dW)
            return when (m) {
                dN -> ((p.x - r.left) / inch).coerceIn(0f, w)
                dE -> w + ((p.y - r.top) / inch).coerceIn(0f, h)
                dS -> w + h + ((r.right - p.x) / inch).coerceIn(0f, w)
                // Short of the very end, which is the start again (the north-west corner, on the north side).
                else -> 2f * w + h + ((r.bottom - p.y) / inch).coerceIn(0f, h - 0.01f)
            }
        }

        /** Distance from [p] to the nearest face, inches. */
        fun offFace(p: Offset) = minOf(abs(p.y - r.top), abs(p.x - r.right), abs(p.y - r.bottom), abs(p.x - r.left)) / inch

        /** The outline span [from, to] (inches) a box against face [side] covers, or null when it isn't against it. */
        private fun spanFor(side: Int, b: Rect): Pair<Float, Float> = when (side) {
            0 -> ((max(b.left, r.left) - r.left) / inch) to ((min(b.right, r.right) - r.left) / inch)
            1 -> (w + (max(b.top, r.top) - r.top) / inch) to (w + (min(b.bottom, r.bottom) - r.top) / inch)
            2 -> (w + h + (r.right - min(b.right, r.right)) / inch) to (w + h + (r.right - max(b.left, r.left)) / inch)
            else -> (2f * w + h + (r.bottom - min(b.bottom, r.bottom)) / inch) to (2f * w + h + (r.bottom - max(b.top, r.top)) / inch)
        }
        private fun alongX(b: Rect) = b.right > r.left + inch && b.left < r.right - inch
        private fun alongY(b: Rect) = b.bottom > r.top + inch && b.top < r.bottom - inch

        /**
         * The outline span (inches) an item inside the room covers on the face its back is against: [side] (0 N, 1 E,
         * 2 S, 3 W, from its turn) when given, else the first face it is within [reachIn] of. Null when it isn't against one.
         */
        fun spanOn(b: Rect, reachIn: Float, side: Int? = null): Pair<Float, Float>? {
            val tol = reachIn * inch
            val lo = -3f * inch
            val sides = listOfNotNull(
                0.takeIf { alongX(b) && b.top - r.top in lo..tol },
                1.takeIf { alongY(b) && r.right - b.right in lo..tol },
                2.takeIf { alongX(b) && r.bottom - b.bottom in lo..tol },
                3.takeIf { alongY(b) && b.left - r.left in lo..tol })
            val pick = if (side != null) sides.firstOrNull { it == side } else sides.firstOrNull()
            return pick?.let { spanFor(it, b) }
        }

        /** The spans of the room's outline a wall lines: the wall's face on one of the room's sides. */
        fun wallSpans(b: Rect): List<Pair<Float, Float>> = listOfNotNull(
            spanFor(0, b).takeIf { alongX(b) && abs(b.bottom - r.top) <= inch },
            spanFor(1, b).takeIf { alongY(b) && abs(b.left - r.right) <= inch },
            spanFor(2, b).takeIf { alongX(b) && abs(b.top - r.bottom) <= inch },
            spanFor(3, b).takeIf { alongY(b) && abs(b.right - r.left) <= inch })
    }

    /** A door on the room's outline. [latch]: outline inches of the latch jamb; [toward]: +1 the latch is further along. */
    data class Door(val symbol: DrawingElement.Symbol, val from: Float, val to: Float, val latch: Float, val toward: Int,
                    val exterior: Boolean, val otherSide: RoomType?)

    /** The room's doors, in order along its outline. */
    fun doorsOf(rect: Rect, state: CanvasState): List<Door> {
        val inch = state.inchesToPx(1f)
        val o = Outline(rect, inch)
        val walls = Layers.onActiveLevel(state).filterIsInstance<DrawingElement.Symbol>().filter { it.category == PlanSymbols.WALLS }.map { FloorPlan.bounds(it) }
        if (walls.isEmpty()) return emptyList()
        return Layers.onActiveLevel(state).filterIsInstance<DrawingElement.Symbol>()
            .filter { it.category == PlanSymbols.DOORS_WINDOWS &&
                (PlanSymbols.isDoor(it.symbolName) || PlanSymbols.isPocketDoor(it.symbolName) || PlanSymbols.isSlidingDoor(it.symbolName)) }
            .mapNotNull { d ->
                val anchor = Elevation.openingAnchor(d)
                // In a wall that lines this room: the threshold within the wall's thickness of one of the room's faces.
                if (o.offFace(anchor) > 10f) return@mapNotNull null
                // The threshold is in the wall, a few inches past the room's face.
                val inRange = rect.inflate(10f * inch).contains(anchor)
                if (!inRange) return@mapNotNull null
                val c = o.along(anchor)
                val slides = PlanSymbols.isPocketDoor(d.symbolName) || PlanSymbols.isSlidingDoor(d.symbolName)
                val latchPt = if (slides) {
                    // A pocket or sliding door is drawn its width along the wall and the wall's depth across, standing
                    // up in a north-south wall. Its open side is away from the pocket (Dan, Nov 27: the switch goes there).
                    val along = max(d.size.width, d.size.height)
                    val axis = if (d.size.width >= d.size.height) Offset(1f, 0f) else Offset(0f, 1f)
                    val end = axis * (if (PlanSymbols.pocketRight(d)) -along / 2f else along / 2f)
                    rotateAbout(d.center + end, d.center, d.rotation)
                } else rotateAbout(d.center + Offset(if (PlanSymbols.hingeRight(d)) -d.size.width / 2f else d.size.width / 2f, d.size.height / 2f), d.center, d.rotation)
                val half = (if (slides) max(d.size.width, d.size.height) else d.size.width) / inch / 2f
                val latch = o.along(latchPt)
                val toward = if (latch >= c) 1 else -1
                // The other side: 24" through the door along the door's own direction (not the room's outline, which can
                // put the probe inside the wall when the door sits at a corner of the room's box; Bedroom 3, Nov 27).
                val slidesAcross = slides && d.size.width < d.size.height
                val normal = rotateAbout(d.center + (if (slidesAcross) Offset(1f, 0f) else Offset(0f, 1f)), d.center, d.rotation) - d.center
                val p1 = anchor + normal * (24f * inch)
                val p2 = anchor - normal * (24f * inch)
                val beyond = when {
                    rect.contains(p1) && !rect.contains(p2) -> p2
                    rect.contains(p2) && !rect.contains(p1) -> p1
                    else -> if ((p1 - rect.center).getDistance() > (p2 - rect.center).getDistance()) p1 else p2
                }
                val outer = Rect(walls.minOf { it.left }, walls.minOf { it.top }, walls.maxOf { it.right }, walls.maxOf { it.bottom })
                val inWall = walls.any { it.contains(beyond) }
                val outside = !outer.contains(beyond) || (!inWall && FloorPlan.areaAt(beyond, state) == null)
                val otherType = if (outside) null else roomAt(beyond, state)?.type
                Door(d, c - half - 3.5f, c + half + 3.5f, if (toward > 0) c + half else c - half, toward, outside, otherType)
            }
            .sortedBy { it.from }
    }

    // ---------------------------------------------------------------- spacing

    /**
     * The places along [a]..[b] (inches) that, with the [fixed] ones already there, leave no point more than [reach]
     * from one: each open end counts as a plug [reach] past it, then any gap wider than 2 x [reach] gets the fewest
     * new plugs, spaced evenly.
     */
    fun fill(a: Float, b: Float, fixed: List<Float>, reach: Float): List<Float> {
        val pts = (listOf(a - reach) + fixed.filter { it in a..b }.sorted() + listOf(b + reach))
        val out = mutableListOf<Float>()
        for (i in 0 until pts.size - 1) {
            val g = pts[i + 1] - pts[i]
            if (g <= 2f * reach + 0.01f) continue
            val m = ceil(g / (2f * reach) - 0.0001f).toInt() - 1
            for (k in 1..m) out += pts[i] + g * k / (m + 1)
        }
        return out
    }

    /** Runs of true in a ring of [n] samples (one per inch): (start, end) with end possibly past n (wrapped). */
    private fun runs(mask: BooleanArray): List<Pair<Int, Int>> {
        val n = mask.size
        if (n == 0) return emptyList()
        if (mask.all { it }) return listOf(0 to n)
        val start = (0 until n).first { !mask[it] }
        val out = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < n) {
            val k = (start + i) % n
            if (mask[k]) {
                var j = i
                while (j < n && mask[(start + j) % n]) j++
                out += (start + i) to (start + j)
                i = j
            } else i++
        }
        return out
    }

    // ---------------------------------------------------------------- the layout

    private class Builder(val state: CanvasState, val layer: String) {
        val inch = state.inchesToPx(1f)
        val added = mutableListOf<DrawingElement.Symbol>()
        val legs = mutableListOf<SwitchLeg>()

        fun put(name: String, center: Offset, rotation: Float = 0f, mountIn: Float? = null): DrawingElement.Symbol {
            val (wIn, dIn) = Mep.footprints[name] ?: (10f to 8f)
            val props = buildMap {
                put("widthInches", wIn); put("depthInches", dIn)
                put(GEN, 1f); put(GX, center.x); put(GY, center.y)
                if (mountIn != null) put(Mep.MOUNT_H, mountIn)
            }
            val s = DrawingElement.Symbol(symbolName = name, category = Mep.ELECTRICAL, center = center,
                size = Size(wIn * inch, dIn * inch), color = state.currentColor, rotation = rotation, properties = props, layerId = layer)
            added += s
            return s
        }

        /** A wall device at [s] inches along [o], its back to the wall. */
        fun onWall(o: Outline, s: Float, name: String, mountIn: Float? = null): DrawingElement.Symbol {
            val (p, inward, rot) = o.at(s)
            val depth = (Mep.footprints[name]?.second ?: 8f) * inch
            return put(name, p + inward * (depth / 2f), rot, mountIn)
        }
    }

    /** Lays out every room on the active level (null) or the one room at [roomPoint], replacing the button's last layout there. */
    fun apply(state: CanvasState, roomPoint: Offset? = null): CanvasState {
        val all = rooms(state)
        val targets = if (roomPoint == null) all else listOfNotNull(roomAt(roomPoint, state))
        if (targets.isEmpty()) return state
        val inch = state.inchesToPx(1f)
        val level = Layers.onActiveLevel(state).map { it.id }.toSet()
        // Clear what the button put there before and nobody moved.
        val zone: (DrawingElement.Symbol) -> Boolean = if (roomPoint == null) { _ -> true } else { s ->
            targets.any { it.rect.inflate(14f * inch).contains(Offset(s.properties[GX] ?: s.center.x, s.properties[GY] ?: s.center.y)) }
        }
        val gone = state.elements.filterIsInstance<DrawingElement.Symbol>()
            .filter { it.id in level && it.category == Mep.ELECTRICAL && isGen(it) && !moved(it) && zone(it) }.map { it.id }.toSet()
        val kept = state.copy(elements = state.elements.filter { it.id !in gone },
            switchLegs = state.switchLegs.filter { it.switchId !in gone && it.deviceId !in gone })
        val b = Builder(kept, layerId(kept))
        for (room in targets) layRoom(room, kept, b)
        if (roomPoint == null) {
            outside(all, kept, b)
            stairs(all, kept, b)
            alarms(all, kept, b)
            equipment(kept, b)
        } else if (targets.first().type == RoomType.BEDROOM) {
            // A bedroom laid out alone still gets its alarm.
            alarms(targets, kept, b, storyAlarm = false)
        }
        return kept.copy(elements = kept.elements + b.added, switchLegs = (kept.switchLegs + b.legs).distinct())
    }

    /** The face a symbol's back is against, from its turn: 0 N, 1 E, 2 S, 3 W (its back is its local top). */
    private fun backSide(s: DrawingElement.Symbol): Int = ((kotlin.math.round(s.rotation / 90f).toInt() % 4) + 4) % 4
    private fun Outline.spanOf(s: DrawingElement.Symbol, reachIn: Float) = spanOn(FloorPlan.bounds(s), reachIn, backSide(s)) ?: spanOn(FloorPlan.bounds(s), reachIn)

    /** Electrical items already in [rect] that stay (placed by hand or moved). */
    private fun existing(rect: Rect, state: CanvasState, inch: Float) = state.elements.filterIsInstance<DrawingElement.Symbol>()
        .filter { it.category == Mep.ELECTRICAL && rect.inflate(2f * inch).contains(it.center) }

    private fun layRoom(room: Room, state: CanvasState, b: Builder) {
        val inch = b.inch
        val r = room.rect
        val o = Outline(r, inch)
        val n = o.len.toInt().coerceAtLeast(1)
        val t = room.type
        val opts = room.opts
        val here = existing(r, state, inch)
        val onLevel = Layers.onActiveLevel(state).filterIsInstance<DrawingElement.Symbol>()
        val walls = onLevel.filter { it.category == PlanSymbols.WALLS }.map { FloorPlan.bounds(it) }
        val doors = doorsOf(r, state)

        // Where there is wall along the outline.
        val wall = BooleanArray(n)
        for (wb in walls) for ((f, e) in o.wallSpans(wb)) for (i in f.toInt() until min(n, ceil(e).toInt())) wall[i] = true
        // A cased opening ("Opening 6'-0\"") is a way in with no wall: a gap, like a door with no door (Practice House's kitchen).
        for (op in onLevel.filter { it.category == PlanSymbols.DOORS_WINDOWS && PlanSymbols.isOpening(it.symbolName) }) {
            if (o.offFace(op.center) > 10f || !r.inflate(10f * inch).contains(op.center)) continue
            val c = o.along(op.center)
            val half = max(op.size.width, op.size.height) / inch / 2f
            for (i in (c - half).toInt()..ceil(c + half).toInt()) wall[((i % n) + n) % n] = false
        }
        val usable = wall.copyOf()
        for (d in doors) for (i in d.from.toInt()..ceil(d.to).toInt()) usable[((i % n) + n) % n] = false
        // Fixed things against the walls break the wall space.
        val fixed = onLevel.filter { s ->
            r.inflate(2f * inch).overlaps(FloorPlan.bounds(s)) && when (s.category) {
                PlanSymbols.CABINETS -> !PlanSymbols.isUpperCabinet(s) && !PlanSymbols.isIsland(s)
                PlanSymbols.APPLIANCES -> !Appliances.isOverlay(s)
                PlanSymbols.SHOWERS, PlanSymbols.STAIRS -> true
                PlanSymbols.FIXTURES -> s.symbolName.contains("Tub") || s.symbolName.contains("Shower") || s.symbolName == "Refrigerator" ||
                    s.symbolName == "Range" || s.symbolName == "Dishwasher" || s.symbolName == "Water Heater"
                else -> false
            }
        }
        for (s in fixed) o.spanOf(s, 6f)?.let { (f, e) -> for (i in f.toInt() until min(n, ceil(e).toInt())) usable[i] = false }

        fun nudge(s0: Float): Float {
            val s = ((s0 % o.len) + o.len) % o.len
            for (c in o.corners) { val d = s - c; if (abs(d) < 6f) return c + (if (d < 0f) -6f else 6f) }
            if (abs(s - o.len) < 6f) return o.len - 6f
            return s
        }
        val wallPlugsAt = mutableListOf<Float>()

        // ---- wall plugs (6'/12')
        if (wallRule(t)) {
            val fixedPlugs = here.filter { Mep.isOutlet(it.symbolName) && it.symbolName != Mep.COUNTER && o.offFace(it.center) <= 10f }.map { o.along(it.center) }
            val placed = mutableListOf<Pair<Int, Float>>()
            for ((a, e) in runs(usable)) {
                if (e - a < 24) continue
                val fx = fixedPlugs.flatMap { listOf(it, it + o.len) }.filter { it >= a && it <= e }
                fill(a.toFloat(), e.toFloat(), fx, 72f).forEach { placed += (a to it) }
            }
            // Extra plugs (card): into the widest gaps between plugs on the same run.
            val extra = opts?.extraPlugs ?: 0
            val positions = placed.map { it.second }.toMutableList()
            repeat(extra) {
                val runsList = runs(usable).filter { it.second - it.first >= 24 }
                var best: Pair<Float, Float>? = null
                for ((a, e) in runsList) {
                    val pts = (listOf(a.toFloat()) + positions.filter { it >= a && it <= e } + fixedPlugs.filter { it >= a && it <= e } + listOf(e.toFloat())).sorted()
                    for (i in 0 until pts.size - 1) if (best == null || pts[i + 1] - pts[i] > best!!.second - best!!.first) best = pts[i] to pts[i + 1]
                }
                best?.let { positions += (it.first + it.second) / 2f }
            }
            for (p in positions) { val s = nudge(p); b.onWall(o, s, Mep.DUPLEX); wallPlugsAt += s }
        }

        // ---- counters (2'/4') and islands
        if (counterRule(t)) {
            val counter = BooleanArray(n)
            val breaks = BooleanArray(n)
            for (s in onLevel) {
                val bb = FloorPlan.bounds(s)
                if (!r.inflate(2f * inch).overlaps(bb)) continue
                val name = s.symbolName
                val isCounter = (s.category == PlanSymbols.CABINETS && !PlanSymbols.isUpperCabinet(s) && !PlanSymbols.isIsland(s) &&
                    !PlanSymbols.isTallCabinet(name)) || name.startsWith("Dishwasher")
                val isBreak = name.startsWith("Sink Base") || Appliances.isRange(name) || Appliances.isFridge(name) || name == "Range" ||
                    name == "Refrigerator" || (s.category == PlanSymbols.FIXTURES && name.contains("Sink")) || PlanSymbols.isTallCabinet(name)
                val span = o.spanOf(s, 6f) ?: continue
                for (i in span.first.toInt() until min(n, ceil(span.second).toInt())) { if (isBreak) breaks[i] = true else if (isCounter) counter[i] = true }
            }
            for (i in 0 until n) if (breaks[i]) counter[i] = false
            for ((a, e) in runs(counter)) {
                if (e - a < 12) continue
                fill(a.toFloat(), e.toFloat(), emptyList(), 24f).forEach { b.onWall(o, nudge(it), Mep.COUNTER) }
            }
            for (isl in onLevel.filter { PlanSymbols.isIsland(it) && r.contains(it.center) }) {
                // On the island's end (its short side), on the counter.
                val ib = FloorPlan.bounds(isl)
                val wide = ib.width >= ib.height
                val c = if (wide) Offset(ib.right, ib.center.y) else Offset(ib.center.x, ib.bottom)
                val rot = if (wide) 270f else 0f
                b.put(Mep.COUNTER, c + (if (wide) Offset(4f * inch, 0f) else Offset(0f, 4f * inch)), rot)
            }
        }

        // ---- baths: GFCI by each basin
        if (t == RoomType.BATH) {
            val basins = onLevel.filter { s -> r.contains(s.center) &&
                ((s.category == PlanSymbols.CABINETS && PlanSymbols.isVanity(s.symbolName)) || (s.category == PlanSymbols.FIXTURES && s.symbolName.contains("Sink"))) }
                .filter { s -> s.category != PlanSymbols.FIXTURES || onLevel.none { v -> v.category == PlanSymbols.CABINETS && PlanSymbols.isVanity(v.symbolName) && FloorPlan.bounds(v).contains(s.center) } }
            if (basins.isEmpty()) {
                runs(usable).maxByOrNull { it.second - it.first }?.let { (a, e) -> b.onWall(o, nudge((a + e) / 2f), Mep.GFCI, 40f) }
            }
            for (v in basins) {
                val span = o.spanOf(v, 6f) ?: continue
                val beside = listOf(span.second + 6f, span.first - 6f).firstOrNull { s -> usable[((s.toInt() % n) + n) % n] }
                if (beside != null) b.onWall(o, nudge(beside), Mep.GFCI, 40f) else b.onWall(o, span.second - 4f, Mep.GFCI, 40f)
                if (span.second - span.first > 60f) b.onWall(o, (span.first + span.second) / 2f, Mep.GFCI, 40f)
            }
        }

        // ---- laundry, halls, garage, basement
        fun longestRunMiddle(): Float? = runs(usable).filter { it.second - it.first >= 24 }.maxByOrNull { it.second - it.first }?.let { (a, e) -> (a + e) / 2f }
        val hasPlug = here.any { Mep.isOutlet(it.symbolName) }
        when (t) {
            RoomType.LAUNDRY -> {
                val washer = onLevel.firstOrNull { r.contains(it.center) && it.symbolName == "Washer" }
                val s = washer?.let { o.spanOf(it, 6f)?.let { (f, e) -> (f + e) / 2f } } ?: longestRunMiddle()
                if (s != null && !hasPlug) b.onWall(o, nudge(s), Mep.DUPLEX, 42f)
            }
            RoomType.HALL, RoomType.FOYER -> if (max(r.width, r.height) / inch >= 120f && !hasPlug) longestRunMiddle()?.let { b.onWall(o, nudge(it), Mep.DUPLEX) }
            RoomType.GARAGE -> {
                val perBay = state.building.codeEdition in setOf(CodeEdition.IRC_2021, CodeEdition.IRC_2024)
                val count = if (perBay) max(1, (max(r.width, r.height) / inch / 132f).toInt()) else 1
                val run = runs(usable).filter { it.second - it.first >= 24 }.maxByOrNull { it.second - it.first }
                if (run != null && !hasPlug) for (k in 0 until count) b.onWall(o, nudge(run.first + (run.second - run.first) * (k + 0.5f) / count), Mep.GFCI)
            }
            RoomType.BASEMENT -> if (!hasPlug) longestRunMiddle()?.let { b.onWall(o, nudge(it), Mep.GFCI) }
            else -> {}
        }

        // ---- card extras
        if (opts?.floorPlug == true) b.put(Mep.FLOOR_OUTLET, r.center + Offset(0f, 18f * inch))
        if (opts?.tvPlug == true) longestRunMiddle()?.let { b.onWall(o, nudge(it + 30f), Mep.DUPLEX, 60f) }

        // ---- lights
        val light = lightOf(room, state)
        val hasLight = here.any { Mep.isSwitched(it.symbolName) }
        val lights = mutableListOf<DrawingElement.Symbol>()
        val wIn = r.width / inch
        val hIn = r.height / inch
        if (!hasLight && light != LightKind.NONE) {
            fun grid(count: Int?) {
                var nx = if (wIn < 96f) 1 else max(2, (wIn / 60f).roundToInt())
                var ny = if (hIn < 96f) 1 else max(2, (hIn / 60f).roundToInt())
                if (count != null && count > 0) {
                    // The asked-for number, spread to the room's shape.
                    val ratio = wIn / hIn
                    ny = max(1, kotlin.math.sqrt(count / ratio).roundToInt()); nx = max(1, ceil(count / ny.toFloat()).toInt())
                }
                // No can over an island that gets pendants (Dan, Nov 27).
                val skip = if (light == LightKind.PENDANTS) onLevel.filter { PlanSymbols.isIsland(it) && r.contains(it.center) }.map { FloorPlan.bounds(it).inflate(6f * inch) } else emptyList()
                for (i in 0 until nx) for (j in 0 until ny) {
                    val at = Offset(r.left + r.width * (i + 0.5f) / nx, r.top + r.height * (j + 0.5f) / ny)
                    if (skip.none { it.contains(at) }) lights += b.put(Mep.CAN_6, at)
                }
            }
            when (light) {
                LightKind.CANS -> grid(opts?.cans?.takeIf { it > 0 })
                LightKind.PENDANTS -> {
                    grid(opts?.cans?.takeIf { it > 0 })
                    for (isl in onLevel.filter { PlanSymbols.isIsland(it) && r.contains(it.center) }) {
                        val ib = FloorPlan.bounds(isl)
                        val wide = ib.width >= ib.height
                        val lenIn = (if (wide) ib.width else ib.height) / inch
                        val k = if (lenIn >= 84f) 3 else 2
                        for (q in 0 until k) lights += b.put(Mep.PENDANT,
                            if (wide) Offset(ib.left + ib.width * (q + 0.5f) / k, ib.center.y) else Offset(ib.center.x, ib.top + ib.height * (q + 0.5f) / k))
                    }
                }
                LightKind.FAN -> {
                    val area = wIn * hIn / 144f
                    lights += b.put(if (area > 300f) Mep.FAN_60 else if (area >= 144f) Mep.FAN_52 else Mep.FAN_44, r.center)
                }
                LightKind.VANITY -> {
                    val v = onLevel.firstOrNull { s -> r.contains(s.center) && s.category == PlanSymbols.CABINETS && PlanSymbols.isVanity(s.symbolName) }
                    val span = v?.let { o.spanOf(it, 6f) }
                    if (span != null) lights += b.onWall(o, (span.first + span.second) / 2f, Mep.SCONCE, 78f)
                    else lights += b.put(Mep.CEILING_LIGHT, r.center)
                }
                LightKind.CENTER -> {
                    val long = max(wIn, hIn)
                    val k = when (t) {
                        RoomType.HALL -> max(1, ceil(long / 120f).toInt())
                        RoomType.GARAGE -> if (wIn * hIn / 144f > 400f) 2 else 1
                        else -> 1
                    }
                    for (q in 0 until k) lights += b.put(Mep.CEILING_LIGHT,
                        if (wIn >= hIn) Offset(r.left + r.width * (q + 0.5f) / k, r.center.y) else Offset(r.center.x, r.top + r.height * (q + 0.5f) / k))
                }
                LightKind.NONE -> {}
            }
        }
        if (t == RoomType.BATH && here.none { it.symbolName == Mep.BATH_FAN || it.symbolName == Mep.BATH_FAN_LIGHT }) {
            val at = if (lights.any { abs(it.center.x - r.center.x) < inch && abs(it.center.y - r.center.y) < inch }) r.center + Offset(0f, 24f * inch) else r.center
            lights += b.put(Mep.BATH_FAN, at)
        }
        // A stair in the room: a light over it, a 3-way switch by it.
        for (st in onLevel.filter { it.category == PlanSymbols.STAIRS && r.contains(it.center) }) {
            val l = b.put(Mep.CEILING_LIGHT, st.center)
            val sw = runs(usable).minByOrNull { (a, e) -> (o.at((a + e) / 2f).first - st.center).getDistance() }?.let { (a, e) ->
                val sNear = (a..e).minByOrNull { s -> (o.at(s.toFloat()).first - st.center).getDistance() }!!.toFloat()
                b.onWall(o, nudge(sNear), Mep.SWITCH_3)
            }
            if (sw != null) b.legs += SwitchLeg(sw.id, l.id)
        }

        // ---- switches at the door(s): for the new lights, or for lights kept from before (moved or placed by hand)
        if (lights.isEmpty()) lights += here.filter { Mep.isSwitched(it.symbolName) }
        if (lights.isEmpty()) return
        val switchable = doors.filter { it.otherSide != RoomType.CLOSET || t == RoomType.CLOSET }
        fun switchAt(d: Door, name: String): DrawingElement.Symbol {
            // Tight to the casing on the side the door opens from (Dan, Nov 27): 3 1/2" casing, then the box (5" to its middle).
            var s = d.latch + d.toward * 5f
            if (!wall[((s.toInt() % n) + n) % n]) s = (if (d.toward > 0) d.from else d.to) - d.toward * 2.5f
            val near = here.firstOrNull { Mep.isSwitch(it.symbolName) && (o.at(s).first - it.center).getDistance() < 18f * inch }
            return near ?: b.onWall(o, nudge(s), name)
        }
        val chosen: List<Door> = run {
            val pick = opts?.switchDoor?.takeIf { it >= 0 }?.let { doors.getOrNull(it) }
            val main = pick ?: switchable.firstOrNull { it.otherSide in setOf(RoomType.HALL, RoomType.FOYER, RoomType.LIVING, RoomType.FAMILY, RoomType.OTHER) }
                ?: switchable.firstOrNull { !it.exterior } ?: switchable.firstOrNull()
            // Two ways in = 3-way switches, one at each (Dan, Nov 27), e.g. the two pocket doors of a Jack-and-Jill bath,
            // each on the door's open side. The card can turn it off.
            val want3 = when (opts?.threeWay ?: -1) { 1 -> true; 0 -> false; else -> switchable.size >= 2 }
            val second = if (want3 && main != null) switchable.filter { it != main }.maxByOrNull { abs(it.latch - main.latch).let { d -> min(d, o.len - d) } } else null
            listOfNotNull(main, second)
        }
        val switches = if (chosen.isEmpty()) {
            // No door (an open room, like a kitchen open to the living room; Dan, Nov 27): beside its widest opening, the
            // gap in its walls 2' or wider, on the wall just past the opening's edge. With no opening, the longest wall run.
            val gap = runs(BooleanArray(n) { !wall[it] }).filter { it.second - it.first >= 24 }.maxByOrNull { it.second - it.first }
            val at = gap?.let { (a, e) -> listOf(e + 6f, a - 6f).firstOrNull { p -> usable[((p.toInt() % n) + n) % n] } ?: (e + 6f) }
                ?: runs(usable).maxByOrNull { it.second - it.first }?.let { (a, _) -> a + 6f }
            listOfNotNull(at?.let { here.firstOrNull { sw -> Mep.isSwitch(sw.symbolName) && (o.at(it).first - sw.center).getDistance() < 18f * inch } ?: b.onWall(o, nudge(it), Mep.SWITCH) })
        } else chosen.map { switchAt(it, if (chosen.size > 1) Mep.SWITCH_3 else Mep.SWITCH) }
        for (sw in switches) for (l in lights) b.legs += SwitchLeg(sw.id, l.id)
    }

    /** Outside: a light at each outside door (switched inside), and weatherproof plugs front and back. */
    private fun outside(rooms: List<Room>, state: CanvasState, b: Builder) {
        val inch = b.inch
        val outs = rooms.flatMap { room -> doorsOf(room.rect, state).filter { it.exterior }.map { room to it } }
            .distinctBy { it.second.symbol.id }
        val sides = mutableSetOf<Int>()
        for ((room, d) in outs) {
            val o = Outline(room.rect, inch)
            val (p, inward, rot) = o.at(d.latch + d.toward * 8f)
            val thick = b.state.elements.filterIsInstance<DrawingElement.Symbol>().filter { it.category == PlanSymbols.WALLS }
                .map { FloorPlan.bounds(it) }.firstOrNull { it.inflate(inch).contains(p - inward * inch) }
                ?.let { if (inward.x != 0f) it.width else it.height } ?: (6f * inch)
            val outsideFace = p - inward * thick
            val depth = (Mep.footprints[Mep.EXTERIOR_LIGHT]?.second ?: 6f) * inch
            val lamp = b.put(Mep.EXTERIOR_LIGHT, outsideFace - inward * (depth / 2f), (rot + 180f) % 360f)
            val sw = b.onWall(o, d.latch + d.toward * 14f, Mep.SWITCH)
            b.legs += SwitchLeg(sw.id, lamp.id)
            val side = o.side(d.latch)
            if (sides.size < 2 && side !in sides) {
                sides += side
                val (q, inw2, r2) = o.at(d.from - 12f)
                val wpDepth = (Mep.footprints[Mep.WP]?.second ?: 8f) * inch
                b.put(Mep.WP, q - inw2 * thick - inw2 * (wpDepth / 2f), (r2 + 180f) % 360f)
            }
        }
        // Front and back (E3901.7): with no outside door on the far side, a plug in the middle of the building's far wall.
        val walls = Layers.onActiveLevel(state).filterIsInstance<DrawingElement.Symbol>().filter { it.category == PlanSymbols.WALLS }.map { FloorPlan.bounds(it) }
        if (walls.isEmpty()) return
        val outer = Rect(walls.minOf { it.left }, walls.minOf { it.top }, walls.maxOf { it.right }, walls.maxOf { it.bottom })
        val want = when (sides.size) { 0 -> listOf(0, 2); 1 -> listOf((sides.first() + 2) % 4); else -> emptyList() }
        val half = (Mep.footprints[Mep.WP]?.second ?: 8f) * inch / 2f
        for (k in want) when (k) {
            0 -> b.put(Mep.WP, Offset(outer.center.x, outer.top - half), 180f)
            1 -> b.put(Mep.WP, Offset(outer.right + half, outer.center.y), 270f)
            2 -> b.put(Mep.WP, Offset(outer.center.x, outer.bottom + half), 0f)
            else -> b.put(Mep.WP, Offset(outer.left - half, outer.center.y), 90f)
        }
    }

    /** A stair not inside a room (open to a hall): a light over it and a 3-way switch on the nearest wall (E3903.3). */
    private fun stairs(rooms: List<Room>, state: CanvasState, b: Builder) {
        val inch = b.inch
        val onLevel = Layers.onActiveLevel(state).filterIsInstance<DrawingElement.Symbol>()
        val walls = onLevel.filter { it.category == PlanSymbols.WALLS }.map { FloorPlan.bounds(it) }
        for (st in onLevel.filter { it.category == PlanSymbols.STAIRS && rooms.none { r -> r.rect.contains(it.center) } }) {
            if (existing(FloorPlan.bounds(st), state, inch).any { Mep.isSwitched(it.symbolName) }) continue
            val c = FloorPlan.bounds(st).center
            val light = b.put(Mep.CEILING_LIGHT, c)
            // The nearest wall face to the stair's middle, and the device's turn so its back is to that wall.
            var best: Triple<Float, Offset, Float>? = null
            for (w in walls) {
                val faces = listOf(
                    Triple(Offset(c.x.coerceIn(w.left, w.right), w.bottom), Offset(0f, 1f), 0f),
                    Triple(Offset(c.x.coerceIn(w.left, w.right), w.top), Offset(0f, -1f), 180f),
                    Triple(Offset(w.right, c.y.coerceIn(w.top, w.bottom)), Offset(1f, 0f), 270f),
                    Triple(Offset(w.left, c.y.coerceIn(w.top, w.bottom)), Offset(-1f, 0f), 90f))
                for ((p, out, rot) in faces) {
                    val d = (p - c).getDistance()
                    // Only a face that looks toward the stair.
                    if ((c - p).x * out.x + (c - p).y * out.y <= 0f) continue
                    if (best == null || d < best!!.first) best = Triple(d, p + out * (4f * inch), rot)
                }
            }
            best?.let { (_, p, rot) -> val sw = b.put(Mep.SWITCH_3, p, rot); b.legs += SwitchLeg(sw.id, light.id) }
        }
    }

    /** Rooms that never get a smoke/CO alarm: steam and cooking set them off, and closets and garages aren't where code puts them. */
    private val noAlarm = setOf(RoomType.BATH, RoomType.KITCHEN, RoomType.CLOSET, RoomType.PANTRY, RoomType.GARAGE, RoomType.LAUNDRY)

    /** Smoke in each bedroom, smoke/CO outside each sleeping area, and one on the story if none yet. */
    private fun alarms(rooms: List<Room>, state: CanvasState, b: Builder, storyAlarm: Boolean = true) {
        val inch = b.inch
        val placed = mutableListOf<Offset>()
        fun hasAlarm(r: Rect) = existing(r, state, inch).any { it.symbolName == Mep.SMOKE || it.symbolName == Mep.SMOKE_CO } ||
            b.added.any { (it.symbolName == Mep.SMOKE || it.symbolName == Mep.SMOKE_CO) && r.contains(it.center) }
        for (room in rooms.filter { it.type == RoomType.BEDROOM }) {
            val r = room.rect
            if (!hasAlarm(r)) {
                // Just inside the door from the hall (Dan, Nov 27: a fire elsewhere in the house reaches it sooner), on the
                // ceiling 12" in from the wall. Kept at least 36" past a ceiling fan's blade tips (NFPA 72): slid along
                // the wall when the fan is closer.
                val o = Outline(r, inch)
                val doors = doorsOf(r, state)
                val d = doors.firstOrNull { it.otherSide !in setOf(RoomType.BATH, RoomType.CLOSET) } ?: doors.firstOrNull()
                val fan = b.added.firstOrNull { it.symbolName.startsWith("Ceiling Fan") && r.contains(it.center) }
                val clear = fan?.let { it.size.width / 2f + 36f * inch } ?: 0f
                val at = if (d == null) r.center + Offset(24f * inch, 0f) else {
                    val mid = (d.from + d.to) / 2f
                    listOf(0f, 6f, -6f, 12f, -12f, 18f, -18f, 24f, -24f, 36f, -36f, 48f, -48f).map { k ->
                        val (p, inward, _) = o.at(mid + k)
                        p + inward * (12f * inch)
                    }.firstOrNull { p -> r.deflate(4f * inch).contains(p) && (fan == null || (p - fan.center).getDistance() >= clear) }
                        ?: o.at(mid).let { (p, inward, _) -> p + inward * (12f * inch) }
                }
                b.put(Mep.SMOKE, at)
            }
            // Outside the sleeping area: in the room past each bedroom door.
            for (d in doorsOf(r, state)) {
                // Only into the rest of the house (a hall, living room...): never a bath, kitchen, closet or garage (Dan, Nov 27).
                if (d.exterior || d.otherSide in noAlarm || d.otherSide == RoomType.BEDROOM) continue
                val o = Outline(r, inch)
                val (p, inward, _) = o.at((d.from + d.to) / 2f)
                val thick = 6f * inch
                var at = p - inward * (thick + 30f * inch)
                if (placed.any { (it - at).getDistance() < 180f * inch }) continue
                // Not within 3' of a bath door.
                val bathDoors = rooms.filter { it.type == RoomType.BATH }.flatMap { doorsOf(it.rect, state) }.map { Elevation.openingAnchor(it.symbol) }
                bathDoors.firstOrNull { (it - at).getDistance() < 36f * inch }?.let { bd -> val v = at - bd; val l = v.getDistance(); at = bd + (if (l < 0.01f) Offset(36f * inch, 0f) else v / l * (40f * inch)) }
                // And it has to land in such a room (a shallow closet can't send it through into the bath behind).
                val landing = roomAt(at, state)
                if (landing == null || landing.type in noAlarm || landing.type == RoomType.BEDROOM) continue
                b.put(Mep.SMOKE_CO, at)
                placed += at
            }
        }
        if (storyAlarm && b.added.none { it.symbolName == Mep.SMOKE || it.symbolName == Mep.SMOKE_CO } &&
            state.elements.filterIsInstance<DrawingElement.Symbol>().none { (it.symbolName == Mep.SMOKE || it.symbolName == Mep.SMOKE_CO) && it.id in Layers.onActiveLevel(state).map { e -> e.id } }) {
            rooms.filter { it.type !in setOf(RoomType.KITCHEN, RoomType.BATH, RoomType.GARAGE, RoomType.CLOSET, RoomType.PANTRY) }
                .maxByOrNull { it.rect.width * it.rect.height }?.let { b.put(Mep.SMOKE, it.rect.center + Offset(30f * inch, 0f)) }
        }
    }

    /** A plug beside each furnace (E3901.11: within 25' of HVAC equipment). */
    private fun equipment(state: CanvasState, b: Builder) {
        val inch = b.inch
        for (f in Layers.onActiveLevel(state).filterIsInstance<DrawingElement.Symbol>().filter { it.symbolName == Mep.FURNACE }) {
            val r = FloorPlan.areaAt(f.center, state) ?: continue
            if (existing(r, state, inch).any { Mep.isOutlet(it.symbolName) && (it.center - f.center).getDistance() < 300f * inch }) continue
            val o = Outline(r, inch)
            val s = o.along(f.center)
            b.onWall(o, s + 30f, Mep.DUPLEX)
        }
    }

    /** One line for the app's answer: what the button placed. */
    fun summary(before: CanvasState, after: CanvasState): String {
        val ids = before.elements.map { it.id }.toSet()
        val added = after.elements.filterIsInstance<DrawingElement.Symbol>().filter { it.id !in ids }
        fun count(p: (String) -> Boolean) = added.count { p(it.symbolName) }
        val plugs = count { Mep.isOutlet(it) || it == Mep.FLOOR_OUTLET }
        val sw = count { Mep.isSwitch(it) }
        val lights = count { Mep.isSwitched(it) && it != Mep.BATH_FAN && it != Mep.BATH_FAN_LIGHT }
        val fans = count { it == Mep.BATH_FAN || it == Mep.BATH_FAN_LIGHT }
        val alarms = count { it == Mep.SMOKE || it == Mep.SMOKE_CO }
        return "Electrical to code (${before.building.codeEdition.short}): $plugs plugs, $sw switches, $lights lights, $fans bath fans, $alarms smoke/CO alarms."
    }
}
