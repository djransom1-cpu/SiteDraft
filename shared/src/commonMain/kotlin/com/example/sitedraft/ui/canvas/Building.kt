package com.example.sitedraft.ui.canvas

import kotlinx.serialization.Serializable

/**
 * The building the drawing is of: its levels (footing to roof) and the layers its parts are
 * drawn on. Everything is stored once in [CanvasState.elements]; a layer is just a tag on each
 * element ([DrawingElement.layerId]), so plans, sheets and (later) sections are views of the
 * same model.
 *
 * Heights are inputs, not drawings: the level elevations are worked out from [BuildingSettings]
 * (datum, subfloor, joists, sill, block courses, footing), so changing one input moves every
 * level that depends on it.
 *
 * Old projects carry none of this. A project with no [CanvasState.layers] gets the default list,
 * and an element with no layer tag lands on a default layer by what it is (cabinets on Cabinets,
 * furniture on Furniture, everything else on the first-floor Floor plan). Nothing is moved.
 */
@Serializable
enum class FoundationType(val label: String) {
    BASEMENT("Basement"), CRAWL("Crawl space"), SLAB("Slab on grade")
}

/**
 * What the foundation walls are (Dan, Nov 22-23 2026): 12" block is his standard for block
 * basements, poured basement walls are 10". 8" block is fine above grade, but anywhere earth pushes
 * against the wall it's 12" block (8" would need a pilaster every 10', which no one does anymore).
 */
@Serializable
enum class FoundationWall(val label: String, val thicknessIn: Float, val poured: Boolean, val callout: String, val symbol: String) {
    BLOCK_12("12\" block", 11.625f, false, "12\" BLOCK", "12\" Block Wall"),
    POURED_10("10\" poured", 10f, true, "10\" POURED CONCRETE", "10\" Concrete Wall"),
    BLOCK_8("8\" block (above grade)", 7.625f, false, "8\" BLOCK", "8\" Block Wall")
}

/**
 * The residential code the job is drawn to (Nov 23 2026): Site Draft is used outside Michigan, and
 * each state adopts its own edition. [footingTable] is the edition whose footing table applies, when
 * Site Draft has it: the 2015 and 2018 IRC tables are the same; the 2021 IRC revised them (smaller
 * in most cases) and those numbers aren't loaded yet. The 2" projection rule is in every edition.
 */
@Serializable
enum class CodeEdition(val label: String, val short: String, val footingTable: Int?) {
    MRC_2015("Michigan Residential Code 2015 (IRC 2015)", "2015 MRC", 2015),
    IRC_2015("IRC 2015", "2015 IRC", 2015),
    IRC_2018("IRC 2018", "2018 IRC", 2015),
    IRC_2021("IRC 2021", "2021 IRC", null),
    IRC_2024("IRC 2024", "2024 IRC", null)
}

/** What drains the footing (Dan: Form-A-Drain, the forms stay as the drain tile; plain perforated pipe the alternative). */
@Serializable
enum class DrainTile(val label: String) { FORM_A_DRAIN("Form-A-Drain"), PERFORATED("4\" perforated pipe") }

/** An upper floor: its name and the elevation of the top of its subfloor, inches. */
@Serializable
data class UpperFloor(val name: String, val elevationIn: Float)

/**
 * The inputs the levels are worked out from. Defaults are Dan's (Nov 19 2026): first floor top of
 * subfloor at 100'-0", 3/4" subfloor on 2x10 joists on a 1 1/2" sill, basements 11 block of 8"
 * above the footing, crawl spaces 24" clear below the joists, footing 8" x 18" (trench 12" x 42").
 */
