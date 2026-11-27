package com.example.sitedraft.ui.canvas

/**
 * The floors above the first, made from the floor below (Dan, Nov 27 2026: "the second floor should be made
 * automatically when a floor is added or its layer is opened"). Live until edited, like the framing ([DerivedLayers]):
 *  - the outside walls go up to every upper floor, as the same walls on that floor's plan layer. With a traced roof
 *    ([RoofAreas]) only the walls under an area whose plate is above that floor go up, so a one-story wing or bay
 *    stays one story;
 *  - a stair going up from a floor shows on the floor above at the same spot, going down (DN and its riser count);
 *    the guard round its opening is already built for that floor ([Guards]).
 * Doors and windows don't go up: each floor has its own.
 */
object UpperFloors {
    /** Marks a stair that is the floor above's view of a stair coming up (not a stair of its own). */
    const val UP_COPY = "upCopy"

    fun isUpCopy(e: DrawingElement): Boolean = e is DrawingElement.Symbol && (e.properties[UP_COPY] ?: 0f) >= 1f

    fun parts(state: CanvasState): List<DrawingElement.Symbol> {
        val s = state.building
        if (s.upperFloors.isEmpty()) return emptyList()
        val k = state.inchesToPx(1f)
        if (k <= 0f) return emptyList()
        val layers = state.effectiveLayers.map { it.id }.toSet()
        val of = Layers.resolver(state)
        val firstWalls = state.elements.filterIsInstance<DrawingElement.Symbol>().filter {
            it.category == PlanSymbols.WALLS && !DerivedLayers.isGenerated(it) && of(it).id == Building.FLOOR1_PLAN &&
                Remodel.status(it) != WallStatus.DEMO
        }
        // The outside walls: the 2x6 exterior walls, or an old house's outside walls found from the plan.
        val outside = firstWalls.filter { it.symbolName == FloorPlan.EXTERIOR_WALL }.ifEmpty { firstWalls.filter { HouseOutline.isExterior(it, state) } }
        val areas = if (RoofAreas.active(state)) RoofAreas.built(state)?.areas.orEmpty() else emptyList()
        val heel = s.roof?.heelIn ?: 0f
        val out = mutableListOf<DrawingElement.Symbol>()
        for (n in 2..(s.upperFloors.size + 1)) {
            val layerId = "${Building.floorId(n)}-plan"
            if (layerId !in layers) continue
            val floorAt = Building.floorElevationIn(s, n) ?: continue
            // With a traced roof: the walls with an area reaching this floor (its plate a foot or more above it) on one
            // side and none on the other, whatever they are downstairs (the wall between the stairs and a one-story wing
            // is an outside wall upstairs).
            val reaching = areas.filter { it.base - heel > floorAt + 12f }.map { it.poly }
            val going = if (areas.isEmpty()) outside else firstWalls.filter { w ->
                val b = FloorPlan.bounds(w)
                val c = b.center / k
                val across = if (b.width >= b.height) androidx.compose.ui.geometry.Offset(0f, 1f) else androidx.compose.ui.geometry.Offset(1f, 0f)
                val reach = (minOf(b.width, b.height) / k) / 2f + 3f
                fun covered(p: androidx.compose.ui.geometry.Offset) = reaching.any { RoofAreas.inside(it, p.x, p.y) }
                covered(c + across * reach) != covered(c - across * reach)
            }
            for (w in going) out += w.copy(id = DerivedLayers.GEN_PREFIX + "up$n:" + w.id, layerId = layerId)
        }
        // Stairs going up show on the floor above, going down.
        for (st in state.elements.filterIsInstance<DrawingElement.Symbol>().filter { it.category == PlanSymbols.STAIRS && !DerivedLayers.isGenerated(it) }) {
            val n = Stairs.floorOf(st, state) ?: continue
            if (Stairs.goesDown(st, state)) continue
            val above = n + 1
            val layerId = "${Building.floorId(above)}-plan"
            if (layerId !in layers || Building.floorElevationIn(s, above) == null) continue
            out += st.copy(
                id = DerivedLayers.GEN_PREFIX + "up$above:st:" + st.id, layerId = layerId,
                properties = st.properties + mapOf(Stairs.GOES to 2f, Stairs.RISE_SET to 0f, Stairs.GUARD_AUTO to 0f, UP_COPY to 1f)
            )
        }
        return out
    }
}
