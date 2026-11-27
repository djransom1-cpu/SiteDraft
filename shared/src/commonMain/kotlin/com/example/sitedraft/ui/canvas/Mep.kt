package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The MEP library (Dan, Nov 27): electrical devices and lights, and the HVAC pieces - registers, return grilles, the
 * furnace and condenser, duct trunks and round runs.
 *
 * Electrical boxes go on the walls at Dan's heights, to the bottom of the box: plugs 16", switches 44", plugs over a
 * counter 40" ([MOUNT_H], changeable per device). Those on a wall show on its interior elevations; ceiling lights, cans,
 * fans and bath fans are plan only. Registers go on the floor (4x10, 4x12) or the wall (6x12); return grilles are 6" tall
 * and one or two stud bays wide. Duct trunks are drawn their real width and stretched along their run; round runs too.
 *
 * Plan symbols are drawn bigger than the real box where the real one would be a speck at 1/4" = 1' (a plug is drawn
 * about 10" across), but fans, registers, grilles, ducts and the equipment are their real size.
 */
object Mep {
    const val ELECTRICAL = "Electrical"
    const val HVAC = "HVAC"
    const val PLUMBING = "Plumbing"
    val categories = setOf(ELECTRICAL, HVAC, PLUMBING)

    // ---- Electrical ----
    const val DUPLEX = "Duplex Outlet"
    const val COUNTER = "Counter Outlet"
    const val GFCI = "GFCI Outlet"
    const val WP = "Weatherproof Outlet"
    const val OUTLET_240 = "240V Outlet"
    const val FLOOR_OUTLET = "Floor Outlet"
    const val SWITCH = "Switch"
    const val SWITCH_3 = "3-Way Switch"
    const val SWITCH_4 = "4-Way Switch"
    const val DIMMER = "Dimmer Switch"
    const val CEILING_LIGHT = "Ceiling Light"
    const val CAN_4 = "Recessed Can 4\""
    const val CAN_6 = "Recessed Can 6\""
    const val PENDANT = "Pendant Light"
    const val SCONCE = "Wall Sconce"
    const val EXTERIOR_LIGHT = "Exterior Light"
    const val UNDER_CABINET = "Under-Cabinet Light"
    const val FAN_44 = "Ceiling Fan 44\""
    const val FAN_52 = "Ceiling Fan 52\""
    const val FAN_60 = "Ceiling Fan 60\""
    const val BATH_FAN = "Bath Fan"
    const val BATH_FAN_LIGHT = "Bath Fan/Light"
    const val SMOKE = "Smoke Detector"
    const val SMOKE_CO = "Smoke/CO Detector"
    const val JBOX = "Junction Box"
    const val THERMOSTAT = "Thermostat"

    // ---- HVAC ----
    const val REG_4X10 = "Floor Register 4x10"
    const val REG_4X12 = "Floor Register 4x12"
    const val WALL_REG_6X12 = "Wall Register 6x12"
    const val RETURN_6X14 = "Return Grille 6x14"
    const val RETURN_6X30 = "Return Grille 6x30"
    const val FURNACE = "Furnace"
    const val CONDENSER = "AC Condenser"
    val trunks = listOf("Duct Trunk 8x12", "Duct Trunk 8x16", "Duct Trunk 8x20", "Duct Trunk 8x24", "Duct Trunk 10x20", "Duct Trunk 10x24")
    val rounds = listOf("Round Duct 4\"", "Round Duct 5\"", "Round Duct 6\"", "Round Duct 7\"", "Round Duct 8\"", "Round Duct 10\"", "Round Duct 12\"")

    // Duct fittings (Nov 27): drawn at the run's size, turned to suit.
    const val ELBOW_90 = "Duct Elbow 90"
    const val ELBOW_45 = "Duct Elbow 45"
    const val TEE = "Duct Tee"
    const val TAKEOFF = "Duct Takeoff"
    const val BOOT = "Register Boot"
    const val REDUCER = "Duct Reducer"
    const val END_CAP = "Duct End Cap"
    val fittings = listOf(ELBOW_90, ELBOW_45, TEE, TAKEOFF, BOOT, REDUCER, END_CAP)