@Serializable
data class BuildingSettings(
    /** Top of the first-floor subfloor, inches (100'-0" = 1200"). Walls are built on the subfloor. */
    val datumIn: Float = 1200f,
    val foundation: FoundationType = FoundationType.BASEMENT,
    /** The code the job is drawn to; Michigan's 2015 code unless picked. */
    val codeEdition: CodeEdition = CodeEdition.MRC_2015,
    val subfloorIn: Float = 0.75f,
    /** Joist depth, inches (a 2x10 is 9 1/4"). */
    val joistDepthIn: Float = 9.25f,
    val sillIn: Float = 1.5f,
    /** Foundation wall height in block courses above the footing. */
    val blockCourses: Int = 11,
    /** One course of block with its joint, inches. */
    val courseIn: Float = 8f,
    /** Crawl spaces: clear height from the crawl floor to the bottom of the joists, inches. */
    val crawlClearanceIn: Float = 24f,
    /**
     * Basements: top of the slab above the top of the footing, inches (Dan, Nov 24: a 4" slab on
     * the footing is the usual; set per job). Basement stairs size their rise to it. New in 1.0.38.
     */
    val basementSlabIn: Float = 4f,
    val footingThicknessIn: Float = 8f,
    val footingWidthIn: Float = 18f,
    /** Old (before Nov 23 2026): a fixed 6" past the outside face. No longer read; see [footingOutsideIn]. */
    val footingProjectionIn: Float = 6f,
    /**
     * How far the footing runs past the outside face of the wall, inches; null = centred under the
     * wall (Nov 23: Dan went with the code rule, not a fixed 6"). See [Building.footingProjections].
     */
    val footingOutsideIn: Float? = null,
    /** What the foundation walls are; 12" block unless picked (Dan). */
    val foundationWall: FoundationWall = FoundationWall.BLOCK_12,
    /** Footing rebar size, the bar number (Dan: #4 residential, #5 commercial, #6 when soil or stress calls for it). */
    val rebarSize: Int = 4,
    /** Continuous bars in the top of the footing (Dan: at least two) and in the bottom (0 = none). */
    val footingBarsTop: Int = 2,
    val footingBarsBottom: Int = 0,
    /** Vertical bars from the footing up into the wall, on centre, inches (Dan: every 4' on a basement, code). */
    val dowelSpacingIn: Float = 48f,
    val drainTile: DrainTile = DrainTile.FORM_A_DRAIN,
    /** Anchor bolts (Dan): 1/2" bolts this far in from each corner or end of the sill, inches. */
    val anchorBoltEndIn: Float = 12f,
    /** ...and this far apart between (Dan: 32" o.c., never more than 4'). */
    val anchorBoltSpacingIn: Float = 32f,
    /** Rigid foam on the outside of the foundation wall, inches (Dan: 2"); 0 = none. */
    val foundationFoamIn: Float = 2f,
    val upperFloors: List<UpperFloor> = emptyList(),
    /** Top of the wall plate the roof bears on, inches; null = worked out from [plateHeightIn] once the roof is set up. */
    val roofPlateIn: Float? = null,
    /**
     * Wall height on each floor, top of subfloor to top of the double top plate, inches (Dan:
     * 8' walls, 92 5/8" precut studs and three plates = 97 1/8"). New in 1.0.37.
     */
    val plateHeightIn: Float = 97.125f,
    /** The roof (RoofPlan); null until it is set up on the Roof structural layer. New in 1.0.37. */
    val roof: RoofSettings? = null,
    /**
     * How much foundation shows above grade, inches, set per job (Dan, Nov 22): the exterior
     * elevations ([ExteriorElevation]) are made once it is set. Null = no elevations yet.
     */
    val gradeExposureIn: Float? = null,
    /** The exterior wall finish ([ExteriorFinish]); null until it is picked. New Nov 22. */
    val exterior: ExteriorFinish? = null,
    /** The lot from the survey ([SiteLot]); null until it is typed in. New Nov 22. */
    val site: SitePlan? = null,
    /** Loads, soil and post type for the beam, post and footing sizing ([StructuralDesign]). New Nov 22. */
    val structure: StructureSpecs = StructureSpecs(),
    /**
     * Build the foundation and first-floor framing from the first-floor plan ([DerivedLayers]).
     * Off for projects saved before 1.0.31, so a kitchen layout doesn't sprout footings.
     */
    val autoLayers: Boolean = false,
    /**
     * Remodels (Dan, Nov 24): show the existing house's floor framing, the simple way an
     * existing-conditions plan does (joist direction and size, existing beam and posts; no layout,
     * theirs is unknown). Off: an existing house gets no floor framing, only its foundation.
     */
    val showExistingFraming: Boolean = false
)

/** How a roof is framed: Dan picks it per job. */
@Serializable
enum class RoofFraming(val label: String) { TRUSSES("Trusses"), RAFTERS("Rafters") }

/** What the basement beam stands on (Dan: picked per job). */
@Serializable
enum class PostType(val label: String) { LALLY("Steel lally column (pipe)"), HSS("Steel tube (HSS)") }

/**
 * The structure's design inputs (Dan, Nov 22): floor loads (40 live / 20 dead psf, his usual),
 * the soil's allowable bearing (2,000 psf unless a soils report says otherwise), what the posts
 * are, and his layout: joists span no more than [maxJoistSpanFt] between beams, posts no more than
 * [postSpacingFt] apart (or the standard 8'), W8s only or the best option, and the deflection limit. Each floor above whose bearing wall stacks over the
 * beam adds its load.
 */
@Serializable
data class StructureSpecs(
    val liveLoadPsf: Float = 40f,
    val deadLoadPsf: Float = 20f,
    val soilBearingPsf: Float = 2000f,
    /** Roof snow load (or roof live load, the bigger), psf, for the code's footing table ([FootingCode]). */
    val roofSnowPsf: Float = 30f,
    val post: PostType = PostType.LALLY,
    val maxJoistSpanFt: Float = 15f,
    /** Posts no more than this far apart (Dan: set the spacing, or the standard 8'). */
    val postSpacingFt: Float = 12f,
    val standardPosts: Boolean = false,
    /** Dan's own limit on the beam's total deflection, inches (0.25": a more solid floor); 0 = code limits only. */
    val maxTotalDeflectionIn: Float = 0.25f,
    /**
     * W8s only, or the best option (the lightest W that passes, any depth). Null until picked: W8s
     * only in a basement under [w10HeightIn] (Dan: 8'-0", floor to the bottom of the joists).
     */
    val w8Only: Boolean? = null,
    /** A beam picked by hand ("W8x21"): the posts space themselves to what it carries. Null = the engine picks. */
    val beamShape: String? = null,
    val w10HeightIn: Float = 96f
)

/** One line of the lot from the survey: its bearing as written ("N 45°30'15\" E") and its length in feet. */
@Serializable
data class LotCall(val bearing: String, val distanceFt: Float, val setbackFt: Float = 0f)

/**
 * The lot for the site plan (Dan, Nov 22), typed in from the survey's calls, starting at the point
 * of beginning and going round. The site plan is its own drawing, away from the others: the lot
 * north-up from its bearings, with a copy of the roof plan for the house, put in the middle of the
 * lot, turned [rotationDeg] (clockwise) and moved [shiftEastFt] / [shiftSouthFt] until it meets the
 * setbacks; then the whole site plan turned [viewRotationDeg] to fit the sheet. Each line can carry its setback.
 */
@Serializable
data class SitePlan(
    val calls: List<LotCall> = emptyList(),
    val rotationDeg: Float = 0f,
    val shiftEastFt: Float = 0f,
    val shiftSouthFt: Float = 0f,
    val street: String = "",
    val viewRotationDeg: Float = 0f
)

