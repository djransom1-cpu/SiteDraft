@file:UseSerializers(OffsetSerializer::class, SizeSerializer::class, ColorSerializer::class, BitmapSerializer::class)

package com.example.sitedraft.ui.canvas

import kotlinx.serialization.UseSerializers
import kotlinx.serialization.Serializable
import com.example.sitedraft.platform.nowMillis
import com.example.sitedraft.platform.randomId
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.ImageBitmap
import com.example.sitedraft.platform.NoopPlatform
import com.example.sitedraft.platform.PageSpec
import com.example.sitedraft.platform.Platform
import com.example.sitedraft.platform.todayDateText
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.example.sitedraft.platform.ioDispatcher
import com.example.sitedraft.cloud.ProjectSync
import com.example.sitedraft.cloud.ProjectSyncListener
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.atan2

private const val AUTOSAVE_DEBOUNCE_MS = 1500L
/** Name of the project to reopen at the next launch. */
private const val LAST_PROJECT_FILE = "last_project.txt"

/**
 * The drawing's state and every operation on it. Platform-neutral: files, preferences, PDF
 * export and the laser client come through [platform]; the Android app wraps one of these in a
 * ViewModel, the desktop and web apps keep one for the window's lifetime. Call [dispose] when
 * the host goes away.
 */
class DrawingManager(private val platform: Platform = NoopPlatform) : ProjectSyncListener {
    private val _state = LayerTaggingState(CanvasState())
    val state: State<CanvasState> = _state

    /**
     * Cloud sync, attached by the root screen once the signed-in user has passed the gate and
     * detached at sign-out. Every local save is handed to it; it calls back through
     * [ProjectSyncListener] when a newer copy arrives from another device.
     */
    var sync: ProjectSync? = null

    private var startOffset: Offset = Offset.Zero
    private var lastOffset: Offset = Offset.Zero
    private var currentPoints: MutableList<DrawingPoint> = mutableListOf()
    private var isResizing = false
    private var isRotating = false
    // A continuous drag (move/resize/rotate/group-move/dimension-resize) mutates `elements` once
    // per Move *event*, not once per gesture - without this flag, a single drag would push
    // dozens of undo snapshots (one per frame), so Undo would only ever creep back a tiny
    // fraction of the drag instead of undoing the whole thing in one step. Reset on every touch
    // down; the first mutating Move event of a gesture snapshots and flips it, every later one
    // in that same gesture is a no-op here.
    private var hasSnapshotThisGesture = false
    private var moveGestureStart: Offset? = null
    private var moveGestureBefore: List<DrawingElement>? = null
    /** The drawing before the last Move drag, so "make that exactly ___" can put it back and redo it at the typed distance. */
    private var lastDragElements: List<DrawingElement>? = null

    /** Where an element sits, for measuring how far it moved: a symbol's centre, else its bounding box's. */
    private fun elementAnchor(e: DrawingElement): Offset = if (e is DrawingElement.Symbol) e.center else e.boundingRect().center

    /**
     * Moves elements by [delta] (world px) the way a typed move should (Dan, Nov 27): a square wall carries its doors and
     * windows and the square walls that meet it stretch or shorten to stay on it; anything else moves with what it owns.
     */
    private fun moveTyped(ids: Set<String>, delta: Offset, base: List<DrawingElement>): List<DrawingElement> {
        val inch = _state.value.inchesToPx(1f)
        val tol = inch * 1f
        val moving = base.filter { it.id in ids }
        val walls = moving.filterIsInstance<DrawingElement.Symbol>().filter { it.category == PlanSymbols.WALLS && it.rotation == 0f }
        val st = _state.value.copy(elements = base)
        // Doors and windows in a moving wall go with it.
        val riders = base.filterIsInstance<DrawingElement.Symbol>().filter { o ->
            o.category == PlanSymbols.DOORS_WINDOWS && o.id !in ids && OpeningPosition.hostWall(o, st)?.id?.let { it in walls.map { w -> w.id } } == true
        }.map { it.id }.toSet()
        // Walls meeting a moving wall square to it: the end that touches it follows.
        val stretch = HashMap<String, DrawingElement.Symbol>()
        for (w in walls) {
            val wb = FloorPlan.bounds(w)
            val horizontal = wb.width >= wb.height
            val along = if (horizontal) Offset(0f, delta.y) else Offset(delta.x, 0f)
            if (along.getDistance() < 0.01f) continue
            for (o in base.filterIsInstance<DrawingElement.Symbol>()) {
                if (o.id in ids || o.category != PlanSymbols.WALLS || o.rotation != 0f || Layers.layerOf(o, st).id != Layers.layerOf(w, st).id) continue
                val ob = FloorPlan.bounds(o)
                val oHorizontal = ob.width >= ob.height
                if (oHorizontal == horizontal) continue
                val cur = stretch[o.id] ?: o
                val cb = FloorPlan.bounds(cur)
                if (horizontal) {
                    if (cb.right < wb.left - tol || cb.left > wb.right + tol) continue
                    // Its top or bottom end at the moving wall.
                    val topAt = cb.top >= wb.top - tol && cb.top <= wb.bottom + tol
                    val botAt = cb.bottom >= wb.top - tol && cb.bottom <= wb.bottom + tol
                    if (!topAt && !botAt) continue
                    val newTop = if (topAt) cb.top + along.y else cb.top
                    val newBot = if (botAt && !topAt) cb.bottom + along.y else cb.bottom
                    if (newBot - newTop < inch) continue
                    stretch[o.id] = cur.copy(center = Offset(cb.center.x, (newTop + newBot) / 2f), size = Size(cb.width, newBot - newTop))
                } else {
                    if (cb.bottom < wb.top - tol || cb.top > wb.bottom + tol) continue
                    val leftAt = cb.left >= wb.left - tol && cb.left <= wb.right + tol
                    val rightAt = cb.right >= wb.left - tol && cb.right <= wb.right + tol
                    if (!leftAt && !rightAt) continue
                    val newLeft = if (leftAt) cb.left + along.x else cb.left
                    val newRight = if (rightAt && !leftAt) cb.right + along.x else cb.right
                    if (newRight - newLeft < inch) continue
                    stretch[o.id] = cur.copy(center = Offset((newLeft + newRight) / 2f, cb.center.y), size = Size(newRight - newLeft, cb.height))
                }
            }
        }
        return base.map { e ->
            when {
                e.id in ids || e.id in riders || ids.any { e.isOwnedBy(it) } -> e.move(delta)
                e.id in stretch -> stretch[e.id]!!
                else -> e
            }
        }
    }
    // Which endpoint of a selected Dimension is being resize-dragged. Dimension is the only
    // element type with two independently-grabbable handles (both ends need to be draggable so
    // a placed dimension can be pulled clear of the geometry it measures, not just extended from
    // a single fixed end) - everything else in the resize system assumes one handle.
    private var resizingDimensionStart = false
    // The grip at the dimension line's own midpoint - dragging it adjusts `offset` (how far the
    // dimension line sits from the measured points) without touching start/end.
    private var resizingDimensionOffset = false
    private var moveBasePoint: Offset? = null
    // Objects sitting on the PDF plan being dragged this gesture: they ride along with it.
    private var carriedIds: Set<String> = emptySet()
    // A press on a plan that was not selected: a drag from there is a box-select (so groups can
    // be picked over the plan), a plain tap selects the plan - decided in endDrawing().
    private var planTapCandidate: String? = null
    // Where a Dimension-tool press landed, unsnapped: a tap there (no drag) dimensions what is under it.
    private var dimensionTapRaw: Offset? = null
    // The dimension placed last with the Dimension tool: the next one that starts at its end and
    // runs the same way continues its string on the same line, with no third tap.
    private var lastPlacedDimensionId: String? = null
    // Bosch GLM laser client (Android only; null elsewhere). Lazy so unit tests never touch Bluetooth.
    private val laserDelegate = lazy { platform.createLaserClient() }
    val laser: Any? by laserDelegate

    // Tool to return to when the S Pen side button is released; null while not button-erasing.
    private var toolBeforeStylusErase: DrawingTool? = null
    // How far the dragged object currently sits from where the pointer alone would have put it,
    // i.e. the snap adjustment applied so far this gesture. See moveDrawing's plain-move delta.
    private var moveSnapCorrection: Offset = Offset.Zero

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // ------------------------------------------------------------------ Claude's commands

    /** When someone last used this copy of the app (any event that did not come from Claude). */
    var lastUserActivityAt: Long = nowMillis()
        private set
    /** Messages the app shows while Claude's commands run (a refusal, a hint), handed back to Claude. */
    private var claudeNotes: MutableList<String>? = null

    /** A message for the person at the screen; while Claude's commands run, Claude gets it too. */
    private fun notify(message: String) {
        claudeNotes?.add(message)
        platform.toast(message)
    }

    /**
     * Runs Claude's commands (ClaudeBridge) through [onEvent], exactly as taps and panel buttons
     * would. Main thread. Stops at the first event that throws.
     */
    fun runForClaude(events: List<DrawingEvent>): ClaudeCommands.RunOutcome {
        val before = _state.value
        val notes = mutableListOf<String>()
        claudeNotes = notes
        var ran = 0
        var error: String? = null
        try {
            for (e in events) {
                try {
                    onEvent(e)
                    ran++
                } catch (t: Throwable) {
                    error = "${e::class.simpleName}: ${t.message ?: t::class.simpleName}"
                    break
                }
            }
        } finally {
            claudeNotes = null
        }
        return ClaudeCommands.RunOutcome(ran, error, notes, before, _state.value)
    }

    /** Claude asked for a project that isn't the open one: open it (true when it exists here). */
    fun openForClaude(name: String): Boolean {
        if (name == _state.value.projectName) return true
        val known = platform.storage?.exists(projectFileName(name)) == true || name in _state.value.availableProjects
        if (!known) return false
        pendingSave?.let { if (it.isActive) saveNow() }
        loadProjectByName(name)
        return _state.value.projectName == name
    }

    /** After Claude's commands: the same upkeep as an autosave, then saved at once (waits for the write). */
    suspend fun saveForClaude() {
        withContext(Dispatchers.Main) {
            syncDerived()
            saveNow()
        }
        pendingSave?.join()
    }