    // ---- Plumbing rough-in (Nov 27) ----
    val coldLines = listOf("Cold Water 1/2\"", "Cold Water 3/4\"", "Cold Water 1\"")
    val hotLines = listOf("Hot Water 1/2\"", "Hot Water 3/4\"")
    val drains = listOf("Drain 1-1/2\"", "Drain 2\"", "Drain 3\"", "Drain 4\"")
    val vents = listOf("Vent 1-1/2\"", "Vent 2\"", "Vent 3\"")
    const val GAS_LINE = "Gas Line 3/4\""
    const val CLEANOUT = "Cleanout"
    const val FLOOR_DRAIN = "Floor Drain"
    const val HOSE_BIB = "Hose Bib"
    const val SHUTOFF = "Main Shutoff"
    const val METER = "Water Meter"
    const val VTR = "Vent Thru Roof"
    const val WASHER_BOX = "Washer Box"
    const val ICE_BOX = "Ice Maker Box"
    const val SUMP = "Sump Pit"
    const val GAS_STUB = "Gas Stub"
    val plumbingPoints = listOf(CLEANOUT, FLOOR_DRAIN, HOSE_BIB, SHUTOFF, METER, VTR, WASHER_BOX, ICE_BOX, SUMP, GAS_STUB)
    val pipes = coldLines + hotLines + drains + vents + GAS_LINE
    val plumbing = pipes + plumbingPoints
    val plumbingGroups: List<PlanSymbols.SizedGroup> = listOf(
        PlanSymbols.SizedGroup("Cold water", coldLines),
        PlanSymbols.SizedGroup("Hot water", hotLines),
        PlanSymbols.SizedGroup("Drains", drains),
        PlanSymbols.SizedGroup("Vents", vents),
        PlanSymbols.SizedGroup("Gas", listOf(GAS_LINE, GAS_STUB)),
        PlanSymbols.SizedGroup("Drain points", listOf(CLEANOUT, FLOOR_DRAIN, SUMP, VTR)),
        PlanSymbols.SizedGroup("Water points", listOf(HOSE_BIB, SHUTOFF, METER, WASHER_BOX, ICE_BOX)),
    )
    fun isPipe(name: String) = name in pipes

    val electrical = listOf(DUPLEX, COUNTER, GFCI, WP, OUTLET_240, FLOOR_OUTLET, SWITCH, SWITCH_3, SWITCH_4, DIMMER,
        CEILING_LIGHT, CAN_4, CAN_6, PENDANT, SCONCE, EXTERIOR_LIGHT, UNDER_CABINET, FAN_44, FAN_52, FAN_60, BATH_FAN, BATH_FAN_LIGHT,
        SMOKE, SMOKE_CO, JBOX, THERMOSTAT)
    val hvac = listOf(REG_4X10, REG_4X12, WALL_REG_6X12, RETURN_6X14, RETURN_6X30, FURNACE, CONDENSER) + trunks + rounds + fittings

    /** The library pages: the kind, then its size (SizedPicker). */
    val electricalGroups: List<PlanSymbols.SizedGroup> = listOf(
        PlanSymbols.SizedGroup("Plugs", listOf(DUPLEX, COUNTER, GFCI, WP, OUTLET_240, FLOOR_OUTLET)),
        PlanSymbols.SizedGroup("Switches", listOf(SWITCH, SWITCH_3, SWITCH_4, DIMMER)),
        PlanSymbols.SizedGroup("Lights", listOf(CEILING_LIGHT, PENDANT, SCONCE, EXTERIOR_LIGHT, UNDER_CABINET)),
        PlanSymbols.SizedGroup("Recessed cans", listOf(CAN_4, CAN_6)),
        PlanSymbols.SizedGroup("Ceiling fans", listOf(FAN_44, FAN_52, FAN_60)),
        PlanSymbols.SizedGroup("Bath fans", listOf(BATH_FAN, BATH_FAN_LIGHT)),
        PlanSymbols.SizedGroup("Detectors", listOf(SMOKE, SMOKE_CO)),
        PlanSymbols.SizedGroup("Other", listOf(JBOX, THERMOSTAT)),
    )
    val hvacGroups: List<PlanSymbols.SizedGroup> = listOf(
        PlanSymbols.SizedGroup("Floor registers", listOf(REG_4X10, REG_4X12)),
        PlanSymbols.SizedGroup("Wall registers", listOf(WALL_REG_6X12)),
        PlanSymbols.SizedGroup("Return grilles", listOf(RETURN_6X14, RETURN_6X30)),
        PlanSymbols.SizedGroup("Duct trunks", trunks),
        PlanSymbols.SizedGroup("Round ducts", rounds),
        PlanSymbols.SizedGroup("Duct fittings", fittings),
        PlanSymbols.SizedGroup("Equipment", listOf(FURNACE, CONDENSER)),
    )