/**
 * An exterior wall finish (Dan, Nov 22), drawn on the exterior elevations. [defaultIn] is the
 * spacing its pattern is drawn at: a course's exposure or height, or the batten spacing.
 */
@Serializable
enum class WallFinish(val label: String, val defaultIn: Float, val spacingLabel: String) {
    LAP("Lap siding (vinyl / fiber cement)", 7f, "Exposure"),
    BOARD_BATTEN("Board and batten", 12f, "Batten spacing"),
    SHAKE("Shake / shingle siding", 7f, "Exposure"),
    BRICK("Brick veneer", 8f / 3f, "Course height"),
    STONE("Stone veneer", 8f, "Average course height")
}

/**
 * The house's exterior finish: the main finish, a wainscot (brick or stone, say) up to a height
 * above the top of the foundation, a different finish in the gables, and the corner boards.
 * Null spacings are the finish's own.
 */
@Serializable
data class ExteriorFinish(
    val main: WallFinish = WallFinish.LAP,
    val mainIn: Float? = null,
    val wainscot: WallFinish? = null,
    val wainscotIn: Float? = null,
    val wainscotHeightIn: Float = 36f,
    /** The stone sill capping the wainscot, inches tall (Dan, Nov 22); 0 = none. */
    val wainscotSillIn: Float = 2f,
    val gable: WallFinish? = null,
    val gableIn: Float? = null,
    /** Corner board width, inches; 0 = none (brick and stone have none). */
    val cornerTrimIn: Float = 3.5f
)

/**
 * What the roof is covered with (Dan, Nov 22), drawn on the exterior elevations. [defaultIn] is the
 * spacing the pattern is drawn at: the exposure of a course, or the panel width / rib spacing.
 * [courses]: rows across the roof (shingles, shakes, slate, tile); else seams or ribs down it.
 * [barrelIn]: tile's barrels down the roof too, this far apart.
 */
@Serializable
enum class RoofCovering(val label: String, val defaultIn: Float, val courses: Boolean, val spacingLabel: String, val barrelIn: Float = 0f) {
    ARCH_SHINGLES("Architectural shingles", 5.625f, true, "Exposure"),
    STANDING_SEAM("Standing seam metal", 16f, false, "Panel width"),
    EXPOSED_FASTENER("Exposed-fastener metal", 9f, false, "Rib spacing"),
    CEDAR_SHAKES("Cedar shakes", 10f, true, "Exposure"),
    SPANISH_TILE("Spanish tile", 14f, true, "Exposure", barrelIn = 11f),
    SLATE("Slate", 7.5f, true, "Exposure")
}

/** A roof end: a gable wall, or a hip (the ridge stops half the span in). */
@Serializable
enum class RoofEnd(val label: String) { GABLE("Gable"), HIP("Hip") }

/**
 * The roof's inputs (RoofPlan draws it; ProCalc's RoofEngine and RafterEngine give the numbers).
 * Pitch is rise per 12" of run. Overhangs are level distances past the outside of the wall:
 * [overhangIn] at the eaves (and round a hip end), [rakeIn] at a gable end. The ridge runs the
 * long way of the building unless [turnRidge]. [endA] is the left (or top) end, [endB] the right (or bottom).
 */
@Serializable
data class RoofSettings(
    val framing: RoofFraming = RoofFraming.TRUSSES,
    val pitch: Float = 6f,
    val overhangIn: Float = 12f,
    /** The roofing (new Nov 22) and its pattern spacing; null spacing = the covering's own. */
    val covering: RoofCovering = RoofCovering.ARCH_SHINGLES,
    val coveringIn: Float? = null,
    val rakeIn: Float = 12f,
    val heelIn: Float = 4f,
    val ocIn: Float = 24f,
    val rafterSize: String = "2x8",
    val endA: RoofEnd = RoofEnd.GABLE,
    val endB: RoofEnd = RoofEnd.GABLE,
    val turnRidge: Boolean = false,
    /** The eave, per job (Dan, Nov 22): soffit, attic insulation and its R-value, ice and water shield up from the eave (ft). */
    val soffit: SoffitType = SoffitType.BOXED,
    val attic: AtticInsulation = AtticInsulation.BLOWN,
    val atticR: Int = 49,
    val iceWaterFt: Float = 6f,
    /** Truss hip ends (Dan, Nov 24): the hip girder sits where the truss is about this tall (4'-6'), on the layout. */
    val girderHeightIn: Float = 60f,
    /** Garage and wing trusses' spacing (Dan: residential trusses 24" o.c.), whatever the house is framed with. */
    val addOnOcIn: Float = 24f
)

/** How the eave's underside is finished (Dan: per job, boxed level by default). */
@Serializable
enum class SoffitType(val label: String) { BOXED("Boxed, level"), SLOPED("Sloped, under the tails"), OPEN("Open tails") }

/** What's in the attic at the eave (Dan: per job). */
@Serializable
enum class AtticInsulation(val label: String) { BLOWN("Blown, with baffles"), BATTS("Batts, with baffles"), SPRAY_FOAM("Spray foam at the deck (unvented)") }

/** What a level is. */
@Serializable
enum class LevelKind { FOOTING, FOUNDATION, CRAWL_FLOOR, FLOOR_FRAMING, FLOOR, ROOF }

/**
 * A level of the building. [elevationIn] is the height that names it (top of footing, top of
 * foundation wall, top of sill under the joists, top of subfloor, top of roof plate); null when
 * it isn't known yet (the roof before it is set up).
 */
data class Level(val id: String, val name: String, val kind: LevelKind, val elevationIn: Float?, val note: String = "")