    fun onEvent(event: DrawingEvent) {
        if (claudeNotes == null) lastUserActivityAt = nowMillis()
        when (event) {
            is DrawingEvent.Start -> startDrawing(event.offset, event.pressure, event.resizeThreshold)
            is DrawingEvent.Move -> moveDrawing(event.offset, event.pressure)
            is DrawingEvent.End -> endDrawing()
            is DrawingEvent.ToggleEraser -> toggleEraser()
            is DrawingEvent.StylusButtonErase -> stylusButtonErase(event.active)
            is DrawingEvent.SelectTool -> {
                val keepsSelection = event.tool == DrawingTool.SELECT || event.tool == DrawingTool.MOVE || event.tool == DrawingTool.MOVE_PRECISE
                _state.value = _state.value.copy(
                    currentTool = event.tool,
                    selectedElementId = if (keepsSelection) _state.value.selectedElementId else null,
                    selectedElementIds = if (keepsSelection) _state.value.selectedElementIds else emptySet(),
                    preciseMoveFromPoint = null,
                    // Picking a tool means the user is done placing a symbol or room.
                    pendingPlacement = null,
                    placementPreview = null,
                    // Abandon an in-progress dimension (mid-way through its 3-touch placement)
                    // if the user switches tools instead of finishing it - otherwise its
                    // half-placed preview would linger with nothing left able to complete it.
                    currentDrawingElement = if (_state.value.awaitingDimensionOffset) null else _state.value.currentDrawingElement,
                    awaitingDimensionOffset = false,
                    // Abandon an in-progress box-select drag the same way.
                    boxSelectStart = null,
                    boxSelectEnd = null
                )
            }
            is DrawingEvent.SelectColor -> {
                // The top-bar colour is the colour for what is drawn next, and it recolours what
                // is selected too: picking black with an elevation selected used to change only
                // the next drawing, which left no obvious way to fix a brown elevation.
                val s = _state.value
                val ids = s.selectedElementIds.ifEmpty { setOfNotNull(s.selectedElementId) }
                if (ids.isNotEmpty() && s.elements.any { it.id in ids && it.color != event.color }) {
                    pushUndoSnapshot()
                    _state.value = _state.value.copy(
                        currentColor = event.color,
                        elements = _state.value.elements.map { if (it.id in ids) it.withColor(event.color) else it }
                    )
                    triggerAutoSave()
                } else {
                    _state.value = _state.value.copy(currentColor = event.color)
                }
            }
            is DrawingEvent.SelectStrokeWidth -> {
                _state.value = _state.value.copy(currentStrokeWidth = event.width)
                triggerAutoSave()
            }
            is DrawingEvent.SelectBackground -> _state.value = _state.value.copy(background = event.background)
            is DrawingEvent.SelectBackgroundColor -> {
                updateBackgroundColor(event.color)
                triggerAutoSave()
            }
            is DrawingEvent.SelectDimensionFormat -> updateDimensionFormat(event.format)
            is DrawingEvent.UpdateZoom -> zoomBy(event.delta, event.focal)
            is DrawingEvent.UpdatePan -> _state.value = _state.value.copy(pan = _state.value.pan + event.delta)
            is DrawingEvent.SetViewportSize -> if (event.size != _state.value.viewportSize) _state.value = _state.value.copy(viewportSize = event.size)
            DrawingEvent.ZoomToFit -> zoomToFit()
            // Picking a symbol, outline or room arms placement; the next canvas tap says where it goes.
            is DrawingEvent.SelectSymbol -> _state.value = _state.value.copy(pendingPlacement = Placement.Symbol(event.name, event.category, event.widthIn), placementPreview = null)
            is DrawingEvent.StartPlacement -> _state.value = _state.value.copy(pendingPlacement = event.placement, placementPreview = null)
            is DrawingEvent.PreviewPlacementAt -> _state.value.pendingPlacement?.let { pending ->
                // Calibration points are not snapped, so neither is their crosshair.
                val at = if (pending is Placement.Calibrate || pending is Placement.Plan || pending is Placement.Hatch || pending is Placement.DimensionRoom || pending is Placement.RoomElevations || pending is Placement.RoomFinish || pending is Placement.RoomElectrical) event.point else findSnapPoint(event.point)
                _state.value = _state.value.copy(placementPreview = at)
            }
            is DrawingEvent.PlaceAt -> _state.value.pendingPlacement?.let { pending ->
                _state.value = _state.value.copy(pendingPlacement = null, placementPreview = null)
                val point = findSnapPoint(event.point)
                when (pending) {
                    is Placement.Symbol -> {
                        // A door or window tapped on a wall sits in that wall, turned to match it.
                        val opening = if (pending.category == PlanSymbols.DOORS_WINDOWS)
                            FloorPlan.alignOpening(pending.name, event.point, point, _state.value, pending.widthIn) else null
                        if (opening != null) addSymbol(pending.name, pending.category, opening.center, opening.rotation, opening.size, pending.widthIn)
                        else addSymbol(pending.name, pending.category, point, widthIn = pending.widthIn)
                    }
                    is Placement.Shell -> placeShell(point, pending)
                    is Placement.Room -> placeRoom(point, pending)
                    is Placement.Elevation -> placeElevation(point, pending)
                    is Placement.RoomElevations -> placeRoomElevations(event.point, pending.walls)
                    is Placement.RoomFinish -> addRoomFinish(event.point, pending.name)
                    is Placement.RoomElectrical -> {
                        if (ElectricalCode.roomAt(event.point, _state.value) == null) notify("No room there: tap inside a room with walls all around.")
                        else _state.value = _state.value.copy(electricalRoom = event.point, selectedElementId = null)
                    }
                    // Unsnapped: the tap only has to land inside the area.
                    is Placement.Hatch -> placeHatch(event.point, pending)
                    // One room's inside size, its strings outside its walls; tapping it again redoes them.
                    is Placement.DimensionRoom -> {
                        // The room's inside size, and the elevations of its walls and of an island in it.
                        val s = _state.value
                        val dims = AutoDimension.room(event.point, s)
                        val owner = dims.firstOrNull()?.ownerId
                        if (owner != null) {
                            val sets = linkedMapOf(owner to dims)
                            FloorPlan.areaAt(event.point, s)?.let { room ->
                                for (v in AutoDimension.elevationsOf(room, s)) sets[v.id] = AutoDimension.elevation(v, s)
                            }
                            replaceOwnedDimensionsMany(sets)
                        }
                    }
                    // Unsnapped: the point goes exactly where the pen lifted on the printed plan.
                    is Placement.Calibrate -> calibrationPointPlaced(pending, event.point)
                    // Unsnapped too: the tap is the middle of the plan.
                    is Placement.Plan -> addPdfPlan(pending.pages, event.point)
                }
            }
            is DrawingEvent.CancelPlacement -> _state.value = _state.value.copy(pendingPlacement = null, placementPreview = null)
            // Deselecting closes the Properties panel, which on a phone covers the canvas the
            // points are picked on; applying the calibration selects the plan again.
            is DrawingEvent.StartCalibration -> _state.value = _state.value.copy(
                pendingPlacement = Placement.Calibrate(event.pdfId),
                placementPreview = null,
                pendingCalibration = null,
                selectedElementId = null,
                selectedElementIds = emptySet()
            )
            is DrawingEvent.ApplyCalibration -> applyCalibration(event.realLength)
            DrawingEvent.CancelCalibration -> _state.value = _state.value.copy(pendingCalibration = null)
            DrawingEvent.UnlockAllPdfs -> unlockAllPdfs()
            DrawingEvent.GroupSelection -> groupSelection()
            DrawingEvent.UngroupSelection -> ungroupSelection()
            is DrawingEvent.RotateSelection -> rotateSelection(event.degrees)
            is DrawingEvent.SelectElements -> _state.value = _state.value.copy(
                selectedElementId = event.ids.singleOrNull(),
                selectedElementIds = if (event.ids.size > 1) event.ids else emptySet()
            )
            is DrawingEvent.UpdateElementProperty -> updateElementProperty(event.id, event.property, event.value)
            is DrawingEvent.AddWing -> addWing(event)
            is DrawingEvent.UpdateScale -> {
                updateScale(event.ratio, event.label)
                triggerAutoSave()
            }
            is DrawingEvent.SelectUnitSystem -> {
                updateUnitSystem(event.unit)
                triggerAutoSave()
            }
            is DrawingEvent.ImportImage -> importImage(event.bitmap)
            is DrawingEvent.AddLine -> addLine(event.start, event.end)
            is DrawingEvent.SetWallMode -> _state.value = _state.value.copy(wallMode = _state.value.wallMode.copy(active = event.active))
            // Remodel (Remodel.kt): which plan shows, what new walls are drawn as, a wall's status, demo part of a wall.
            is DrawingEvent.SetRemodelPhase -> { _state.value = _state.value.copy(remodelPhase = event.phase); triggerAutoSave() }
            is DrawingEvent.SetWallDrawStatus -> { _state.value = _state.value.copy(wallDrawStatus = event.status); triggerAutoSave() }
            is DrawingEvent.SetWallStatus -> setWallStatus(event.status)
            is DrawingEvent.DemoWallPart -> demoWallPart(event)
            is DrawingEvent.SetWallDirection -> _state.value = _state.value.copy(wallMode = _state.value.wallMode.copy(direction = event.direction))
            is DrawingEvent.SetWallCursor -> setWallCursor(event.offset)
            is DrawingEvent.AddWall -> addWall(event.lengthPx)
            is DrawingEvent.CloseRoom -> closeRoom()
            is DrawingEvent.NewRoom -> _state.value = _state.value.copy(wallMode = _state.value.wallMode.copy(cursor = null, roomStart = null))
            is DrawingEvent.ContinueWallsFromSelectedLine -> continueWallsFromSelectedLine()
            is DrawingEvent.ImportPdf -> importPdf(event.bitmaps)
            is DrawingEvent.ImportDxf -> importDxf(event.elements)
            DrawingEvent.Undo -> {
                undo()
                triggerAutoSave()
            }
            DrawingEvent.Redo -> {
                redo()
                triggerAutoSave()
            }
            DrawingEvent.Clear -> {
                pushUndoSnapshot()
                _state.value = _state.value.copy(elements = emptyList(), selectedElementId = null)
                triggerAutoSave()
            }
            // Back to 100% keeping what is in the middle of the view in the middle; jumping to
            // the canvas origin (as before) usually showed an empty corner of the grid.
            DrawingEvent.ResetZoom -> if (_state.value.viewportSize == Size.Zero) _state.value = _state.value.copy(zoom = 1f, pan = Offset.Zero)
                else zoomBy(1f / _state.value.zoom, null)
            is DrawingEvent.SaveProject -> saveNow()
            is DrawingEvent.LoadProject -> loadProject()
            is DrawingEvent.RefreshProjectList -> refreshProjectList()
            is DrawingEvent.ExportPdf -> exportToPdf(event.settings, event.density)
            is DrawingEvent.Print -> printPage(event.settings, event.density)
            is DrawingEvent.ExportDxf -> exportToDxf()
            is DrawingEvent.OffsetSelectedElement -> {
                offsetSelectedElement(event.distance, event.direction)
                triggerAutoSave()
            }
            is DrawingEvent.ToggleStylusOnlyDrawing -> _state.value = _state.value.copy(stylusOnlyDrawing = event.enabled)
            DrawingEvent.DuplicateSelectedElement -> {
                duplicateSelectedElement()
                triggerAutoSave()
            }
            is DrawingEvent.RenameProject -> {
                _state.value = _state.value.copy(projectName = event.name)
                triggerAutoSave()
            }
            DrawingEvent.NewProject -> {
                newProject()
                triggerAutoSave()
            }
            is DrawingEvent.ToggleSnap -> _state.value = _state.value.copy(snapEnabled = event.enabled)
            is DrawingEvent.ToggleGridSnap -> _state.value = _state.value.copy(snapToGrid = event.enabled)
            is DrawingEvent.PositionOpening -> {
                // A door or window's position along its wall: the way it is measured, and (when
                // a distance is typed) slid along the wall to sit that far from the chosen end.
                val s = _state.value
                val o = s.elements.find { it.id == event.id } as? DrawingElement.Symbol
                if (o != null && o.category == PlanSymbols.DOORS_WINDOWS) {
                    var updated = o.copy(properties = o.properties +
                        (OpeningPosition.PROP_FROM_END to if (event.fromEnd) 1f else 0f) +
                        (OpeningPosition.PROP_TO_CENTER to if (event.toCenter) 1f else 0f) +
                        (OpeningPosition.PROP_OUTSIDE to if (event.outside) 1f else 0f))
                    val typed = event.distance?.let { t ->
                        if (s.unitSystem == UnitSystem.METRIC) t.trim().removeSuffix("m").trim().toFloatOrNull() else parseFeet(t)
                    }
                    if (typed != null && typed >= 0f) {
                        OpeningPosition.placed(updated, s, typed * s.scale.ratio)?.let { updated = it }
                    }
                    pushUndoSnapshot()
                    _state.value = _state.value.copy(elements = _state.value.elements.map { if (it.id == o.id) updated else it })
                    triggerAutoSave()
                }
            }
            is DrawingEvent.UpdateSheets -> {
                _state.value = _state.value.copy(sheets = event.sheets)
                triggerAutoSave()
            }
            // The sheet set checklist (Nov 28): who the job is for, and which sheets the set gets (null = that type's preset).
            is DrawingEvent.SetSheetSetChoice -> {
                _state.value = _state.value.copy(projectType = event.projectType, sheetGroups = event.groups)
                triggerAutoSave()
            }
            // The typical wall section in model space: placed once, to the right of the drawing,
            // and selected so its inputs show in Properties.
            is DrawingEvent.PlaceWallSection -> {
                val existed = WallSection.sectionIn(_state.value) != null
                val section = ensureWallSection()
                _state.value = _state.value.copy(selectedElementId = section.id, selectedElementIds = emptySet())
                triggerAutoSave()
                notify(if (existed) "The typical wall section is already in the drawing: it's selected" else "Typical wall section placed to the right of the drawing")
            }
            // A section view on a sheet, from the model-space section (placed first if there isn't one).
            is DrawingEvent.AddWallSectionToSheet -> {
                val section = ensureWallSection()
                val s = _state.value
                val sheet = s.sheets.firstOrNull { it.id == event.sheetId }
                if (sheet != null && sheet.views.none { it.kind == SheetViewKind.SECTION }) {
                    val view = SheetView(kind = SheetViewKind.SECTION, elementId = section.id, title = "Typical Wall Section", scale = PrintScale.THREE_QUARTER)
                    val placed = Sheets.placed(sheet, view, s)
                    _state.value = s.copy(sheets = s.sheets.map { if (it.id == sheet.id) it.copy(views = it.views + placed) else it })
                }
                triggerAutoSave()
            }
            is DrawingEvent.AddDetailToSheet -> {
                val kind = DetailKit.Detail.entries.getOrNull(event.kind) ?: DetailKit.Detail.OVERHANG
                var s = _state.value
                val detail = DetailKit.detailIn(s, kind) ?: DetailKit.newSymbol(s, kind, DetailKit.spotFor(s, kind)).also {
                    pushUndoSnapshot()
                    s = s.copy(elements = s.elements + it)
                }
                val sheet = s.sheets.firstOrNull { it.id == event.sheetId }
                if (sheet != null && sheet.views.none { it.elementId == detail.id }) {
                    val scale = PrintScale.entries.firstOrNull { it.denominator == kind.denominator } ?: PrintScale.ONE
                    val view = SheetView(kind = SheetViewKind.ELEVATION, elementId = detail.id, title = kind.title, scale = scale)
                    val placed = Sheets.placed(sheet, view, s)
                    s = s.copy(sheets = s.sheets.map { if (it.id == sheet.id) it.copy(views = it.views + placed) else it })
                }
                _state.value = s
                triggerAutoSave()
            }
            is DrawingEvent.ExportSheetSet -> exportSheetSet(event.density, event.separate)
            // A notes layer only this viewport shows: made, added to the viewport, and made the
            // layer drawing goes on, with the viewport's own layers turned on faint to trace over.
            is DrawingEvent.AddSheetNotesLayer -> {
                val s = _state.value
                val sheet = s.sheets.firstOrNull { it.id == event.sheetId }
                val view = sheet?.views?.firstOrNull { it.id == event.viewId && it.kind == SheetViewKind.PLAN }
                if (sheet != null && view != null) {
                    val base = view.layerIds ?: s.effectiveLayers.filter { !Sheets.isSheetNotes(it.id) }.map { it.id }
                    val level = view.layerIds?.firstNotNullOfOrNull { id -> s.effectiveLayers.firstOrNull { it.id == id }?.levelId }
                    val name = "${sheet.number} ${view.title} notes".trim()
                    val r = LayerEdits.add(s, name, level, id = Sheets.SHEET_NOTES_PREFIX + randomId().take(8))
                    if (r.refused != null) {
                        notify(r.refused)
                    } else {
                        val newId = r.state.layers.last().id
                        val active = LayerEdits.setActive(r.state, newId).state
                        _state.value = active.copy(
                            shownLayerIds = active.shownLayerIds + base,
                            sheets = active.sheets.map { sh ->
                                if (sh.id != sheet.id) sh else sh.copy(views = sh.views.map { if (it.id == view.id) it.copy(layerIds = base + newId) else it })
                            }
                        )
                        triggerAutoSave()
                        notify("$name made and active: text and dimensions you add now print only on ${sheet.number}")
                    }
                }
            }
            is DrawingEvent.UpdateProjectInfo -> {
                _state.value = _state.value.copy(projectInfo = event.info)
                triggerAutoSave()
            }
            is DrawingEvent.SetActiveLayer -> applyLayerEdit(LayerEdits.setActive(_state.value, event.layerId))
            is DrawingEvent.SetLayerVisible -> applyLayerEdit(LayerEdits.setVisible(_state.value, event.layerId, event.visible))
            is DrawingEvent.ToggleLayerShown -> applyLayerEdit(LayerEdits.toggleShown(_state.value, event.layerId))
            is DrawingEvent.AddLayer -> LayerEdits.add(_state.value, event.name, event.levelId).let { r ->
                // The new layer becomes the one drawing goes on.
                val added = r.state.layers.lastOrNull()
                applyLayerEdit(if (r.refused == null && added != null) LayerEdits.setActive(r.state, added.id) else r)
            }
            is DrawingEvent.RenameLayer -> applyLayerEdit(LayerEdits.rename(_state.value, event.layerId, event.name))
            is DrawingEvent.RemoveLayer -> applyLayerEdit(LayerEdits.remove(_state.value, event.layerId))
            is DrawingEvent.SetBuilding -> applyLayerEdit(LayerEdits.setBuilding(_state.value, event.settings))
            is DrawingEvent.MoveSelectedBy -> {
                // Typed move (Dan, Nov 27): east and south positive, inches.
                val s = _state.value
                val ids = s.selectedElementIds.ifEmpty { setOfNotNull(s.selectedElementId) }
                if (ids.isNotEmpty() && (event.eastIn != 0f || event.southIn != 0f)) {
                    pushUndoSnapshot()
                    val k = s.inchesToPx(1f)
                    _state.value = _state.value.copy(elements = moveTyped(ids, Offset(event.eastIn * k, event.southIn * k), _state.value.elements), lastMoveDir = null)
                    triggerAutoSave()
                }
            }
            is DrawingEvent.MakeLastMoveExactly -> {
                // After a Move drag: put it back and move it the typed distance the same way (Nov 27).
                val s = _state.value
                val dir = s.lastMoveDir
                val before = lastDragElements
                val sel = s.selectedElementId
                if (dir != null && before != null && sel != null && event.inches > 0f) {
                    pushUndoSnapshot()
                    val k = s.inchesToPx(1f)
                    _state.value = _state.value.copy(elements = moveTyped(setOf(sel), dir * (event.inches * k), before), lastMoveDir = null)
                    lastDragElements = null
                    triggerAutoSave()
                }
            }
            is DrawingEvent.DismissLastMove -> _state.value = _state.value.copy(lastMoveDir = null)
            is DrawingEvent.SetRoofArea -> {
                val b = _state.value.building
                val r = b.roof ?: RoofSettings()
                val areas = if (r.areas.any { it.id == event.area.id }) r.areas.map { if (it.id == event.area.id) event.area else it } else r.areas + event.area
                applyLayerEdit(LayerEdits.setBuilding(_state.value, b.copy(roof = r.copy(areas = areas))))
            }
            is DrawingEvent.DeleteRoofArea -> {
                val b = _state.value.building
                val r = b.roof
                if (r != null) applyLayerEdit(LayerEdits.setBuilding(_state.value, b.copy(roof = r.copy(areas = r.areas.filter { it.id != event.id && it.name != event.id }))))
            }
            // The layer builder (DerivedLayers): on, off (taking away the parts it made that
            // nobody touched), or start over.
            is DrawingEvent.SetAutoLayers -> {
                pushUndoSnapshot()
                val s = _state.value.copy(building = _state.value.building.copy(autoLayers = event.on))
                _state.value = if (event.on) s else DerivedLayers.clearUntouched(s)
                triggerAutoSave()
            }
            // Match floor plan: draw the active layer's parts once, from the first-floor plan.
            is DrawingEvent.MatchFloorPlan -> {
                val s = _state.value
                val layer = s.activeLayer
                if (s.building.autoLayers) {
                    notify("Build from the floor plan is on: ${layer.name} already follows the plan")
                } else {
                    val parts = DerivedLayers.matchLayer(s, layer.id)
                    if (parts.isEmpty()) {
                        notify("Nothing to match on ${layer.name}: draw the exterior (2x6) walls on the first-floor plan first")
                    } else {
                        pushUndoSnapshot()
                        // Under the plan, and selected so they can be moved or checked together.
                        _state.value = s.copy(elements = parts + s.elements, selectedElementIds = parts.map { it.id }.toSet(), selectedElementId = null)
                        triggerAutoSave()
                        notify("${parts.size} parts drawn on ${layer.name} to match the floor plan")
                    }
                }
            }
            is DrawingEvent.RebuildLayers -> {
                pushUndoSnapshot()
                _state.value = DerivedLayers.rebuild(_state.value)
                triggerAutoSave()
            }
            is DrawingEvent.MoveSelectionToLayer -> {
                val ids = _state.value.selectedElementIds.ifEmpty { setOfNotNull(_state.value.selectedElementId) }
                if (ids.isNotEmpty()) {
                    if (LayerEdits.moveTo(_state.value, ids, event.layerId).refused == null) pushUndoSnapshot()
                    // Worked out after the snapshot, so the undo history it carries includes it.
                    applyLayerEdit(LayerEdits.moveTo(_state.value, ids, event.layerId))
                }
            }
            is DrawingEvent.ShowOpeningMarks -> {
                _state.value = _state.value.copy(showOpeningMarks = event.show)
                triggerAutoSave()
            }
            is DrawingEvent.ShowCabinetLabels -> {
                _state.value = _state.value.copy(showCabinetLabels = event.show)
                triggerAutoSave()
            }
            DrawingEvent.DeleteSelectedElement -> {
                deleteSelectedElement()
                triggerAutoSave()
            }
            DrawingEvent.DeselectElement -> {
                // Dedicated deselect - re-dispatching SelectTool with the tool that's already
                // active (what the Properties panel's and floating toolbar's "Close" used to do)
                // is a no-op by design: SelectTool intentionally KEEPS the selection when staying
                // on Select/Move/Pt-to-Pt, so switching tools shouldn't drop what you had
                // selected. That's correct for tool switches, but meant "Close" never actually
                // closed anything since it wasn't switching tools at all.
                _state.value = _state.value.copy(
                    selectedElementId = null,
                    selectedElementIds = emptySet(),
                    boxSelectStart = null,
                    boxSelectEnd = null
                )
                isResizing = false
                isRotating = false
            }
            DrawingEvent.ExplodeSelectedElement -> {
                explodeSelectedElement()
                triggerAutoSave()
            }
            // Door handing. Swapping the hinge mirrors the door in place; swinging it to the
            // other side turns it half round about its threshold (so it stays in the wall) and
            // mirrors it, so the hinge stays on the same jamb.
            is DrawingEvent.ElectricalToCode -> {
                // Electrical to code in one press (Nov 27): the whole level, or one room (its card's Redo).
                val before = _state.value
                val at = if (event.x != null && event.y != null) Offset(event.x, event.y) else null
                ElectricalCode.refusal(before)?.let { notify(it); return }
                if (ElectricalCode.rooms(before).isEmpty()) { notify("No rooms found: electrical goes in rooms with walls all around."); return }
                pushUndoSnapshot()
                val after = ElectricalCode.apply(before, at)
                // Show the electrical layer so what was placed can be seen.
                val elec = ElectricalCode.layerId(after)
                _state.value = LayerEdits.setVisible(after, elec, true).state
                notify(ElectricalCode.summary(before, after))
                triggerAutoSave()
            }
            is DrawingEvent.SetRoomElectrical -> {
                // The room card's choices, then that room laid out again with them.
                val s0 = _state.value
                val at = Offset(event.x, event.y)
                val room = ElectricalCode.roomAt(at, s0) ?: run { notify("No room there."); return }
                val old = ElectricalCode.optsFor(room.rect, s0)
                val opts = event.opts.copy(id = old?.id ?: event.opts.id, x = room.rect.center.x, y = room.rect.center.y)
                pushUndoSnapshot()
                val withOpts = s0.copy(roomElectrical = s0.roomElectrical.filter { it.id != old?.id } + opts)
                _state.value = ElectricalCode.apply(withOpts, room.rect.center)
                triggerAutoSave()
            }
            DrawingEvent.CloseRoomElectrical -> _state.value = _state.value.copy(electricalRoom = null)
            is DrawingEvent.NameRoom -> {
                val s = _state.value
                val p = Offset(event.x, event.y)
                val room = RoomNames.unnamed(s).firstOrNull { it.contains(p) } ?: FloorPlan.areaAt(p, s)
                if (room == null || event.name.isBlank()) notify("No room there, or no name typed.")
                else {
                    pushUndoSnapshot()
                    _state.value = s.copy(elements = RoomNames.named(room, event.name, s), namingRoom = null)
                    triggerAutoSave()
                }
            }
            DrawingEvent.CloseNameRoom -> _state.value = _state.value.copy(namingRoom = null)
            is DrawingEvent.ToggleSwitchLeg -> {
                // A switch leg on or off (switch card, Nov 27).
                val st = _state.value
                val leg = SwitchLeg(event.switchId, event.deviceId)
                pushUndoSnapshot()
                _state.value = st.copy(switchLegs = if (leg in st.switchLegs) st.switchLegs - leg else st.switchLegs + leg)
                triggerAutoSave()
            }
            is DrawingEvent.ReplaceSymbol -> {
                // Another library symbol of the same kind in the same spot and turn (MEP card, Nov 27).
                val o = _state.value.elements.find { it.id == event.id } as? DrawingElement.Symbol
                val inch = _state.value.inchesToPx(1f)
                if (o != null && inch > 0f && event.widthIn > 0f && event.depthIn > 0f) {
                    pushUndoSnapshot()
                    _state.value = _state.value.copy(elements = _state.value.elements.map {
                        if (it.id == o.id) o.copy(symbolName = event.name, size = Size(event.widthIn * inch, event.depthIn * inch),
                            properties = o.properties + mapOf("widthInches" to event.widthIn, "depthInches" to event.depthIn)) else it
                    })
                    triggerAutoSave()
                }
            }
            is DrawingEvent.SetWallKind -> {
                // Another wall type (2x4, 2x6...) in place, on the same centre line (wall card, Nov 27).
                val w = _state.value.elements.find { it.id == event.id } as? DrawingElement.Symbol
                val inch = _state.value.inchesToPx(1f)
                val t = PlanSymbols.footprintInches[event.name]?.second
                if (w != null && w.category == PlanSymbols.WALLS && t != null && inch > 0f) {
                    val horizontal = w.size.width >= w.size.height
                    val size = if (horizontal) Size(w.size.width, t * inch) else Size(t * inch, w.size.height)
                    pushUndoSnapshot()
                    _state.value = _state.value.copy(elements = _state.value.elements.map {
                        if (it.id == w.id) w.copy(symbolName = event.name, size = size, properties = w.properties + ("depthInches" to t)) else it
                    })
                    triggerAutoSave()
                }
            }
            is DrawingEvent.SetWallLength -> {
                // A wall's length, its west (or north) end staying put (wall card, Nov 27).
                val w = _state.value.elements.find { it.id == event.id } as? DrawingElement.Symbol
                val inch = _state.value.inchesToPx(1f)
                if (w != null && w.category == PlanSymbols.WALLS && event.lengthIn > 1f && inch > 0f) {
                    val b = FloorPlan.bounds(w)
                    val len = event.lengthIn * inch
                    val horizontal = w.size.width >= w.size.height
                    val updated = if (horizontal) w.copy(center = Offset(b.left + len / 2f, w.center.y), size = Size(len, w.size.height))
                        else w.copy(center = Offset(w.center.x, b.top + len / 2f), size = Size(w.size.width, len))
                    pushUndoSnapshot()
                    _state.value = _state.value.copy(elements = _state.value.elements.map { if (it.id == w.id) updated.copy(properties = updated.properties + ("widthInches" to event.lengthIn)) else it })
                    triggerAutoSave()
                }
            }
            is DrawingEvent.SetCabinetKind -> {
                // Another cabinet or width in place (cabinet card, Nov 27): the back stays against the wall.
                val c = _state.value.elements.find { it.id == event.id } as? DrawingElement.Symbol
                val inch = _state.value.inchesToPx(1f)
                val fp = PlanSymbols.footprintInches[event.name]
                if (c != null && c.category == PlanSymbols.CABINETS && fp != null && inch > 0f) {
                    val wIn = event.widthIn ?: fp.first
                    val size = Size(wIn * inch, fp.second * inch)
                    // The back is the symbol's top edge (local -y): keep it where it is.
                    val center = c.center + rotateAbout(Offset(0f, (size.height - c.size.height) / 2f), Offset.Zero, c.rotation)
                    pushUndoSnapshot()
                    _state.value = _state.value.copy(elements = _state.value.elements.map {
                        if (it.id == c.id) c.copy(symbolName = event.name, size = size, center = center,
                            properties = c.properties + mapOf("widthInches" to wIn, "depthInches" to fp.second)) else it
                    })
                    triggerAutoSave()
                }
            }
            is DrawingEvent.ApplyCabinetStyleInRoom -> {
                // This cabinet's door style and hardware for every cabinet in its room (Dan, Nov 27: "just in that room").
                val c = _state.value.elements.find { it.id == event.id } as? DrawingElement.Symbol
                val st = _state.value
                val room = c?.let { CabinetList.roomAt(it.center, st)?.first }
                if (c != null && room != null) {
                    val style = PlanSymbols.doorStyle(c).toFloat()
                    val hw = PlanSymbols.hardware(c).toFloat()
                    pushUndoSnapshot()
                    _state.value = st.copy(elements = st.elements.map { e ->
                        if (e is DrawingElement.Symbol && PlanSymbols.hasStyleChoice(e) && room.contains(e.center))
                            e.copy(properties = e.properties + (PlanSymbols.PROP_STYLE to style) + (PlanSymbols.PROP_HARDWARE to hw)) else e
                    })
                    triggerAutoSave()
                }
            }
            is DrawingEvent.SetOpeningKind -> {
                // Another kind or width of door or window, in place (context card, Nov 27): a door keeps its wall side
                // where it is (its swing grows or shrinks into the room); a window stays centred in the wall.
                val o = _state.value.elements.find { it.id == event.id } as? DrawingElement.Symbol
                val inch = _state.value.inchesToPx(1f)
                if (o != null && o.category == PlanSymbols.DOORS_WINDOWS && inch > 0f) {
                    val wIn = event.widthIn ?: (o.size.width / inch)
                    val fp = PlanSymbols.footprintAt(event.name, wIn)
                    if (fp != null) {
                        val size = Size(fp.first * inch, fp.second * inch)
                        val center = if (PlanSymbols.isDoor(o.symbolName) || PlanSymbols.isDoor(event.name))
                            o.center + rotateAbout(Offset(0f, (o.size.height - size.height) / 2f), Offset.Zero, o.rotation) else o.center
                        val updated = o.copy(symbolName = event.name, size = size, center = center,
                            properties = o.properties + mapOf("widthInches" to fp.first, "depthInches" to fp.second))
                        pushUndoSnapshot()
                        _state.value = _state.value.copy(elements = _state.value.elements.map { if (it.id == o.id) updated else it })
                        triggerAutoSave()
                    }
                }
            }
            is DrawingEvent.SwapDoorHinge, is DrawingEvent.SwingDoorOtherSide -> {
                val id = if (event is DrawingEvent.SwapDoorHinge) event.id else (event as DrawingEvent.SwingDoorOtherSide).id
                val door = _state.value.elements.find { it.id == id } as? DrawingElement.Symbol
                if (door != null && PlanSymbols.isDoor(door.symbolName)) {
                    val flipped = (if (PlanSymbols.hingeRight(door)) 0f else 1f)
                    var updated = door.copy(properties = door.properties + (PlanSymbols.PROP_HINGE_RIGHT to flipped))
                    if (event is DrawingEvent.SwingDoorOtherSide) {
                        val threshold = rotateAbout(door.center + Offset(0f, door.size.height / 2f), door.center, door.rotation)
                        updated = updated.copy(center = threshold * 2f - door.center, rotation = (door.rotation + 180f) % 360f)
                    }
                    pushUndoSnapshot()
                    _state.value = _state.value.copy(elements = _state.value.elements.map { if (it.id == id) updated else it })
                    triggerAutoSave()
                }
            }
            is DrawingEvent.ApplyCabinetStyle -> {
                // One door style and hardware for every cabinet in the drawing.
                pushUndoSnapshot()
                _state.value = _state.value.copy(elements = _state.value.elements.map { e ->
                    if (e is DrawingElement.Symbol && PlanSymbols.hasStyleChoice(e)) e.copy(
                        properties = e.properties + (PlanSymbols.PROP_STYLE to event.style.toFloat()) + (PlanSymbols.PROP_HARDWARE to event.hardware.toFloat())
                    ) else e
                })
                triggerAutoSave()
            }
            is DrawingEvent.AddShowerWalls -> {
                val shower = _state.value.elements.find { it.id == event.id } as? DrawingElement.Symbol
                if (shower != null && shower.category == PlanSymbols.SHOWERS) {
                    val walls = Shower.wallsFor(shower, _state.value)
                    pushUndoSnapshot()
                    _state.value = _state.value.copy(elements = _state.value.elements + walls)
                    triggerAutoSave()
                }
            }
            is DrawingEvent.DimensionPlan -> {
                val s = _state.value
                val sets = linkedMapOf(AutoDimension.PLAN_OWNER to AutoDimension.plan(s, AutoDimension.PlanOptions(event.outside, event.rooms, event.interiorToCenter)))
                // Every elevation too, in the same tap (and the same undo step).
                if (event.elevations) for (v in s.elements.filterIsInstance<DrawingElement.ElevationView>()) sets[v.id] = AutoDimension.elevation(v, s)
                replaceOwnedDimensionsMany(sets)
            }
            is DrawingEvent.AddRoomElevations -> placeRoomElevations(event.point, event.walls)
            is DrawingEvent.AddWallArt -> addDecor(event.elevationId, Picture(name = event.name, bitmap = WallDecorations.shrink(event.bitmap)), WallDecor.ART, 0f)
            is DrawingEvent.AddPatternWallpaper -> {
                val p = WallpaperPatterns.find(event.pattern)
                val pic = _state.value.pictures.firstOrNull { it.id == WallpaperPatterns.pictureId(event.pattern, event.colorway) }
                    ?: WallpaperPatterns.picture(event.pattern, event.colorway)
                if (p == null || pic == null) notify("No wallpaper pattern called ${event.pattern}.")
                else addDecor(event.elevationId, pic, WallDecor.WALLPAPER, p.widthIn)
            }
            is DrawingEvent.AddWallpaper -> addDecor(event.elevationId, Picture(name = "Wallpaper", bitmap = WallDecorations.shrink(event.bitmap)), WallDecor.WALLPAPER, event.repeatIn)
            is DrawingEvent.UsePicture -> _state.value.pictures.firstOrNull { it.id == event.pictureId }?.let { addDecor(event.elevationId, it, event.kind, WallDecorations.WALLPAPER_REPEAT_IN) }
            is DrawingEvent.UpdateWallDecor -> updateDecor(event.id, event.property, event.value)
            is DrawingEvent.SetWallPaint -> setPaint(event.elevationId, event.hex, event.code, event.name)
            is DrawingEvent.AddRoomFinish -> addRoomFinish(event.point, event.name)
            is DrawingEvent.UpdateRoomFinish -> {
                val s = _state.value
                val f = s.roomFinishes.firstOrNull { it.id == event.id } ?: return
                val nf = FinishSchedule.withField(f, event.field, event.value) ?: return
                pushUndoSnapshot()
                _state.value = s.copy(roomFinishes = s.roomFinishes.map { if (it.id == f.id) nf else it })
                triggerAutoSave()
            }
            is DrawingEvent.UpdateItemSpec -> {
                val s = _state.value
                val old = ItemSchedules.spec(s, event.elementId)
                val ns = ItemSchedules.withField(old, event.field, event.value) ?: return
                pushUndoSnapshot()
                _state.value = s.copy(itemSpecs = s.itemSpecs.filter { it.elementId != event.elementId } + ns)
                triggerAutoSave()
            }
            is DrawingEvent.UpdateCabinetSpec -> {
                val s = _state.value
                val old = CabinetSchedule.spec(s, event.room)
                val ns = CabinetSchedule.withField(old, event.field, event.value) ?: return
                pushUndoSnapshot()
                _state.value = s.copy(cabinetSpecs = s.cabinetSpecs.filter { it.room != event.room } + ns)
                triggerAutoSave()
            }
            is DrawingEvent.RemoveRoomFinish -> {
                pushUndoSnapshot()
                _state.value = _state.value.copy(roomFinishes = _state.value.roomFinishes.filter { it.id != event.id })
                triggerAutoSave()
            }
            is DrawingEvent.RemoveWallDecor -> {
                pushUndoSnapshot()
                _state.value = _state.value.copy(wallDecor = _state.value.wallDecor.filter { it.id != event.id })
                triggerAutoSave()
            }
            is DrawingEvent.DimensionElevation -> {
                val view = _state.value.elements.find { it.id == event.id } as? DrawingElement.ElevationView
                if (view != null) replaceOwnedDimensions(view.id, AutoDimension.elevation(view, _state.value))
            }
            is DrawingEvent.RemoveOwnedDimensions -> replaceOwnedDimensions(event.ownerId, emptyList())
        }
    }

    /** Swaps the dimensions [owner] (the plan, or an elevation) made last time for [dims], as one undo step. */
    private fun replaceOwnedDimensions(owner: String, dims: List<DrawingElement.Dimension>) {
        val had = _state.value.elements.any { it.isOwnedBy(owner) }
        if (!had && dims.isEmpty()) return
        pushUndoSnapshot()
        _state.value = _state.value.copy(elements = _state.value.elements.filterNot { it.isOwnedBy(owner) } + dims)
        triggerAutoSave()
    }

    /** [replaceOwnedDimensions] for several owners at once, as one undo step. */
    private fun replaceOwnedDimensionsMany(sets: Map<String, List<DrawingElement.Dimension>>) {
        val owners = sets.keys
        val had = _state.value.elements.any { e -> owners.any { e.isOwnedBy(it) } }
        if (!had && sets.values.all { it.isEmpty() }) return
        pushUndoSnapshot()
        _state.value = _state.value.copy(elements = _state.value.elements.filterNot { e -> owners.any { e.isOwnedBy(it) } } + sets.values.flatten())
        triggerAutoSave()
    }

    private fun deleteSelectedElement() {
        val groupIds = _state.value.selectedElementIds
        if (groupIds.isNotEmpty()) {
            pushUndoSnapshot()
            _state.value = _state.value.copy(
                elements = _state.value.elements.filterNot { it.id in groupIds || groupIds.any { id -> it.isOwnedBy(id) } },
                selectedElementIds = emptySet()
            )
            return
        }
        val selectedId = _state.value.selectedElementId ?: return
        pushUndoSnapshot()
        _state.value = _state.value.copy(
            // An elevation's dimensions go with it.
            elements = _state.value.elements.filter { it.id != selectedId && !it.isOwnedBy(selectedId) },
            selectedElementId = null
        )
    }

    // Autosave is debounced and runs OFF the main thread. It used to serialize the whole project
    // to JSON - every undo snapshot included, see @Transient on CanvasState.undoHistory - and
    // write it synchronously after every single stroke. Each pen lift therefore froze the UI
    // for longer and longer as the drawing grew: the "delay before the next stroke" feel.
    private val saveScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var pendingSave: Job? = null

    init {
        restoreLastProject()
    }

    /**
     * Reopens the project that was open when the app was last closed. Without this every
     * launch started on an empty Untitled Project, and the first autosave then overwrote the
     * saved one - work "vanished" after a restart even though it had been written to disk.
     */
    private fun restoreLastProject() {
        val storage = platform.storage ?: return // nothing persisted (unit tests)
        saveScope.launch {
            try {
                val name = storage.read(LAST_PROJECT_FILE)?.trim()?.ifBlank { null } ?: _state.value.projectName
                val text = storage.read(projectFileName(name)) ?: return@launch
                val loaded = json.decodeFromString(CanvasState.serializer(), text)
                val list = storage.read("project_list.txt")?.lines()?.filter { it.isNotBlank() }?.sorted() ?: emptyList()
                withContext(Dispatchers.Main) {
                    // The canvas may already have reported its size before this load finished: keep it (Nov 25).
                    _state.replace(Layers.reconcile(loaded).let { it.copy(availableProjects = list, viewportSize = _state.value.viewportSize, selectedElementId = null, selectedElementIds = emptySet(), sheets = Sheets.renumbered(it.sheets, it)) })
                    // Generated parts (markers, framing, roof, elevations...) brought up to date on open, as an
                    // edit would, so a newer app version's changes show without a throwaway edit (Nov 25).
                    val beforeSync = _state.value
                    syncDerived()
                    if (_state.value !== beforeSync) triggerAutoSave()
                }
            } catch (e: Exception) {
                platform.log("SiteDraft", "restore failed: ${e.message}")
            }
        }
    }

    private fun projectFileName(name: String) = "project_${name.replace(" ", "_")}.json"