    /** Plan size (along the wall or the run, then depth), inches. */
    val footprints: Map<String, Pair<Float, Float>> = buildMap {
        for (n in listOf(DUPLEX, COUNTER, GFCI, WP, OUTLET_240)) put(n, 10f to 8f)
        put(FLOOR_OUTLET, 8f to 8f)
        for (n in listOf(SWITCH, SWITCH_3, SWITCH_4, DIMMER)) put(n, 8f to 8f)
        put(CEILING_LIGHT, 12f to 12f); put(CAN_4, 6f to 6f); put(CAN_6, 8f to 8f); put(PENDANT, 12f to 12f)
        put(SCONCE, 10f to 6f); put(EXTERIOR_LIGHT, 10f to 6f); put(UNDER_CABINET, 24f to 3f)
        put(FAN_44, 44f to 44f); put(FAN_52, 52f to 52f); put(FAN_60, 60f to 60f)
        put(BATH_FAN, 12f to 12f); put(BATH_FAN_LIGHT, 12f to 12f)
        put(SMOKE, 10f to 10f); put(SMOKE_CO, 10f to 10f); put(JBOX, 8f to 8f); put(THERMOSTAT, 8f to 6f)
        put(REG_4X10, 10f to 4f); put(REG_4X12, 12f to 4f); put(WALL_REG_6X12, 12f to 3f)
        put(RETURN_6X14, 14f to 3f); put(RETURN_6X30, 30f to 3f)
        put(FURNACE, 22f to 30f); put(CONDENSER, 30f to 30f)
        for (n in trunks) { val (a, b) = trunkSize(n)!!; put(n, 120f to b.coerceAtLeast(a)) }
        for (n in rounds) put(n, 120f to roundSize(n)!!)
        put(ELBOW_90, 12f to 12f); put(ELBOW_45, 12f to 12f); put(TEE, 24f to 12f); put(TAKEOFF, 8f to 8f)
        put(BOOT, 12f to 8f); put(REDUCER, 12f to 12f); put(END_CAP, 4f to 12f)
        for (n in pipes) put(n, 120f to 3f)
        put(CLEANOUT, 6f to 6f); put(FLOOR_DRAIN, 6f to 6f); put(HOSE_BIB, 8f to 4f); put(SHUTOFF, 8f to 6f); put(METER, 10f to 8f)
        put(VTR, 6f to 6f); put(WASHER_BOX, 10f to 4f); put(ICE_BOX, 8f to 4f); put(SUMP, 18f to 18f); put(GAS_STUB, 6f to 6f)
    }

    /** A pipe's nominal size, inches ("Drain 1-1/2\"" -> 1.5). */
    fun pipeSizeIn(name: String): Float? {
        val t = Regex("""(\d+(?:-\d+/\d+)?|\d+/\d+)"$""").find(name)?.groupValues?.get(1) ?: return null
        return if (t.contains('-')) t.substringBefore('-').toFloat() + parseFraction(t.substringAfter('-'))
            else if (t.contains('/')) parseFraction(t) else t.toFloat()
    }
    private fun parseFraction(t: String): Float = t.split('/').let { it[0].toFloat() / it[1].toFloat() }

    // ---- Duct sizing (Nov 27) ----
    // The equal-friction method on the standard round-duct friction equation for galvanized duct (ASHRAE Fundamentals):
    // friction (in. w.c. per 100 ft) = 0.109136 x CFM^1.9 / D^5.02, D in inches. A rectangular duct's equivalent round:
    // De = 1.30 (a b)^0.625 / (a + b)^0.25. Residential supply runs are commonly sized at 0.08 to 0.10 in./100 ft.
    const val DEFAULT_FRICTION = 0.10f
    const val CFM = "cfm"

    /** The round duct, inches, that carries [cfm] at [friction] in./100 ft. */
    fun roundFor(cfm: Float, friction: Float = DEFAULT_FRICTION): Float =
        (0.109136 * cfm.toDouble().pow(1.9) / friction).pow(1.0 / 5.02).toFloat()

    /** What a [d]-inch round duct carries at [friction], CFM. */
    fun cfmFor(d: Float, friction: Float = DEFAULT_FRICTION): Float =
        (friction * d.toDouble().pow(5.02) / 0.109136).pow(1.0 / 1.9).toFloat()

    /** A rectangular duct's equivalent round, inches. */
    fun equivalentRound(a: Float, b: Float): Float =
        (1.30 * (a * b).toDouble().pow(0.625) / (a + b).toDouble().pow(0.25)).toFloat()

    /** Air speed in a duct, feet per minute. */
    fun velocityFpm(cfm: Float, areaSqIn: Float) = if (areaSqIn <= 0f) 0f else cfm / (areaSqIn / 144f)

    /** The duct's own equivalent round, inches (a round duct's diameter). */
    fun ductRound(name: String): Float? = roundSize(name) ?: trunkSize(name)?.let { (h, w) -> equivalentRound(h, w) }

    /** The smallest library duct of the same kind (round or trunk) that carries [cfm] at [friction]. */
    fun sizeFor(name: String, cfm: Float, friction: Float = DEFAULT_FRICTION): String? {
        val need = roundFor(cfm, friction)
        val list = if (roundSize(name) != null) rounds else trunks
        return list.firstOrNull { (ductRound(it) ?: 0f) >= need - 0.01f }
    }

    /** "Duct Trunk 8x16" -> 8 (height) to 16 (width). */
    fun trunkSize(name: String): Pair<Float, Float>? =
        Regex("""^Duct Trunk (\d+)x(\d+)$""").find(name)?.let { it.groupValues[1].toFloat() to it.groupValues[2].toFloat() }