/** The trade a layer holds. */
@Serializable
enum class LayerTrade(val label: String) {
    FOUNDATION("Foundation and foundation walls"),
    FLOOR_STRUCTURAL("Floor structural"),
    FLOOR_PLAN("Floor plan"),
    CABINETS("Cabinets"),
    FURNITURE("Furniture"),
    ROOF_STRUCTURAL("Roof structural"),
    /** The roof plan for the A sheets (Dan, Nov 22): the same roof as the framing, drawn finished, with the roofing. */
    ROOF_PLAN("Roof plan"),
    EXTERIOR_ELEVATION("Exterior elevation"),
    SITE_PLAN("Site plan"),
    CUSTOM("Custom")
}

/** A layer: a trade on a level (or the whole building when [levelId] is null), switched on and off. */
@Serializable
data class Layer(
    val id: String,
    val name: String,
    val trade: LayerTrade,
    val levelId: String? = null,
    val visible: Boolean = true
)

object Building {
    const val FOOTING = "footing"
    const val FOUNDATION = "foundation"
    const val CRAWL_FLOOR = "crawl-floor"
    const val ROOF = "roof"
    /** The first floor (level 1). */
    const val FLOOR1 = "floor1"

    fun floorId(n: Int) = "floor$n"
    fun framingId(n: Int) = "floor$n-framing"

    /** "First", "Second"... for floor [n] (1-based). */
    fun ordinal(n: Int): String = listOf("First", "Second", "Third", "Fourth", "Fifth").getOrElse(n - 1) { "Floor $n" }

    /** Top of the foundation wall: the datum less the floor stack (subfloor, joist, sill). */
    fun foundationTopIn(s: BuildingSettings): Float = s.datumIn - s.subfloorIn - s.joistDepthIn - s.sillIn

    /** Top of the plate the roof bears on: set by hand, or the top floor plus the wall height once there is a roof. */
    fun roofPlateIn(s: BuildingSettings): Float? =
        s.roofPlateIn ?: s.roof?.let { (s.upperFloors.lastOrNull()?.elevationIn ?: s.datumIn) + s.plateHeightIn }

    /** Top of the sill plate, where the first-floor joists bear. */
    fun sillTopIn(s: BuildingSettings): Float = s.datumIn - s.subfloorIn - s.joistDepthIn

    /** Foundation wall height from the courses. */
    fun wallHeightIn(s: BuildingSettings): Float = s.blockCourses * s.courseIn

    fun footingTopIn(s: BuildingSettings): Float = foundationTopIn(s) - wallHeightIn(s)
    fun footingBottomIn(s: BuildingSettings): Float = footingTopIn(s) - s.footingThicknessIn

    /** Top of the basement slab: the top of the footing plus [BuildingSettings.basementSlabIn]. */
    fun basementFloorIn(s: BuildingSettings): Float = footingTopIn(s) + s.basementSlabIn

    /** Top of subfloor of floor [n] (1-based), or null past the top floor. */
    fun floorElevationIn(s: BuildingSettings, n: Int): Float? = when {
        n == 1 -> s.datumIn
        n >= 2 -> s.upperFloors.getOrNull(n - 2)?.elevationIn
        else -> null
    }

    /** The foundation wall's thickness, inches. */
    fun foundationWallIn(s: BuildingSettings): Float = s.foundationWall.thicknessIn

    /** How far the footing runs past the wall (outside, inside), inches: centred unless set. */
    fun footingProjections(s: BuildingSettings): Pair<Float, Float> {
        val spare = s.footingWidthIn - foundationWallIn(s)
        val outside = s.footingOutsideIn ?: (spare / 2f)
        return outside to (spare - outside)
    }

    /**
     * The footing against the residential code (IRC R403.1.1, the same in 2015, 2018 and 2021):
     * the footing has to stick out at least 2" past each face of the wall and no farther than it
     * is thick. Empty when it passes. Width by soil and stories comes from the code's table.
     */
    fun footingCodeIssues(s: BuildingSettings): List<String> {
        if (s.foundation == FoundationType.SLAB) return emptyList()
        val (outP, inP) = footingProjections(s)
        val out = mutableListOf<String>()
        for ((side, p) in listOf("outside" to outP, "inside" to inP)) {
            if (p < 2f - 0.01f) out += "Footing sticks out only ${formatInches(p)}\" on the $side; code wants at least 2\""
            if (p > s.footingThicknessIn + 0.01f) out += "Footing sticks out ${formatInches(p)}\" on the $side, more than its ${formatInches(s.footingThicknessIn)}\" thickness"
        }
        return out
    }

    /** The levels, lowest first, worked out from the settings. */
    fun levels(s: BuildingSettings): List<Level> {
        val out = mutableListOf<Level>()
        if (s.foundation != FoundationType.SLAB) {
            out += Level(FOOTING, "Footing", LevelKind.FOOTING, footingTopIn(s),
                "Top of footing; bottom at ${elevationText(footingBottomIn(s))}")
            if (s.foundation == FoundationType.CRAWL) {
                out += Level(CRAWL_FLOOR, "Crawl space floor", LevelKind.CRAWL_FLOOR, sillTopIn(s) - s.crawlClearanceIn,
                    "${formatInches(s.crawlClearanceIn)}\" below the bottom of the joists")
            }
            out += Level(FOUNDATION, if (s.foundation == FoundationType.BASEMENT) "Foundation (basement)" else "Foundation (crawl space)",
                LevelKind.FOUNDATION, foundationTopIn(s), "Top of foundation wall")
            out += Level(framingId(1), "First floor framing", LevelKind.FLOOR_FRAMING, sillTopIn(s), "Top of sill, under the joists")
        }
        out += Level(FLOOR1, "First floor", LevelKind.FLOOR, s.datumIn,
            if (s.foundation == FoundationType.SLAB) "Top of slab" else "Top of subfloor")
        s.upperFloors.forEachIndexed { i, f ->
            val n = i + 2
            out += Level(framingId(n), "${f.name} framing", LevelKind.FLOOR_FRAMING, f.elevationIn - s.subfloorIn - s.joistDepthIn, "Top of plate, under the joists")
            out += Level(floorId(n), f.name, LevelKind.FLOOR, f.elevationIn, "Top of subfloor")
        }
        val plate = roofPlateIn(s)
        out += Level(ROOF, "Roof", LevelKind.ROOF, plate, if (plate == null) "Set when the roof is set up" else "Top of plate")
        return out
    }