    private fun triggerAutoSave() {
        syncDerived()
        registerProjectName()
        pendingSave?.cancel()
        // Serialise on the caller's thread (state is read here), write off it.
        val snapshot = _state.value
        pendingSave = saveScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            saveProject(snapshot)
        }
    }

    /** The parts built from the plan (stairs, layers, elevations, roof, sheets...) brought up to date. */
    private fun syncDerived() {
        syncStairs()
        syncUpperFit()
        syncDerivedLayers()
        syncElevationViews()
        syncWallSections()
        syncScheduleMarks()
        syncRoof()
        syncExteriorElevations()
        syncSiteLot()
        syncDetails()
        syncSheetNumbers()
    }

    // Explicit Save: no debounce, still off the main thread.
    private fun saveNow() {
        registerProjectName()
        pendingSave?.cancel()
        val snapshot = _state.value
        pendingSave = saveScope.launch { saveProject(snapshot) }
    }

    // The in-memory project list is updated here, on the caller's (main) thread, so the
    // background writer never touches _state.
    private fun registerProjectName() {
        val name = _state.value.projectName
        if (name !in _state.value.availableProjects) {
            _state.value = _state.value.copy(availableProjects = (_state.value.availableProjects + name).sorted())
        }
    }

    /** Stops autosave and disconnects the laser; the host calls this when it is torn down. */
    fun dispose() {
        if (laserDelegate.isInitialized()) (laser as? AutoCloseable)?.close()
        pendingSave?.cancel()
    }

    /** What can be picked, box-selected and snapped to: what the screen shows (working-layer mode). */
    private fun liveElements(): List<DrawingElement> = Layers.shownElements(_state.value)

    /** A level/layer edit: applied and saved, or its reason shown. */
    private fun applyLayerEdit(r: LayerEdits.Result) {
        if (r.refused != null) { notify(r.refused); return }
        _state.value = r.state
        triggerAutoSave()
    }

    private fun newProject() {
        _state.replace(CanvasState(
            projectName = "Project ${nowMillis() / 1000}",
            // New projects build their foundation and framing from the plan.
            building = BuildingSettings(autoLayers = true),
            unitSystem = _state.value.unitSystem,
            scale = _state.value.scale,
            backgroundColor = _state.value.backgroundColor,
            background = _state.value.background,
            availableProjects = _state.value.availableProjects
        ))
    }

    private fun duplicatedWithOffset(element: DrawingElement, offset: Offset): DrawingElement = when (element) {
        is DrawingElement.Freehand -> element.copy(id = randomId(), points = element.points.map { it.copy(offset = it.offset + offset) })
        is DrawingElement.Line -> element.copy(id = randomId(), start = element.start + offset, end = element.end + offset)
        is DrawingElement.Rectangle -> element.copy(id = randomId(), topLeft = element.topLeft + offset)
        is DrawingElement.Oval -> element.copy(id = randomId(), topLeft = element.topLeft + offset)
        is DrawingElement.Triangle -> element.copy(id = randomId(), topLeft = element.topLeft + offset)
        is DrawingElement.Circle -> element.copy(id = randomId(), center = element.center + offset)
        is DrawingElement.Symbol -> element.copy(id = randomId(), center = element.center + offset)
        is DrawingElement.Dimension -> element.copy(id = randomId(), start = element.start + offset, end = element.end + offset, ownerId = null)
        is DrawingElement.Text -> element.copy(id = randomId(), topLeft = element.topLeft + offset)
        is DrawingElement.Image -> element.copy(id = randomId(), topLeft = element.topLeft + offset)
        is DrawingElement.Pdf -> element.copy(id = randomId(), topLeft = element.topLeft + offset)
        is DrawingElement.Dxf -> element.copy(id = randomId(), topLeft = element.topLeft + offset)
        is DrawingElement.ElevationView -> element.copy(id = randomId(), origin = element.origin + offset)
    }

    private fun duplicateSelectedElement() {
        val offset = Offset(40f, 40f) // Slight shift for visibility
        val groupIds = _state.value.selectedElementIds
        if (groupIds.isNotEmpty()) {
            val newElements = _state.value.elements.filter { it.id in groupIds }.map { duplicatedWithOffset(it, offset) }
            pushUndoSnapshot()
            _state.value = _state.value.copy(
                elements = _state.value.elements + newElements,
                selectedElementIds = newElements.map { it.id }.toSet()
            )
            return
        }
        val selectedId = _state.value.selectedElementId ?: return
        val element = _state.value.elements.find { it.id == selectedId } ?: return
        val newElement = duplicatedWithOffset(element, offset)

        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements + newElement,
            selectedElementId = newElement.id
        )
    }

    /**
     * Zooms by [factor] about [focal] (screen px): the drawing under that point stays put, so a
     * pinch zooms about the fingers, the wheel about the mouse, and the +/- buttons about the
     * middle of the view. Zooming about the canvas origin (the old behaviour) sent whatever was
     * being looked at sliding off screen with every step.
     */
    private fun zoomBy(factor: Float, focal: Offset?) {
        val s = _state.value
        val newZoom = (s.zoom * factor).coerceIn(0.1f, 100f)
        val applied = newZoom / s.zoom
        if (applied == 1f) return
        val f = focal ?: Offset(s.viewportSize.width / 2f, s.viewportSize.height / 2f)
        // screen = pan + world * zoom; keep the world point under f where it is.
        val newPan = f - (f - s.pan) * applied
        _state.value = s.copy(zoom = newZoom, pan = newPan)
    }

    /** Frames every element in the view with a margin (or resets when there is nothing to frame). */
    private fun zoomToFit() {
        val s = _state.value
        val vw = s.viewportSize.width
        val vh = s.viewportSize.height
        val rects = s.elements.map { it.boundingRect() }.filter { it.width.isFinite() && it.height.isFinite() }
        if (rects.isEmpty() || vw <= 0f || vh <= 0f) {
            _state.value = s.copy(zoom = 1f, pan = Offset.Zero)
            return
        }
        val bounds = Rect(rects.minOf { it.left }, rects.minOf { it.top }, rects.maxOf { it.right }, rects.maxOf { it.bottom })
        val margin = 0.08f
        val zoom = minOf(
            vw * (1f - 2f * margin) / maxOf(bounds.width, 1f),
            vh * (1f - 2f * margin) / maxOf(bounds.height, 1f)
        ).coerceIn(0.1f, 100f)
        val pan = Offset(vw / 2f, vh / 2f) - bounds.center * zoom
        _state.value = s.copy(zoom = zoom, pan = pan)
    }

    private fun updateBackgroundColor(color: Color) {
        val newDefaultColor = if (color == Color.White) Color.Black else Color.White
        val oldDefaultColor = if (color == Color.White) Color.White else Color.Black
        
        _state.value = _state.value.copy(
            backgroundColor = color,
            currentColor = if (_state.value.currentColor == oldDefaultColor) newDefaultColor else _state.value.currentColor,
            elements = _state.value.elements.map { element ->
                if (element.color == oldDefaultColor) {
                    when (element) {
                        is DrawingElement.Freehand -> element.copy(color = newDefaultColor)
                        is DrawingElement.Line -> element.copy(color = newDefaultColor)
                        is DrawingElement.Rectangle -> element.copy(color = newDefaultColor)
                        is DrawingElement.Oval -> element.copy(color = newDefaultColor)
                        is DrawingElement.Triangle -> element.copy(color = newDefaultColor)
                        is DrawingElement.Circle -> element.copy(color = newDefaultColor)
                        is DrawingElement.Dimension -> element.copy(color = newDefaultColor)
                        is DrawingElement.Symbol -> element.copy(color = newDefaultColor)
                        is DrawingElement.Text -> element.copy(color = newDefaultColor)
                        else -> element
                    }
                } else element
            }
        )
    }

    private fun updateDimensionFormat(format: DimensionFormat) {
        _state.value = _state.value.copy(
            dimensionFormat = format,
            elements = _state.value.elements.map { element ->
                if (element is DrawingElement.Dimension) {
                    element.copy(format = format)
                } else element
            }
        )
    }

    private fun offsetSelectedElement(distance: Float, direction: Int) {
        val selectedId = _state.value.selectedElementId ?: return
        val element = _state.value.elements.find { it.id == selectedId } ?: return
        
        val pixelOffset = distance * _state.value.scale.ratio
        val newElement = when (element) {
            is DrawingElement.Line -> {
                val dir = (element.end - element.start)
                val perp = if (dir != Offset.Zero) {
                    Offset(-dir.y, dir.x).normalize() * (pixelOffset * direction)
                } else Offset(pixelOffset * direction, 0f)
                element.copy(id = randomId(), start = element.start + perp, end = element.end + perp)
            }
            is DrawingElement.Rectangle -> {
                val d = pixelOffset * direction
                val center = element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
                val newSize = Size((element.size.width + d * 2f).coerceAtLeast(1f), (element.size.height + d * 2f).coerceAtLeast(1f))
                element.copy(
                    id = randomId(), 
                    topLeft = center - Offset(newSize.width / 2, newSize.height / 2),
                    size = newSize
                )
            }
            is DrawingElement.Circle -> {
                val d = pixelOffset * direction
                element.copy(id = randomId(), radius = (element.radius + d).coerceAtLeast(1f))
            }
            is DrawingElement.Oval -> {
                val d = pixelOffset * direction
                val center = element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
                val newSize = Size((element.size.width + d * 2f).coerceAtLeast(1f), (element.size.height + d * 2f).coerceAtLeast(1f))
                element.copy(
                    id = randomId(), 
                    topLeft = center - Offset(newSize.width / 2, newSize.height / 2),
                    size = newSize
                )
            }
            is DrawingElement.Symbol -> {
                // A parallel copy, like a line: moved across its thin axis (a wall is offset
                // sideways, not grown). Side A/B pick which way.
                val across = if (element.size.width >= element.size.height) Offset(0f, 1f) else Offset(1f, 0f)
                val shift = rotatePoint(across, Offset.Zero, element.rotation) * (pixelOffset * direction)
                element.copy(id = randomId(), center = element.center + shift)
            }
            else -> null
        }

        newElement?.let {
            pushUndoSnapshot()
            _state.value = _state.value.copy(elements = _state.value.elements + it)
        }
    }

    private fun Offset.normalize(): Offset {
        val dist = getDistance()
        return if (dist > 0) this / dist else Offset.Zero
    }

    // Snap-to-grid resolution in world units: 1 inch (Imperial) or 5 cm (Metric).
    private fun gridStep(): Float {
        return if (_state.value.unitSystem == UnitSystem.IMPERIAL) {
            _state.value.scale.ratio / 12f
        } else {
            _state.value.scale.ratio * 0.05f
        }
    }

    private fun nearestGridPoint(offset: Offset): Offset {
        val step = gridStep()
        return Offset(
            (kotlin.math.round(offset.x / step) * step).toFloat(),
            (kotlin.math.round(offset.y / step) * step).toFloat()
        )
    }

    // General snap search for a pointer position, in strict priority order:
    //   1. object key points (vertices, midpoints, centers) within snapThreshold
    //   2. nearest point on an object's edge within edgeThreshold (skipped when includeEdges
    //      is false - see findKeyPointSnap)
    //   3. nearest grid intersection, only if neither of the above hit
    //
    // The grid MUST be the last resort, not a competitor in a nearest-wins race. The grid
    // step is 1" (~4 world px at the default 1'=50px scale), so a grid intersection is never
    // more than ~3 px from any pointer position. The old implementation checked the grid first
    // and let that ~3 px distance become the bar an object corner had to beat - a corner could
    // only win if the pointer was already sitting on it, and the edge pass (gated on the
    // running minimum still being > 15 px) never ran at all. Net effect with both snap toggles
    // on (the defaults): drawn endpoints quantized to the inch grid and essentially never
    // locked onto existing geometry, which is the opposite of what "Snap to Objects" promises.
    private fun findSnapPoint(offset: Offset, excludeId: String? = null, includeEdges: Boolean = true): Offset {
        val snapThreshold = 20f / _state.value.zoom // Increased from 12f for easier mobile use
        val edgeThreshold = 15f / _state.value.zoom
        val others = liveElements().filter { it.id != excludeId }

        // 1. Key points. Vertices take priority over everything else: a corner is a real,
        // named point the user is aiming at, whereas an edge point or grid point is only ever
        // "somewhere nearby".
        var bestVertex: Offset? = null
        var minVertexDistance = snapThreshold
        others.forEach { element ->
            getElementKeyPoints(element).forEach { pt ->
                val dist = (pt - offset).getDistance()
                if (dist < minVertexDistance) {
                    minVertexDistance = dist
                    bestVertex = pt
                }
            }
        }
        bestVertex?.let { return it }

        // 2. Edges.
        if (includeEdges) {
            var bestEdge: Offset? = null
            var minEdgeDistance = edgeThreshold
            others.forEach { element ->
                getNearestPointOnElement(offset, element)?.let { pt ->
                    val dist = (pt - offset).getDistance()
                    if (dist < minEdgeDistance) {
                        minEdgeDistance = dist
                        bestEdge = pt
                    }
                }
            }
            bestEdge?.let { return it }
        }

        // 3. Grid.
        if (_state.value.snapToGrid) {
            val gridPt = nearestGridPoint(offset)
            if ((gridPt - offset).getDistance() < snapThreshold) {
                return gridPt
            }
        }
        return offset
    }

    // Vertex-only snap search (grid intersections + object key points - vertices, midpoints,
    // centers), deliberately WITHOUT the arbitrary-point-on-edge fallback findSnapPoint has.
    // Used for Pt-to-Pt's "To" point so it can't land on some arbitrary spot along an edge
    // that happens to be nearest to the tap - it matches how the "From" point is already
    // chosen (via getElementKeyPoints), so both ends of a point-to-point move only ever
    // resolve to real, named points. Without this, one axis can end up snapped (e.g. flush
    // with an edge) while the other isn't, since a point on an edge only constrains position
    // along that edge, not the perpendicular position of the edge's actual endpoint/corner.
    private fun findKeyPointSnap(offset: Offset, excludeId: String? = null): Offset {
        return findSnapPoint(offset, excludeId, includeEdges = false)
    }

    // Axis-aligned bounding box for elements whose corners are meaningful to compare
    // (skips rotated elements - a rotated bounding box can't be compared axis-by-axis
    // the way the overlap-avoidance in resolvePreciseMoveDelta needs).
    private fun getAxisAlignedBounds(element: DrawingElement): Rect? {
        if (element.rotation != 0f) return null
        return when (element) {
            is DrawingElement.Rectangle -> Rect(element.topLeft, element.size)
            is DrawingElement.Oval -> Rect(element.topLeft, element.size)
            is DrawingElement.Triangle -> Rect(element.topLeft, element.size)
            is DrawingElement.Symbol -> Rect(
                element.center - Offset(element.size.width / 2f, element.size.height / 2f),
                element.size
            )
            else -> null
        }
    }

    // Point-to-point snapping matches a single pair of points exactly, but which of the
    // moved element's corners the user tapped as the "From" point determines which way the
    // rest of its body extends from that point - tap the corner nearest the target and the
    // body swings back over it instead of away from it, so two flush-butted pieces (e.g. a
    // stud end against a sheet edge) can end up overlapping instead of just touching. Since
    // the touch point is shared by construction, real overlap only happens when the moved
    // element's body extends toward the target on BOTH axes at once - so if the naive match
    // does overlap, forcing just one axis to extend outward from whichever edge of the
    // target the touch point sits on is enough to separate them, without needing to know
    // (or second-guess) which corner was actually tapped.
    private fun resolvePreciseMoveDelta(movingElement: DrawingElement, fromPoint: Offset, toPoint: Offset): Offset {
        val naiveDelta = toPoint - fromPoint
        val targetElement = liveElements().firstOrNull { el ->
            el.id != movingElement.id && getElementKeyPoints(el).any { (it - toPoint).getDistance() < 0.5f }
        } ?: return naiveDelta

        val movingBounds = getAxisAlignedBounds(movingElement) ?: return naiveDelta
        val targetBounds = getAxisAlignedBounds(targetElement) ?: return naiveDelta

        val epsilon = 0.5f
        val movedLeft = movingBounds.left + naiveDelta.x
        val movedRight = movingBounds.right + naiveDelta.x
        val movedTop = movingBounds.top + naiveDelta.y
        val movedBottom = movingBounds.bottom + naiveDelta.y
        val overlapX = minOf(movedRight, targetBounds.right) - maxOf(movedLeft, targetBounds.left)
        val overlapY = minOf(movedBottom, targetBounds.bottom) - maxOf(movedTop, targetBounds.top)
        if (overlapX <= epsilon || overlapY <= epsilon) return naiveDelta // already just touching or separate

        if (kotlin.math.abs(toPoint.x - targetBounds.right) < epsilon) {
            return Offset(toPoint.x - movingBounds.left, naiveDelta.y)
        }
        if (kotlin.math.abs(toPoint.x - targetBounds.left) < epsilon) {
            return Offset(toPoint.x - movingBounds.right, naiveDelta.y)
        }
        if (kotlin.math.abs(toPoint.y - targetBounds.bottom) < epsilon) {
            return Offset(naiveDelta.x, toPoint.y - movingBounds.top)
        }
        if (kotlin.math.abs(toPoint.y - targetBounds.top) < epsilon) {
            return Offset(naiveDelta.x, toPoint.y - movingBounds.bottom)
        }
        return naiveDelta
    }

    // Distance from a point to an element's bounding box (0 if the point is inside it) - a
    // uniform, good-enough metric across every element type (unlike getNearestPointOnElement
    // below, which only actually computes edge-exact distance for Line/Rectangle/Circle/Symbol),
    // used only to break ties between multiple candidates that all satisfy contains().
    private fun distanceToElement(element: DrawingElement, p: Offset): Float {
        val rect = element.boundingRect()
        val nearestX = p.x.coerceIn(rect.left, rect.right)
        val nearestY = p.y.coerceIn(rect.top, rect.bottom)
        return (Offset(nearestX, nearestY) - p).getDistance()
    }

    // How far the tap is from the element's drawn outline (its edges, stroke or circle), or from
    // the edge of its bounding box for the types that have no outline geometry (text, images).
    // Unlike distanceToElement this is NOT zero everywhere inside a shape, which is what lets an
    // object sitting inside another one be picked at all.
    private fun pickDistance(element: DrawingElement, p: Offset): Float {
        getNearestPointOnElement(p, element)?.let { return (it - p).getDistance() }
        val r = element.boundingRect()
        val toEdge = if (r.contains(p)) minOf(p.x - r.left, r.right - p.x, p.y - r.top, r.bottom - p.y) else distanceToElement(element, p)
        return maxOf(0f, toEdge)
    }

    // Selection hit-testing: among every element whose contains() matches this tap, pick the one
    // whose drawn outline is nearest to the tap, and when two are equally near (an upper cabinet
    // whose back edge sits on the base cabinet's back edge, a small rectangle drawn inside a big
    // one), the smaller one. Picking by distance-to-bounding-box (the previous rule) made every
    // enclosing shape a tie at distance 0, so a 12"-deep upper cabinet placed over a 24"-deep base
    // could never be selected, a line drawn inside a rectangle always lost to the rectangle, and
    // which of two overlapping cabinets won depended on creation order. Outline distance was
    // already the right tie-breaker for the exploded-rectangle case this replaced (4 short edges
    // close together: the one the pen is actually nearest to wins).
    private fun findHitElement(offset: Offset, threshold: Float): DrawingElement? {
        val scored = liveElements().filter { !it.isLockedPlan && it.contains(offset, threshold) }.map { it to pickDistance(it, offset) }
        val best = scored.minOfOrNull { it.second } ?: return null
        // Outlines within a fingertip's wobble (8 screen px) of the nearest one count as a tie -
        // an upper cabinet's back edge sits on the base cabinet's back edge, give or take a px -
        // and the smaller object wins the tie.
        val tie = 8f / _state.value.zoom
        // Among the tied: an annotation (dimension, text) beats the geometry it sits on - a
        // dimension run along the middle of a wall is otherwise unreachable - then the
        // smaller object wins.
        return scored.filter { it.second <= best + tie }
            .minWithOrNull(
                compareBy<Pair<DrawingElement, Float>> { if (it.first is DrawingElement.Dimension || it.first is DrawingElement.Text) 0 else 1 }
                    .thenBy { it.first.boundingRect().let { r -> r.width * r.height } }
            )?.first
    }

    /** The point an element turns about when it has a rotation; null for elements never drawn rotated. */
    private fun rotationPivot(element: DrawingElement): Offset? = when (element) {
        is DrawingElement.Rectangle -> element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
        is DrawingElement.Oval -> element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
        is DrawingElement.Triangle -> element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
        is DrawingElement.Symbol -> element.center
        is DrawingElement.Circle -> element.center
        is DrawingElement.Line -> element.start + (element.end - element.start) / 2f
        else -> null
    }

    /**
     * Edge snapping: the nearest point on the element's outline to [p]. A rotated shape is
     * handled by turning [p] into the shape's own frame, finding the point there, and turning
     * it back - so the edges of a rotated door, rectangle or symbol snap where they are drawn.
     */
    private fun getNearestPointOnElement(p: Offset, element: DrawingElement): Offset? {
        val pivot = rotationPivot(element)
        val rotated = element.rotation != 0f && pivot != null
        val local = if (rotated) rotatePoint(p, pivot!!, -element.rotation) else p
        val found = nearestPointUnrotated(local, element) ?: return null
        return if (rotated) rotatePoint(found, pivot!!, element.rotation) else found
    }

    /** Nearest point on a pen stroke (its polyline). Strokes can run to thousands of points and this is called per pointer move, so far-away strokes are rejected by bounding box first. */
    private fun nearestPointOnStroke(p: Offset, stroke: DrawingElement.Freehand): Offset? {
        val pts = stroke.points
        if (pts.isEmpty()) return null
        if (pts.size == 1) return pts[0].offset
        val margin = 40f / _state.value.zoom
        val b = stroke.boundingRect()
        if (p.x < b.left - margin || p.x > b.right + margin || p.y < b.top - margin || p.y > b.bottom + margin) return null
        var best: Offset? = null
        var bestD = Float.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val q = getNearestPointOnSegment(p, pts[i].offset, pts[i + 1].offset)
            val d = (q - p).getDistanceSquared()
            if (d < bestD) { bestD = d; best = q }
        }
        return best
    }

    private fun nearestPointUnrotated(p: Offset, element: DrawingElement): Offset? {
        return when (element) {
            is DrawingElement.Line -> getNearestPointOnSegment(p, element.start, element.end)
            is DrawingElement.Freehand -> nearestPointOnStroke(p, element)
            is DrawingElement.Dimension -> {
                // The dimension LINE as drawn (offset from the measured points) and its two
                // extension lines - what the pen actually lands on. Measuring to the measured
                // points made a dimension sitting on a wall lose every tap to the wall.
                val (dimStart, dimEnd) = element.dimLineEndpoints()
                listOf(
                    getNearestPointOnSegment(p, dimStart, dimEnd),
                    getNearestPointOnSegment(p, element.start, dimStart),
                    getNearestPointOnSegment(p, element.end, dimEnd)
                ).minByOrNull { (it - p).getDistance() }
            }
            is DrawingElement.Triangle -> {
                val (a, b, c) = element.getVertices()
                listOf(
                    getNearestPointOnSegment(p, a, b),
                    getNearestPointOnSegment(p, b, c),
                    getNearestPointOnSegment(p, c, a)
                ).minByOrNull { (it - p).getDistance() }
            }
            is DrawingElement.Oval -> {
                // Close enough for snapping: the ellipse point in the pointer's direction, with
                // the direction measured in the ellipse's own (stretched) coordinates.
                val rx = element.size.width / 2f
                val ry = element.size.height / 2f
                val c = element.topLeft + Offset(rx, ry)
                val d = p - c
                if (rx <= 0f || ry <= 0f || d == Offset.Zero) null else {
                    val theta = atan2((d.y / ry).toDouble(), (d.x / rx).toDouble())
                    c + Offset((rx * kotlin.math.cos(theta)).toFloat(), (ry * kotlin.math.sin(theta)).toFloat())
                }
            }
            is DrawingElement.Rectangle -> {
                val p1 = element.topLeft
                val p2 = element.topLeft + Offset(element.size.width, 0f)
                val p3 = element.topLeft + Offset(element.size.width, element.size.height)
                val p4 = element.topLeft + Offset(0f, element.size.height)
                listOf(
                    getNearestPointOnSegment(p, p1, p2),
                    getNearestPointOnSegment(p, p2, p3),
                    getNearestPointOnSegment(p, p3, p4),
                    getNearestPointOnSegment(p, p4, p1)
                ).minByOrNull { (it - p).getDistance() }
            }
            is DrawingElement.Circle -> {
                val dir = (p - element.center)
                if (dir == Offset.Zero) null
                else element.center + dir.normalize() * element.radius
            }
            is DrawingElement.Symbol -> {
                val halfW = element.size.width / 2f
                val halfH = element.size.height / 2f
                val c = element.center
                val p1 = c + Offset(-halfW, -halfH)
                val p2 = c + Offset(halfW, -halfH)
                val p3 = c + Offset(halfW, halfH)
                val p4 = c + Offset(-halfW, halfH)
                listOf(
                    getNearestPointOnSegment(p, p1, p2),
                    getNearestPointOnSegment(p, p2, p3),
                    getNearestPointOnSegment(p, p3, p4),
                    getNearestPointOnSegment(p, p4, p1)
                ).minByOrNull { (it - p).getDistance() }
            }
            else -> null
        }
    }

    private fun getNearestPointOnSegment(p: Offset, a: Offset, b: Offset): Offset {
        val l2 = (a - b).getDistanceSquared()
        if (l2 == 0f) return a
        var t = ((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / l2
        t = maxOf(0f, minOf(1f, t))
        return Offset(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
    }

    private fun getElementKeyPoints(element: DrawingElement): List<Offset> {
        val rawPoints = when (element) {
            is DrawingElement.Line -> listOf(element.start, element.end, element.start + (element.end - element.start) / 2f)
            is DrawingElement.Rectangle -> {
                val w = element.size.width
                val h = element.size.height
                val tl = element.topLeft
                val tr = tl + Offset(w, 0f)
                val bl = tl + Offset(0f, h)
                val br = tl + Offset(w, h)
                listOf(
                    tl, tr, bl, br, // Corners
                    tl + Offset(w/2, 0f), // Top mid
                    bl + Offset(w/2, 0f), // Bottom mid
                    tl + Offset(0f, h/2), // Left mid
                    tr + Offset(0f, h/2), // Right mid
                    tl + Offset(w/2, h/2) // Center
                )
            }
            is DrawingElement.Circle -> {
                val center = element.center
                listOf(
                    center,
                    center + Offset(element.radius, 0f),
                    center + Offset(-element.radius, 0f),
                    center + Offset(0f, element.radius),
                    center + Offset(0f, -element.radius)
                )
            }
            is DrawingElement.Oval -> {
                val center = element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
                listOf(
                    center,
                    element.topLeft + Offset(element.size.width / 2, 0f),
                    element.topLeft + Offset(element.size.width / 2, element.size.height),
                    element.topLeft + Offset(0f, element.size.height / 2),
                    element.topLeft + Offset(element.size.width, element.size.height / 2)
                )
            }
            is DrawingElement.Triangle -> {
                val (p1, p2, p3) = element.getVertices()
                listOf(p1, p2, p3, (p1 + p2 + p3) / 3f)
            }
            is DrawingElement.Symbol -> {
                val halfW = element.size.width / 2f
                val halfH = element.size.height / 2f
                val c = element.center
                
                // For thin members (Lumber/Plywood), only show corners and midpoints on long edges.
                // Center is deliberately excluded: two framing members should butt edge-to-edge
                // or corner-to-corner, never centerline-to-edge - the latter snaps this member's
                // thickness half onto/half off of whatever it's snapping to, i.e. an overlap
                // instead of a flush joint.
                if (element.category == "Lumber" || element.category == "Plywood") {
                    val threshold = 10f // ~2 inches at default scale
                    val isThinW = element.size.width < threshold
                    val isThinH = element.size.height < threshold

                    val pts = mutableListOf(
                        c + Offset(-halfW, -halfH), // TL
                        c + Offset(halfW, -halfH),  // TR
                        c + Offset(-halfW, halfH),  // BL
                        c + Offset(halfW, halfH)    // BR
                    )
                    
                    if (!isThinW) {
                        pts.add(c + Offset(0f, -halfH)) // Top Mid
                        pts.add(c + Offset(0f, halfH))  // Bottom Mid
                    }
                    if (!isThinH) {
                        pts.add(c + Offset(-halfW, 0f)) // Left Mid
                        pts.add(c + Offset(halfW, 0f))  // Right Mid
                    }
                    pts
                } else {
                    listOf(
                        c, // Center
                        c + Offset(-halfW, -halfH), // TL
                        c + Offset(halfW, -halfH),  // TR
                        c + Offset(-halfW, halfH),  // BL
                        c + Offset(halfW, halfH),   // BR
                        c + Offset(0f, -halfH),     // Top Mid
                        c + Offset(0f, halfH),      // Bottom Mid
                        c + Offset(-halfW, 0f),     // Left Mid
                        c + Offset(halfW, 0f)       // Right Mid
                    )
                }
            }
            is DrawingElement.Dimension -> listOf(element.start, element.end, element.start + (element.end - element.start) / 2f)
            is DrawingElement.Text -> listOf(element.topLeft)
            is DrawingElement.ElevationView -> element.frame.let { listOf(it.topLeft, it.topRight, it.bottomLeft, it.bottomRight) }
            // A pen stroke: its two ends are the points worth aiming at.
            is DrawingElement.Freehand -> listOfNotNull(element.points.firstOrNull()?.offset, element.points.lastOrNull()?.offset)
            else -> emptyList()
        }

        if (element.rotation == 0f) return rawPoints

        val center = when (element) {
            is DrawingElement.Rectangle -> element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
            is DrawingElement.Oval -> element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
            is DrawingElement.Triangle -> element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
            is DrawingElement.Symbol -> element.center
            is DrawingElement.Circle -> element.center
            is DrawingElement.Line -> element.start + (element.end - element.start) / 2f
            else -> Offset.Zero
        }

        return rawPoints.map { rotatePoint(it, center, element.rotation) }
    }

    private fun rotatePoint(point: Offset, center: Offset, angleDegrees: Float): Offset {
        val angleRad = com.example.sitedraft.util.toRadians(angleDegrees.toDouble())
        val cos = kotlin.math.cos(angleRad).toFloat()
        val sin = kotlin.math.sin(angleRad).toFloat()

        val dx = point.x - center.x
        val dy = point.y - center.y

        return Offset(
            center.x + dx * cos - dy * sin,
            center.y + dx * sin + dy * cos
        )
    }

    private fun findObjectSnapDelta(selectedElement: DrawingElement, delta: Offset, basePoint: Offset? = null): Pair<Offset?, Offset> {
        val snapThreshold = 20f / _state.value.zoom // Increased from 15f
        var bestDelta: Offset? = null
        var minDistance = snapThreshold
        var snapPoint: Offset = Offset.Zero

        val sourcePoints = if (basePoint != null) listOf(basePoint) else getElementKeyPoints(selectedElement)
        val otherElements = liveElements().filter { it.id != selectedElement.id }
        
        // 1. Point-to-Point Snapping
        sourcePoints.forEach { srcPt ->
            val movedSrcPt = srcPt + delta
            otherElements.forEach { element ->
                val targetPoints = getElementKeyPoints(element)
                targetPoints.forEach { tgtPt ->
                    val dist = (tgtPt - movedSrcPt).getDistance()
                    if (dist < minDistance) {
                        minDistance = dist
                        bestDelta = delta + (tgtPt - movedSrcPt)
                        snapPoint = tgtPt
                    }
                }
            }
        }
        
        // 2. Point-to-Edge Snapping (if no key point found nearby)
        if (bestDelta == null || minDistance > 15f / _state.value.zoom) {
            sourcePoints.forEach { srcPt ->
                val movedSrcPt = srcPt + delta
                otherElements.forEach { element ->
                    val nearestOnEdge = getNearestPointOnElement(movedSrcPt, element)
                    nearestOnEdge?.let { tgtPt ->
                        val dist = (tgtPt - movedSrcPt).getDistance()
                        if (dist < minDistance) {
                            minDistance = dist
                            bestDelta = delta + (tgtPt - movedSrcPt)
                            snapPoint = tgtPt
                        }
                    }
                }
            }
        }

        // 3. Grid Snapping - last resort only, same priority rule as findSnapPoint. Previously
        // object moves had no grid snap at all, so "Snap to Grid" silently did nothing while
        // dragging with Select/Move: an object only ever landed somewhere meaningful if it
        // happened to touch another object. Snap whichever of the moved source points is
        // closest to a grid intersection, so a dragged corner (or the Move tool's picked base
        // point) lands exactly on the grid.
        if (bestDelta == null && _state.value.snapToGrid) {
            sourcePoints.forEach { srcPt ->
                val movedSrcPt = srcPt + delta
                val gridPt = nearestGridPoint(movedSrcPt)
                val dist = (gridPt - movedSrcPt).getDistance()
                if (dist < minDistance) {
                    minDistance = dist
                    bestDelta = delta + (gridPt - movedSrcPt)
                    snapPoint = gridPt
                }
            }
        }

        return Pair(bestDelta, snapPoint)
    }

    private fun updateScale(ratio: Float, label: String) {
        val oldScale = _state.value.scale.ratio
        val newScale = ratio
        val ratioOfScales = oldScale / newScale
        
        _state.value = _state.value.copy(
            scale = DrawingScale(ratio, label),
            elements = _state.value.elements.map { element ->
                when (element) {
                    is DrawingElement.Line -> element.copy(
                        start = element.start,
                        end = element.start + ((element.end - element.start) * ratioOfScales),
                    )
                    is DrawingElement.Rectangle -> element.copy(size = element.size * ratioOfScales)
                    is DrawingElement.Circle -> element.copy(radius = element.radius * ratioOfScales)
                    is DrawingElement.Dimension -> {
                        val dist = (element.end - element.start).getDistance()
                        element.copy(value = dist / newScale)
                    }
                    is DrawingElement.Symbol -> {
                        if ((element.symbolName == "I-Beam") || element.symbolName.contains("W-Beam")) {
                            val newSize = calculateBeamPixelSize(element.properties, ratio, _state.value.unitSystem)
                            element.copy(size = newSize)
                        } else {
                            element
                        }
                    }
                    else -> element
                }
            }
        )
    }

    private fun updateUnitSystem(unit: UnitSystem) {
        val oldUnit = _state.value.unitSystem
        if (oldUnit == unit) return

        val newScale = if (unit == UnitSystem.METRIC) {
            DrawingScale(100f, "1m = 100px")
        } else {
            DrawingScale(50f, "1' = 50px")
        }

        val oldScale = _state.value.scale.ratio
        val ratioOfScales = oldScale / newScale.ratio

        _state.value = _state.value.copy(
            unitSystem = unit,
            scale = newScale,
            elements = _state.value.elements.map { element ->
                when (element) {
                    is DrawingElement.Line -> element.copy(
                        start = element.start,
                        end = element.start + (element.end - element.start) * ratioOfScales
                    )
                    is DrawingElement.Rectangle -> element.copy(size = element.size * ratioOfScales)
                    is DrawingElement.Circle -> element.copy(radius = element.radius * ratioOfScales)
                    is DrawingElement.Dimension -> {
                        val newValue = if (unit == UnitSystem.IMPERIAL) {
                            element.value * 3.28084f
                        } else {
                            element.value / 3.28084f
                        }
                        element.copy(
                            start = element.start,
                            end = element.start + (element.end - element.start) * ratioOfScales,
                            value = newValue,
                            unit = unit
                        )
                    }
                    is DrawingElement.Symbol -> {
                        if ((element.symbolName == "I-Beam") || element.symbolName.contains("W-Beam")) {
                            val newSize = calculateBeamPixelSize(element.properties, newScale.ratio, unit)
                            element.copy(size = newSize)
                        } else {
                            element
                        }
                    }
                    else -> element
                }
            }
        )
    }

    private fun startDrawing(offset: Offset, pressure: Float, resizeThreshold: Float) {
        hasSnapshotThisGesture = false
        // A Move drag (Nov 27): remember where it started, to offer "make that exactly ___" when it ends.
        moveGestureStart = if (_state.value.currentTool == DrawingTool.MOVE) offset else null
        moveGestureBefore = if (_state.value.currentTool == DrawingTool.MOVE) _state.value.elements else null
        if (_state.value.lastMoveDir != null) _state.value = _state.value.copy(lastMoveDir = null)
        moveSnapCorrection = Offset.Zero
        carriedIds = emptySet()
        planTapCandidate = null
        var snappedOffset = offset
        var snapFound = false
        // Freehand strokes never snap their first point: a pen stroke that starts by jumping
        // to the nearest grid line or corner feels like the pen is being pulled around.
        val freehandStart = _state.value.currentTool in setOf(DrawingTool.PEN, DrawingTool.PENCIL, DrawingTool.MARKER, DrawingTool.ERASER)
        if (_state.value.snapEnabled && !freehandStart) {
            // Pt-to-Pt's "To" point uses the vertex-only search (matches how the "From" point
            // is already chosen via getElementKeyPoints - no arbitrary edge points for either
            // end of a point-to-point move) and must not snap to the very object being moved -
            // otherwise, picking a target near another object can lock onto one of the
            // source object's own key points instead (whichever is closer), producing a
            // delta that lands the two points close together but not exactly coincident.
            // (Step 1, picking the "From" point, does its own separate search restricted
            // to the selected element's key points, so excluding it here doesn't affect that.)
            val bestSnap = if (_state.value.currentTool == DrawingTool.MOVE_PRECISE) {
                findKeyPointSnap(offset, excludeId = _state.value.selectedElementId)
            } else {
                findSnapPoint(offset)
            }
            if (bestSnap != offset) {
                snappedOffset = bestSnap
                snapFound = true
            }
        }
        
        startOffset = snappedOffset
        lastOffset = snappedOffset
        val color = _state.value.currentColor
        val width = _state.value.currentStrokeWidth

        _state.value = _state.value.copy(lastSnapPoint = if (snapFound) snappedOffset else null)

        when (_state.value.currentTool) {
            DrawingTool.ERASER -> eraseAt(offset)
            DrawingTool.PEN, DrawingTool.MARKER, DrawingTool.PENCIL -> {
                currentPoints = mutableListOf(DrawingPoint(snappedOffset, pressure))
                val isHighlighter = _state.value.currentTool == DrawingTool.MARKER
                val isPencil = _state.value.currentTool == DrawingTool.PENCIL
                val strokeWidth = when {
                    isHighlighter -> width * 4f
                    isPencil -> width * 0.5f
                    else -> width
                }
                _state.value = _state.value.copy(
                    currentDrawingElement = DrawingElement.Freehand(
                        points = currentPoints.toList(), 
                        color = color, 
                        strokeWidth = strokeWidth,
                        isHighlighter = isHighlighter || isPencil // Pencil also uses transparency logic
                    )
                )
            }
            DrawingTool.LINE -> {
                _state.value = _state.value.copy(
                    currentDrawingElement = DrawingElement.Line(start = snappedOffset, end = snappedOffset, color = color, strokeWidth = width)
                )
            }
            DrawingTool.RECTANGLE -> {
                _state.value = _state.value.copy(
                    currentDrawingElement = DrawingElement.Rectangle(topLeft = snappedOffset, size = Size.Zero, color = color, strokeWidth = width)
                )
            }
            DrawingTool.OVAL -> {
                _state.value = _state.value.copy(
                    currentDrawingElement = DrawingElement.Oval(topLeft = snappedOffset, size = Size.Zero, color = color, strokeWidth = width)
                )
            }
            DrawingTool.TRIANGLE -> {
                _state.value = _state.value.copy(
                    currentDrawingElement = DrawingElement.Triangle(topLeft = snappedOffset, size = Size.Zero, color = color, strokeWidth = width)
                )
            }
            DrawingTool.CIRCLE -> {
                _state.value = _state.value.copy(
                    currentDrawingElement = DrawingElement.Circle(center = snappedOffset, radius = 0f, color = color, strokeWidth = width)
                )
            }
            DrawingTool.SELECT -> {
                // A tap on a room's "Name this room" tag opens the box to name it (RoomNames, Nov 28).
                RoomNames.tagAt(offset, _state.value)?.let { room ->
                    _state.value = _state.value.copy(namingRoom = room.center, selectedElementId = null, selectedElementIds = emptySet())
                    return
                }
                val groupIds = _state.value.selectedElementIds
                if (groupIds.size > 1) {
                    // A box-selected group is active. Touching one of its members starts a group
                    // move; touching empty space starts a fresh box-select (replacing the group);
                    // touching a different single element collapses back to single-selection -
                    // all standard behavior for a CAD-style group selection.
                    val hitElement = findHitElement(offset, resizeThreshold)
                    isResizing = false
                    isRotating = false
                    moveBasePoint = null
                    if (hitElement != null && hitElement.id in groupIds) {
                        // Keep the group selected; moveDrawing applies the drag to all of it.
                        // From the raw press, so the group doesn't jump by the snap distance.
                        lastOffset = offset
                    } else if (hitElement is DrawingElement.Pdf) {
                        // A drag over a plan box-selects what is on it; a tap selects the plan.
                        planTapCandidate = hitElement.id
                        _state.value = _state.value.copy(
                            selectedElementId = null,
                            selectedElementIds = emptySet(),
                            boxSelectStart = snappedOffset,
                            boxSelectEnd = snappedOffset
                        )
                    } else if (hitElement != null) {
                        selectWithGroup(hitElement.id)
                        lastOffset = offset
                    } else {
                        _state.value = _state.value.copy(
                            selectedElementId = null,
                            selectedElementIds = emptySet(),
                            boxSelectStart = snappedOffset,
                            boxSelectEnd = snappedOffset
                        )
                    }
                    return
                }

                val selectedId = _state.value.selectedElementId
                val element = _state.value.elements.find { it.id == selectedId }
                // Resize/rotate handles get a tighter capture radius than plain element
                // selection. resizeThreshold (48dp) is sized for comfortably tapping an element
                // with a fingertip, but on a short element (e.g. a 5-6" Line, common right after
                // Explode) that same 48dp radius around its single endpoint handle can cover the
                // entire element - so every later tap meant as "just re-select/tap this" instead
                // grabs the handle and nudges it, confirmed via a field recording where a short
                // Line's length/angle kept mutating (5.50in/0deg -> 1.36in/87deg -> 5.50in/180deg
                // - flipped end-for-end) purely from what were meant as plain taps. A deliberate
                // handle-grab still needs to land close to the actual handle; a looser tap
                // anywhere else on the element now falls through to plain select/move instead.
                val handleThreshold = resizeThreshold * 0.5f

                // Handle checks and hit-tests below use the RAW tap (offset), never snappedOffset.
                // Snapping exists to place geometry, not to decide what was tapped: with object
                // snap on, a tap within ~20px of ANOTHER element's corner was pulled onto that
                // corner before hit-testing, so the element actually under the pen lost to its
                // neighbour, and a tap meant to grab a resize handle could be pulled off it.
                if (element != null && isNearRotationHandle(element, offset, handleThreshold)) {
                    isRotating = true
                    isResizing = false
                } else if (element is DrawingElement.Dimension && (element.start - offset).getDistance() < handleThreshold) {
                    // Dimension's "end" handle is covered by isNearResizeHandle below; its
                    // "start" point needs its own check since every other element type only
                    // ever exposes one draggable handle.
                    isResizing = true
                    isRotating = false
                    resizingDimensionStart = true
                    resizingDimensionOffset = false
                } else if (element is DrawingElement.Dimension && run {
                        val (dimStart, dimEnd) = element.dimLineEndpoints()
                        (((dimStart + dimEnd) / 2f) - offset).getDistance() < handleThreshold
                    }) {
                    // Third grip, at the midpoint of the visible dimension line itself (not a
                    // measured point) - dragging it slides the dimension line/text clear of the
                    // drawing without touching what's actually being measured.
                    isResizing = true
                    isRotating = false
                    resizingDimensionStart = false
                    resizingDimensionOffset = true
                } else if (element != null && isNearResizeHandle(element, offset, handleThreshold)) {
                    isResizing = true
                    isRotating = false
                    resizingDimensionStart = false
                    resizingDimensionOffset = false
                } else {
                    val hitElement = findHitElement(offset, resizeThreshold)
                    if (hitElement != null && hitElement.id == selectedId) {
                        // Keep current selection and allow moving
                        isResizing = false
                        isRotating = false
                        moveBasePoint = null // clear any stale point from a prior Move-tool drag
                        // A selected plan carries the objects drawn on it.
                        if (hitElement is DrawingElement.Pdf) startCarrying(hitElement, offset)
                    } else if (hitElement is DrawingElement.Pdf) {
                        // An unselected plan: a drag box-selects over it, a tap selects it.
                        isResizing = false
                        isRotating = false
                        moveBasePoint = null
                        planTapCandidate = hitElement.id
                        _state.value = _state.value.copy(
                            selectedElementId = null,
                            boxSelectStart = snappedOffset,
                            boxSelectEnd = snappedOffset
                        )
                    } else if (hitElement != null) {
                        // New selection (the whole group when it is in one)
                        isResizing = false
                        isRotating = false
                        moveBasePoint = null
                        selectWithGroup(hitElement.id)
                        if (_state.value.selectedElementIds.size > 1) lastOffset = offset
                    } else {
                        // Empty space - start a box-select drag instead of deselecting
                        // immediately; endDrawing() decides between "was just a tap, deselect"
                        // and "was a real drag, select everything inside the box" once it knows
                        // whether the pointer actually moved.
                        isResizing = false
                        isRotating = false
                        moveBasePoint = null
                        _state.value = _state.value.copy(
                            selectedElementId = null,
                            boxSelectStart = snappedOffset,
                            boxSelectEnd = snappedOffset
                        )
                    }
                }
            }
            DrawingTool.MOVE -> {
                val selectedId = _state.value.selectedElementId
                val element = _state.value.elements.find { it.id == selectedId }

                if (_state.value.selectedElementIds.size > 1) {
                    // A selected group: the drag moves all of it (moveDrawing's group branch).
                    moveBasePoint = null
                    isResizing = false
                    isRotating = false
                    lastOffset = offset
                } else if (element is DrawingElement.Pdf) {
                    // A plan carries what is drawn on it.
                    moveBasePoint = null
                    isResizing = false
                    isRotating = false
                    startCarrying(element, offset)
                } else if (element != null) {
                    val keyPoints = getElementKeyPoints(element)
                    // Base-point pick and selection use the RAW tap, not snappedOffset - see the
                    // note in the SELECT branch.
                    val closest = keyPoints.minByOrNull { (it - offset).getDistance() }
                    if (closest != null && (closest - offset).getDistance() < resizeThreshold) {
                        moveBasePoint = closest
                    } else {
                        moveBasePoint = null
                    }
                    isResizing = false
                    isRotating = false
                } else {
                    val hitElement = findHitElement(offset, resizeThreshold)
                    _state.value = _state.value.copy(selectedElementId = hitElement?.id)
                    moveBasePoint = null
                    isResizing = false
                    isRotating = false
                }
            }
            DrawingTool.MOVE_PRECISE -> {
                val selectedId = _state.value.selectedElementId
                val selectedElement = _state.value.elements.find { it.id == selectedId }
                
                if (selectedElement != null) {
                    if (_state.value.preciseMoveFromPoint == null) {
                        // Step 1: Select "From" point on the object. Prefer an exact key point
                        // (corner/edge-midpoint) when the tap lands close enough to one;
                        // otherwise, as long as the tap landed on the object at all, fall back
                        // to the nearest point on its boundary. Center is deliberately not a key
                        // point for Lumber/Plywood (it would let this move re-create the
                        // centerline-overlap bug), but a tap in the middle of a small symbol -
                        // the natural place to tap it - must still grab something instead of
                        // silently doing nothing.
                        val keyPoints = getElementKeyPoints(selectedElement)
                        val closest = keyPoints.minByOrNull { (it - offset).getDistance() }
                        val fromPoint = if (closest != null && (closest - offset).getDistance() < resizeThreshold) {
                            closest
                        } else if (selectedElement.contains(offset, resizeThreshold)) {
                            getNearestPointOnElement(offset, selectedElement)
                        } else null
                        if (fromPoint != null) {
                            _state.value = _state.value.copy(preciseMoveFromPoint = fromPoint)
                        }
                    } else {
                        // Step 2: Select "To" point on target
                        val fromPoint = _state.value.preciseMoveFromPoint!!
                        val delta = resolvePreciseMoveDelta(selectedElement, fromPoint, snappedOffset)
                        pushUndoSnapshot()
                        _state.value = _state.value.copy(
                            elements = _state.value.elements.map {
                                if (it.id == selectedId || it.isOwnedBy(selectedId)) it.move(delta) else it
                            },
                            preciseMoveFromPoint = null
                        )
                        triggerAutoSave()
                    }
                } else {
                    // Try to select an object first
                    val hitElement = findHitElement(offset, resizeThreshold)
                    _state.value = _state.value.copy(selectedElementId = hitElement?.id)
                }
            }
            DrawingTool.DIMENSION -> {
                // Phase 1 (picking the two measured points) starts a fresh Dimension on touch
                // down, same as every other draw tool. Phase 2 (awaitingDimensionOffset) is a
                // SEPARATE touch after phase 1 already committed start/end into
                // currentDrawingElement below in endDrawing() - leave it alone here so this
                // down event doesn't stomp the points just picked; moveDrawing/endDrawing handle
                // previewing and committing the offset from here.
                if (!_state.value.awaitingDimensionOffset) {
                    dimensionTapRaw = offset
                    _state.value = _state.value.copy(
                        currentDrawingElement = DrawingElement.Dimension(
                            start = snappedOffset,
                            end = snappedOffset,
                            value = 0f,
                            unit = _state.value.unitSystem,
                            color = color
                        )
                    )
                }
            }
            DrawingTool.TEXT -> {
                val element = DrawingElement.Text(
                    text = "Double tap to edit",
                    topLeft = snappedOffset,
                    fontSize = 24f,
                    color = color
                )
                pushUndoSnapshot()
                _state.value = _state.value.copy(
                    elements = _state.value.elements + element,
                    selectedElementId = element.id
                )
            }
            // Trim/Extend are single-tap actions, not draw-a-preview-then-release tools like
            // everything above - they act immediately on touch down and have nothing to do on
            // Move/End, so there's no corresponding case for either in moveDrawing/endDrawing.
            DrawingTool.TRIM -> trimAt(offset, resizeThreshold)
            DrawingTool.EXTEND -> extendAt(offset, resizeThreshold)
            else -> {}
        }
    }

    private fun isNearRotationHandle(element: DrawingElement, offset: Offset, threshold: Float): Boolean {
        val zoom = _state.value.zoom
        val rawHandlePos = when (element) {
            is DrawingElement.Rectangle -> element.topLeft + Offset(element.size.width / 2f, -40f / zoom)
            is DrawingElement.Image -> element.topLeft + Offset(element.size.width / 2f, -40f / zoom)
            is DrawingElement.Symbol -> element.center + Offset(0f, -element.size.height / 2f - 40f / zoom)
            is DrawingElement.Oval -> element.topLeft + Offset(element.size.width / 2f, -40f / zoom)
            is DrawingElement.Triangle -> element.topLeft + Offset(element.size.width / 2f, -40f / zoom)
            else -> return false
        }
        
        val center = getElementCenter(element)
        val rotatedHandlePos = if (element.rotation != 0f) rotatePoint(rawHandlePos, center, element.rotation) else rawHandlePos
        
        return (offset - rotatedHandlePos).getDistance() < threshold
    }

    private fun isNearResizeHandle(element: DrawingElement, offset: Offset, threshold: Float): Boolean {
        val rawHandlePos = when (element) {
            is DrawingElement.Rectangle -> element.topLeft + Offset(element.size.width, element.size.height)
            is DrawingElement.Image -> element.topLeft + Offset(element.size.width, element.size.height)
            is DrawingElement.Pdf -> element.topLeft + Offset(element.size.width, element.size.height)
            is DrawingElement.Line -> element.end
            is DrawingElement.Dimension -> element.end
            is DrawingElement.Circle -> element.center + Offset(element.radius, 0f)
            is DrawingElement.Symbol -> element.center + Offset(element.size.width / 2f, element.size.height / 2f)
            is DrawingElement.Oval -> element.topLeft + Offset(element.size.width, element.size.height)
            is DrawingElement.Triangle -> element.topLeft + Offset(element.size.width, element.size.height)
            else -> return false
        }
        
        val center = getElementCenter(element)
        val rotatedHandlePos = if (element.rotation != 0f) rotatePoint(rawHandlePos, center, element.rotation) else rawHandlePos
        
        return (offset - rotatedHandlePos).getDistance() < threshold
    }

    private fun getElementCenter(element: DrawingElement): Offset {
        return when (element) {
            is DrawingElement.Rectangle -> element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
            is DrawingElement.Oval -> element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
            is DrawingElement.Triangle -> element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
            is DrawingElement.Symbol -> element.center
            is DrawingElement.Circle -> element.center
            is DrawingElement.Line -> element.start + (element.end - element.start) / 2f
            is DrawingElement.Dimension -> element.start + (element.end - element.start) / 2f
            is DrawingElement.Text -> element.topLeft // Simplified
            is DrawingElement.Image -> element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
            is DrawingElement.Pdf -> element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
            else -> Offset.Zero
        }
    }

    private fun moveDrawing(offset: Offset, pressure: Float) {
        if (_state.value.currentTool == DrawingTool.SELECT && _state.value.boxSelectStart != null) {
            // Rubber-band box-select in progress - just track the live drag rectangle here;
            // endDrawing() resolves which elements it covers once the pointer lifts.
            _state.value = _state.value.copy(boxSelectEnd = offset)
            return
        }
        if (carriedIds.isNotEmpty() && !isResizing && !isRotating &&
            (_state.value.currentTool == DrawingTool.SELECT || _state.value.currentTool == DrawingTool.MOVE)) {
            // Dragging a plan: it and everything on it move by the same raw delta, unsnapped
            // (snapping the plan onto a nearby corner would drag the work drawn on it off true).
            val delta = offset - lastOffset
            lastOffset = offset
            if (!hasSnapshotThisGesture) {
                pushUndoSnapshot()
                hasSnapshotThisGesture = true
            }
            _state.value = _state.value.copy(
                elements = _state.value.elements.map { if (it.id in carriedIds) it.move(delta) else it }
            )
            return
        }
        val groupIds = _state.value.selectedElementIds
        if ((_state.value.currentTool == DrawingTool.SELECT || _state.value.currentTool == DrawingTool.MOVE) && groupIds.size > 1) {
            // Group move: every box-selected element slides together by the same raw delta.
            // No object-snapping here, same reasoning as dimension-drag - snapping one member of
            // a multi-object drag onto some unrelated nearby corner would fight the others.
            val delta = offset - lastOffset
            lastOffset = offset
            if (!hasSnapshotThisGesture) {
                pushUndoSnapshot()
                hasSnapshotThisGesture = true
            }
            _state.value = _state.value.copy(
                elements = _state.value.elements.map { if (it.id in groupIds && !it.isLockedPlan) it.move(delta) else it }
            )
            return
        }
        val selectedId = _state.value.selectedElementId
        // Dragging a selected object (not resizing/rotating it) should snap the OBJECT's own
        // edges/corners into alignment with other objects, same as the dedicated Move tool -
        // otherwise "snapping objects together" only works if the finger/stylus itself happens
        // to land exactly on a corner, which is what most users hit when they just drag with Select.
        val isPlainObjectMove = _state.value.currentTool == DrawingTool.MOVE ||
            (_state.value.currentTool == DrawingTool.SELECT && !isResizing && !isRotating && selectedId != null)
        // Dragging ANY element's own resize or rotation handle is exempt from the general "snap
        // to the nearest point on ANY element" search below - not just Dimension's. This was
        // originally scoped to Dimension only, but the exact same hijacking happens for a plain
        // Line/Rectangle/etc.'s resize handle: it's normally dragged right next to the geometry
        // it's being sized against, so that area is thick with corner/edge/grid snap candidates,
        // and since resize applies its delta incrementally (new end = old end + delta), a single
        // frame where the search grabs the wrong nearby point doesn't just wobble - it
        // permanently shifts the endpoint away from the real pointer, and every subsequent frame
        // compounds on top of that shifted position. That's what "grab the handle with the S Pen
        // but it wanders off and doesn't stay attached" turned out to be: confirmed via a field
        // recording where the on-screen touch indicator and the dragged endpoint drift further
        // and further apart over the course of one continuous drag, not just at the start.
        val isHandleDrag = isResizing || isRotating
        // Freehand tools are exempt from the same general snap search, for the same reason -
        // every point of a Pen/Pencil/Marker/Eraser stroke was being tested against every other
        // element's corners and the grid, so a stroke drawn anywhere near existing geometry (the
        // normal case - sketching notes ON a framing drawing) kept hopping onto whichever corner
        // or grid line was nearest at that instant instead of following the actual touch/stylus
        // path. Unlike Line/Rectangle/Dimension placement, a freehand stroke is never meant to
        // align precisely with anything - snapping only fights it.
        val isFreehandTool = _state.value.currentTool in setOf(DrawingTool.PEN, DrawingTool.PENCIL, DrawingTool.MARKER, DrawingTool.ERASER)
        var snappedOffset = offset
        var snapFound = false
        if (_state.value.snapEnabled && !isPlainObjectMove && !isHandleDrag && !isFreehandTool) {
            val bestSnap = findSnapPoint(offset, excludeId = selectedId)
            if (bestSnap != offset) {
                snappedOffset = bestSnap
                snapFound = true
            }
        }

        val color = _state.value.currentColor
        val width = _state.value.currentStrokeWidth
        val delta = if (_state.value.snapEnabled && isPlainObjectMove) {
             val selectedElement = _state.value.elements.find { it.id == selectedId }
             if (selectedElement != null) {
                 // Snap is evaluated from where the pointer ALONE would have put the object this
                 // frame, not from where the previous frame's snap left it. The object's current
                 // position already includes moveSnapCorrection (the sum of snap adjustments
                 // applied so far this gesture), so subtracting it from the raw delta first
                 // brings the object back under the pointer; then the snap search either
                 // re-grabs a target (and a fresh correction is recorded) or finds nothing and
                 // the object simply follows the pen. Without this, every snap adjustment was
                 // baked permanently into the object's position: once it snapped onto a corner
                 // and the pen kept going, the object trailed the pen by the snap distance for
                 // the rest of the drag, and each later snap stacked another offset on top.
                 val unsnappedDelta = offset - lastOffset - moveSnapCorrection
                 val result = findObjectSnapDelta(selectedElement, unsnappedDelta, moveBasePoint)
                 if (result.first != null) {
                     snapFound = true
                     // Target point for visual indicator
                     snappedOffset = result.second
                 }
                 val applied = result.first ?: unsnappedDelta
                 moveSnapCorrection = applied - unsnappedDelta
                 applied
             } else offset - lastOffset
        } else {
             snappedOffset - lastOffset
        }
        
        // Update moveBasePoint if we are moving
        if (_state.value.currentTool == DrawingTool.MOVE) {
            moveBasePoint = moveBasePoint?.let { it + delta }
        }
        
        lastOffset = offset // Always track the RAW offset to avoid delta errors

        _state.value = _state.value.copy(lastSnapPoint = if (snapFound) snappedOffset else null)

        when (_state.value.currentTool) {
            DrawingTool.ERASER -> eraseAt(offset)
            DrawingTool.PEN, DrawingTool.MARKER, DrawingTool.PENCIL -> {
                // Mirrors startDrawing's PEN/ERASER/MARKER/PENCIL case exactly - Marker and
                // Pencil were missing from this branch entirely, so dragging with either tool
                // never grew the stroke past its starting point (they fell through to the
                // generic `else -> {}` below every Move event) and, had they been grouped in with
                // plain PEN/ERASER without also copying this width/highlighter logic, would have
                // drawn at the wrong width and without the pencil/marker transparency treatment.
                currentPoints.add(DrawingPoint(snappedOffset, pressure))
                val isHighlighter = _state.value.currentTool == DrawingTool.MARKER
                val isPencil = _state.value.currentTool == DrawingTool.PENCIL
                val strokeWidth = when {
                    isHighlighter -> width * 4f
                    isPencil -> width * 0.5f
                    else -> width
                }
                _state.value = _state.value.copy(
                    currentDrawingElement = DrawingElement.Freehand(
                        points = currentPoints.toList(),
                        color = color,
                        strokeWidth = strokeWidth,
                        isHighlighter = isHighlighter || isPencil
                    )
                )
            }
            DrawingTool.LINE -> {
                _state.value = _state.value.copy(
                    currentDrawingElement = DrawingElement.Line(start = startOffset, end = snappedOffset, color = color, strokeWidth = width)
                )
            }
            DrawingTool.RECTANGLE -> {
                val topLeft = Offset(
                    minOf(startOffset.x, snappedOffset.x),
                    minOf(startOffset.y, snappedOffset.y)
                )
                val size = Size(
                    abs(startOffset.x - snappedOffset.x),
                    abs(startOffset.y - snappedOffset.y)
                )
                _state.value = _state.value.copy(
                    currentDrawingElement = DrawingElement.Rectangle(topLeft = topLeft, size = size, color = color, strokeWidth = width)
                )
            }
            DrawingTool.OVAL -> {
                val topLeft = Offset(
                    minOf(startOffset.x, snappedOffset.x),
                    minOf(startOffset.y, snappedOffset.y)
                )
                val size = Size(
                    abs(startOffset.x - snappedOffset.x),
                    abs(startOffset.y - snappedOffset.y)
                )
                _state.value = _state.value.copy(
                    currentDrawingElement = DrawingElement.Oval(topLeft = topLeft, size = size, color = color, strokeWidth = width)
                )
            }
            DrawingTool.TRIANGLE -> {
                val topLeft = Offset(
                    minOf(startOffset.x, snappedOffset.x),
                    minOf(startOffset.y, snappedOffset.y)
                )
                val size = Size(
                    abs(startOffset.x - snappedOffset.x),
                    abs(startOffset.y - snappedOffset.y)
                )
                _state.value = _state.value.copy(
                    currentDrawingElement = DrawingElement.Triangle(topLeft = topLeft, size = size, color = color, strokeWidth = width)
                )
            }
            DrawingTool.CIRCLE -> {
                val radius = sqrt(
                    (snappedOffset.x - startOffset.x) * (snappedOffset.x - startOffset.x) +
                            (snappedOffset.y - startOffset.y) * (snappedOffset.y - startOffset.y)
                )
                _state.value = _state.value.copy(
                    currentDrawingElement = DrawingElement.Circle(center = startOffset, radius = radius, color = color, strokeWidth = width)
                )
            }
            DrawingTool.SELECT -> {
                val selectedId = _state.value.selectedElementId
                if (selectedId != null) {
                    if (!hasSnapshotThisGesture) {
                        pushUndoSnapshot()
                        hasSnapshotThisGesture = true
                    }
                    _state.value = _state.value.copy(
                        elements = _state.value.elements.map {
                            if (it.id == selectedId) {
                                when {
                                    isRotating -> {
                                        // Simple rotation logic: angle between center and current offset
                                        val center = when(it) {
                                            is DrawingElement.Rectangle -> it.topLeft + Offset(it.size.width / 2, it.size.height / 2)
                                            is DrawingElement.Oval -> it.topLeft + Offset(it.size.width / 2, it.size.height / 2)
                                            is DrawingElement.Triangle -> it.topLeft + Offset(it.size.width / 2, it.size.height / 2)
                                            is DrawingElement.Symbol -> it.center
                                            else -> Offset.Zero
                                        }
                                        val angle = com.example.sitedraft.util.toDegrees(atan2((snappedOffset.y - center.y).toDouble(), (snappedOffset.x - center.x).toDouble())).toFloat() + 90f
                                        it.rotate(angle - it.rotation)
                                    }
                                    isResizing && resizingDimensionStart && it is DrawingElement.Dimension -> {
                                        val newStart = it.start + delta
                                        val newValue = (it.end - newStart).getDistance() / _state.value.scale.ratio
                                        it.copy(start = newStart, value = newValue)
                                    }
                                    isResizing && resizingDimensionOffset && it is DrawingElement.Dimension -> {
                                        val segDir = it.end - it.start
                                        val len = segDir.getDistance()
                                        if (len > 0.0001f) {
                                            val unit = segDir / len
                                            val perp = Offset(-unit.y, unit.x)
                                            val deltaOffset = delta.x * perp.x + delta.y * perp.y
                                            it.copy(offset = it.offset + deltaOffset)
                                        } else it
                                    }
                                    isResizing -> it.resize(delta, _state.value.scale.ratio, _state.value.unitSystem)
                                    else -> it.move(delta)
                                }
                            } else if (!isResizing && !isRotating && it.isOwnedBy(selectedId)) it.move(delta) else it
                        }
                    )
                }
            }
            DrawingTool.MOVE -> {
                val selectedId = _state.value.selectedElementId
                if (selectedId != null) {
                    if (!hasSnapshotThisGesture) {
                        pushUndoSnapshot()
                        hasSnapshotThisGesture = true
                    }
                    _state.value = _state.value.copy(
                        elements = _state.value.elements.map {
                            if (it.id == selectedId || it.isOwnedBy(selectedId)) it.move(delta) else it
                        }
                    )
                }
            }
            DrawingTool.DIMENSION -> {
                val current = _state.value.currentDrawingElement as? DrawingElement.Dimension
                current?.let {
                    if (_state.value.awaitingDimensionOffset) {
                        // Phase 2: how far (and to which side) to offset the dimension line from
                        // the measured segment. Uses the raw pointer, not snappedOffset - object
                        // snapping is meaningless for a perpendicular distance.
                        val segDir = it.end - it.start
                        val len = segDir.getDistance()
                        if (len > 0.0001f) {
                            val unit = segDir / len
                            val perp = Offset(-unit.y, unit.x)
                            val proj = (offset.x - it.start.x) * perp.x + (offset.y - it.start.y) * perp.y
                            _state.value = _state.value.copy(currentDrawingElement = it.copy(offset = proj))
                        }
                    } else {
                        val dist = (snappedOffset - it.start).getDistance()
                        val realValue = dist / _state.value.scale.ratio
                        _state.value = _state.value.copy(
                            currentDrawingElement = it.copy(end = snappedOffset, value = realValue)
                        )
                    }
                }
            }
            else -> {}
        }
    }

    // Stroke eraser: removes any freehand stroke (pen, pencil, marker) the pointer passes over.
    // The previous "eraser" painted white strokes, which on the Blueprint canvas drew bright
    // white lines over everything instead of removing anything. Shapes, symbols, text and
    // dimensions are deliberately left alone - Select + Delete is how those are removed.
    // Hit-tests against stroke SEGMENTS, not just recorded points, so a fast stroke with
    // widely spaced points can still be erased between them.
    private fun eraseAt(p: Offset) {
        val radius = (12f + _state.value.currentStrokeWidth) / _state.value.zoom
        val hits = _state.value.elements.filter { element ->
            element is DrawingElement.Freehand && element.points.size >= 1 && (
                element.points.any { (it.offset - p).getDistance() < radius } ||
                    element.points.zipWithNext().any { (a, b) -> (getNearestPointOnSegment(p, a.offset, b.offset) - p).getDistance() < radius }
                )
        }
        if (hits.isEmpty()) return
        if (!hasSnapshotThisGesture) {
            pushUndoSnapshot()
            hasSnapshotThisGesture = true
        }
        _state.value = _state.value.copy(elements = _state.value.elements - hits.toSet(), selectedElementId = null)
        triggerAutoSave()
    }

    // Momentary eraser for the S Pen side button. Remembers the tool in use when the button
    // went down and restores it on release; if the eraser was already the active tool there
    // is nothing to switch, so release leaves it alone.
    private fun stylusButtonErase(active: Boolean) {
        if (active) {
            if (toolBeforeStylusErase == null && _state.value.currentTool != DrawingTool.ERASER) {
                toolBeforeStylusErase = _state.value.currentTool
                _state.value = _state.value.copy(currentTool = DrawingTool.ERASER)
            }
        } else {
            toolBeforeStylusErase?.let { previous ->
                _state.value = _state.value.copy(currentTool = previous)
            }
            toolBeforeStylusErase = null
        }
    }

    // Used by the laser wall mode: one measurement becomes one wall segment.
    private fun addLine(start: Offset, end: Offset) {
        pushUndoSnapshot()
        val line = DrawingElement.Line(start = start, end = end, color = _state.value.currentColor, strokeWidth = _state.value.currentStrokeWidth)
        _state.value = _state.value.copy(elements = _state.value.elements + line, selectedElementId = line.id)
        triggerAutoSave()
    }

    // ---- Laser wall mode (see WallMode.kt) ----
    private fun setWallCursor(offset: Offset) {
        val p = if (_state.value.snapEnabled) findSnapPoint(offset) else offset
        _state.value = _state.value.copy(wallMode = _state.value.wallMode.copy(cursor = p))
    }

    private fun setWallStatus(status: WallStatus) {
        val s = _state.value
        val ids = s.selectedElementIds.ifEmpty { setOfNotNull(s.selectedElementId) }
        val walls = s.elements.filter { it.id in ids && Remodel.canHaveStatus(it) }
        if (walls.isEmpty()) { notify("Select a wall or framing first"); return }
        pushUndoSnapshot()
        _state.value = _state.value.copy(elements = _state.value.elements.map { e ->
            if (e.id in ids && Remodel.canHaveStatus(e)) Remodel.withStatus(e as DrawingElement.Symbol, status) else e
        })
        triggerAutoSave()
        if (walls.size > 1) notify("${walls.size} set to ${status.label}")
    }

    private fun demoWallPart(event: DrawingEvent.DemoWallPart) {
        val s = _state.value
        val wall = s.elements.firstOrNull { it.id == event.wallId } as? DrawingElement.Symbol ?: return
        val r = Remodel.splitForDemo(wall, event.fromIn, event.lengthIn, event.fromStart, s)
        val pieces = r.getOrElse { notify(it.message ?: "Couldn't split that wall"); return }
        pushUndoSnapshot()
        val i = s.elements.indexOfFirst { it.id == wall.id }
        val out = s.elements.toMutableList().apply { removeAt(i); addAll(i, pieces) }
        val demo = pieces.first { Remodel.status(it) == WallStatus.DEMO }
        _state.value = _state.value.copy(elements = out, selectedElementId = demo.id, selectedElementIds = emptySet())
        triggerAutoSave()
        notify("Wall split: the demo piece is selected")
    }

    private fun addWall(lengthPx: Float) {
        val wall = _state.value.wallMode
        val start = wall.cursor ?: return
        if (lengthPx <= 0f) return
        val end = start + wall.direction.unit * lengthPx
        addLine(start, end)
        _state.value = _state.value.copy(wallMode = _state.value.wallMode.copy(cursor = end, roomStart = wall.roomStart ?: start))
    }

    private fun closeRoom() {
        val wall = _state.value.wallMode
        val from = wall.cursor ?: return
        val to = wall.roomStart ?: return
        if ((from - to).getDistance() < 0.5f) return
        addLine(from, to)
        _state.value = _state.value.copy(wallMode = _state.value.wallMode.copy(cursor = to))
    }

    private fun continueWallsFromSelectedLine() {
        val line = _state.value.elements.find { it.id == _state.value.selectedElementId } as? DrawingElement.Line ?: return
        _state.value = _state.value.copy(
            wallMode = _state.value.wallMode.copy(active = true, cursor = line.end, roomStart = _state.value.wallMode.roomStart ?: line.start)
        )
    }

    private fun toggleEraser() {
        val newTool = if (_state.value.currentTool == DrawingTool.ERASER) DrawingTool.PEN else DrawingTool.ERASER
        _state.value = _state.value.copy(currentTool = newTool, selectedElementId = null)
    }

    private fun endDrawing() {
        // A Move drag that moved something: its squared direction and distance, for "make that exactly ___" (Nov 27).
        run {
            val start = moveGestureStart ?: return@run
            val sel = _state.value.selectedElementId ?: return@run
            if (!hasSnapshotThisGesture) return@run
            val before = moveGestureBefore?.firstOrNull { it.id == sel } ?: return@run
            val after = _state.value.elements.firstOrNull { it.id == sel } ?: return@run
            val d = elementAnchor(after) - elementAnchor(before)
            if (d.getDistance() < 1f) return@run
            val dir = if (kotlin.math.abs(d.x) >= kotlin.math.abs(d.y)) Offset(if (d.x > 0) 1f else -1f, 0f) else Offset(0f, if (d.y > 0) 1f else -1f)
            // A square wall dragged on its own (Dan, Nov 27): the walls meeting it stretch to stay on it and its doors and
            // windows go with it, as a typed move does. The drag is redone from where it started, the same distance.
            val base = moveGestureBefore
            val others = _state.value.selectedElementIds - sel
            if (base != null && others.isEmpty() && before is DrawingElement.Symbol && before.category == PlanSymbols.WALLS && before.rotation == 0f) {
                _state.value = _state.value.copy(elements = moveTyped(setOf(sel), d, base))
            }
            // A return grille dropped back onto the stud/joist layout after a drag (Mep.alignToFraming).
            if (after is DrawingElement.Symbol && after.symbolName.startsWith("Return Grille")) {
                val aligned = Mep.alignToFraming(after, _state.value)
                if (aligned.center != after.center) _state.value = _state.value.copy(elements = _state.value.elements.map { if (it.id == sel) aligned else it })
            }
            lastDragElements = moveGestureBefore
            _state.value = _state.value.copy(lastMoveDir = dir, lastMoveDistanceIn = kotlin.math.abs(if (dir.x != 0f) d.x else d.y) / _state.value.inchesToPx(1f))
        }
        moveGestureStart = null
        val boxStart = _state.value.boxSelectStart
        if (_state.value.currentTool == DrawingTool.SELECT && boxStart != null) {
            val boxEnd = _state.value.boxSelectEnd ?: boxStart
            val dragDist = (boxEnd - boxStart).getDistance()
            val tappedPlan = planTapCandidate
            planTapCandidate = null
            if (dragDist < 5f && tappedPlan != null) {
                // A tap (not a drag) on an unselected plan: select it.
                _state.value = _state.value.copy(boxSelectStart = null, boxSelectEnd = null)
                selectWithGroup(tappedPlan)
                return
            }
            _state.value = if (dragDist < 5f) {
                // Negligible movement - this was a tap on empty space, not a drag. Plain deselect.
                _state.value.copy(
                    selectedElementId = null,
                    selectedElementIds = emptySet(),
                    boxSelectStart = null,
                    boxSelectEnd = null
                )
            } else {
                val selectionRect = Rect(
                    minOf(boxStart.x, boxEnd.x), minOf(boxStart.y, boxEnd.y),
                    maxOf(boxStart.x, boxEnd.x), maxOf(boxStart.y, boxEnd.y)
                )
                // A plan is only taken when the box covers all of it: a box drawn ON a plan
                // means "the things on it", not the sheet underneath. Whole groups come along.
                val hitIds = expandGroups(
                    liveElements()
                        .filter { el ->
                            if (el.isLockedPlan) false
                            else if (el is DrawingElement.Pdf) el.boundingRect().let { r -> selectionRect.contains(r.topLeft) && selectionRect.contains(r.bottomRight) }
                            else el.boundingRect().overlaps(selectionRect)
                        }
                        .mapTo(LinkedHashSet()) { it.id }
                ).filterTo(LinkedHashSet()) { id -> _state.value.elements.find { it.id == id }?.isLockedPlan != true }
                if (hitIds.size <= 1) {
                    _state.value.copy(
                        selectedElementId = hitIds.firstOrNull(),
                        selectedElementIds = emptySet(),
                        boxSelectStart = null,
                        boxSelectEnd = null
                    )
                } else {
                    _state.value.copy(
                        selectedElementId = null,
                        selectedElementIds = hitIds,
                        boxSelectStart = null,
                        boxSelectEnd = null
                    )
                }
            }
            return
        }
        val current = _state.value.currentDrawingElement
        if (current is DrawingElement.Dimension && _state.value.currentTool == DrawingTool.DIMENSION) {
            if (!_state.value.awaitingDimensionOffset) {
                // Phase 1 (picking the two measured points) just finished. A real dimension
                // isn't committed yet - it still needs phase 2, a separate touch to place the
                // offset dimension line. A negligible drag is an accidental tap, not an intent
                // to place a dimension - cancel it outright instead of leaving a stuck
                // zero-length dimension waiting on a phase 2 that was never intended.
                val dist = (current.end - current.start).getDistance()
                val previous = lastPlacedDimensionId?.let { id -> _state.value.elements.find { it.id == id } as? DrawingElement.Dimension }
                val chainOffset = if (dist >= 2f && previous != null)
                    AutoDimension.continued(previous, current.start, current.end, _state.value.inchesToPx(1f)) else null
                if (dist < 2f) {
                    // A tap, not a drag: dimension what is under it (a wall, a door or window, a
                    // cabinet or fixture, or the room's inside size). Nothing there: no dimension.
                    val quick = AutoDimension.quick(dimensionTapRaw ?: current.start, _state.value)
                    if (quick.isNotEmpty()) {
                        pushUndoSnapshot()
                        _state.value = _state.value.copy(elements = _state.value.elements + quick, currentDrawingElement = null, lastSnapPoint = null)
                        lastPlacedDimensionId = quick.last().id
                        triggerAutoSave()
                    } else {
                        _state.value = _state.value.copy(currentDrawingElement = null, lastSnapPoint = null)
                    }
                } else if (chainOffset != null) {
                    // Starts where the last one ended and runs the same way: it continues that
                    // string on the same dimension line, so it needs no third tap.
                    val placed = current.copy(offset = chainOffset)
                    pushUndoSnapshot()
                    _state.value = _state.value.copy(elements = _state.value.elements + placed, currentDrawingElement = null, lastSnapPoint = null)
                    lastPlacedDimensionId = placed.id
                    triggerAutoSave()
                } else {
                    _state.value = _state.value.copy(awaitingDimensionOffset = true, lastSnapPoint = null)
                }
            } else {
                // Phase 2 (placing the offset) just finished - the dimension is fully defined now.
                lastPlacedDimensionId = current.id
                pushUndoSnapshot()
                _state.value = _state.value.copy(
                    elements = _state.value.elements + current,
                    currentDrawingElement = null,
                    lastSnapPoint = null,
                    awaitingDimensionOffset = false
                )
                triggerAutoSave()
            }
            return
        }
        if (current != null) {
            pushUndoSnapshot()
            _state.value = _state.value.copy(
                elements = _state.value.elements + current,
                currentDrawingElement = null,
                lastSnapPoint = null
            )
            triggerAutoSave()
        } else {
            // A wall (or window) dragged round to a right angle with the rotate handle becomes
            // an axis-aligned wall again, so it keeps working with doors, trim and elevations.
            val elements = if (isRotating) _state.value.elements.map { if (it.id == _state.value.selectedElementId) it.squaredUp() else it } else _state.value.elements
            _state.value = _state.value.copy(elements = elements, lastSnapPoint = null)
            isRotating = false
            // A plan dragged with its objects is saved like any other edit.
            if (carriedIds.isNotEmpty() && hasSnapshotThisGesture) triggerAutoSave()
            carriedIds = emptySet()
        }
    }

    // Intersection of infinite line P1->P2 (parametrized by t) with infinite line P3->P4
    // (parametrized by u). Neither t nor u is clamped here - callers decide what range counts as
    // a valid hit (a finite segment needs its own parameter in [0,1]; extending a line past its
    // own endpoint deliberately wants t > 1 on that side). Null means the lines are parallel.
    private fun lineIntersectionParams(p1: Offset, p2: Offset, p3: Offset, p4: Offset): Pair<Float, Float>? {
        val r = p2 - p1
        val s = p4 - p3
        val rxs = r.x * s.y - r.y * s.x
        if (abs(rxs) < 1e-6f) return null
        val diff = p3 - p1
        val t = (diff.x * s.y - diff.y * s.x) / rxs
        val u = (diff.x * r.y - diff.y * r.x) / rxs
        return Pair(t, u)
    }

    // Trim: shorten the tapped Line at the nearest crossing(s) with any other element's straight
    // edges, discarding the piece actually tapped. If the tapped piece sits between two crossings
    // (not at either original end), the line splits in two rather than just shrinking, since a
    // single Line can't represent "everything except a middle chunk."
    /**
     * Something Trim/Extend can shorten or stretch along a straight axis: a Line, or a wall
     * section (an axis-aligned Walls symbol) treated as its centre line. [halfWidth] is how far
     * off that axis a tap still counts as "on" it; [rebuild] makes the element again between two
     * new end points (keeping id, colour, thickness, name).
     */
    private data class Trimmable(
        val element: DrawingElement,
        val start: Offset,
        val end: Offset,
        val halfWidth: Float,
        val rebuild: (Offset, Offset) -> DrawingElement
    )

    private fun trimmables(): List<Trimmable> = _state.value.elements.mapNotNull { e ->
        when {
            e is DrawingElement.Line -> Trimmable(e, e.start, e.end, 0f) { a, b -> e.copy(start = a, end = b) }
            e is DrawingElement.Symbol && e.category == PlanSymbols.WALLS -> {
                // A wall at any angle: its centre line is its long axis turned by its rotation
                // (a wall turned with the rotate handle used to be silently skipped here, so
                // Trim and Extend did nothing on it).
                val horizontal = e.size.width >= e.size.height
                val t = minOf(e.size.width, e.size.height)
                val half = if (horizontal) Offset(e.size.width / 2f, 0f) else Offset(0f, e.size.height / 2f)
                val turned = rotatePoint(half, Offset.Zero, e.rotation)
                Trimmable(e, e.center - turned, e.center + turned, t / 2f) { a, b ->
                    val len = (b - a).getDistance()
                    e.copy(center = (a + b) / 2f, size = if (horizontal) Size(len, t) else Size(t, len))
                }
            }
            else -> null
        }
    }

    /**
     * Closing an outside corner. [target] is a wall being extended along [fixedPoint]->[movingPoint]
     * (axis-aligned, [halfWidth] thick each side of its centre line) and [other] is the wall its
     * end is about to meet. When [other] ENDS alongside the target (a corner, not a T through it:
     * its end lies within the target's thickness band or just past it) the two should close
     * with their outside faces meeting: the target runs on to the far face of [other], and
     * [other]'s end is pulled to the target's outer face. Returns the target's new end and the
     * adjusted other wall, or null when this is not a corner.
     */
    private fun closeOutsideCorner(
        target: Trimmable, other: DrawingElement.Symbol, fixedPoint: Offset, movingPoint: Offset
    ): Pair<Offset, DrawingElement.Symbol>? {
        if (target.halfWidth <= 0f || other.category != PlanSymbols.WALLS || other.rotation != 0f) return null
        val dir = movingPoint - fixedPoint
        val targetHorizontal = abs(dir.x) >= abs(dir.y)
        if (abs(if (targetHorizontal) dir.y else dir.x) > 0.01f) return null // target must be axis-aligned too
        val ob = FloorPlan.bounds(other)
        val otherHorizontal = ob.width >= ob.height
        if (otherHorizontal == targetHorizontal) return null // parallel walls never make a corner
        val h = target.halfWidth
        // Where the moving end would reach along the target's axis to clear the other wall.
        val farFace: Float
        val cross: Float // the target's centre-line coordinate across the other wall's length
        if (targetHorizontal) {
            cross = fixedPoint.y
            val forward = dir.x > 0f
            farFace = if (forward) ob.right else ob.left
            if (forward && ob.left < movingPoint.x - 0.01f) return null
            if (!forward && ob.right > movingPoint.x + 0.01f) return null
        } else {
            cross = fixedPoint.x
            val forward = dir.y > 0f
            farFace = if (forward) ob.bottom else ob.top
            if (forward && ob.top < movingPoint.y - 0.01f) return null
            if (!forward && ob.bottom > movingPoint.y + 0.01f) return null
        }
        val bandLow = cross - h
        val bandHigh = cross + h
        val tol = h // an end up to half a thickness past the target's outer face still reads as a corner
        val oStart = if (targetHorizontal) ob.top else ob.left
        val oEnd = if (targetHorizontal) ob.bottom else ob.right
        // Which end of the other wall is the one in the corner band, if any.
        val endsAtLow = oEnd in (bandLow - tol)..(bandHigh + tol) && oStart < bandLow - tol   // other comes from the low side
        val endsAtHigh = oStart in (bandLow - tol)..(bandHigh + tol) && oEnd > bandHigh + tol // other comes from the high side
        if (!endsAtLow && !endsAtHigh) return null
        val newStart = if (endsAtHigh) bandLow else oStart
        val newEnd = if (endsAtLow) bandHigh else oEnd
        val adjusted = if (targetHorizontal)
            Rect(ob.left, newStart, ob.right, newEnd) else Rect(newStart, ob.top, newEnd, ob.bottom)
        val newOther = other.copy(center = adjusted.center, size = Size(adjusted.width, adjusted.height))
        val newEndPoint = if (targetHorizontal) Offset(farFace, movingPoint.y) else Offset(movingPoint.x, farFace)
        // Sanity: the corner must be ahead, not behind the moving end.
        if (((newEndPoint - fixedPoint) - dir).let { if (targetHorizontal) it.x * dir.x else it.y * dir.y } < -0.01f) return null
        return newEndPoint to newOther
    }

    private fun DrawingElement.withNewId(): DrawingElement = when (this) {
        is DrawingElement.Line -> copy(id = randomId())
        is DrawingElement.Symbol -> copy(id = randomId())
        else -> this
    }

    // Works on Lines and on wall sections alike: a wall crossing another wall is cut back to
    // the other wall's face.
    private fun trimAt(tapPoint: Offset, resizeThreshold: Float) {
        val target = trimmables()
            .filter { (tapPoint - getNearestPointOnSegment(tapPoint, it.start, it.end)).getDistance() <= resizeThreshold + it.halfWidth }
            .minByOrNull { (tapPoint - getNearestPointOnSegment(tapPoint, it.start, it.end)).getDistance() }
            ?: return

        val r = target.end - target.start
        val lenSq = r.x * r.x + r.y * r.y
        if (lenSq < 0.0001f) return

        val tTap = (((tapPoint.x - target.start.x) * r.x + (tapPoint.y - target.start.y) * r.y) / lenSq).coerceIn(0f, 1f)

        val otherEdges = _state.value.elements.filter { it.id != target.element.id }.flatMap { it.edgeSegments() }
        val hitTs = otherEdges.mapNotNull { (p3, p4) ->
            val (t, u) = lineIntersectionParams(target.start, target.end, p3, p4) ?: return@mapNotNull null
            if (t in 0.0001f..0.9999f && u in -0.0001f..1.0001f) t else null
        }.sorted()

        if (hitTs.isEmpty()) return // nothing crosses this line - nothing to trim against

        val lowerBound = hitTs.filter { it < tTap }.maxOrNull()
        val upperBound = hitTs.filter { it > tTap }.minOrNull()

        val replacement: List<DrawingElement> = when {
            lowerBound == null && upperBound == null -> return
            lowerBound == null -> listOf(target.rebuild(target.start + r * upperBound!!, target.end))
            upperBound == null -> listOf(target.rebuild(target.start, target.start + r * lowerBound))
            else -> listOf(
                target.rebuild(target.start, target.start + r * lowerBound).withNewId(),
                target.rebuild(target.start + r * upperBound, target.end).withNewId()
            )
        }

        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements.filter { it.id != target.element.id } + replacement,
            selectedElementId = null
        )
        triggerAutoSave()
    }

    // Extend: tap near one END of a Line to stretch that end out until it meets the nearest
    // other element's edge lying ahead of it. Tapping mid-line (not near either endpoint) does
    // nothing - extend needs to know which end is being pulled.
    private fun extendAt(tapPoint: Offset, resizeThreshold: Float) {
        val target = trimmables()
            .minByOrNull { minOf((it.start - tapPoint).getDistance(), (it.end - tapPoint).getDistance()) - it.halfWidth }
            ?: return

        val distToStart = (target.start - tapPoint).getDistance()
        val distToEnd = (target.end - tapPoint).getDistance()
        if (minOf(distToStart, distToEnd) > resizeThreshold + target.halfWidth) return

        val extendingStart = distToStart < distToEnd
        val fixedPoint = if (extendingStart) target.end else target.start
        val movingPoint = if (extendingStart) target.start else target.end
        val dir = movingPoint - fixedPoint
        if (dir.getDistance() < 0.0001f) return

        val others = _state.value.elements.filter { it.id != target.element.id }
        // t is parametrized along fixedPoint->movingPoint: t=1 is the current (un-extended)
        // endpoint, so t > 1 means genuinely further out in the direction the line already
        // points, not doubling back over itself.
        // A wall looks ahead along its centre line AND both faces: a wall whose end sits
        // within this wall's thickness (the corner case) is missed by the centre line alone.
        val unit = dir / dir.getDistance()
        val perp = Offset(-unit.y, unit.x)
        val rays = if (target.halfWidth > 0f) listOf(0f, target.halfWidth, -target.halfWidth) else listOf(0f)
        val nearest = others.flatMap { owner ->
            owner.edgeSegments().flatMap { (p3, p4) ->
                rays.mapNotNull { off ->
                    val (t, u) = lineIntersectionParams(fixedPoint + perp * off, movingPoint + perp * off, p3, p4) ?: return@mapNotNull null
                    if (t > 1.0001f && u in -0.0001f..1.0001f) owner to t else null
                }
            }
        }.minByOrNull { it.second } ?: return // nothing ahead to extend to

        var newEndpoint = fixedPoint + dir * nearest.second
        var adjustedOther: DrawingElement? = null
        // A wall meeting the END of another wall closes the corner outside-to-outside, instead
        // of stopping at the near face and leaving a notch in the outside corner.
        (nearest.first as? DrawingElement.Symbol)?.let { other ->
            closeOutsideCorner(target, other, fixedPoint, movingPoint)?.let { (end, fixedOther) ->
                newEndpoint = end
                adjustedOther = fixedOther
            }
        }
        val updated = if (extendingStart) target.rebuild(newEndpoint, target.end) else target.rebuild(target.start, newEndpoint)

        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements.map {
                when (it.id) {
                    target.element.id -> updated
                    adjustedOther?.id -> adjustedOther!!
                    else -> it
                }
            },
            selectedElementId = target.element.id
        )
        triggerAutoSave()
    }

    // Explode: replace a compound shape (Rectangle/Triangle/Symbol) with plain Lines along each
    // of its edges, so each side can be selected, moved, trimmed, or extended on its own. This
    // is a one-way operation - a Symbol loses its parametric identity (nominal size, category,
    // its parametric icon/grain rendering) and becomes 4 (or 3, for Triangle) ordinary Lines,
    // same as AutoCAD's EXPLODE reducing a block to its raw constituent geometry. Reuses
    // edgeSegments() - the exact same edges Trim/Extend already treat as this shape's boundary -
    // so "what you can butt another line against" and "what you get when you explode it" always
    // agree.
    private fun explodeSelectedElement() {
        val selectedId = _state.value.selectedElementId ?: return
        val element = _state.value.elements.find { it.id == selectedId } ?: return
        val explodable = element is DrawingElement.Rectangle || element is DrawingElement.Triangle ||
            element is DrawingElement.Symbol || element is DrawingElement.ElevationView
        if (!explodable) return

        if (element is DrawingElement.ElevationView) {
            // Freeze the live view into plain lines, rectangles and text for hand editing.
            val parts = Elevation.partsOf(Elevation.withVisibleInk(element, _state.value.backgroundColor), _state.value)
            pushUndoSnapshot()
            _state.value = _state.value.copy(
                elements = _state.value.elements.filter { it.id != selectedId } + parts,
                selectedElementId = null,
                selectedElementIds = parts.map { it.id }.toSet()
            )
            triggerAutoSave()
            return
        }

        val edges = element.edgeSegments()
        if (edges.isEmpty()) return

        val newLines = edges.map { (a, b) ->
            DrawingElement.Line(start = a, end = b, color = element.color, strokeWidth = element.strokeWidth)
        }

        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements.filter { it.id != selectedId } + newLines,
            selectedElementId = null,
            // Select the resulting pieces as a group - immediate visual confirmation of what the
            // explode produced, and puts them straight into the existing group move/delete/copy
            // flow if that's what's wanted next.
            selectedElementIds = newLines.map { it.id }.toSet()
        )
    }

    private fun wallThicknessPx(wallName: String): Float =
        _state.value.inchesToPx(PlanSymbols.footprintInches[wallName]?.second ?: 4.5f)

    /** Building outline: [corner] is the top-left OUTSIDE corner. */
    private fun placeShell(corner: Offset, shell: Placement.Shell) {
        val s = _state.value
        val outer = Rect(corner, Size(shell.width * s.scale.ratio, shell.depth * s.scale.ratio))
        val walls = FloorPlan.wallsAround(
            outer, wallThicknessPx(shell.wallName), shell.wallName, s.currentColor,
            Layers.onActiveLevel(s).filterIsInstance<DrawingElement.Symbol>(), s.inchesToPx(1f)
        )
        pushUndoSnapshot()
        _state.value = _state.value.copy(elements = _state.value.elements + walls, selectedElementId = null)
        triggerAutoSave()
    }

    /**
     * Room: [corner] is the top-left INSIDE corner (or the top-left OUTSIDE corner when the room
     * was entered by outside dimensions); its walls go around the outside. Butting up to an
     * existing wall shares it rather than standing a few inches off it (see roomInside).
     */
    private fun placeRoom(corner: Offset, room: Placement.Room) {
        val s = _state.value
        val t = wallThicknessPx(room.wallName)
        val inside = FloorPlan.roomInside(room, corner, t, s)
        val outer = Rect(inside.left - t, inside.top - t, inside.right + t, inside.bottom + t)
        val walls = FloorPlan.wallsAround(
            outer, t, room.wallName, s.currentColor,
            Layers.onActiveLevel(s).filterIsInstance<DrawingElement.Symbol>(), s.inchesToPx(1f)
        )
        val label = FloorPlan.roomLabel(room, inside, s)
        pushUndoSnapshot()
        _state.value = _state.value.copy(elements = _state.value.elements + walls + label, selectedElementId = null)
        triggerAutoSave()
    }

    /**
     * Hatches the area around [point]: the rectangle between the nearest walls (or straight
     * lines) on each side. It goes under everything but imported plans, so it never covers the
     * walls or fixtures; its outline is a hairline that sits on the wall faces.
     */
    private fun placeHatch(point: Offset, placement: Placement.Hatch) {
        val s = _state.value
        val area = FloorPlan.areaAt(point, s)
        if (area == null) {
            notify("No closed area there: hatch needs walls or lines on all four sides.")
            return
        }
        // Dan, Nov 27: on the floor plan of the walls round it, not the active layer: drawn on the Exterior layer it sat
        // over the tub and vanity instead of under them.
        val inflated = Rect(area.left - 2f, area.top - 2f, area.right + 2f, area.bottom + 2f)
        val planLayer = s.elements.filterIsInstance<DrawingElement.Symbol>()
            .filter { it.category == PlanSymbols.WALLS && FloorPlan.bounds(it).overlaps(inflated) }
            .map { Layers.layerOf(it, s) }.firstOrNull { it.trade == LayerTrade.FLOOR_PLAN }?.id ?: Building.FLOOR1_PLAN
        val hatch = DrawingElement.Rectangle(
            topLeft = area.topLeft, size = area.size, color = s.currentColor, strokeWidth = 0f,
            fill = Fill(
                pattern = placement.pattern, spacing = s.inchesToPx(placement.sizeIn), lineWidth = 1f,
                length = if (placement.lengthIn > 0f) s.inchesToPx(placement.lengthIn) else 0f
            ),
            layerId = planLayer
        )
        // A hatch already filling that same area is replaced, not stacked (Dan, Nov 27: it made another over the first).
        fun same(e: DrawingElement) = e is DrawingElement.Rectangle && e.fill.pattern != FillPattern.NONE && e.strokeWidth == 0f &&
            kotlin.math.abs(e.topLeft.x - area.left) < 1f && kotlin.math.abs(e.topLeft.y - area.top) < 1f &&
            kotlin.math.abs(e.size.width - area.width) < 1f && kotlin.math.abs(e.size.height - area.height) < 1f
        val rest = s.elements.filterNot(::same)
        val underlayEnd = rest.indexOfLast { it is DrawingElement.Pdf || it is DrawingElement.Image } + 1
        pushUndoSnapshot()
        _state.value = s.copy(
            elements = rest.take(underlayEnd) + hatch + rest.drop(underlayEnd),
            pendingPlacement = null,
            placementPreview = null,
            selectedElementId = hatch.id,
            selectedElementIds = emptySet()
        )
        triggerAutoSave()
    }

    /** A live elevation of a plan wall with [origin] as the floor line's left end. */
    /**
     * Elevations of the walls of the room around [point], each cut to the room (RoomElevations, Nov 25), placed in
     * a row above the plan and anything already there. Trim on, the rest the usual defaults.
     */
    private fun placeRoomElevations(point: Offset, walls: List<String>) {
        val s = _state.value
        val faces = RoomElevations.faces(point, s).filter { walls.isEmpty() || it.direction in walls }
        if (faces.isEmpty()) { notify("No room found there: tap inside a room with walls on all four sides."); return }
        val inch = s.inchesToPx(1f)
        val ink = Elevation.visibleInk(s.currentColor, s.backgroundColor)
        val planTop = s.elements.filter { it !is DrawingElement.ElevationView && it !is DrawingElement.Dimension }.minOfOrNull { it.boundingRect().top } ?: point.y
        val above = s.elements.filterIsInstance<DrawingElement.ElevationView>().minOfOrNull { it.frame.top } ?: planTop
        val floorY = minOf(planTop, above) - 36f * inch
        var x = s.elements.filter { it !is DrawingElement.ElevationView }.minOfOrNull { it.boundingRect().left } ?: point.x
        // Titled by the room's name when it has one (Nov 28): "Kitchen, 12' wide x 8' high, looking north".
        val roomName = ElectricalCode.roomAt(point, s)?.name?.takeUnless { it.startsWith("Room ") } ?: ""
        val views = faces.map { f ->
            val spec = ElevationSpec(wallId = f.wall.id, side = f.side, fromIn = f.fromIn, toIn = f.toIn, trimOn = true, foreIn = f.depthIn, roomName = roomName)
            val size = Elevation.sizePx(f.wall, spec, s)
            DrawingElement.ElevationView(spec = spec, origin = Offset(x, floorY), size = size, color = ink).also { x += size.width + 36f * inch }
        }
        pushUndoSnapshot()
        _state.value = _state.value.copy(elements = _state.value.elements + views,
            selectedElementId = views.singleOrNull()?.id, selectedElementIds = if (views.size > 1) views.map { it.id }.toSet() else emptySet())
        notify("${views.size} room elevations: ${faces.joinToString { it.direction }}")
        triggerAutoSave()
    }

    /** Art or wallpaper on the wall face an elevation shows (WallDecor, Nov 25); the picture joins the project. */
    private fun addDecor(elevationId: String, picture: Picture, kind: Int, repeatIn: Float) {
        val s = _state.value
        val view = s.elements.find { it.id == elevationId } as? DrawingElement.ElevationView
            ?: run { notify("Select an elevation of a wall first."); return }
        val wall = s.elements.find { it.id == view.spec.wallId } as? DrawingElement.Symbol ?: return
        if (wall.category != PlanSymbols.WALLS) { notify("Art and wallpaper go on a wall's elevation."); return }
        val inch = s.inchesToPx(1f)
        val (a, b) = Elevation.cropPx(wall, view.spec, s)
        val decor = if (kind == WallDecor.WALLPAPER) WallDecorations.newWallpaper(wall.id, view.spec.side, picture, repeatIn.coerceAtLeast(1f))
            else WallDecorations.newArt(wall.id, view.spec.side, picture, (a + b) / 2f / inch)
        pushUndoSnapshot()
        _state.value = s.copy(
            pictures = if (s.pictures.any { it.id == picture.id }) s.pictures else s.pictures + picture,
            // One wallpaper per wall face: a new one replaces the old.
            wallDecor = (if (kind == WallDecor.WALLPAPER) s.wallDecor.filterNot { it.kind == WallDecor.WALLPAPER && it.wallId == wall.id && it.side == view.spec.side } else s.wallDecor) + decor
        )
        triggerAutoSave()
    }

    /** A room for the finish schedule (Nov 26): the room around [point]; tapping a room already in it renames it. */
    private fun addRoomFinish(point: Offset, name: String) {
        val s = _state.value
        val area = FloorPlan.areaAt(point, s) ?: run { notify("No room there: tap inside a room with walls all around."); return }
        val same = s.roomFinishes.firstOrNull { f -> FloorPlan.areaAt(FinishSchedule.point(f), s)?.let { (it.center - area.center).getDistance() < s.inchesToPx(2f) } == true }
        pushUndoSnapshot()
        _state.value = if (same != null) s.copy(roomFinishes = s.roomFinishes.map { if (it.id == same.id && name.isNotBlank()) it.copy(name = name.trim()) else it })
            else s.copy(roomFinishes = s.roomFinishes + RoomFinish(name = name.trim(), x = area.center.x, y = area.center.y))
        triggerAutoSave()
    }

    /** Paint on the wall face an elevation shows (Nov 26): one colour per face, a new one replaces the old. */
    private fun setPaint(elevationId: String, hex: String, code: String, name: String) {
        val s = _state.value
        val view = s.elements.find { it.id == elevationId } as? DrawingElement.ElevationView
            ?: run { notify("Select an elevation of a wall first."); return }
        val wall = s.elements.find { it.id == view.spec.wallId } as? DrawingElement.Symbol ?: return
        if (wall.category != PlanSymbols.WALLS) { notify("Paint goes on a wall's elevation."); return }
        val rgb = WallDecorations.parseHex(hex) ?: PaintColors.find(code)?.rgb
            ?: run { notify("Give a colour as a hex code like #D1C7B8, or a Sherwin-Williams number like SW 7036."); return }
        val known = if (name.isBlank()) PaintColors.find(code) else null
        // A room elevation paints just that room's stretch of the wall; a whole-wall one, the whole face (Nov 26).
        val inch = s.inchesToPx(1f)
        val spans = Wainscot.roomSpans(wall, view.spec.side, s)
        val (a, b) = Elevation.cropPx(wall, view.spec, s)
        val rooms = WallDecorations.roomsShown(spans, a / inch, b / inch, Elevation.lengthPx(wall) / inch)
        val paint = WallDecorations.newPaint(wall.id, view.spec.side, rgb, known?.code ?: code, known?.name ?: name, rooms)
        val onFace = s.wallDecor.filter { it.kind == WallDecor.PAINT && it.wallId == wall.id && it.side == view.spec.side }
        pushUndoSnapshot()
        _state.value = s.copy(wallDecor = (s.wallDecor - onFace.toSet()) + WallDecorations.paintsAfter(onFace, rooms, spans.size) + paint)
        triggerAutoSave()
    }

    private fun updateDecor(id: String, property: String, value: String) {
        val v = parseInches(value) ?: return
        val s = _state.value
        val d = s.wallDecor.firstOrNull { it.id == id } ?: return
        val pic = WallDecorations.picture(s, d.pictureId)
        val nd = when (property) {
            "xIn" -> d.copy(xIn = v)
            "bottomIn" -> d.copy(bottomIn = v.coerceAtLeast(0f))
            // Width keeps the picture's shape; height may be set on its own after.
            "widthIn" -> if (v <= 0f) return else d.copy(widthIn = v, heightIn = pic?.let { WallDecorations.heightFor(it, v) } ?: d.heightIn,
                xIn = d.xIn + (d.widthIn - v) / 2f)
            "heightIn" -> if (v <= 0f) return else d.copy(heightIn = v)
            "frameIn" -> d.copy(frameIn = v.coerceAtLeast(0f))
            "repeatIn" -> if (v <= 0f) return else d.copy(repeatIn = v)
            else -> return
        }
        pushUndoSnapshot()
        _state.value = s.copy(wallDecor = s.wallDecor.map { if (it.id == id) nd else it })
        triggerAutoSave()
    }

    private fun placeElevation(origin: Offset, placement: Placement.Elevation) {
        val s = _state.value
        val wall = s.elements.find { it.id == placement.spec.wallId } as? DrawingElement.Symbol ?: return
        val view = DrawingElement.ElevationView(
            spec = placement.spec,
            origin = origin,
            size = Elevation.sizePx(wall, placement.spec, s),
            color = Elevation.visibleInk(s.currentColor, s.backgroundColor)
        )
        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements + view,
            selectedElementId = view.id,
            selectedElementIds = emptySet()
        )
        triggerAutoSave()
    }

    /**
     * Keeps each elevation view's frame in step with its wall (the wall may have been stretched,
     * trimmed or the heights edited). Runs with every autosave; cheap when nothing changed.
     */
    /** Stairs: up or down, and the rise from the building unless typed (Stairs.sync). */
    /** Uppers over a sink or cooktop take their height from the clearances (UpperFit, Nov 25). */
    private fun syncUpperFit() {
        val s = _state.value
        val synced = UpperFit.sync(s)
        if (synced !== s) _state.value = synced
    }

    private fun syncStairs() {
        val s = _state.value
        val synced = Stairs.sync(s)
        if (synced !== s) _state.value = synced
    }

    /** Foundation and framing built from the first-floor plan, when the builder is on (DerivedLayers). */
    private fun syncDerivedLayers() {
        val s = _state.value
        // DerivedLayers.sync does nothing more than the upper floors (UpperFloors) when the builder is off.
        val synced = DerivedLayers.sync(s)
        if (synced !== s) _state.value = synced
    }

    /**
     * Keeps the model-space wall section's frame the size of the section (the building inputs or
     * the exterior walls may have changed). Its centre stays put. Cheap when nothing changed.
     */
    private fun syncWallSections() {
        val s = _state.value
        if (s.elements.none { WallSection.isSection(it) }) return
        val size = WallSection.worldSize(s)
        var changed = false
        val updated = s.elements.map { e ->
            if (e is DrawingElement.Symbol && WallSection.isSection(e) && e.size != size) { changed = true; e.copy(size = size) } else e
        }
        if (changed) _state.value = s.copy(elements = updated)
    }

    /**
     * The roof symbol on the Roof structural layer, kept over the first-floor exterior walls with
     * its frame on the eave line (RoofPlan); taken away when the roof is removed or there are no walls.
     */
    private fun syncRoof() {
        val s = _state.value
        val geo = s.building.roof?.let { RoofPlan.geometry(s, it) }
        // A traced roof (RoofAreas) is framed on its own extent.
        val frame = (if (s.building.roof != null) RoofAreas.boundsPx(s) else null) ?: geo?.eave
        if (frame == null) {
            if (s.elements.any { RoofPlan.isRoof(it) || RoofPlan.isRoofFinish(it) })
                _state.value = s.copy(elements = s.elements.filter { !RoofPlan.isRoof(it) && !RoofPlan.isRoofFinish(it) })
            return
        }
        // The framing plan (Roof structural) and the roof plan with its roofing (Roof plan): one roof, both framed on the eave line.
        fun want(id: String, name: String, layer: String) = DrawingElement.Symbol(
            id = id, symbolName = name, category = RoofPlan.CATEGORY,
            center = frame.center, size = frame.size, color = Color.White, layerId = layer
        )
        var elements = s.elements
        var changed = false
        for ((w, test) in listOf(
            want(RoofPlan.ID, RoofPlan.SYMBOL, RoofPlan.LAYER) to { e: DrawingElement -> RoofPlan.isRoof(e) },
            want(RoofPlan.FINISH_ID, RoofPlan.FINISH_SYMBOL, RoofPlan.FINISH_LAYER) to { e: DrawingElement -> RoofPlan.isRoofFinish(e) }
        )) {
            val existing = elements.firstOrNull(test) as? DrawingElement.Symbol
            if (existing == null) { elements = elements + w; changed = true }
            else if (existing.center != w.center || existing.size != w.size || existing.rotation != 0f || existing.layerId != w.layerId) {
                elements = elements.map { if (test(it)) w else it }; changed = true
            }
        }
        if (changed) _state.value = s.copy(elements = elements)
    }

    /**
     * The four exterior elevations in model space (ExteriorElevation), once the grade exposure is
     * set: new ones in a row under the drawing, existing ones kept where they were put and sized
     * to what they now show. Taken away when the exposure is cleared or there are no exterior walls.
     */
    private fun syncExteriorElevations() {
        val s = _state.value
        val existing = ExteriorElevation.all(s)
        if (s.building.gradeExposureIn == null || RoofPlan.footprint(s) == null) {
            if (existing.isNotEmpty()) _state.value = s.copy(elements = s.elements.filter { !ExteriorElevation.isExterior(it) })
            return
        }
        var elements = s.elements
        var changed = false
        val missing = ExteriorElevation.Face.entries.filter { f -> existing.none { ExteriorElevation.faceOf(it) == f } }
        val spots = if (missing.isEmpty()) emptyMap() else ExteriorElevation.spots(s)
        for (f in ExteriorElevation.Face.entries) {
            val size = ExteriorElevation.worldSize(s, f) ?: continue
            val cur = existing.firstOrNull { ExteriorElevation.faceOf(it) == f }
            if (cur == null) {
                val at = spots[f] ?: continue
                elements = elements + DrawingElement.Symbol(
                    id = ExteriorElevation.idFor(f), symbolName = ExteriorElevation.SYMBOL, category = ExteriorElevation.CATEGORY,
                    center = at, size = size, color = Color.White, layerId = ExteriorElevation.LAYER,
                    properties = mapOf(ExteriorElevation.PROP_FACE to f.ordinal.toFloat())
                )
                changed = true
            } else if (cur.size != size || cur.rotation != 0f) {
                elements = elements.map { if (it.id == cur.id) cur.copy(size = size, rotation = 0f) else it }
                changed = true
            }
        }
        if (changed) _state.value = s.copy(elements = elements)
    }

    /**
     * The site plan on the Site layer (SiteLot): its own drawing, away from the others. Made to the
     * right of everything when the lot is first typed in; after that it stays where it is put (drag
     * it, and the lot, the house and its roof plan all go with it) and only its size follows what it
     * draws. Taken away when the calls are cleared.
     */
    private fun syncSiteLot() {
        val s = _state.value
        val existing = s.elements.firstOrNull { SiteLot.isLot(it) } as? DrawingElement.Symbol
        val g = SiteLot.geometry(s)
        if (g == null) {
            if (existing != null) _state.value = s.copy(elements = s.elements.filter { !SiteLot.isLot(it) })
            return
        }
        val size = g.extent.size
        if (existing == null) {
            val lot = DrawingElement.Symbol(
                id = SiteLot.ID, symbolName = SiteLot.SYMBOL, category = SiteLot.CATEGORY,
                center = SiteLot.spotFor(s, size), size = size, color = Color.White, layerId = SiteLot.LAYER
            )
            _state.value = s.copy(elements = s.elements + lot)
        } else if (existing.size != size || existing.rotation != 0f || existing.layerId != SiteLot.LAYER) {
            _state.value = s.copy(elements = s.elements.map { if (SiteLot.isLot(it)) existing.copy(size = size, rotation = 0f, layerId = SiteLot.LAYER) else it })
        }
    }

    /** The construction details in model space, framed on what they draw now (the roof or wall may have changed). */
    private fun syncDetails() {
        val s = _state.value
        if (s.elements.none { DetailKit.isDetail(it) }) return
        var changed = false
        val updated = s.elements.map { e ->
            if (e is DrawingElement.Symbol && DetailKit.isDetail(e)) {
                val size = DetailKit.worldSize(s, DetailKit.kindOf(e))
                if (e.size != size) { changed = true; e.copy(size = size) } else e
            } else e
        }
        if (changed) _state.value = s.copy(elements = updated)
    }

    /** The sheet set kept in Dan's order and numbered by it (Sheets.renumbered). */
    private fun syncSheetNumbers() {
        val s = _state.value
        if (s.sheets.isEmpty()) return
        val renumbered = Sheets.renumbered(s.sheets, s)
        if (renumbered != s.sheets) _state.value = s.copy(sheets = renumbered)
    }

    /** Door and window marks for the schedules: new openings get the next number (Schedules). */
    private fun syncScheduleMarks() {
        val s = _state.value
        if (!Schedules.inUse(s)) return
        val marked = Schedules.assignMarks(s)
        if (marked !== s) _state.value = marked
    }

    /** The wall section in model space: the one there, or a new one to the right of the drawing. */
    private fun ensureWallSection(): DrawingElement.Symbol {
        WallSection.sectionIn(_state.value)?.let { return it }
        val s = _state.value
        val section = WallSection.newSymbol(s, WallSection.spotFor(s))
        pushUndoSnapshot()
        _state.value = s.copy(elements = s.elements + section)
        return section
    }

    private fun syncElevationViews() {
        val s = _state.value
        if (s.elements.none { it is DrawingElement.ElevationView }) return
        var changed = false
        val updated = s.elements.map { e ->
            if (e !is DrawingElement.ElevationView) return@map e
            val wall = s.elements.find { it.id == e.spec.wallId } as? DrawingElement.Symbol ?: return@map e
            val size = Elevation.sizePx(wall, e.spec, s)
            if (size == e.size) e else { changed = true; e.copy(size = size) }
        }
        if (changed) _state.value = _state.value.copy(elements = updated)
    }

    /** Rotates the selection (a box-selected group, or the single selected element) about its centre. */
    private fun rotateSelection(degrees: Float) {
        val s = _state.value
        val ids = if (s.selectedElementIds.isNotEmpty()) s.selectedElementIds else setOfNotNull(s.selectedElementId)
        val targets = s.elements.filter { it.id in ids }
        if (targets.isEmpty()) return
        val pivot = targets.map { it.boundingRect() }.reduce { a, r ->
            Rect(minOf(a.left, r.left), minOf(a.top, r.top), maxOf(a.right, r.right), maxOf(a.bottom, r.bottom))
        }.center
        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements.map { if (it.id in ids) it.rotateAround(pivot, degrees) else it }
        )
        triggerAutoSave()
    }

    private fun addSymbol(
        name: String, category: String, center: Offset = Offset(500f, 500f),
        rotation: Float = 0f, sizeOverride: Size? = null,
        /** A typed width (Nov 27), inches: its footprint at that width. */
        widthIn: Float? = null
    ) {
        val symbol = SymbolFactory.build(name, category, _state.value, center, rotation, sizeOverride, widthIn)
            .let { Mep.alignToFraming(it, _state.value) } // a return grille lands on the stud/joist layout
        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements + symbol,
            selectedElementId = symbol.id
        )
        triggerAutoSave()
    }

    /** A wing off the house (Wings): [AddWing.wall] 0 N, 1 S, 2 W, 3 E; from the corner (null = centred on the wall). */
    private fun addWing(event: DrawingEvent.AddWing) {
        val st = _state.value
        val fp = RoofPlan.footprint(st) ?: return
        val inch = st.inchesToPx(1f)
        if (inch <= 0f || event.widthIn <= 0f || event.depthIn <= 0f) return
        val wall = event.wall.coerceIn(0, 3)
        val size = if (wall <= 1) Size(event.widthIn * inch, event.depthIn * inch) else Size(event.depthIn * inch, event.widthIn * inch)
        val probe = DrawingElement.Symbol(symbolName = Wings.NAME, category = Wings.CATEGORY, center = Offset.Zero, size = size, color = st.currentColor)
        val from = event.fromCornerIn ?: Garages.centeredOffsetIn(probe, fp, inch, wall)
        val symbol = probe.copy(
            center = Garages.centerFor(probe, fp, inch, wall, from, 0f), layerId = Building.FLOOR1_PLAN,
            properties = Wings.defaults() + mapOf(Wings.FOUND to event.foundation.toFloat(), Wings.END to event.end.toFloat(),
                Wings.JOISTS to event.joists.toFloat(), "widthInches" to size.width / inch, "depthInches" to size.height / inch)
        )
        pushUndoSnapshot()
        _state.value = st.copy(elements = st.elements + symbol, selectedElementId = symbol.id)
        triggerAutoSave()
    }

    private fun updateElementProperty(id: String, property: String, value: String) {
        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements.map { element ->
                if (element.id == id) {
                    when (property) {
                        // A line as a room divider (Nov 28): made square to the plan (rooms are found square), about its middle.
                        "divider" -> {
                            if (element !is DrawingElement.Line) return@map element
                            val on = value == "1" || value.equals("true", true)
                            if (!on) return@map element.copy(divider = false)
                            val mid = (element.start + element.end) / 2f
                            val d = element.end - element.start
                            if (kotlin.math.abs(d.x) >= kotlin.math.abs(d.y))
                                element.copy(divider = true, rotation = 0f, start = Offset(element.start.x, mid.y), end = Offset(element.end.x, mid.y))
                            else element.copy(divider = true, rotation = 0f, start = Offset(mid.x, element.start.y), end = Offset(mid.x, element.end.y))
                        }
                        "length", "width" -> {
                            val newValue = value.toFloatOrNull() ?: return@map element
                            val pixelValue = newValue * _state.value.scale.ratio
                            when (element) {
                                is DrawingElement.Line -> element.copy(end = element.start + Offset(pixelValue, 0f)) // Simplified
                                is DrawingElement.Rectangle -> element.copy(size = Size(pixelValue, element.size.height))
                                is DrawingElement.Triangle -> element.copy(size = Size(pixelValue, element.size.height))
                                is DrawingElement.Dimension -> element.copy(value = newValue, end = element.start + Offset(pixelValue, 0f))
                                is DrawingElement.Symbol -> {
                                    if ((element.symbolName == "I-Beam") || element.symbolName.contains("W-Beam")) {
                                        element
                                    } else {
                                        element.copy(size = Size(pixelValue, element.size.height))
                                    }
                                }
                                else -> element
                            }
                        }
                        "height" -> {
                            val newValue = value.toFloatOrNull() ?: return@map element
                            val pixelValue = newValue * _state.value.scale.ratio
                            when (element) {
                                is DrawingElement.Rectangle -> element.copy(size = Size(element.size.width, pixelValue))
                                is DrawingElement.Triangle -> element.copy(size = Size(element.size.width, pixelValue))
                                is DrawingElement.Symbol -> {
                                    if ((element.symbolName == "I-Beam") || element.symbolName.contains("W-Beam")) {
                                        element
                                    } else {
                                        element.copy(size = Size(element.size.width, pixelValue))
                                    }
                                }
                                else -> element
                            }
                        }
                        "radius" -> {
                            val newValue = value.toFloatOrNull() ?: return@map element
                            val pixelValue = newValue * _state.value.scale.ratio
                            when (element) {
                                is DrawingElement.Circle -> element.copy(radius = pixelValue)
                                else -> element
                            }
                        }
                        "diameter" -> {
                            val newValue = value.toFloatOrNull() ?: return@map element
                            val pixelRadius = (newValue / 2f) * _state.value.scale.ratio
                            when (element) {
                                is DrawingElement.Circle -> element.copy(radius = pixelRadius)
                                else -> element
                            }
                        }
                        "rotation" -> {
                            val newValue = value.toFloatOrNull() ?: return@map element
                            element.rotate(newValue - element.rotation).squaredUp()
                        }
                        // PDF plan: lock, opacity, page shown.
                        "locked" -> (element as? DrawingElement.Pdf)?.copy(locked = value.toBoolean()) ?: element
                        "opacity" -> (element as? DrawingElement.Pdf)?.let { pdf ->
                            pdf.copy(opacity = (value.toFloatOrNull() ?: pdf.opacity).coerceIn(0.1f, 1f))
                        } ?: element
                        "currentPage" -> (element as? DrawingElement.Pdf)?.let { pdf ->
                            val page = value.toIntOrNull() ?: return@map element
                            pdf.copy(currentPage = page.coerceIn(0, (pdf.pages.size - 1).coerceAtLeast(0)))
                        } ?: element
                        // Elevation view heights (inches) and viewing side.
                        "wallHeightIn", "counterHeightIn", "upperBottomIn", "upperHeightIn", "sillIn", "windowHeightIn", "doorHeightIn" -> {
                            val v = value.toFloatOrNull() ?: return@map element
                            if (element !is DrawingElement.ElevationView || v < 0f) return@map element
                            val spec = element.spec
                            val newSpec = when (property) {
                                "wallHeightIn" -> spec.copy(wallHeightIn = v)
                                "counterHeightIn" -> spec.copy(counterHeightIn = v)
                                "upperBottomIn" -> spec.copy(upperBottomIn = v)
                                "upperHeightIn" -> spec.copy(upperHeightIn = v)
                                "sillIn" -> spec.copy(sillIn = v)
                                "windowHeightIn" -> spec.copy(windowHeightIn = v)
                                else -> spec.copy(doorHeightIn = v)
                            }
                            element.copy(spec = newSpec, size = Size(element.size.width, _state.value.inchesToPx(newSpec.wallHeightIn)))
                        }
                        // Cabinet fronts, door count and island overhang: small codes (see PlanSymbols).
                        PlanSymbols.PROP_FRONT, PlanSymbols.PROP_DOORS, PlanSymbols.PROP_OVERHANG, PlanSymbols.PROP_OVERHANG_IN,
                        PlanSymbols.PROP_SINKS, PlanSymbols.PROP_TOP_IN, PlanSymbols.PROP_STYLE, PlanSymbols.PROP_HARDWARE,
                        PlanSymbols.PROP_HINGE_RIGHT, PlanSymbols.PROP_WINDOW_TYPE -> {
                            val v = parseInches(value) ?: return@map element
                            if (element is DrawingElement.Symbol && v >= 0f) element.copy(properties = element.properties + (property to v)) else element
                        }
                        // The supplier's rough opening for a door or window (Schedules): blank goes back to Dan's rule.
                        Schedules.PROP_RO_W, Schedules.PROP_RO_H -> {
                            if (element !is DrawingElement.Symbol) return@map element
                            if (value.isBlank()) return@map element.copy(properties = element.properties - property)
                            val v = parseInches(value) ?: return@map element
                            if (v > 0f) element.copy(properties = element.properties + (property to v)) else element
                        }
                        // An electrical box's or grille's height to its bottom, inches (Mep, Nov 27): blank goes back to the default.
                        // Airflow (CFM) on a register or duct, a device's circuit: 0 or blank clears it.
                        Mep.CFM, Mep.CIRCUIT -> {
                            if (element !is DrawingElement.Symbol || !Mep.isMep(element)) return@map element
                            val v = value.trim().toFloatOrNull() ?: 0f
                            if (v <= 0f) element.copy(properties = element.properties - property) else element.copy(properties = element.properties + (property to v))
                        }
                        Mep.MOUNT_H -> {
                            if (element !is DrawingElement.Symbol || !Mep.isMep(element)) return@map element
                            if (value.isBlank()) return@map element.copy(properties = element.properties - property)
                            val v = parseInches(value) ?: return@map element
                            if (v >= 0f) element.copy(properties = element.properties + (property to v)) else element
                        }
                        // A door's or window's own trim (context card, Nov 27): blank goes back to the room's.
                        Trim.PROP_CASING_IN, Trim.PROP_HEAD, Trim.PROP_WINDOW_TRIM -> {
                            if (element !is DrawingElement.Symbol || element.category != PlanSymbols.DOORS_WINDOWS) return@map element
                            if (value.isBlank()) return@map element.copy(properties = element.properties - property)
                            val v = parseInches(value) ?: return@map element
                            if (v >= 0f) element.copy(properties = element.properties + (property to v)) else element
                        }
                        // A door's or window's own height / a window's sill, in inches ("6 1/2" welcome).
                        PlanSymbols.PROP_SILL_IN, PlanSymbols.PROP_HEIGHT_IN, Mirrors.PROP_FRAME_IN -> {
                            val v = parseInches(value) ?: return@map element
                            if (element is DrawingElement.Symbol && v >= 0f) element.copy(properties = element.properties + (property to v)) else element
                        }
                        // Wainscot on one face of a wall (Wainscot): the style as a code, height in inches, count of panels/boxes.
                        in Wainscot.allKeys -> {
                            val v = parseInches(value) ?: return@map element
                            if (element is DrawingElement.Symbol && element.category == PlanSymbols.WALLS && v >= 0f)
                                element.copy(properties = element.properties + (property to v)) else element
                        }
                        // Tub/shower settings on an alcove tub (TubShower): inches, or 0/1 for the choices.
                        in TubShower.keys -> {
                            val v = parseInches(value) ?: return@map element
                            if (element is DrawingElement.Symbol && TubShower.isAlcoveTub(element) && v >= 0f)
                                element.copy(properties = element.properties + (property to v)) else element
                        }
                        // Shower settings: inches, or small codes for the choices (see Shower).
                        in Shower.keys -> {
                            val v = parseInches(value) ?: return@map element
                            if (element is DrawingElement.Symbol && v >= 0f) element.copy(properties = element.properties + (property to v)) else element
                        }
                        // An elevation's wall tile: heights and size in inches, the pattern as an index.
                        // The stretch of the wall an elevation shows, inches along it (blank/0 to 0 = the whole wall).
                        "fromIn", "toIn" -> {
                            if (element !is DrawingElement.ElevationView) return@map element
                            val v = if (value.isBlank()) 0f else parseInches(value) ?: return@map element
                            if (v < 0f) return@map element
                            element.copy(spec = if (property == "fromIn") element.spec.copy(fromIn = v) else element.spec.copy(toIn = v))
                        }
                        // How deep in front of the face to show what stands in the room (Natalie, Nov 28); 0 = off.
                        "foreIn" -> {
                            if (element !is DrawingElement.ElevationView) return@map element
                            val v = if (value.isBlank()) 0f else parseInches(value) ?: return@map element
                            if (v < 0f) return@map element
                            element.copy(spec = element.spec.copy(foreIn = v))
                        }
                        "tileExtent" -> {
                            val v = value.toIntOrNull() ?: return@map element
                            if (element is DrawingElement.ElevationView && v in 0..1) element.copy(spec = element.spec.copy(tileExtent = v)) else element
                        }
                        // Hood settings: inches, or small codes for the choices (see Appliances).
                        PlanSymbols.PROP_UPPER_HEIGHT_IN -> {
                            val v = parseInches(value) ?: return@map element
                            // Typed by hand: the user's height from now on, no longer fitted automatically (UpperFit).
                            if (element is DrawingElement.Symbol && v > 0f) element.copy(properties = element.properties + (property to v) - UpperFit.AUTO) else element
                        }
                        "crownIn" -> {
                            val v = parseInches(value) ?: return@map element
                            if (element !is DrawingElement.ElevationView || v < 0f) return@map element
                            element.copy(spec = element.spec.copy(crownIn = v))
                        }
                        "trimOn", "casingIn", "headStyle", "windowTrim", "friezeIn", "baseIn" -> {
                            // Door and window casing and the base on an elevation (Trim.kt).
                            if (element !is DrawingElement.ElevationView) return@map element
                            val sp = element.spec
                            val spec = when (property) {
                                "trimOn" -> sp.copy(trimOn = value == "1" || value.equals("true", ignoreCase = true))
                                "headStyle" -> sp.copy(headStyle = (value.toIntOrNull() ?: return@map element).coerceIn(0, 2))
                                "windowTrim" -> sp.copy(windowTrim = (value.toIntOrNull() ?: return@map element).coerceIn(0, 1))
                                else -> {
                                    val v = parseInches(value) ?: return@map element
                                    when (property) {
                                        "casingIn" -> if (v > 0f) sp.copy(casingIn = v) else return@map element
                                        "friezeIn" -> sp.copy(friezeIn = v.coerceAtLeast(Trim.MIN_FRIEZE_IN))
                                        else -> if (v >= 0f) sp.copy(baseIn = v) else return@map element
                                    }
                                }
                            }
                            element.copy(spec = spec)
                        }
                        "valance" -> {
                            if (element !is DrawingElement.ElevationView) return@map element
                            element.copy(spec = element.spec.copy(valance = value == "1" || value.equals("true", ignoreCase = true)))
                        }
                        "islandFace" -> {
                            val v = value.toIntOrNull() ?: return@map element
                            if (element !is DrawingElement.ElevationView || v !in 0..3) return@map element
                            val island = _state.value.elements.find { it.id == element.spec.wallId } as? DrawingElement.Symbol ?: return@map element
                            val spec = element.spec.copy(islandFace = v)
                            element.copy(spec = spec, size = Elevation.sizePx(island, spec, _state.value))
                        }
                        // What a callout points to: the targets' bits (Callouts).
                        // Put a garage on a house wall at a distance from the corner, or centre it there (Garages).
                        Garages.CENTER, Garages.PLACE -> {
                            if (element !is DrawingElement.Symbol || !(Garages.isGarage(element) || Wings.isWing(element))) return@map element
                            val fp = RoofPlan.footprint(_state.value) ?: return@map element
                            val inch = _state.value.inchesToPx(1f)
                            val now = Garages.placement(element, fp, inch) ?: return@map element
                            val parts = value.split(",")
                            val wall = if (property == Garages.PLACE) parts.getOrNull(0)?.trim()?.toIntOrNull() ?: now.first else now.first
                            val gap = (if (property == Garages.PLACE) parts.getOrNull(2)?.trim()?.toFloatOrNull() else null) ?: now.third
                            val from = if (property == Garages.CENTER) Garages.centeredOffsetIn(element, fp, inch, wall.coerceIn(0, 3))
                                else parts.getOrNull(1)?.trim()?.toFloatOrNull() ?: now.second
                            element.copy(center = Garages.centerFor(element, fp, inch, wall.coerceIn(0, 3), from, gap.coerceAtLeast(0f)))
                        }
                        Wings.WALL_H, Wings.PITCH -> {
                            // A height or pitch; 0 = the house's.
                            val v = value.trim().toFloatOrNull() ?: return@map element
                            if (element !is DrawingElement.Symbol || v < 0f || v > 600f) return@map element
                            element.copy(properties = element.properties + (property to v))
                        }
                        in Wings.keys -> {
                            val v = value.trim().toIntOrNull() ?: return@map element
                            if (element !is DrawingElement.Symbol || v < 0 || v > 2) return@map element
                            element.copy(properties = element.properties + (property to v.toFloat()))
                        }
                        in Garages.keys -> {
                            val v = value.trim().toIntOrNull() ?: return@map element
                            if (element !is DrawingElement.Symbol || v < 0 || v > 3) return@map element
                            element.copy(properties = element.properties + (property to v.toFloat()))
                        }
                        Porches.PITCH -> {
                            val v = value.trim().toFloatOrNull() ?: return@map element
                            if (element !is DrawingElement.Symbol || v <= 0f || v > 24f) return@map element
                            element.copy(properties = element.properties + (property to v))
                        }
                        in Porches.keys -> {
                            val v = value.trim().toIntOrNull() ?: return@map element
                            if (element !is DrawingElement.Symbol || v < 0 || v > 2) return@map element
                            element.copy(properties = element.properties + (property to v.toFloat()))
                        }
                        in Callouts.keys -> {
                            val v = value.trim().toIntOrNull() ?: return@map element
                            if (element !is DrawingElement.Symbol || v < 0) return@map element
                            element.copy(properties = element.properties + (property to v.toFloat()))
                        }
                        // Foundation and framing settings (Structure.kt) and a wall's bearing flag.
                        in Structure.keys -> {
                            val v = parseInches(value) ?: return@map element
                            if (element !is DrawingElement.Symbol || v < 0f) return@map element
                            element.copy(properties = element.properties + (property to v))
                        }
                        in Appliances.keys -> {
                            val v = parseInches(value) ?: return@map element
                            if (element is DrawingElement.Symbol && v >= 0f) element.copy(properties = element.properties + (property to v)) else element
                        }
                        "tileFromIn", "tileToIn", "tileSizeIn", "tileLengthIn", "tilePattern" -> {
                            if (element !is DrawingElement.ElevationView) return@map element
                            val v = (if (property == "tilePattern") value.toFloatOrNull() else parseInches(value)) ?: return@map element
                            if (v < 0f) return@map element
                            val spec = element.spec
                            element.copy(spec = when (property) {
                                "tileFromIn" -> spec.copy(tileFromIn = v)
                                "tileToIn" -> spec.copy(tileToIn = v)
                                "tileSizeIn" -> spec.copy(tileSizeIn = v.coerceAtLeast(0.5f))
                                "tileLengthIn" -> spec.copy(tileLengthIn = v)
                                else -> spec.copy(tilePattern = v.toInt())
                            })
                        }
                        // Stair settings; the footprint follows the rise, treads and width.
                        in Stairs.keys -> {
                            val v = parseInches(value) ?: return@map element
                            if (element !is DrawingElement.Symbol || v < 0f) return@map element
                            // A rise typed by hand stops following the building (Stairs.RISE_SET).
                            val typed = if (property == Stairs.RISE) mapOf(Stairs.RISE_SET to 1f) else emptyMap()
                            val updated = element.copy(properties = element.properties + (property to v) + typed)
                            val (wIn, dIn) = Stairs.footprintIn(updated)
                            val st = _state.value
                            updated.copy(size = Size(st.inchesToPx(wIn), st.inchesToPx(dIn)))
                        }
                        "stairSide" -> {
                            val v = value.toIntOrNull() ?: return@map element
                            if (element !is DrawingElement.ElevationView || v !in 0..1) return@map element
                            val stair = _state.value.elements.find { it.id == element.spec.wallId } as? DrawingElement.Symbol ?: return@map element
                            val spec = element.spec.copy(stairSide = v)
                            element.copy(spec = spec, size = Elevation.sizePx(stair, spec, _state.value))
                        }
                        // Which face of the shower a shower elevation shows.
                        "showerFace" -> {
                            val v = value.toIntOrNull() ?: return@map element
                            if (element !is DrawingElement.ElevationView || v !in 0..3) return@map element
                            val shower = _state.value.elements.find { it.id == element.spec.wallId } as? DrawingElement.Symbol ?: return@map element
                            if (v !in Shower.faces(Shower.type(shower.symbolName))) return@map element
                            val spec = element.spec.copy(showerFace = v)
                            element.copy(spec = spec, size = Elevation.sizePx(shower, spec, _state.value))
                        }
                        "viewerSide" -> {
                            if (element is DrawingElement.ElevationView) {
                                ViewerSide.entries.firstOrNull { it.name == value }?.let { element.copy(spec = element.spec.copy(side = it)) } ?: element
                            } else element
                        }
                        "text" -> {
                            if (element is DrawingElement.Text) element.copy(text = value) else element
                        }
                        "fontSize" -> {
                            val newValue = value.toFloatOrNull() ?: return@map element
                            if (element is DrawingElement.Text) element.copy(fontSize = newValue) else element
                        }
                        "isBold" -> {
                            if (element is DrawingElement.Text) element.copy(isBold = value.toBoolean()) else element
                        }
                        "isItalic" -> {
                            if (element is DrawingElement.Text) element.copy(isItalic = value.toBoolean()) else element
                        }
                        "textAlign" -> {
                            if (element is DrawingElement.Text) element.copy(textAlign = value) else element
                        }
                        "listType" -> {
                            if (element is DrawingElement.Text) element.copy(listType = TextListType.valueOf(value)) else element
                        }
                        "fontFamily" -> {
                            if (element is DrawingElement.Text) element.copy(fontFamily = value) else element
                        }
                        "fillPattern" -> element.fillOrNull()?.let { f -> element.withFill(f.copy(pattern = FillPattern.entries.firstOrNull { it.name == value } ?: f.pattern)) } ?: element
                        "fillColor" -> element.fillOrNull()?.let { f -> element.withFill(f.copy(color = if (value == "auto") null else (parseHexColor(value) ?: f.color))) } ?: element
                        "fillSpacing" -> element.fillOrNull()?.let { f -> element.withFill(f.copy(spacing = value.toFloatOrNull() ?: f.spacing)) } ?: element
                        // The tile or plank's long side, px (0 = the pattern's own proportion).
                        "fillLength" -> element.fillOrNull()?.let { f -> element.withFill(f.copy(length = value.toFloatOrNull()?.coerceAtLeast(0f) ?: f.length)) } ?: element
                        // Tile width and length typed in inches (feet-and-inches welcome); length 0 = the pattern's own proportion.
                        "fillWidthIn" -> element.fillOrNull()?.let { f ->
                            val v = parseInches(value)?.takeIf { it > 0f } ?: return@map element
                            element.withFill(f.copy(spacing = _state.value.inchesToPx(v)))
                        } ?: element
                        "fillLengthIn" -> element.fillOrNull()?.let { f ->
                            val v = parseInches(value)?.takeIf { it >= 0f } ?: return@map element
                            element.withFill(f.copy(length = _state.value.inchesToPx(v)))
                        } ?: element
                        "fillLineWidth" -> element.fillOrNull()?.let { f -> element.withFill(f.copy(lineWidth = value.toFloatOrNull() ?: f.lineWidth)) } ?: element
                        // Hex string from the colour picker (see ColorHex.kt).
                        "color" -> parseHexColor(value)?.let { element.withColor(it) } ?: element
                        "strokeWidth" -> {
                            val newValue = value.toFloatOrNull() ?: return@map element
                            when (element) {
                                is DrawingElement.Freehand -> element.copy(strokeWidth = newValue)
                                is DrawingElement.Line -> element.copy(strokeWidth = newValue)
                                is DrawingElement.Rectangle -> element.copy(strokeWidth = newValue)
                                is DrawingElement.Oval -> element.copy(strokeWidth = newValue)
                                is DrawingElement.Triangle -> element.copy(strokeWidth = newValue)
                                is DrawingElement.Circle -> element.copy(strokeWidth = newValue)
                                is DrawingElement.Dimension -> element.copy(strokeWidth = newValue)
                                else -> element
                            }
                        }
                        "triangleType" -> {
                            when (element) {
                                is DrawingElement.Triangle -> element.copy(triangleType = value)
                                else -> element
                            }
                        }
                        "flangeWidth", "webHeight", "flangeThickness", "webThickness" -> {
                            val newValue = value.toFloatOrNull() ?: return@map element
                            if (element is DrawingElement.Symbol) {
                                val valueInInches = if (_state.value.unitSystem == UnitSystem.METRIC) {
                                    newValue / 25.4f
                                } else {
                                    newValue
                                }
                                val updatedProperties = element.properties.toMutableMap().apply {
                                    put(property, valueInInches)
                                }
                                val newSize = calculateBeamPixelSize(updatedProperties, _state.value.scale.ratio, _state.value.unitSystem)
                                element.copy(properties = updatedProperties, size = newSize)
                            } else {
                                element
                            }
                        }
                        else -> element
                    }
                } else element
            }
        )
        // A plan just locked lets go of the selection: while selected its grips would still
        // drag it, which is exactly what locking is for stopping.
        if (property == "locked" && value.toBoolean() && _state.value.selectedElementId == id) {
            _state.value = _state.value.copy(selectedElementId = null)
        }
        triggerAutoSave()
    }

    private fun importImage(bitmap: ImageBitmap) {
        val element = DrawingElement.Image(
            bitmap = bitmap,
            topLeft = Offset(100f, 100f),
            size = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
        )
        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements + element
        )
        triggerAutoSave()
    }

    /** A picked PDF waits for a tap that says where it goes (Placement.Plan), like a symbol. */
    private fun importPdf(bitmaps: List<ImageBitmap>) {
        if (bitmaps.isEmpty()) return
        _state.value = _state.value.copy(
            pendingPlacement = Placement.Plan(bitmaps),
            placementPreview = null,
            selectedElementId = null,
            selectedElementIds = emptySet()
        )
    }

    /** The plan, centred on [center]. */
    private fun addPdfPlan(bitmaps: List<ImageBitmap>, center: Offset) {
        if (bitmaps.isEmpty()) return
        val size = Size(bitmaps[0].width.toFloat(), bitmaps[0].height.toFloat())
        val element = DrawingElement.Pdf(
            pages = bitmaps,
            topLeft = center - Offset(size.width / 2f, size.height / 2f),
            size = size
        )
        pushUndoSnapshot()
        // First in the list = drawn first = under everything already drawn: a plan is
        // something to trace over, never something that covers the work.
        _state.value = _state.value.copy(
            elements = listOf(element) + _state.value.elements
        )
        triggerAutoSave()
    }

    /** The saved group [id] belongs to (members that still exist), or null when it is in none. */
    private fun groupOf(id: String): Set<String>? {
        val group = _state.value.groups.firstOrNull { id in it } ?: return null
        val existing = _state.value.elements.mapTo(HashSet()) { it.id }
        return group.filterTo(LinkedHashSet()) { it in existing }.takeIf { it.size > 1 }
    }

    /** [ids] plus every member of any group one of them is in. */
    private fun expandGroups(ids: Set<String>): Set<String> = ids.flatMapTo(LinkedHashSet()) { groupOf(it) ?: setOf(it) }

    /** Selects [id], or its whole group (as a box-style multi-selection) when it is in one. */
    private fun selectWithGroup(id: String) {
        val group = groupOf(id)
        _state.value = if (group != null) _state.value.copy(selectedElementId = null, selectedElementIds = group)
        else _state.value.copy(selectedElementId = id, selectedElementIds = emptySet())
    }

    /**
     * Starts a plan drag: [pdf] and the objects lying wholly on it (with the rest of their
     * groups) move together. Locked plans never move.
     */
    private fun startCarrying(pdf: DrawingElement.Pdf, from: Offset) {
        if (pdf.locked) return
        val sheet = pdf.boundingRect()
        val onPlan = _state.value.elements
            .filter { el -> el.id != pdf.id && el !is DrawingElement.Pdf && el.boundingRect().let { b -> sheet.contains(b.topLeft) && sheet.contains(b.bottomRight) } }
            .mapTo(LinkedHashSet()) { it.id }
        carriedIds = expandGroups(onPlan + pdf.id).filterTo(LinkedHashSet()) { id -> _state.value.elements.find { it.id == id }?.isLockedPlan != true }
        lastOffset = from
    }

    private fun groupSelection() {
        val ids = _state.value.selectedElementIds
        if (ids.size < 2) return
        // Groups the selection touches are merged into the new one, so an object is never in two.
        val touched = _state.value.groups.filter { g -> g.any { it in ids } }
        val merged = touched.fold(ids) { acc, g -> acc + g }
        _state.value = _state.value.copy(groups = _state.value.groups - touched.toSet() + listOf(merged))
        triggerAutoSave()
    }

    private fun ungroupSelection() {
        val ids = _state.value.selectedElementIds.ifEmpty { setOfNotNull(_state.value.selectedElementId) }
        val touched = _state.value.groups.filter { g -> g.any { it in ids } }
        if (touched.isEmpty()) return
        _state.value = _state.value.copy(groups = _state.value.groups - touched.toSet())
        triggerAutoSave()
    }

    /** A calibration point lifted on the plan: keep the first, ask for the length after the second. */
    private fun calibrationPointPlaced(pending: Placement.Calibrate, point: Offset) {
        val first = pending.first
        _state.value = when {
            first == null -> _state.value.copy(pendingPlacement = pending.copy(first = point))
            // The same spot twice measures nothing: pick the second point again.
            (point - first).getDistance() < 2f -> _state.value.copy(pendingPlacement = pending)
            else -> _state.value.copy(pendingCalibration = PendingCalibration(pending.pdfId, first, point))
        }
    }

    /**
     * Scales the plan so the distance between the two picked points is [realLength] document
     * units at the drawing's scale (scale.ratio px per ft or m). The drawing's own scale is not
     * touched: everything already drawn keeps its size, and the plan is brought to match it.
     * The first point stays exactly where it was picked, so the plan grows or shrinks about it.
     */
    private fun applyCalibration(realLength: Float) {
        val pending = _state.value.pendingCalibration ?: return
        val pdf = _state.value.elements.find { it.id == pending.pdfId } as? DrawingElement.Pdf
        val measured = pending.pixelLength
        if (pdf == null || realLength <= 0f || measured <= 0f) {
            _state.value = _state.value.copy(pendingCalibration = null)
            return
        }
        val k = realLength * _state.value.scale.ratio / measured
        // The renderer turns a PDF about the canvas origin (DrawingCanvas.drawElement, pivot
        // Offset.Zero), so the first point is turned back into the plan's own frame, the plan
        // is scaled about it there, and turning it again puts that point back where it was.
        val anchor = rotateAbout(pending.first, Offset.Zero, -pdf.rotation)
        val scaled = pdf.copy(
            topLeft = anchor + (pdf.topLeft - anchor) * k,
            size = Size(pdf.size.width * k, pdf.size.height * k),
            calibration = "${formatLength(realLength, _state.value.unitSystem)} between 2 points"
        )
        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements.map { if (it.id == pdf.id) scaled else it },
            pendingCalibration = null,
            selectedElementId = pdf.id,
            selectedElementIds = emptySet()
        )
        triggerAutoSave()
    }

    private fun unlockAllPdfs() {
        if (_state.value.elements.none { it.isLockedPlan }) return
        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements.map { if (it is DrawingElement.Pdf && it.locked) it.copy(locked = false) else it }
        )
        triggerAutoSave()
    }

    private fun importDxf(elements: List<DrawingElement>) {
        val element = DrawingElement.Dxf(
            elements = elements,
            topLeft = Offset.Zero
        )
        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements + element
        )
        triggerAutoSave()
    }

    // Maximum snapshots retained - unbounded growth here would mean an unbounded memory cost per
    // session (each snapshot holds a full copy of the elements list reference, cheap individually
    // since DrawingElement is immutable and shared, but still worth a hard ceiling).
    private val maxHistoryDepth = 100

    // Call this once, right before applying ANY change to `elements` - drawing, deleting, moving,
    // resizing, rotating, trimming, extending, exploding, duplicating, offsetting, editing a
    // property, importing. Captures what `elements` looked like right before that action so Undo
    // can restore it, and clears redoHistory since branching off into a new action invalidates
    // whatever used to be "ahead" in the redo direction (standard undo/redo semantics - the same
    // reason every other app's redo history disappears the moment you do something new after an
    // undo).
    private fun pushUndoSnapshot() {
        _state.value = _state.value.copy(
            undoHistory = (_state.value.undoHistory + listOf(_state.value.elements)).takeLast(maxHistoryDepth),
            redoHistory = emptyList()
        )
    }

    private fun undo() {
        val history = _state.value.undoHistory
        if (history.isNotEmpty()) {
            val previous = history.last()
            _state.value = _state.value.copy(
                elements = previous,
                undoHistory = history.dropLast(1),
                redoHistory = (_state.value.redoHistory + listOf(_state.value.elements)).takeLast(maxHistoryDepth)
            )
        }
    }

    private fun redo() {
        val future = _state.value.redoHistory
        if (future.isNotEmpty()) {
            val next = future.last()
            _state.value = _state.value.copy(
                elements = next,
                redoHistory = future.dropLast(1),
                undoHistory = (_state.value.undoHistory + listOf(_state.value.elements)).takeLast(maxHistoryDepth)
            )
        }
    }

    private fun saveProject(snapshot: CanvasState) {
        val storage = platform.storage ?: return // nothing persisted (unit tests)
        try {
            val text = json.encodeToString(CanvasState.serializer(), snapshot)
            storage.write(projectFileName(snapshot.projectName), text)
            // Also update the project list file
            val currentList = storage.read("project_list.txt")?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            storage.write("project_list.txt", (currentList + snapshot.projectName).sorted().joinToString("\n"))
            storage.write(LAST_PROJECT_FILE, snapshot.projectName)
            sync?.onLocalSaved(snapshot.projectName, text)
        } catch (e: Exception) {
            platform.log("SiteDraft", "save failed: ${e.message}")
        }
    }

    // ProjectSyncListener: both arrive on the main thread.
    override fun onProjectPulled(name: String) {
        // Only the open project needs reloading; others are picked up when opened.
        if (name == _state.value.projectName) {
            pendingSave?.cancel()
            reloadFromStorage(name)
        }
    }

    override fun onProjectListChanged() = refreshProjectList()

    override fun openProjectName(): String? = _state.value.projectName

    override fun hasUnsavedEdits(): Boolean = pendingSave?.isActive == true

    private fun refreshProjectList() {
        val storage = platform.storage ?: return
        val list = storage.read("project_list.txt")?.lines()?.filter { it.isNotBlank() }?.sorted() ?: emptyList()
        _state.value = _state.value.copy(availableProjects = list)
    }

    private fun loadProject() {
        // This is a generic load, usually we want to load a SPECIFIC project
        loadProjectByName(_state.value.projectName)
    }

    fun loadProjectByName(name: String) {
        reloadFromStorage(name)
        // Opening a project is the moment to fetch a newer copy another device may have saved.
        sync?.syncProject(name)
    }

    private fun reloadFromStorage(name: String) {
        val storage = platform.storage ?: return
        try {
            val projectData = storage.read(projectFileName(name)) ?: return
            val loadedState = json.decodeFromString(CanvasState.serializer(), projectData)
            // When loading, preserve the available projects list from current session
            val cur = _state.value
            // The window's size is this screen's, never the file's: without it Fit and the zoom buttons
            // stop working after a reload. Reloading the project that's open (a change from Claude or
            // another device) keeps the view where Dan left it: zoom, pan, grid and colours (Nov 25).
            val same = name == cur.projectName
            _state.replace(Layers.reconcile(loadedState).let {
                val kept = if (same) it.copy(zoom = cur.zoom, pan = cur.pan, background = cur.background, backgroundColor = cur.backgroundColor) else it
                kept.copy(availableProjects = cur.availableProjects, viewportSize = cur.viewportSize, selectedElementId = null, selectedElementIds = emptySet(), sheets = Sheets.renumbered(kept.sheets, kept))
            })
            storage.write(LAST_PROJECT_FILE, name)
            // Stairs placed by the connector (or saved before Nov 24): up or down and the rise from
            // the building, before the framing opens the floor for them.
            val stairs = Stairs.sync(_state.value)
            val stairsChanged = stairs !== _state.value
            if (stairsChanged) _state.value = stairs
            // Walls the connector (or another device) drew since: build the layers under them now.
            run {
                val before = _state.value
                val synced = DerivedLayers.sync(before)
                if (synced !== before) _state.value = synced
                if (synced !== before || stairsChanged) triggerAutoSave()
            }
            // The roof, the exterior elevations and elevation frames the connector set up since: build them now
            // too, so they show without another edit first (Dan, Nov 23: voice drawing through the connector).
            val beforeSync = _state.value
            syncElevationViews()
            syncRoof()
            syncExteriorElevations()
            // And everything else an edit brings up to date (wall sections, marks, details, sheet numbers), Nov 25.
            syncDerived()
            if (_state.value !== beforeSync) triggerAutoSave()
        } catch (e: Exception) {
            platform.log("SiteDraft", "load failed: ${e.message}")
        }
    }

    /**
     * Composes the sheet for [settings]: which elements, where they sit on the paper and at what
     * scale (PrintPage.layout), and the title block. Print and Export PDF both come through here,
     * so the PDF is exactly what the printer gets. Returns null, with a message to the user, when
     * there is nothing to print (no elevation selected).
     */
    private fun composePage(settings: PrintSettings, density: Float): Pair<PageSpec, DrawScope.() -> Unit>? {
        val state = _state.value
        if (settings.subject == PrintSubject.SHEET) {
            // A sheet of views: its own paper, each view at its own scale (Sheets.kt).
            val sheet = state.sheets.firstOrNull { it.id == settings.sheetId } ?: state.sheets.firstOrNull()
            if (sheet == null || sheet.views.isEmpty()) { notify("Add a view to the sheet first"); return null }
            val dpi = PrintPage.dpiFor(sheet.paper)
            val (wIn, hIn) = Sheets.paperIn(sheet)
            val spec = PageSpec((wIn * dpi).roundToInt(), (hIn * dpi).roundToInt(), dpi, density)
            val date = todayDateText()
            return spec to { drawSheet(sheet, state, dpi, state.projectName, date) }
        }
        val elements = PrintPage.subjectElements(state, settings.subject)
        if (elements == null) { notify("Select an elevation view first"); return null }
        val metric = state.unitSystem == UnitSystem.METRIC
        val layout = PrintPage.layout(settings, PrintPage.subjectBounds(state, settings.subject), PrintPage.worldPxPerInch(state), metric)

        // Render through the SAME code path as the on-screen canvas (drawDocumentContents in
        // DrawingCanvas.kt), so the sheet shows exactly what the screen shows - symbols,
        // dimensions, text, rotation, images - on a white page with labels sized for paper.
        val pageState = PrintPage.pageState(state, elements, layout)
        val titleBlock = TitleBlock(
            project = state.projectName,
            sheet = settings.sheetTitle.trim().ifEmpty { PrintPage.defaultSheetTitle(settings.subject) },
            date = todayDateText(),
            scale = layout.scaleText
        )
        // Device density, not 1f: symbol icon strokes are specified in dp, and the sheet should
        // match what the user sees on this device.
        val spec = PageSpec(layout.widthPx, layout.heightPx, layout.dpi, density)
        return spec to { drawPrintPage(layout, pageState, titleBlock) }
    }

    /** "Kitchen Remodel - Floor Plan" made safe for a file name. */
    private fun sheetFileStem(settings: PrintSettings): String {
        if (settings.subject == PrintSubject.SHEET) {
            val sheet = _state.value.sheets.firstOrNull { it.id == settings.sheetId } ?: _state.value.sheets.firstOrNull()
            if (sheet != null) return "${_state.value.projectName.trim()} - ${sheet.number} ${sheet.title}".replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        }
        val sheet = settings.sheetTitle.trim().ifEmpty { PrintPage.defaultSheetTitle(settings.subject) }
        return "${_state.value.projectName.trim()} - $sheet".replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    private fun exportToPdf(settings: PrintSettings, density: Float) {
        val (spec, draw) = composePage(settings, density) ?: return
        // The platform makes the page (a real PDF on Android and desktop, an image download in
        // the browser) and tells the user where it went.
        val message = try {
            platform.exportPage("${sheetFileStem(settings)}.pdf", spec, draw)
        } catch (e: Exception) {
            "PDF export failed: ${e.message}"
        }
        notify(message ?: "PDF export is not available here")
    }

    /** Every sheet, in order: as one PDF, or [separate] a PDF per sheet (Sheets screen > Export set). */
    private fun exportSheetSet(density: Float, separate: Boolean) {
        val state = _state.value
        val sheets = state.sheets.filter { it.views.isNotEmpty() }
        if (sheets.isEmpty()) { notify("No sheets with views yet"); return }
        val date = todayDateText()
        val pages: List<Pair<PageSpec, DrawScope.() -> Unit>> = sheets.map { sheet ->
            val dpi = PrintPage.dpiFor(sheet.paper)
            val (wIn, hIn) = Sheets.paperIn(sheet)
            val spec = PageSpec((wIn * dpi).roundToInt(), (hIn * dpi).roundToInt(), dpi, density)
            val draw: DrawScope.() -> Unit = { drawSheet(sheet, state, dpi, state.projectName, date) }
            spec to draw
        }
        val name = "${state.projectName.trim()} - Sheets".replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val message = try {
            if (separate) {
                // One file each, named like a single sheet's export ("Job - A-1 Floor Plan.pdf").
                var last: String? = null
                sheets.zip(pages).forEach { (sheet, page) ->
                    val stem = "${state.projectName.trim()} - ${sheet.number} ${sheet.title}".replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                    last = platform.exportPage("$stem.pdf", page.first, page.second) ?: last
                }
                last?.let { "${sheets.size} sheets exported, a PDF each. $it" }
            } else platform.exportPages("$name.pdf", pages)
        } catch (e: Exception) {
            "PDF export failed: ${e.message}"
        }
        notify(message ?: "PDF export is not available here")
    }

    private fun printPage(settings: PrintSettings, density: Float) {
        val (spec, draw) = composePage(settings, density) ?: return
        val message = try {
            platform.printPage(sheetFileStem(settings), spec, draw)
        } catch (e: Exception) {
            "Print failed: ${e.message}"
        }
        message?.let { notify(it) }
    }

    private fun exportToDxf() {
        val state = _state.value
        val name = state.projectName.trim().ifEmpty { "SiteDraft" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val message = try {
            platform.exportText("$name.dxf", DxfExport.export(state))
        } catch (e: Exception) {
            "DXF export failed: ${e.message}"
        }
        notify(message ?: "DXF export is not available here")
    }
}

@Serializable
sealed class DrawingEvent {
    @Serializable
    data class Start(val offset: Offset, val pressure: Float = 1f, val resizeThreshold: Float = 40f) : DrawingEvent()
    @Serializable
    data class Move(val offset: Offset, val pressure: Float = 1f) : DrawingEvent()
    @Serializable
    object End : DrawingEvent()
    @Serializable
    object ToggleEraser : DrawingEvent()
    // S Pen side button held (true) / released (false) - see DrawingCanvas.
    @Serializable
    data class StylusButtonErase(val active: Boolean) : DrawingEvent()
    @Serializable
    data class SelectTool(val tool: DrawingTool) : DrawingEvent()
    @Serializable
    data class SelectColor(val color: Color) : DrawingEvent()
    @Serializable
    data class SelectStrokeWidth(val width: Float) : DrawingEvent()
    @Serializable
    data class SelectBackground(val background: DrawingBackground) : DrawingEvent()
    @Serializable
    data class SelectBackgroundColor(val color: Color) : DrawingEvent()
    @Serializable
    data class SelectDimensionFormat(val format: DimensionFormat) : DrawingEvent()
    /** Multiplies the zoom by [delta] about [focal] (screen px); null = the middle of the view. */
    @Serializable
    data class UpdateZoom(val delta: Float, val focal: Offset? = null) : DrawingEvent()
    @Serializable
    data class UpdatePan(val delta: Offset) : DrawingEvent()
    /** The canvas's on-screen size, from DrawingCanvas. */
    @Serializable
    data class SetViewportSize(val size: Size) : DrawingEvent()
    /** Zoom and pan so the whole drawing fits the view. */
    @Serializable
    object ZoomToFit : DrawingEvent()
    @Serializable
    data class SelectSymbol(val name: String, val category: String, /** A typed width, inches (Nov 27); null = the library size. */ val widthIn: Float? = null) : DrawingEvent()
    /** Arms a building outline or room from the floor-plan panel. */
    @Serializable
    data class StartPlacement(val placement: Placement) : DrawingEvent()
    /** Puts whatever is pending at this canvas point (snapped). */
    @Serializable
    data class PlaceAt(val point: Offset) : DrawingEvent()
    /** Pen hovering or dragging while something is pending: show where it would land. */
    @Serializable
    data class PreviewPlacementAt(val point: Offset) : DrawingEvent()
    @Serializable
    object CancelPlacement : DrawingEvent()
    /** Turns the selection (group or single element) clockwise by [degrees] about its centre. */
    @Serializable
    data class RotateSelection(val degrees: Float) : DrawingEvent()
    /** Selects exactly these elements (one = single selection, several = group). */
    @Serializable
    data class SelectElements(val ids: Set<String>) : DrawingEvent()
    @Serializable
    data class UpdateElementProperty(val id: String, val property: String, val value: String) : DrawingEvent()
    @Serializable
    data class AddWing(val widthIn: Float, val depthIn: Float, val wall: Int, val fromCornerIn: Float?, val foundation: Int, val end: Int = 0, val joists: Int = 0) : DrawingEvent()
    @Serializable
    data class UpdateScale(val ratio: Float, val label: String) : DrawingEvent()
    @Serializable
    data class SelectUnitSystem(val unit: UnitSystem) : DrawingEvent()
    @Serializable
    data class ImportImage(val bitmap: ImageBitmap) : DrawingEvent()
    // A finished line placed programmatically (laser wall mode).
    @Serializable
    data class AddLine(val start: Offset, val end: Offset) : DrawingEvent()
    // Laser wall mode.
    @Serializable
    data class SetWallMode(val active: Boolean) : DrawingEvent()
    @Serializable
    data class SetRemodelPhase(val phase: RemodelPhase) : DrawingEvent()
    @Serializable
    data class SetWallDrawStatus(val status: WallStatus) : DrawingEvent()
    /** Sets the status of the selected wall(s) (single or box selection). */
    @Serializable
    data class SetWallStatus(val status: WallStatus) : DrawingEvent()
    /** Demo part of the selected wall: [fromIn] inches from its start end (left/top when [fromStart]), [lengthIn] long. */
    @Serializable
    data class DemoWallPart(val wallId: String, val fromIn: Float, val lengthIn: Float, val fromStart: Boolean) : DrawingEvent()
    @Serializable
    data class SetWallDirection(val direction: WallDirection) : DrawingEvent()
    @Serializable
    data class SetWallCursor(val offset: Offset) : DrawingEvent()
    @Serializable
    data class AddWall(val lengthPx: Float) : DrawingEvent()
    @Serializable
    object CloseRoom : DrawingEvent()
    @Serializable
    object NewRoom : DrawingEvent()
    @Serializable
    object ContinueWallsFromSelectedLine : DrawingEvent()
    @Serializable
    data class ImportPdf(val bitmaps: List<ImageBitmap>) : DrawingEvent()
    /** Arms scale calibration on PDF plan [pdfId]: the next two canvas taps are the ends of a known dimension. */
    @Serializable
    data class StartCalibration(val pdfId: String) : DrawingEvent()
    /** The real length between the two calibration points, in document units (ft or m). */
    @Serializable
    data class ApplyCalibration(val realLength: Float) : DrawingEvent()
    @Serializable
    object CancelCalibration : DrawingEvent()
    @Serializable
    object UnlockAllPdfs : DrawingEvent()
    /** Saves the box-selected objects as a group: tapping any one of them selects them all. */
    @Serializable
    object GroupSelection : DrawingEvent()
    /** Breaks up any group in the selection; the objects stay selected. */
    @Serializable
    object UngroupSelection : DrawingEvent()
    @Serializable
    data class ImportDxf(val elements: List<DrawingElement>) : DrawingEvent()
    @Serializable
    object Undo : DrawingEvent()
    @Serializable
    object Redo : DrawingEvent()
    @Serializable
    object Clear : DrawingEvent()
    @Serializable
    object ResetZoom : DrawingEvent()
    @Serializable
    object SaveProject : DrawingEvent()
    @Serializable
    object LoadProject : DrawingEvent()
    @Serializable
    object RefreshProjectList : DrawingEvent()
    /** A sheet laid out by [settings] (PrintPage); [density] = the screen density, so dp-sized strokes export at their on-screen weight. */
    @Serializable
    data class ExportPdf(val settings: PrintSettings = PrintSettings(), val density: Float = 1f) : DrawingEvent()
    /** The same sheet as [ExportPdf], sent to the platform print system. */
    @Serializable
    data class Print(val settings: PrintSettings = PrintSettings(), val density: Float = 1f) : DrawingEvent()
    @Serializable
    object ExportDxf : DrawingEvent()
    @Serializable
    data class OffsetSelectedElement(val distance: Float, val direction: Int = 1) : DrawingEvent()
    @Serializable
    data class ToggleStylusOnlyDrawing(val enabled: Boolean) : DrawingEvent()
    @Serializable
    object DuplicateSelectedElement : DrawingEvent()
    @Serializable
    data class RenameProject(val name: String) : DrawingEvent()
    @Serializable
    object NewProject : DrawingEvent()
    @Serializable
    data class ToggleSnap(val enabled: Boolean) : DrawingEvent()
    @Serializable
    data class ToggleGridSnap(val enabled: Boolean) : DrawingEvent()
    /**
     * A door or window's position on its wall: measured from the left/top end ([fromEnd] false)
     * or the right/bottom end, to its near edge or [toCenter], from the inside corner or the
     * [outside] corner; with [distance] (feet-inches typed) it slides to sit that far along.
     */
    @Serializable
    data class PositionOpening(val id: String, val fromEnd: Boolean, val toCenter: Boolean, val outside: Boolean, val distance: String? = null) : DrawingEvent()
    /** The project's print sheets, as edited in the Print dialog. */
    @Serializable
    data class UpdateSheets(val sheets: List<SheetLayout>) : DrawingEvent()
    /** Who the job is for (Builder, Architect, Designer, Engineer) and the sheet set's ticked groups; null groups = the preset. */
    @Serializable
    data class SetSheetSetChoice(val projectType: String, val groups: List<String>? = null) : DrawingEvent()
    /** A notes layer that only viewport [viewId] on sheet [sheetId] shows, made the drawing layer. */
    @Serializable
    data class AddSheetNotesLayer(val sheetId: String, val viewId: String) : DrawingEvent()
    /** Every sheet: as one PDF, or [separate] a PDF per sheet. */
    @Serializable
    data class ExportSheetSet(val density: Float, val separate: Boolean = false) : DrawingEvent()
    /** Puts the typical wall section in model space (or selects the one there). */
    @Serializable
    object PlaceWallSection : DrawingEvent()
    /** Adds the typical wall section view to sheet [sheetId], placing the section in model space first if needed. */
    @Serializable
    data class AddWallSectionToSheet(val sheetId: String) : DrawingEvent()
    /** A construction detail (DetailKit.Detail ordinal) on a sheet, placed in model space first if it isn't there. */
    @Serializable
    data class AddDetailToSheet(val sheetId: String, val kind: Int) : DrawingEvent()
    /** The job's details for the title block (Projects / Jobs > Project info). */
    @Serializable
    data class UpdateProjectInfo(val info: ProjectInfo) : DrawingEvent()
    /** Levels and layers (Building.kt): the layer new drawing goes on, shown or hidden, the user's own layers. */
    @Serializable
    data class SetActiveLayer(val layerId: String) : DrawingEvent()
    @Serializable
    data class SetLayerVisible(val layerId: String, val visible: Boolean) : DrawingEvent()
    /** The quick on/off: the eye for the active layer's group and the plan under it, faint on/off for any other layer. */
    @Serializable
    data class ToggleLayerShown(val layerId: String) : DrawingEvent()
    @Serializable
    data class AddLayer(val name: String, val levelId: String? = null) : DrawingEvent()
    @Serializable
    data class RenameLayer(val layerId: String, val name: String) : DrawingEvent()
    @Serializable
    data class RemoveLayer(val layerId: String) : DrawingEvent()
    /** New building inputs (datum, subfloor, joists, sill, block, footing, floors). */
    @Serializable
    data class SetBuilding(val settings: BuildingSettings) : DrawingEvent()
    /** Moves the selection by a typed distance (Nov 27), inches, east and south positive; a wall's meeting walls follow. */
    @Serializable
    data class MoveSelectedBy(val eastIn: Float, val southIn: Float) : DrawingEvent()
    /** After a Move drag: the same direction, exactly this far from where it started (Nov 27). */
    @Serializable
    data class MakeLastMoveExactly(val inches: Float) : DrawingEvent()
    @Serializable
    object DismissLastMove : DrawingEvent()
    /** A traced roof area (RoofAreas, Dan Nov 26): added, or replaced when one has its id. */
    @Serializable
    data class SetRoofArea(val area: RoofArea) : DrawingEvent()
    /** Takes away a traced roof area, by id or name. */
    @Serializable
    data class DeleteRoofArea(val id: String) : DrawingEvent()
    /** Build the foundation and first-floor framing from the first-floor plan, or stop. */
    @Serializable
    data class SetAutoLayers(val on: Boolean) : DrawingEvent()
    /** Throw away the built layers (edits and deletions included) and build them again. */
    @Serializable
    object RebuildLayers : DrawingEvent()
    /** Draw the active layer's parts (foundation or framing) once, to match the first-floor plan. */
    @Serializable
    object MatchFloorPlan : DrawingEvent()
    /** The selected objects onto another layer (undoable; nothing moves). */
    @Serializable
    data class MoveSelectionToLayer(val layerId: String) : DrawingEvent()
    /** Cabinet codes (W2430, BD18...) on the plan, on or off. */
    @Serializable
    data class ShowCabinetLabels(val show: Boolean) : DrawingEvent()
    /** Door and window marks (D1, W1...) on the plan, for the schedules. */
    @Serializable
    data class ShowOpeningMarks(val show: Boolean) : DrawingEvent()
    @Serializable
    object DeleteSelectedElement : DrawingEvent()
    @Serializable
    object DeselectElement : DrawingEvent()
    @Serializable
    object ExplodeSelectedElement : DrawingEvent()
    /** Puts 2x4 walls along a shower's tiled sides, outside its footprint. */
    @Serializable
    data class AddShowerWalls(val id: String) : DrawingEvent()
    /** Sets this door style and hardware on every cabinet in the drawing. */
    @Serializable
    data class ApplyCabinetStyle(val style: Int, val hardware: Int) : DrawingEvent()
    /** Switch [switchId] controls (or stops controlling) light or fan [deviceId]. */
    @Serializable
    data class ToggleSwitchLeg(val switchId: String, val deviceId: String) : DrawingEvent()
    /** Electrical to code (ElectricalCode): every room on the active level, or with [x], [y] (world px) just the room there. */
    @Serializable
    data class ElectricalToCode(val x: Float? = null, val y: Float? = null) : DrawingEvent()
    /** Sets the electrical choices of the room at [x], [y] (world px) and lays that room out again. */
    @Serializable
    data class SetRoomElectrical(val x: Float, val y: Float, val opts: RoomElectrical) : DrawingEvent()
    /** Closes the room electrical card. */
    @Serializable
    data object CloseRoomElectrical : DrawingEvent()
    /** Names the room around ([x], [y]) (world px): the name goes on its label, over its size (RoomNames, Nov 28). */
    @Serializable
    data class NameRoom(val x: Float, val y: Float, val name: String) : DrawingEvent()
    @Serializable
    data object CloseNameRoom : DrawingEvent()
    /** Swaps symbol [id] for library symbol [name], [widthIn] x [depthIn], in the same spot and turn. */
    @Serializable
    data class ReplaceSymbol(val id: String, val name: String, val widthIn: Float, val depthIn: Float) : DrawingEvent()
    /** Changes wall [id] to wall type [name] (its thickness), on the same centre line. */
    @Serializable
    data class SetWallKind(val id: String, val name: String) : DrawingEvent()
    /** Sets wall [id] to [lengthIn] long, its west (or north) end staying put. */
    @Serializable
    data class SetWallLength(val id: String, val lengthIn: Float) : DrawingEvent()
    /** Changes cabinet [id] to library cabinet [name] at [widthIn] (null = its stock width); its back stays against the wall. */
    @Serializable
    data class SetCabinetKind(val id: String, val name: String, val widthIn: Float? = null) : DrawingEvent()
    /** Gives every cabinet in [id]'s room the door style and hardware [id] has. */
    @Serializable
    data class ApplyCabinetStyleInRoom(val id: String) : DrawingEvent()
    /** Changes door or window [id] to library kind [name] at [widthIn] (null = its width now), in place; a door keeps its wall side. */
    @Serializable
    data class SetOpeningKind(val id: String, val name: String, val widthIn: Float? = null) : DrawingEvent()
    /** Moves a hinged door's hinge to the other jamb, in place. */
    @Serializable
    data class SwapDoorHinge(val id: String) : DrawingEvent()
    /** Swings a hinged door into the other side of its wall, hinge on the same jamb. */
    @Serializable
    data class SwingDoorOtherSide(val id: String) : DrawingEvent()
    /** Dimensions around the outside of the plan and across each room; replaces the last set. */
    @Serializable
    data class DimensionPlan(val outside: Boolean, val rooms: Boolean, val interiorToCenter: Boolean, val elevations: Boolean = true) : DrawingEvent()
    /** Dimensions an elevation: widths across the top, heights down the side; replaces its last set. */
    @Serializable
    data class DimensionElevation(val id: String) : DrawingEvent()
    /**
     * Elevations of the walls of the room around [point] (world px), each cut to the room, placed in a row above
     * the plan. [walls]: any of "north", "east", "south", "west"; empty = all (Nov 25).
     */
    @Serializable
    data class AddRoomElevations(val point: Offset, val walls: List<String> = emptyList()) : DrawingEvent()
    /** Art from a picture, hung on the wall face elevation [elevationId] shows, centred on it at 60" (WallDecor, Nov 25). */
    @Serializable
    data class AddWallArt(val elevationId: String, val bitmap: ImageBitmap, val name: String = "") : DrawingEvent()
    /** Wallpaper from a swatch picture on the wall face elevation [elevationId] shows, [repeatIn] wide. */
    @Serializable
    data class AddWallpaper(val elevationId: String, val bitmap: ImageBitmap, val repeatIn: Float = 21f) : DrawingEvent()
    /** A generic wallpaper (WallpaperPatterns, Natalie Nov 28) on the face the elevation shows: pattern key, colourway index. */
    @Serializable
    data class AddPatternWallpaper(val elevationId: String, val pattern: String, val colorway: Int = 0) : DrawingEvent()
    /** Art or wallpaper again from a picture already in the project ([kind] 0 art, 1 wallpaper). */
    @Serializable
    data class UsePicture(val elevationId: String, val pictureId: String, val kind: Int = 0) : DrawingEvent()
    /** Changes art or wallpaper: xIn, bottomIn, widthIn, heightIn, frameIn or repeatIn (inches). */
    @Serializable
    data class UpdateWallDecor(val id: String, val property: String, val value: String) : DrawingEvent()
    @Serializable
    data class RemoveWallDecor(val id: String) : DrawingEvent()
    /**
     * Paint on the wall face elevation [elevationId] shows (Nov 26), one colour per face: [hex] (#D1C7B8), or leave it
     * blank and give a Sherwin-Williams [code] (SW 7036) to use that colour; [name] for the schedule.
     */
    /** A room for the finish schedule (Nov 26): the room around [point] (world px), named [name]. */
    @Serializable
    data class AddRoomFinish(val point: Offset, val name: String = "") : DrawingEvent()
    /** Sets a finish-schedule room's [field]: name, floor, walls, ceiling, trim or notes (blank floor or walls = from the drawing). */
    @Serializable
    data class UpdateRoomFinish(val id: String, val field: String, val value: String) : DrawingEvent()
    @Serializable
    data class RemoveRoomFinish(val id: String) : DrawingEvent()
    /** Types an appliance's or plumbing fixture's make, model, finish or notes (field: make, model, finish, notes). */
    @Serializable
    data class UpdateItemSpec(val elementId: String, val field: String, val value: String) : DrawingEvent()
    /** Types a room's cabinet line, hardware, countertop or notes for the cabinet schedule (field: cabinets, hardware, countertop, notes). */
    @Serializable
    data class UpdateCabinetSpec(val room: String, val field: String, val value: String) : DrawingEvent()
    @Serializable
    data class SetWallPaint(val elevationId: String, val hex: String = "", val code: String = "", val name: String = "") : DrawingEvent()
    /** Deletes the dimensions the plan ([AutoDimension.PLAN_OWNER]) or an elevation (its id) made. */
    @Serializable
    data class RemoveOwnedDimensions(val ownerId: String) : DrawingEvent()
}