    fun roundSize(name: String): Float? = Regex("""^Round Duct (\d+)"$""").find(name)?.groupValues?.get(1)?.toFloat()

    fun isMep(s: DrawingElement.Symbol) = s.category in categories
    fun isOutlet(name: String) = name in setOf(DUPLEX, COUNTER, GFCI, WP, OUTLET_240) || name == "Outlet"
    fun isSwitch(name: String) = name in setOf(SWITCH, SWITCH_3, SWITCH_4, DIMMER)
    fun isDuct(name: String) = trunkSize(name) != null || roundSize(name) != null

    /** On a wall: shows on that wall's interior elevations at its height. */
    fun isWallDevice(name: String) = isOutlet(name) || isSwitch(name) ||
        name in setOf(SCONCE, EXTERIOR_LIGHT, THERMOSTAT, WALL_REG_6X12, RETURN_6X14, RETURN_6X30, HOSE_BIB, WASHER_BOX, ICE_BOX)

    /** The panel circuit an electrical device is on (0 = not set). */
    const val CIRCUIT = "circuit"

    /** Lights and fans a switch can control (switch legs). */
    fun isSwitched(name: String) = name in setOf(CEILING_LIGHT, CAN_4, CAN_6, PENDANT, SCONCE, EXTERIOR_LIGHT, UNDER_CABINET, FAN_44, FAN_52, FAN_60,
        BATH_FAN, BATH_FAN_LIGHT, "Light Fixture") || name.startsWith("Ceiling Fan")

    /** Height off the floor to the bottom of the box or grille, inches; set per device. */
    const val MOUNT_H = "mountH"

    /**
     * Dan's heights (Nov 25): plugs 16" and switches 44" to the bottom of the box, plugs over a counter 40". The rest are
     * starting values to change: sconces and exterior lights 62" (about 66" to the middle), a thermostat 52", wall
     * registers and return grilles 6" off the floor.
     */
    fun defaultMountIn(name: String): Float = when {
        name == COUNTER -> 40f
        isOutlet(name) -> 16f
        isSwitch(name) -> 44f
        name == SCONCE || name == EXTERIOR_LIGHT -> 62f
        name == THERMOSTAT -> 52f
        name == WASHER_BOX -> 42f
        name == HOSE_BIB -> 18f
        name == ICE_BOX -> 42f
        else -> 6f
    }

    fun mountIn(s: DrawingElement.Symbol) = s.properties[MOUNT_H] ?: defaultMountIn(s.symbolName)

    /** Height of the face on the elevation, inches. */
    private fun faceHeightIn(name: String): Float = when {
        isOutlet(name) || isSwitch(name) -> 4.5f
        name == THERMOSTAT -> 4f
        name == SCONCE || name == EXTERIOR_LIGHT -> 9f
        name == WALL_REG_6X12 || name.startsWith("Return Grille") -> 6f
        name == WASHER_BOX -> 9f
        name == ICE_BOX -> 7f
        name == HOSE_BIB -> 3f
        else -> 4.5f
    }

    /**
     * A return grille lined up with the framing (Dan: "a hundred percent"): on the house's 16" layout (FramingLayout), a
     * one-bay grille centred in a stud or joist bay, a two-bay one centred on the stud between its bays. Anything else,
     * or a house with no layout yet, is returned as it is.
     */
    fun alignToFraming(s: DrawingElement.Symbol, state: CanvasState): DrawingElement.Symbol {
        if (!s.symbolName.startsWith("Return Grille")) return s
        val o = FramingLayout.origin(state) ?: return s
        val inch = state.inchesToPx(1f)
        if (inch <= 0f) return s
        val sp = FramingLayout.SPACING_IN * inch
        val q = ((((s.rotation % 360f) + 360f) % 360f) / 90f).toInt() % 4
        val alongX = q % 2 == 0
        val c = if (alongX) s.center.x else s.center.y
        val start = if (alongX) o.x else o.y
        val twoBays = s.size.width / inch > FramingLayout.SPACING_IN + 1f
        val shift = if (twoBays) 0f else sp / 2f
        val k = kotlin.math.round((c - start - shift) / sp)
        val snapped = start + shift + k * sp
        return if (alongX) s.copy(center = Offset(snapped, s.center.y)) else s.copy(center = Offset(s.center.x, snapped))
    }

    // ---- Plan ----