    /** The default layers for the settings: Dan's list, structural, plan, cabinets and furniture per floor. */
    fun defaultLayers(s: BuildingSettings): List<Layer> {
        val out = mutableListOf<Layer>()
        if (s.foundation != FoundationType.SLAB) out += Layer(FOUNDATION, LayerTrade.FOUNDATION.label, LayerTrade.FOUNDATION, FOUNDATION)
        val floors = 1 + s.upperFloors.size
        for (n in 1..floors) out += floorLayers(n, floorName(s, n), s)
        out += Layer("roof-structural", LayerTrade.ROOF_STRUCTURAL.label, LayerTrade.ROOF_STRUCTURAL, ROOF)
        out += Layer("roof-plan", LayerTrade.ROOF_PLAN.label, LayerTrade.ROOF_PLAN, ROOF)
        out += Layer("exterior", LayerTrade.EXTERIOR_ELEVATION.label, LayerTrade.EXTERIOR_ELEVATION)
        out += Layer("site", LayerTrade.SITE_PLAN.label, LayerTrade.SITE_PLAN)
        return out
    }

    fun floorName(s: BuildingSettings, n: Int): String = if (n == 1) "First floor" else s.upperFloors.getOrNull(n - 2)?.name ?: "${ordinal(n)} floor"

    /** Floor [n]'s own layers. Its structural layer sits on its framing level (below the floor). */
    fun floorLayers(n: Int, floorName: String, s: BuildingSettings): List<Layer> {
        val f = floorId(n)
        val structural = if (n == 1 && s.foundation == FoundationType.SLAB) emptyList()
        else listOf(Layer("$f-structural", "$floorName structural", LayerTrade.FLOOR_STRUCTURAL, framingId(n)))
        return structural + listOf(
            Layer("$f-plan", "$floorName plan", LayerTrade.FLOOR_PLAN, f),
            Layer("$f-cabinets", "$floorName cabinets", LayerTrade.CABINETS, f),
            Layer("$f-furniture", "$floorName furniture", LayerTrade.FURNITURE, f)
        )
    }

    const val FLOOR1_PLAN = "floor1-plan"
    const val FLOOR1_CABINETS = "floor1-cabinets"
    const val FLOOR1_FURNITURE = "floor1-furniture"

    /** An elevation as plans write it: 100'-0", 99'-0 1/2" (always feet and inches). */
    fun elevationText(inches: Float): String {
        val (feet, rest) = splitFeetInches(kotlin.math.abs(inches) / 12f, 1f / INCH_DENOMINATOR)
        // "0 1/2", not "1/2", after the feet: 91'-0 1/2" as plans write it.
        val inchPart = formatInches(rest).let { if (rest > 0f && rest < 1f && !it.startsWith("0")) "0 $it" else it }
        return (if (inches < 0f) "-" else "") + "$feet'-$inchPart\""
    }
}

/** The layers in use: the saved list, or the defaults for a project that has none. */
val CanvasState.effectiveLayers: List<Layer>
    get() = layers.ifEmpty { Building.defaultLayers(building) }

val CanvasState.levels: List<Level> get() = Building.levels(building)

/** The active layer's id (the first-floor plan when none is set, or when the set one is gone). */
val CanvasState.activeLayer: Layer
    get() {
        val all = effectiveLayers
        return all.firstOrNull { it.id == activeLayerId } ?: all.firstOrNull { it.id == Building.FLOOR1_PLAN } ?: all.first()
    }

object Layers {
    /** The layer an untagged element (an old project's, or one the connector drew) is on. */
    fun defaultLayerId(e: DrawingElement): String = when {
        e is DrawingElement.Symbol && (e.category == PlanSymbols.CABINETS || e.category == PlanSymbols.CLOSET) -> Building.FLOOR1_CABINETS
        e is DrawingElement.Symbol && e.category == PlanSymbols.FURNITURE -> Building.FLOOR1_FURNITURE
        else -> Building.FLOOR1_PLAN
    }

    /** The layer [e] is on: its tag when that layer exists, otherwise its default. */
    fun layerOf(e: DrawingElement, state: CanvasState): Layer {
        val all = state.effectiveLayers
        val id = e.layerId ?: defaultLayerId(e)
        return all.firstOrNull { it.id == id }
            ?: all.firstOrNull { it.id == defaultLayerId(e) }
            ?: all.firstOrNull { it.id == Building.FLOOR1_PLAN }
            ?: all.first()
    }

