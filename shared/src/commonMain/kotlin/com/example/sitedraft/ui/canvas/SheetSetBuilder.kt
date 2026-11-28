package com.example.sitedraft.ui.canvas

/**
 * Builds the sheet set in the app (Nov 28; the same order as the connector's build_sheets): T cover and notes, C site,
 * S foundation, framing, roof framing, wall section and calcs, details in building order, A floor plans, roof plan,
 * exterior and interior elevations, and the two schedule sheets by trade. Each sheet is laid out by
 * [SheetLayoutRules] (rows, a detail grid, "(cont.)" when full). Sheets already in the set (by title) are kept.
 */
object SheetSetBuilder {
    data class Result(val sheets: List<SheetLayout>, val added: List<String>, val notMade: List<String>)

    /** The checklist's lines: the kinds of sheet in a set. */
    enum class Group(val label: String) {
        COVER("Cover, legend and symbols"), NOTES("General notes"), SITE("Site plan"), FOUNDATION("Foundation plan"),
        FRAMING("Floor framing plans"), ROOF_FRAMING("Roof framing plan"), SECTION("Wall section and structural calcs"),
        DETAILS("Details"), FLOOR_PLANS("Floor plans"), ROOF_PLAN("Roof plan"), EXTERIOR("Exterior elevations"),
        INTERIOR("Interior elevations"), SCHEDULES_A("Door, window and finish schedules"), SCHEDULES_B("Cabinet, appliance and fixture schedules")
    }

    /** Who the job is for (Dan, Nov 28), each with its starting checklist; editable per project. */
    enum class ProjectType(val label: String, val preset: Set<Group>) {
        BUILDER("Builder", Group.entries.toSet()),
        ARCHITECT("Architect", setOf(Group.COVER, Group.NOTES, Group.SITE, Group.FLOOR_PLANS, Group.ROOF_PLAN, Group.EXTERIOR,
            Group.SECTION, Group.DETAILS, Group.SCHEDULES_A)),
        DESIGNER("Interior designer", setOf(Group.COVER, Group.FLOOR_PLANS, Group.INTERIOR, Group.SCHEDULES_A, Group.SCHEDULES_B)),
        ENGINEER("Engineer", setOf(Group.COVER, Group.NOTES, Group.FOUNDATION, Group.FRAMING, Group.ROOF_FRAMING, Group.SECTION, Group.DETAILS))
    }

    fun typeOf(state: CanvasState): ProjectType? = ProjectType.entries.firstOrNull { it.name == state.projectType }

    /** The groups ticked for this project: what was ticked, else the type's preset, else everything. */
    fun groupsOf(state: CanvasState): Set<Group> =
        state.sheetGroups?.mapNotNull { n -> Group.entries.firstOrNull { it.name == n } }?.toSet() ?: typeOf(state)?.preset ?: Group.entries.toSet()