    /** The plan symbol in its own frame: origin at the centre, back (the wall) at the top, [w] x [h] px. */
    fun planParts(s: DrawingElement.Symbol, w: Float, h: Float, inch: Float): List<Shower.Part> {
        val name = s.symbolName
        val hw = w / 2f
        val hh = h / 2f
        val out = mutableListOf<Shower.Part>()
        val text = (inch * 3f).coerceAtLeast(6f)
        fun line(a: Offset, b: Offset, ink: Shower.Ink = Shower.Ink.LINE) { out += Shower.Part.Seg(a, b, ink) }
        fun ring(c: Offset, r: Float, ink: Shower.Ink = Shower.Ink.LINE) { out += Shower.Part.Ring(c, r, ink) }
        fun box(x1: Float, y1: Float, x2: Float, y2: Float, ink: Shower.Ink = Shower.Ink.LINE) {
            out += Shower.Part.Poly(listOf(Offset(x1, y1), Offset(x2, y1), Offset(x2, y2), Offset(x1, y2)), true, ink)
        }
        fun label(t: String, c: Offset = Offset.Zero, size: Float = text) { out += Shower.Part.Label(t, c, size, Shower.Ink.LINE) }
        val r = min(hw, hh)
        when {
            // A plug: a circle with two lines through it, a lead back to the wall; its kind written beside it.
            isOutlet(name) || name == "Duplex Outlet" -> {
                val c = Offset(0f, -hh + r * 1.1f)
                ring(c, r * 0.6f)
                line(Offset(-r * 0.25f, c.y - r * 0.85f), Offset(-r * 0.25f, c.y + r * 0.85f))
                line(Offset(r * 0.25f, c.y - r * 0.85f), Offset(r * 0.25f, c.y + r * 0.85f))
                val tag = when (name) { GFCI -> "GFI"; WP -> "WP"; OUTLET_240 -> "240V"; COUNTER -> "CTR"; else -> null }
                if (tag != null) label(tag, Offset(0f, hh - text * 0.4f), text * 0.8f)
            }
            name == FLOOR_OUTLET -> { box(-hw, -hh, hw, hh); ring(Offset.Zero, r * 0.5f); line(Offset(-r * 0.2f, -r * 0.6f), Offset(-r * 0.2f, r * 0.6f)); line(Offset(r * 0.2f, -r * 0.6f), Offset(r * 0.2f, r * 0.6f)) }
            isSwitch(name) -> label(when (name) { SWITCH_3 -> "S3"; SWITCH_4 -> "S4"; DIMMER -> "SD"; else -> "S" }, Offset.Zero, r * 1.2f)
            name == CEILING_LIGHT || name == "Light Fixture" -> {
                ring(Offset.Zero, r * 0.7f)
                for (k in 0 until 4) {
                    val a = (k * 90.0 + 45.0) * kotlin.math.PI / 180.0
                    line(Offset((cos(a) * r * 0.7f).toFloat(), (sin(a) * r * 0.7f).toFloat()), Offset((cos(a) * r).toFloat(), (sin(a) * r).toFloat()))
                }
            }
            name == CAN_4 || name == CAN_6 -> { ring(Offset.Zero, r * 0.95f); ring(Offset.Zero, r * 0.55f, Shower.Ink.THIN) }
            name == PENDANT -> { ring(Offset.Zero, r * 0.7f); label("P", Offset.Zero, r * 0.8f) }
            name == SCONCE || name == EXTERIOR_LIGHT -> {
                // A half circle off the wall face; rays on an exterior light.
                val pts = (0..12).map { k -> val a = kotlin.math.PI * k / 12.0; Offset((cos(a) * hw * 0.7f).toFloat(), (-hh + sin(a) * h * 0.8f).toFloat()) }
                out += Shower.Part.Poly(pts, false, Shower.Ink.LINE)
                line(Offset(-hw * 0.7f, -hh), Offset(hw * 0.7f, -hh))
                if (name == EXTERIOR_LIGHT) label("EXT", Offset(0f, hh + text * 0.6f), text * 0.8f)
            }
            name == UNDER_CABINET -> { box(-hw, -hh, hw, hh, Shower.Ink.DASHED); label("UC", Offset.Zero, (h * 0.9f).coerceAtLeast(text * 0.6f)) }
            name.startsWith("Ceiling Fan") -> {
                ring(Offset.Zero, r, Shower.Ink.DASHED)
                ring(Offset.Zero, r * 0.12f)
                for (k in 0 until 4) {
                    val a = (k * 90.0 + 20.0) * kotlin.math.PI / 180.0
                    val a2 = (k * 90.0 + 35.0) * kotlin.math.PI / 180.0
                    val p1 = Offset((cos(a) * r * 0.15f).toFloat(), (sin(a) * r * 0.15f).toFloat())
                    val p2 = Offset((cos(a) * r * 0.95f).toFloat(), (sin(a) * r * 0.95f).toFloat())
                    val p3 = Offset((cos(a2) * r * 0.9f).toFloat(), (sin(a2) * r * 0.9f).toFloat())
                    val p4 = Offset((cos(a2) * r * 0.15f).toFloat(), (sin(a2) * r * 0.15f).toFloat())
                    out += Shower.Part.Poly(listOf(p1, p2, p3, p4), true, Shower.Ink.LINE)
                }
            }
            name == BATH_FAN || name == BATH_FAN_LIGHT -> {
                box(-hw, -hh, hw, hh)
                ring(Offset.Zero, r * 0.6f, Shower.Ink.THIN)
                label(if (name == BATH_FAN) "EF" else "EF/L", Offset.Zero, r * 0.55f)
            }
            name == SMOKE || name == SMOKE_CO -> { ring(Offset.Zero, r * 0.9f); label(if (name == SMOKE) "SD" else "S/CO", Offset.Zero, r * 0.7f) }
            name == JBOX || name == "Junction Box" -> { ring(Offset.Zero, r * 0.8f); label("J", Offset.Zero, r) }
            name == THERMOSTAT -> { ring(Offset(0f, -hh + r), r * 0.9f); label("T", Offset(0f, -hh + r), r) }
            // Registers: the face with its vanes along it, the size beside it.
            name.startsWith("Floor Register") || name == "Vent" || name == "Register/Vent" -> {
                box(-hw, -hh, hw, hh)
                for (k in 1..3) { val y = -hh + h * k / 4f; line(Offset(-hw + inch, y), Offset(hw - inch, y), Shower.Ink.THIN) }
                label(name.substringAfterLast(' '), Offset(0f, hh + text * 0.7f), text * 0.8f)
            }
            name == WALL_REG_6X12 -> { box(-hw, -hh, hw, hh); line(Offset(-hw, -hh), Offset(hw, hh), Shower.Ink.THIN); label("6x12", Offset(0f, hh + text * 0.7f), text * 0.8f) }
            name.startsWith("Return Grille") -> {
                box(-hw, -hh, hw, hh)
                line(Offset(-hw, -hh), Offset(hw, hh), Shower.Ink.THIN); line(Offset(-hw, hh), Offset(hw, -hh), Shower.Ink.THIN)
                label("RA " + name.substringAfterLast(' '), Offset(0f, hh + text * 0.7f), text * 0.8f)
            }
            name == FURNACE -> { box(-hw, -hh, hw, hh); line(Offset(-hw, -hh), Offset(hw, hh), Shower.Ink.THIN); label("FURN", Offset.Zero, text) }
            name == CONDENSER || name == "AC Unit" -> { box(-hw, -hh, hw, hh); ring(Offset.Zero, r * 0.8f, Shower.Ink.THIN); label("AC", Offset.Zero, text) }
            // Ducts: the trunk's two sides with its size; a round run its two sides, a centre line and its diameter.
            trunkSize(name) != null -> {
                box(-hw, -hh, hw, hh)
                val (dh, dw) = trunkSize(name)!!
                label("${dw.toInt()}x${dh.toInt()}", Offset.Zero, min(text, h * 0.6f))
            }
            roundSize(name) != null -> {
                line(Offset(-hw, -hh), Offset(hw, -hh)); line(Offset(-hw, hh), Offset(hw, hh))
                line(Offset(-hw, 0f), Offset(hw, 0f), Shower.Ink.FAINT_DASHED)
                label("${roundSize(name)!!.toInt()}\"Ø", Offset(0f, -hh - text * 0.7f), text * 0.8f)
            }
            // Duct fittings.
            name == ELBOW_90 -> {
                val pts = (0..8).map { k -> val a = kotlin.math.PI / 2 * k / 8; Offset((-hw + cos(a) * w).toFloat(), (hh - sin(a) * h).toFloat()) }
                val inner = (0..8).map { k -> val a = kotlin.math.PI / 2 * k / 8; Offset((-hw + cos(a) * w * 0.35f).toFloat(), (hh - sin(a) * h * 0.35f).toFloat()) }
                out += Shower.Part.Poly(pts, false, Shower.Ink.LINE); out += Shower.Part.Poly(inner, false, Shower.Ink.LINE)
            }
            name == ELBOW_45 -> { line(Offset(-hw, hh), Offset(hw, -hh)); line(Offset(-hw, hh * 0.2f), Offset(hw * 0.2f, -hh)); line(Offset(-hw, hh), Offset(-hw, hh * 0.2f)) }
            name == TEE -> { box(-hw, -hh, hw, hh * 0.2f); box(-hw * 0.3f, hh * 0.2f, hw * 0.3f, hh) }
            name == TAKEOFF -> { ring(Offset.Zero, r * 0.8f); line(Offset(-r * 0.8f, 0f), Offset(r * 0.8f, 0f), Shower.Ink.THIN) }
            name == BOOT -> { box(-hw, -hh, hw, hh); line(Offset(-hw, -hh), Offset(0f, hh), Shower.Ink.THIN); line(Offset(hw, -hh), Offset(0f, hh), Shower.Ink.THIN) }
            name == REDUCER -> { out += Shower.Part.Poly(listOf(Offset(-hw, -hh), Offset(hw, -hh * 0.5f), Offset(hw, hh * 0.5f), Offset(-hw, hh)), true, Shower.Ink.LINE) }
            name == END_CAP -> { box(-hw, -hh, hw, hh); line(Offset(-hw, -hh), Offset(hw, hh), Shower.Ink.THIN) }
            // Pipes: one line along the run, dashed for vents, its service and size written on it.
            isPipe(name) -> {
                val ink = if (name.startsWith("Vent")) Shower.Ink.DASHED else Shower.Ink.LINE
                line(Offset(-hw, 0f), Offset(hw, 0f), ink)
                if (name.startsWith("Hot")) line(Offset(-hw, -inch * 0.8f), Offset(hw, -inch * 0.8f), Shower.Ink.THIN)
                val tag = when { name.startsWith("Cold") -> "CW"; name.startsWith("Hot") -> "HW"; name.startsWith("Drain") -> "DR"; name.startsWith("Vent") -> "V"; else -> "G" }
                label("$tag ${name.substringAfterLast(' ')}", Offset(0f, -hh - text * 0.6f), text * 0.8f)
            }
            name == CLEANOUT -> { ring(Offset.Zero, r * 0.8f); label("CO", Offset(0f, hh + text * 0.6f), text * 0.8f) }
            name == FLOOR_DRAIN -> { box(-hw, -hh, hw, hh); ring(Offset.Zero, r * 0.6f, Shower.Ink.THIN); label("FD", Offset(0f, hh + text * 0.6f), text * 0.8f) }
            name == HOSE_BIB -> { line(Offset(0f, -hh), Offset(0f, hh * 0.3f)); box(-hw * 0.6f, hh * 0.3f, hw * 0.6f, hh); label("HB", Offset(0f, hh + text * 0.6f), text * 0.8f) }
            name == SHUTOFF -> {
                out += Shower.Part.Poly(listOf(Offset(-hw, -hh), Offset(hw, hh), Offset(hw, -hh), Offset(-hw, hh)), true, Shower.Ink.LINE)
                label("WSO", Offset(0f, hh + text * 0.6f), text * 0.8f)
            }
            name == METER -> { box(-hw, -hh, hw, hh); label("WM", Offset.Zero, r * 0.8f) }
            name == VTR -> { ring(Offset.Zero, r * 0.8f); line(Offset(-r, -r), Offset(r, r), Shower.Ink.THIN); label("VTR", Offset(0f, hh + text * 0.6f), text * 0.8f) }
            name == WASHER_BOX -> { box(-hw, -hh, hw, hh); label("WB", Offset.Zero, h * 0.8f) }
            name == ICE_BOX -> { box(-hw, -hh, hw, hh); label("IM", Offset.Zero, h * 0.8f) }
            name == SUMP -> { ring(Offset.Zero, r * 0.95f); ring(Offset.Zero, r * 0.75f, Shower.Ink.THIN); label("SUMP", Offset.Zero, text * 0.8f) }
            name == GAS_STUB -> { ring(Offset.Zero, r * 0.8f); label("G", Offset.Zero, r) }
            else -> { box(-hw, -hh, hw, hh); label(name, Offset.Zero, text * 0.6f) }
        }
        // Its airflow, when set on a register or duct.
        s.properties[CFM]?.takeIf { it > 0f }?.let { label("${it.toInt()} CFM", Offset(0f, hh + text * 1.6f), text * 0.75f) }
        // Its circuit number, on electrical devices.
        s.properties[CIRCUIT]?.takeIf { it > 0f }?.let { label(it.toInt().toString(), Offset(hw + text * 0.5f, -hh), text * 0.7f) }
        return out
    }