    /**
     * The layer a newly drawn [e] goes on: the active layer, except that a cabinet or a piece of
     * furniture drawn while a floor plan is active goes on that floor's cabinets or furniture layer.
     */
    fun layerForNew(e: DrawingElement, state: CanvasState): String {
        val active = state.activeLayer
        if (active.trade == LayerTrade.FLOOR_PLAN && e is DrawingElement.Symbol) {
            val trade = when (e.category) {
                PlanSymbols.CABINETS, PlanSymbols.CLOSET -> LayerTrade.CABINETS
                PlanSymbols.FURNITURE -> LayerTrade.FURNITURE
                else -> null
            }
            if (trade != null) state.effectiveLayers.firstOrNull { it.trade == trade && it.levelId == active.levelId }?.let { return it.id }
        }
        return active.id
    }

    fun isVisible(e: DrawingElement, state: CanvasState): Boolean = layerOf(e, state).visible

    /**
     * The elements on [levelId]'s level (building-wide layers count everywhere): what a wall
     * search for something on that level should see, so the first-floor plan never finds the
     * foundation walls under it. With no level (a building-wide layer) it is everything.
     * An old project's untagged elements are all on the first floor, so for it nothing changes.
     */
    fun onLevel(levelId: String?, state: CanvasState): List<DrawingElement> {
        if (levelId == null) return state.elements
        val byId = state.effectiveLayers.associateBy { it.id }
        return state.elements.filter { e ->
            val layer = e.layerId?.let { byId[it] } ?: byId[defaultLayerId(e)]
            val lv = layer?.levelId
            lv == null || lv == levelId
        }
    }

    /** The elements on the active layer's level. */
    fun onActiveLevel(state: CanvasState): List<DrawingElement> = onLevel(state.activeLayer.levelId, state)

    /** The elements on [e]'s level. */
    fun onLevelOf(e: DrawingElement, state: CanvasState): List<DrawingElement> = onLevel(layerOf(e, state).levelId, state)

    /**
     * [layerOf] for many elements at once: the layer list and its lookup are worked out once,
     * not per element (the canvas calls this for every element it draws).
     */
    fun resolver(state: CanvasState): (DrawingElement) -> Layer {
        val all = state.effectiveLayers
        val byId = all.associateBy { it.id }
        val fallback = byId[Building.FLOOR1_PLAN] ?: all.first()
        return { e -> e.layerId?.let { byId[it] } ?: byId[defaultLayerId(e)] ?: fallback }
    }

    /**
     * The elements on visible layers (the eye in the Layers panel): what prints and goes to DXF.
     * The screen shows fewer: see [shownElements].
     */
    fun visibleElements(state: CanvasState): List<DrawingElement> {
        val all = state.effectiveLayers
        if (all.all { it.visible }) return state.elements
        val of = resolver(state)
        return state.elements.filter { of(it).visible }
    }

    /** How a layer shows on screen. */
    enum class Display { FULL, FAINT, HIDDEN }

    private val PLAN_GROUP = setOf(LayerTrade.FLOOR_PLAN, LayerTrade.CABINETS, LayerTrade.FURNITURE)

    /**
     * The layers worked on together, at full strength: a floor plan with that floor's cabinets,
     * furniture and custom layers; any other layer on its own.
     */
    fun activeGroup(state: CanvasState): Set<String> {
        val active = state.activeLayer
        if (active.trade !in PLAN_GROUP) return setOf(active.id)
        return state.effectiveLayers
            .filter { it.levelId == active.levelId && (it.trade in PLAN_GROUP || it.trade == LayerTrade.CUSTOM) }
            .mapTo(HashSet()) { it.id } + active.id
    }

    /**
     * The floor plan shown faint under a layer that isn't a plan (Dan, Nov 22: the floor plan
     * drives everything): a floor's framing gets that floor's plan, the roof the top floor's,
     * anything else the first floor's. Null while a floor plan itself is active.
     */
    fun referencePlanId(state: CanvasState): String? {
        val active = state.activeLayer
        if (active.trade in PLAN_GROUP) return null
        val plans = state.effectiveLayers.filter { it.trade == LayerTrade.FLOOR_PLAN }
        if (plans.isEmpty()) return null
        val framing = active.levelId?.takeIf { it.endsWith("-framing") }
        val wanted = when {
            active.trade == LayerTrade.ROOF_STRUCTURAL || active.trade == LayerTrade.ROOF_PLAN -> plans.last().id
            framing != null -> framing.removeSuffix("-framing") + "-plan"
            else -> Building.FLOOR1_PLAN
        }
        return (plans.firstOrNull { it.id == wanted } ?: plans.first()).id
    }

    /**
     * Working-layer mode (Dan, Nov 22): the active layer (with its group) at full strength, the
     * floor plan faint underneath it, and every other layer hidden unless turned on, when it
     * shows faint. A layer switched off with the eye never shows.
     */
    fun displayMap(state: CanvasState): Map<String, Display> {
        val group = activeGroup(state)
        val ref = referencePlanId(state)
        return state.effectiveLayers.associate { l ->
            l.id to when {
                !l.visible -> Display.HIDDEN
                l.id in group -> Display.FULL
                l.id == ref || l.id in state.shownLayerIds -> Display.FAINT
                else -> Display.HIDDEN
            }
        }
    }

    fun display(layer: Layer, state: CanvasState): Display = displayMap(state)[layer.id] ?: Display.HIDDEN

    /** Drawn faint on screen: the floor plan under the active layer, or a layer turned on alongside it. */
    fun isFaint(layer: Layer, state: CanvasState): Boolean = display(layer, state) == Display.FAINT

    /** What the screen shows, full or faint: also what can be picked and snapped to. */
    fun shownElements(state: CanvasState): List<DrawingElement> {
        val d = displayMap(state)
        val of = resolver(state)
        return state.elements.filter { d[of(it).id] != Display.HIDDEN }
    }