    fun build(state: CanvasState, paper: PaperSize = PaperSize.ARCH_D, scale: PrintScale = PrintScale.QUARTER): Result {
        val layers = state.effectiveLayers
        val counts = state.elements.groupingBy { Layers.layerOf(it, state).id }.eachCount()
        fun has(ls: List<Layer>) = ls.any { (counts[it.id] ?: 0) > 0 }
        val planned = mutableListOf<Pair<String, List<SheetView>>>()
        val notMade = mutableListOf<String>()
        val on = groupsOf(state)
        var group = Group.COVER
        fun sheet(title: String, views: List<SheetView>) { if (views.isNotEmpty() && group in on) planned += title to views }
        fun plan(title: String, ls: List<Layer>, sc: PrintScale = scale) = SheetView(kind = SheetViewKind.PLAN, title = title, layerIds = ls.map { it.id }, scale = sc)
        fun onLevel(levelId: String, pred: (Layer) -> Boolean = { true }) = layers.filter { it.levelId == levelId && pred(it) }

        group = Group.COVER
        sheet("Cover Sheet, Legend and Symbols", listOf(
            SheetView(kind = SheetViewKind.COVER_TITLE, title = "Project"), SheetView(kind = SheetViewKind.SHEET_INDEX, title = "Sheet Index"),
            SheetView(kind = SheetViewKind.PROJECT_DATA, title = "Project Data"), SheetView(kind = SheetViewKind.LEGEND, title = "Legend and Symbols")))
        group = Group.NOTES
        sheet("General Notes", listOf(SheetView(kind = SheetViewKind.GENERAL_NOTES, title = "General Notes")))

        group = Group.SITE
        val site = layers.filter { it.trade == LayerTrade.SITE_PLAN }
        if (has(site) || state.building.site != null) sheet("Site Plan", listOf(plan("Site Plan", site, PrintScale.ENG_20)))
        else if (group in on) notMade += "site plan (nothing on the Site plan layer yet)"

        val lv = state.levels
        group = Group.FOUNDATION
        lv.filter { it.kind == LevelKind.FOUNDATION }.forEach { l ->
            val ls = onLevel(l.id)
            if (has(ls)) sheet("Foundation Plan", listOf(plan("Foundation Plan", ls))) else if (group in on) notMade += "foundation plan (not built yet)"
        }
        group = Group.FRAMING
        lv.filter { it.kind == LevelKind.FLOOR_FRAMING }.forEach { l ->
            val ls = onLevel(l.id)
            val t = l.name.replace(Regex(" framing$", RegexOption.IGNORE_CASE), "") + " Framing Plan"
            if (has(ls)) sheet(t, listOf(plan(t, ls))) else if (group in on) notMade += "${t.lowercase()} (not built yet)"
        }
        val roof = lv.firstOrNull { it.kind == LevelKind.ROOF }
        val roofFraming = roof?.let { r -> onLevel(r.id) { it.trade != LayerTrade.ROOF_PLAN } } ?: emptyList()
        val roofPlan = roof?.let { r -> onLevel(r.id) { it.trade == LayerTrade.ROOF_PLAN } } ?: emptyList()
        group = Group.ROOF_FRAMING
        if (has(roofFraming)) sheet("Roof Framing Plan", listOf(plan("Roof Framing Plan", roofFraming)))
        else if (roof != null && group in on) notMade += "roof framing plan (no roof yet)"

        val section = state.elements.firstOrNull { WallSection.isSection(it) }
        group = Group.SECTION
        sheet("Wall Section and Structural Calculations", listOf(
            SheetView(kind = SheetViewKind.SECTION, title = "Typical Wall Section", elementId = section?.id, scale = PrintScale.HALF),
            SheetView(kind = SheetViewKind.STRUCTURAL_CALCS, title = "Structural Calculations")))

        val details = state.elements.filterIsInstance<DrawingElement.Symbol>().filter { DetailKit.isDetail(it) }
            .sortedBy { SheetLayoutRules.buildingOrder(DetailKit.kindOf(it)) * 100 + DetailKit.kindOf(it).ordinal }
        group = Group.DETAILS
        sheet("Details", details.map { d -> SheetView(kind = SheetViewKind.ELEVATION, elementId = d.id, title = DetailKit.kindOf(d).title, scale = PrintScale.HALF) })

        group = Group.FLOOR_PLANS
        lv.filter { it.kind == LevelKind.FLOOR }.forEach { l ->
            val ls = onLevel(l.id)
            if (has(ls)) sheet("${l.name} Plan", listOf(plan("${l.name} Plan", ls)))
        }
        group = Group.ROOF_PLAN
        if (has(roofPlan)) sheet("Roof Plan", listOf(plan("Roof Plan", roofPlan)))

        val ext = Sheets.exteriorChoices(state)
        group = Group.EXTERIOR
        if (ext.isNotEmpty()) sheet("Exterior Elevations", ext.map { (sym, label) -> SheetView(kind = SheetViewKind.ELEVATION, elementId = sym.id, title = label, scale = scale) })
        else if (group in on) notMade += "exterior elevations (none yet)"
        group = Group.INTERIOR
        sheet("Interior Elevations", Sheets.elevationChoices(state).map { (v, label) ->
            SheetView(kind = SheetViewKind.ELEVATION, elementId = v.id, title = label.substringBefore(" ("), scale = PrintScale.HALF)
        })

        // Lay each new sheet out, after the ones already there; then the two schedule sheets.
        val before = state.sheets
        var sheets = before
        val titles = before.map { it.title.lowercase() }.toSet()
        for ((title, views) in planned) {
            if (title.lowercase() in titles) continue
            val series = when {
                title.startsWith("Cover") || title.startsWith("General Notes") -> "T"
                title.startsWith("Site") -> "C"
                title.contains("Foundation") || title.contains("Framing") || title.contains("Section") || title == "Details" -> "S"
                else -> "A"
            }
            val blank = SheetLayout(number = Sheets.nextNumber(series, sheets), title = title, paper = paper, views = views)
            sheets = SheetLayoutRules.arrangedSet(blank, sheets + blank, state)
        }
        sheets = SheetLayoutRules.withScheduleSheets(sheets, state,
            SheetLayoutRules.scheduleSheets.map { it.first }.filterIndexed { i, _ -> (if (i == 0) Group.SCHEDULES_A else Group.SCHEDULES_B) in on })
        val added = sheets.filter { s -> before.none { it.id == s.id } }.map { it.title }
        return Result(Sheets.renumbered(sheets, state), added, notMade)
    }
}