    fun DrawScope.drawMepPlan(s: DrawingElement.Symbol, w: Float, h: Float, inch: Float, lineColor: Color, accentColor: Color, faint: Color, line: Stroke, thin: Stroke,
                              state: CanvasState? = null) {
        // Switch legs: a dashed arc from the switch to each light or fan it controls (drawn in the switch's own frame).
        if (state != null && isSwitch(s.symbolName)) {
            val legDash = Stroke(width = thin.width, cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 4f, inch * 2f)))
            for (leg in state.switchLegs.filter { it.switchId == s.id }) {
                val d = state.elements.firstOrNull { it.id == leg.deviceId } as? DrawingElement.Symbol ?: continue
                val end = rotateAbout(d.center, s.center, -s.rotation) - s.center
                val mid = end / 2f
                val n = Offset(-end.y, end.x) * 0.2f // the bow, a fifth of the length off to one side
                drawPath(Path().apply { moveTo(0f, 0f); quadraticTo(mid.x + n.x, mid.y + n.y, end.x, end.y) }, color = lineColor, style = legDash)
            }
        }
        val dashed = Stroke(width = thin.width, cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(inch * 2f, inch * 1.5f)))
        fun color(ink: Shower.Ink) = when (ink) {
            Shower.Ink.FAINT, Shower.Ink.FAINT_DASHED -> faint
            Shower.Ink.ACCENT -> accentColor
            else -> lineColor
        }
        fun stroke(ink: Shower.Ink) = when (ink) {
            Shower.Ink.LINE -> line
            Shower.Ink.DASHED, Shower.Ink.FAINT_DASHED -> dashed
            else -> thin
        }
        for (p in planParts(s, w, h, inch)) when (p) {
            is Shower.Part.Seg -> drawPath(Path().apply { moveTo(p.a.x, p.a.y); lineTo(p.b.x, p.b.y) }, color = color(p.ink), style = stroke(p.ink))
            is Shower.Part.Poly -> drawPath(Path().apply {
                moveTo(p.points[0].x, p.points[0].y); p.points.drop(1).forEach { lineTo(it.x, it.y) }; if (p.closed) close()
            }, color = color(p.ink), style = stroke(p.ink))
            is Shower.Part.Ring -> drawCircle(color = color(p.ink), radius = p.r, center = p.c, style = stroke(p.ink))
            is Shower.Part.Label -> drawLabel(p.text, p.c, p.size, color(p.ink))
        }
    }

    // ---- Elevation ----

    /** A wall device on an interior elevation, its plate or grille [from]..[to] px along the view, at its height. */
    fun drawElevation(s: DrawingElement.Symbol, from: Float, to: Float, p: Appliances.ElevPen) {
        val name = s.symbolName
        val inch = p.inch
        val xc = (from + to) / 2f
        val bottom = mountIn(s)
        val fh = faceHeightIn(name)
        when {
            isOutlet(name) -> {
                // A 2 3/4" x 4 1/2" plate with its two receptacles (one wide one for 240V).
                p.rect(xc - 1.375f * inch, xc + 1.375f * inch, bottom, bottom + fh, 1.5f)
                if (name == OUTLET_240) p.circle(xc, bottom + fh / 2f, 1f, 1f)
                else { p.rect(xc - 0.7f * inch, xc + 0.7f * inch, bottom + 0.75f, bottom + 1.9f, 1f); p.rect(xc - 0.7f * inch, xc + 0.7f * inch, bottom + 2.6f, bottom + 3.75f, 1f) }
                if (name == WP) p.rect(xc - 1.75f * inch, xc + 1.75f * inch, bottom - 0.5f, bottom + fh + 0.5f, 1f) // the cover
            }
            isSwitch(name) -> {
                p.rect(xc - 1.375f * inch, xc + 1.375f * inch, bottom, bottom + fh, 1.5f)
                if (name == DIMMER) p.rect(xc - 0.3f * inch, xc + 0.3f * inch, bottom + 0.8f, bottom + fh - 0.8f, 1f)
                else p.rect(xc - 0.25f * inch, xc + 0.25f * inch, bottom + 1.7f, bottom + 2.8f, 1f)
            }
            name == THERMOSTAT -> { p.rect(xc - 2f * inch, xc + 2f * inch, bottom, bottom + fh, 1.5f); p.circle(xc, bottom + fh / 2f, 1.2f, 1f) }
            name == SCONCE || name == EXTERIOR_LIGHT -> {
                p.rect(xc - 2.25f * inch, xc + 2.25f * inch, bottom + fh * 0.35f, bottom + fh * 0.65f, 1.5f) // the backplate
                p.line(xc - 3f * inch, bottom + fh, xc + 3f * inch, bottom + fh, 1.5f)                      // shade
                p.line(xc - 3f * inch, bottom + fh, xc - 2f * inch, bottom, 1f)
                p.line(xc + 3f * inch, bottom + fh, xc + 2f * inch, bottom, 1f)
                p.line(xc - 2f * inch, bottom, xc + 2f * inch, bottom, 1f)
            }
            name == WASHER_BOX || name == ICE_BOX -> {
                p.rect(xc - (to - from) / 2f, xc + (to - from) / 2f, bottom, bottom + fh, 1.5f)
                p.circle(xc - (to - from) / 6f, bottom + fh / 2f, 0.6f, 1f)
                if (name == WASHER_BOX) p.circle(xc + (to - from) / 6f, bottom + fh / 2f, 0.6f, 1f)
            }
            name == HOSE_BIB -> { p.circle(xc, bottom + fh / 2f, 1.25f, 1.5f); p.line(xc, bottom + fh / 2f, xc, bottom - 1.5f, 1.5f) }
            name == WALL_REG_6X12 || name.startsWith("Return Grille") -> {
                p.rect(from, to, bottom, bottom + fh, 1.5f)
                val n = ((to - from) / inch / 1.5f).toInt().coerceIn(3, 30)
                for (k in 1 until n) { val x = from + (to - from) * k / n; p.line(x, bottom + 0.75f, x, bottom + fh - 0.75f, 0.75f) }
            }
        }
    }
}