    /**
     * Where a layer sits in the real stack, lowest first: by level (footing up to the roof), then
     * by trade on the level (structural, plan, cabinets, furniture). The site plan is under
     * everything; exterior and whole-building layers of your own are on top.
     */
    private fun stackRank(layer: Layer, levelIndex: Map<String, Int>): Int {
        val top = levelIndex.size
        val levelId = layer.levelId
        val level = when {
            layer.trade == LayerTrade.SITE_PLAN -> -1
            levelId == null -> top
            else -> levelIndex[levelId] ?: top
        }
        val trade = when (layer.trade) {
            LayerTrade.SITE_PLAN, LayerTrade.FOUNDATION -> 0
            LayerTrade.FLOOR_STRUCTURAL -> 1
            LayerTrade.FLOOR_PLAN -> 2
            LayerTrade.CABINETS -> 3
            LayerTrade.FURNITURE -> 4
            LayerTrade.ROOF_STRUCTURAL -> 5
            LayerTrade.ROOF_PLAN -> 6
            LayerTrade.CUSTOM -> 7
            LayerTrade.EXTERIOR_ELEVATION -> 8
        }
        return level * 10 + trade
    }

    /** [elements] in drawing order: lower layers first, so the upper layer is always on top. Order within a layer is kept. */
    fun inStackOrder(elements: List<DrawingElement>, state: CanvasState): List<DrawingElement> {
        if (elements.size < 2) return elements
        val levelIndex = state.levels.withIndex().associate { it.value.id to it.index }
        val rank = state.effectiveLayers.associate { it.id to stackRank(it, levelIndex) }
        val of = resolver(state)
        return elements.sortedBy { rank[of(it).id] ?: 0 } // sortedBy is stable
    }

    /**
     * Elements that came in since [before] and have no layer yet, tagged with [layerForNew]. One
     * whose new layer is its default layer anyway stays untagged, so a project that never uses
     * layers is saved exactly as before.
     */
    fun tagNew(before: Set<String>, state: CanvasState): CanvasState {
        var changed = false
        val out = state.elements.map { e ->
            if (e.layerId != null || e.id in before) e
            else {
                val id = layerForNew(e, state)
                if (id == defaultLayerId(e)) e else { changed = true; e.withLayer(id) }
            }
        }
        return if (changed) state.copy(elements = out) else state
    }

    /** The project with its layer list written out (so it can be edited) when it was still the default. */
    fun materialized(state: CanvasState): CanvasState = if (state.layers.isEmpty()) state.copy(layers = state.effectiveLayers) else state

    /** After the settings change: the floors' layers added or kept, custom layers and visibility kept. */
    fun reconcile(state: CanvasState): CanvasState {
        if (state.layers.isEmpty()) return state // still the defaults: nothing to keep
        val wanted = Building.defaultLayers(state.building)
        val old = state.layers.associateBy { it.id }
        // Built-in layers in the default order, keeping visibility and names the user set; then the user's own.
        val builtIn = wanted.map { w -> old[w.id]?.let { o -> w.copy(visible = o.visible, name = o.name) } ?: w }
        val dropped = state.layers.filter { l -> l.trade != LayerTrade.CUSTOM && wanted.none { it.id == l.id } }
        // A built-in layer the settings no longer have (a removed floor) is kept while something is on it.
        val keep = dropped.filter { l -> state.elements.any { it.layerId == l.id } }
        return state.copy(layers = builtIn + keep + state.layers.filter { it.trade == LayerTrade.CUSTOM })
    }
}

/**
 * The drawing's state holder. Every change that brings in a new element tags it with the layer
 * it is drawn on ([Layers.tagNew]), in one place, so none of the many tools that add things has to
 * know about layers. Elements seen before (an undo putting back a deleted one) keep what they had.
 * [replace] swaps in a whole project (open, reload, new) without tagging anything.
 */
class LayerTaggingState(initial: CanvasState) : androidx.compose.runtime.MutableState<CanvasState> {
    private val inner = androidx.compose.runtime.mutableStateOf(initial)
    private var known: MutableSet<String> = initial.elements.mapTo(HashSet()) { it.id }

    override var value: CanvasState
        get() = inner.value
        set(v) {
            val old = inner.value
            val tagged = if (v.elements !== old.elements) Remodel.tagNew(known, Layers.tagNew(known, v)) else v
            if (tagged.elements !== old.elements) tagged.elements.forEach { known.add(it.id) }
            inner.value = tagged
        }

    /** A whole project in place of this one: its elements count as already seen. */
    fun replace(v: CanvasState) {
        known = v.elements.mapTo(HashSet()) { it.id }
        inner.value = v
    }

    override fun component1(): CanvasState = value
    override fun component2(): (CanvasState) -> Unit = { value = it }
}

/** Edits to the levels and layers, as pure functions of the state (the manager and the tests call these). */
object LayerEdits {
    /** Why an edit was refused, for the toast; null when it went through. */
    data class Result(val state: CanvasState, val refused: String? = null)

    fun setActive(state: CanvasState, layerId: String): Result {
        val layer = state.effectiveLayers.firstOrNull { it.id == layerId } ?: return Result(state, "No layer $layerId")
        // The layer drawing goes on is always shown.
        val shown = if (layer.visible) state else setVisibleRaw(state, layerId, true)
        return Result(shown.copy(activeLayerId = layerId))
    }

    fun setVisible(state: CanvasState, layerId: String, visible: Boolean): Result {
        if (state.effectiveLayers.none { it.id == layerId }) return Result(state, "No layer $layerId")
        if (!visible && state.activeLayer.id == layerId) return Result(state, "The active layer can't be hidden. Make another layer active first.")
        return Result(dropHiddenSelection(setVisibleRaw(state, layerId, visible)))
    }

    /**
     * The on/off for a layer as the screen shows it now (the eye in the Layers panel and the
     * quick buttons along the top of the canvas). The active layer's group and the floor plan
     * under it switch with the eye; any other layer is turned on (faint) or off alongside the
     * active one.
     */
    fun toggleShown(state: CanvasState, layerId: String): Result {
        val layer = state.effectiveLayers.firstOrNull { it.id == layerId } ?: return Result(state, "No layer $layerId")
        if (layer.id == state.activeLayer.id) return Result(state, "The active layer can't be hidden. Make another layer active first.")
        if (layer.id in Layers.activeGroup(state) || layer.id == Layers.referencePlanId(state)) {
            return setVisible(state, layerId, !layer.visible)
        }
        val showing = Layers.display(layer, state) != Layers.Display.HIDDEN
        val out = if (showing) state.copy(shownLayerIds = state.shownLayerIds - layerId)
            else setVisibleRaw(state, layerId, true).copy(shownLayerIds = state.shownLayerIds + layerId)
        return Result(dropHiddenSelection(out))
    }

    /** Nothing that isn't on screen stays selected. */
    private fun dropHiddenSelection(out: CanvasState): CanvasState {
        if (out.selectedElementId == null && out.selectedElementIds.isEmpty()) return out
        val shownIds = Layers.shownElements(out).mapTo(HashSet()) { it.id }
        return out.copy(
            selectedElementId = out.selectedElementId?.takeIf { it in shownIds },
            selectedElementIds = out.selectedElementIds.filterTo(LinkedHashSet()) { it in shownIds }
        )
    }

    private fun setVisibleRaw(state: CanvasState, layerId: String, visible: Boolean): CanvasState {
        val m = Layers.materialized(state)
        return m.copy(layers = m.layers.map { if (it.id == layerId) it.copy(visible = visible) else it })
    }

    /** A layer of the user's own, on [levelId] (null = the whole building). */
    fun add(state: CanvasState, name: String, levelId: String? = null, id: String = "custom-" + com.example.sitedraft.platform.randomId().take(8)): Result {
        val clean = name.trim()
        if (clean.isEmpty()) return Result(state, "Give the layer a name")
        if (levelId != null && state.levels.none { it.id == levelId }) return Result(state, "No level $levelId")
        val m = Layers.materialized(state)
        if (m.layers.any { it.name.equals(clean, ignoreCase = true) }) return Result(state, "There is already a layer called $clean")
        return Result(m.copy(layers = m.layers + Layer(id, clean, LayerTrade.CUSTOM, levelId)))
    }

    fun rename(state: CanvasState, layerId: String, name: String): Result {
        val clean = name.trim()
        if (clean.isEmpty()) return Result(state, "Give the layer a name")
        if (state.effectiveLayers.none { it.id == layerId }) return Result(state, "No layer $layerId")
        val m = Layers.materialized(state)
        return Result(m.copy(layers = m.layers.map { if (it.id == layerId) it.copy(name = clean) else it }))
    }

    /** Removes one of the user's own layers, only when nothing is on it (nothing drawn is ever deleted this way). */
    fun remove(state: CanvasState, layerId: String): Result {
        val layer = state.effectiveLayers.firstOrNull { it.id == layerId } ?: return Result(state, "No layer $layerId")
        if (layer.trade != LayerTrade.CUSTOM) return Result(state, "Only layers you added can be removed")
        if (state.elements.any { it.layerId == layerId }) return Result(state, "Move or delete what's on ${layer.name} first")
        val m = Layers.materialized(state)
        return Result(m.copy(
            layers = m.layers.filter { it.id != layerId },
            activeLayerId = m.activeLayerId?.takeIf { it != layerId }
        ))
    }

    /** Puts the elements [ids] on [layerId]. Nothing moves on the drawing. */
    fun moveTo(state: CanvasState, ids: Set<String>, layerId: String): Result {
        if (state.effectiveLayers.none { it.id == layerId }) return Result(state, "No layer $layerId")
        return Result(state.copy(elements = state.elements.map { if (it.id in ids) it.withLayer(layerId) else it }))
    }

    /** New building inputs: the levels follow, and the layers for any floor added or removed. */
    fun setBuilding(state: CanvasState, settings: BuildingSettings): Result {
        val bad = validate(settings)
        if (bad != null) return Result(state, bad)
        return Result(Layers.reconcile(Layers.materialized(state).copy(building = settings)))
    }

    fun validate(s: BuildingSettings): String? = when {
        s.subfloorIn < 0f || s.joistDepthIn < 0f || s.sillIn < 0f -> "Sizes can't be negative"
        s.blockCourses < 0 -> "Block courses can't be negative"
        s.courseIn <= 0f -> "A block course needs a height"
        s.footingThicknessIn < 0f || s.footingWidthIn < 0f -> "Footing sizes can't be negative"
        (s.footingOutsideIn ?: 0f) < 0f -> "The footing can't stop short of the wall"
        s.rebarSize !in 3..8 -> "Rebar size runs #3 to #8"
        s.footingBarsTop < 0 || s.footingBarsBottom < 0 -> "Bar counts can't be negative"
        s.dowelSpacingIn <= 0f -> "Dowel spacing needs a number"
        s.crawlClearanceIn < 0f -> "Crawl clearance can't be negative"
        s.upperFloors.any { it.name.isBlank() } -> "Every floor needs a name"
        s.upperFloors.zipWithNext().any { (a, b) -> b.elevationIn <= a.elevationIn } || (s.upperFloors.firstOrNull()?.let { it.elevationIn <= s.datumIn } ?: false) ->
            "Each floor has to sit higher than the one below it"
        else -> null
    }
}
