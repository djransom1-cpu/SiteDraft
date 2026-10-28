package com.example.sitedraft.ui.canvas

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.atan2

private const val AUTOSAVE_DEBOUNCE_MS = 1500L
/** Name of the project to reopen at the next launch. */
private const val LAST_PROJECT_FILE = "last_project.txt"

class DrawingManager(application: android.app.Application) : AndroidViewModel(application) {
    private val _state = mutableStateOf(CanvasState())
    val state: State<CanvasState> = _state

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
    // Which endpoint of a selected Dimension is being resize-dragged. Dimension is the only
    // element type with two independently-grabbable handles (both ends need to be draggable so
    // a placed dimension can be pulled clear of the geometry it measures, not just extended from
    // a single fixed end) - everything else in the resize system assumes one handle.
    private var resizingDimensionStart = false
    // The grip at the dimension line's own midpoint - dragging it adjusts `offset` (how far the
    // dimension line sits from the measured points) without touching start/end.
    private var resizingDimensionOffset = false
    private var moveBasePoint: Offset? = null
    // Bosch GLM laser client. Lazy so unit tests (stub Application) never touch Bluetooth.
    private val laserDelegate = lazy { com.example.sitedraft.data.laser.GlmLaserClient(getApplication()) }
    val laser: com.example.sitedraft.data.laser.GlmLaserClient by laserDelegate

    // Tool to return to when the S Pen side button is released; null while not button-erasing.
    private var toolBeforeStylusErase: DrawingTool? = null
    // How far the dragged object currently sits from where the pointer alone would have put it,
    // i.e. the snap adjustment applied so far this gesture. See moveDrawing's plain-move delta.
    private var moveSnapCorrection: Offset = Offset.Zero

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun onEvent(event: DrawingEvent) {
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
            is DrawingEvent.SelectColor -> _state.value = _state.value.copy(currentColor = event.color)
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
            is DrawingEvent.UpdateZoom -> _state.value = _state.value.copy(zoom = (_state.value.zoom * event.delta).coerceIn(0.1f, 100f))
            is DrawingEvent.UpdatePan -> _state.value = _state.value.copy(pan = _state.value.pan + event.delta)
            // Picking a symbol, outline or room arms placement; the next canvas tap says where it goes.
            is DrawingEvent.SelectSymbol -> _state.value = _state.value.copy(pendingPlacement = Placement.Symbol(event.name, event.category), placementPreview = null)
            is DrawingEvent.StartPlacement -> _state.value = _state.value.copy(pendingPlacement = event.placement, placementPreview = null)
            is DrawingEvent.PreviewPlacementAt -> if (_state.value.pendingPlacement != null) {
                _state.value = _state.value.copy(placementPreview = findSnapPoint(event.point))
            }
            is DrawingEvent.PlaceAt -> _state.value.pendingPlacement?.let { pending ->
                _state.value = _state.value.copy(pendingPlacement = null, placementPreview = null)
                val point = findSnapPoint(event.point)
                when (pending) {
                    is Placement.Symbol -> {
                        // A door or window tapped on a wall sits in that wall, turned to match it.
                        val opening = if (pending.category == PlanSymbols.DOORS_WINDOWS)
                            FloorPlan.alignOpening(pending.name, event.point, point, _state.value) else null
                        if (opening != null) addSymbol(pending.name, pending.category, opening.center, opening.rotation, opening.size)
                        else addSymbol(pending.name, pending.category, point)
                    }
                    is Placement.Shell -> placeShell(point, pending)
                    is Placement.Room -> placeRoom(point, pending)
                }
            }
            is DrawingEvent.CancelPlacement -> _state.value = _state.value.copy(pendingPlacement = null, placementPreview = null)
            is DrawingEvent.RotateSelection -> rotateSelection(event.degrees)
            is DrawingEvent.SelectElements -> _state.value = _state.value.copy(
                selectedElementId = event.ids.singleOrNull(),
                selectedElementIds = if (event.ids.size > 1) event.ids else emptySet()
            )
            is DrawingEvent.UpdateElementProperty -> updateElementProperty(event.id, event.property, event.value)
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
            DrawingEvent.ResetZoom -> _state.value = _state.value.copy(zoom = 1f, pan = Offset.Zero)
            is DrawingEvent.SaveProject -> saveNow()
            is DrawingEvent.LoadProject -> loadProject(event.context)
            is DrawingEvent.RefreshProjectList -> refreshProjectList(event.context)
            is DrawingEvent.ExportPdf -> exportToPdf(event.context)
            is DrawingEvent.ExportDxf -> exportToDxf(event.context)
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
        }
    }

    private fun deleteSelectedElement() {
        val groupIds = _state.value.selectedElementIds
        if (groupIds.isNotEmpty()) {
            pushUndoSnapshot()
            _state.value = _state.value.copy(
                elements = _state.value.elements.filterNot { it.id in groupIds },
                selectedElementIds = emptySet()
            )
            return
        }
        val selectedId = _state.value.selectedElementId ?: return
        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements.filter { it.id != selectedId },
            selectedElementId = null
        )
    }

    // Autosave is debounced and runs OFF the main thread. It used to serialize the whole project
    // to JSON - every undo snapshot included, see @Transient on CanvasState.undoHistory - and
    // write it synchronously after every single stroke. Each pen lift therefore froze the UI
    // for longer and longer as the drawing grew: the "delay before the next stroke" feel.
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        val dir = getApplication<android.app.Application>().filesDir ?: return // null under JVM unit tests
        saveScope.launch {
            try {
                val name = File(dir, LAST_PROJECT_FILE).takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }
                    ?: _state.value.projectName
                val file = File(dir, "project_${name.replace(" ", "_")}.json")
                if (!file.exists()) return@launch
                val loaded = json.decodeFromString(CanvasState.serializer(), file.readText())
                val listFile = File(dir, "project_list.txt")
                val list = if (listFile.exists()) listFile.readLines().sorted() else emptyList()
                withContext(Dispatchers.Main) {
                    _state.value = loaded.copy(availableProjects = list, selectedElementId = null, selectedElementIds = emptySet())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun triggerAutoSave() {
        registerProjectName()
        val app = getApplication<android.app.Application>()
        pendingSave?.cancel()
        pendingSave = saveScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            saveProject(app)
        }
    }

    // Explicit Save: no debounce, still off the main thread.
    private fun saveNow() {
        registerProjectName()
        val app = getApplication<android.app.Application>()
        pendingSave?.cancel()
        pendingSave = saveScope.launch { saveProject(app) }
    }

    // The in-memory project list is updated here, on the caller's (main) thread, so the
    // background writer never touches _state.
    private fun registerProjectName() {
        val name = _state.value.projectName
        if (name !in _state.value.availableProjects) {
            _state.value = _state.value.copy(availableProjects = (_state.value.availableProjects + name).sorted())
        }
    }

    override fun onCleared() {
        if (laserDelegate.isInitialized()) laser.disconnect()
        pendingSave?.cancel()
        super.onCleared()
    }

    private fun newProject() {
        _state.value = CanvasState(
            projectName = "Project ${System.currentTimeMillis() / 1000}",
            unitSystem = _state.value.unitSystem,
            scale = _state.value.scale,
            backgroundColor = _state.value.backgroundColor,
            background = _state.value.background,
            availableProjects = _state.value.availableProjects
        )
    }

    private fun duplicatedWithOffset(element: DrawingElement, offset: Offset): DrawingElement = when (element) {
        is DrawingElement.Freehand -> element.copy(id = UUID.randomUUID().toString(), points = element.points.map { it.copy(offset = it.offset + offset) })
        is DrawingElement.Line -> element.copy(id = UUID.randomUUID().toString(), start = element.start + offset, end = element.end + offset)
        is DrawingElement.Rectangle -> element.copy(id = UUID.randomUUID().toString(), topLeft = element.topLeft + offset)
        is DrawingElement.Oval -> element.copy(id = UUID.randomUUID().toString(), topLeft = element.topLeft + offset)
        is DrawingElement.Triangle -> element.copy(id = UUID.randomUUID().toString(), topLeft = element.topLeft + offset)
        is DrawingElement.Circle -> element.copy(id = UUID.randomUUID().toString(), center = element.center + offset)
        is DrawingElement.Symbol -> element.copy(id = UUID.randomUUID().toString(), center = element.center + offset)
        is DrawingElement.Dimension -> element.copy(id = UUID.randomUUID().toString(), start = element.start + offset, end = element.end + offset)
        is DrawingElement.Text -> element.copy(id = UUID.randomUUID().toString(), topLeft = element.topLeft + offset)
        is DrawingElement.Image -> element.copy(id = UUID.randomUUID().toString(), topLeft = element.topLeft + offset)
        is DrawingElement.Pdf -> element.copy(id = UUID.randomUUID().toString(), topLeft = element.topLeft + offset)
        is DrawingElement.Dxf -> element.copy(id = UUID.randomUUID().toString(), topLeft = element.topLeft + offset)
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
                element.copy(id = UUID.randomUUID().toString(), start = element.start + perp, end = element.end + perp)
            }
            is DrawingElement.Rectangle -> {
                val d = pixelOffset * direction
                val center = element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
                val newSize = Size((element.size.width + d * 2f).coerceAtLeast(1f), (element.size.height + d * 2f).coerceAtLeast(1f))
                element.copy(
                    id = UUID.randomUUID().toString(), 
                    topLeft = center - Offset(newSize.width / 2, newSize.height / 2),
                    size = newSize
                )
            }
            is DrawingElement.Circle -> {
                val d = pixelOffset * direction
                element.copy(id = UUID.randomUUID().toString(), radius = (element.radius + d).coerceAtLeast(1f))
            }
            is DrawingElement.Oval -> {
                val d = pixelOffset * direction
                val center = element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
                val newSize = Size((element.size.width + d * 2f).coerceAtLeast(1f), (element.size.height + d * 2f).coerceAtLeast(1f))
                element.copy(
                    id = UUID.randomUUID().toString(), 
                    topLeft = center - Offset(newSize.width / 2, newSize.height / 2),
                    size = newSize
                )
            }
            is DrawingElement.Symbol -> {
                // A parallel copy, like a line: moved across its thin axis (a wall is offset
                // sideways, not grown). Side A/B pick which way.
                val across = if (element.size.width >= element.size.height) Offset(0f, 1f) else Offset(1f, 0f)
                val shift = rotatePoint(across, Offset.Zero, element.rotation) * (pixelOffset * direction)
                element.copy(id = UUID.randomUUID().toString(), center = element.center + shift)
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
            (Math.round(offset.x / step) * step).toFloat(),
            (Math.round(offset.y / step) * step).toFloat()
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
        val others = _state.value.elements.filter { it.id != excludeId }

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
        val targetElement = _state.value.elements.firstOrNull { el ->
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

    // Selection hit-testing: among every element whose contains() matches this tap, pick the one
    // whose actual geometry is nearest to the tap, not just whichever happens to sit last in the
    // elements list. lastOrNull{} was the previous behavior, and it meant that whenever a tap's
    // threshold radius (48dp - a generous fingertip-sized net) reached two or more elements at
    // once - routine right after Explode, where 4 short edges of a small rectangle all sit close
    // together - which one got selected depended on element creation order, not on which one the
    // tap was actually closest to. Confirmed via a field recording of repeatedly tapping a
    // specific edge of an exploded rectangle and getting an unrelated edge (or nothing) instead.
    private fun findHitElement(offset: Offset, threshold: Float): DrawingElement? {
        return _state.value.elements
            .filter { it.contains(offset, threshold) }
            .minByOrNull { distanceToElement(it, offset) }
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
            is DrawingElement.Dimension -> getNearestPointOnSegment(p, element.start, element.end)
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
                    c + Offset((rx * Math.cos(theta)).toFloat(), (ry * Math.sin(theta)).toFloat())
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
        val angleRad = Math.toRadians(angleDegrees.toDouble())
        val cos = Math.cos(angleRad).toFloat()
        val sin = Math.sin(angleRad).toFloat()

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
        val otherElements = _state.value.elements.filter { it.id != selectedElement.id }
        
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
        moveSnapCorrection = Offset.Zero
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
                    } else if (hitElement != null) {
                        _state.value = _state.value.copy(selectedElementId = hitElement.id, selectedElementIds = emptySet())
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
                    } else if (hitElement != null) {
                        // New selection
                        isResizing = false
                        isRotating = false
                        moveBasePoint = null
                        _state.value = _state.value.copy(selectedElementId = hitElement.id)
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
                
                if (element != null) {
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
                                if (it.id == selectedId) it.move(delta) else it
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
        val groupIds = _state.value.selectedElementIds
        if (_state.value.currentTool == DrawingTool.SELECT && groupIds.size > 1) {
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
                elements = _state.value.elements.map { if (it.id in groupIds) it.move(delta) else it }
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
                                        val angle = Math.toDegrees(atan2((snappedOffset.y - center.y).toDouble(), (snappedOffset.x - center.x).toDouble())).toFloat() + 90f
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
                            } else it
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
                            if (it.id == selectedId) it.move(delta) else it
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
        val boxStart = _state.value.boxSelectStart
        if (_state.value.currentTool == DrawingTool.SELECT && boxStart != null) {
            val boxEnd = _state.value.boxSelectEnd ?: boxStart
            val dragDist = (boxEnd - boxStart).getDistance()
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
                val hitIds = _state.value.elements
                    .filter { it.boundingRect().overlaps(selectionRect) }
                    .map { it.id }
                    .toSet()
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
                _state.value = if (dist < 2f) {
                    _state.value.copy(currentDrawingElement = null, lastSnapPoint = null)
                } else {
                    _state.value.copy(awaitingDimensionOffset = true, lastSnapPoint = null)
                }
            } else {
                // Phase 2 (placing the offset) just finished - the dimension is fully defined now.
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
            _state.value = _state.value.copy(lastSnapPoint = null)
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
            e is DrawingElement.Symbol && e.category == PlanSymbols.WALLS && e.rotation == 0f -> {
                val horizontal = e.size.width >= e.size.height
                val t = minOf(e.size.width, e.size.height)
                val half = if (horizontal) Offset(e.size.width / 2f, 0f) else Offset(0f, e.size.height / 2f)
                Trimmable(e, e.center - half, e.center + half, t / 2f) { a, b ->
                    val len = (b - a).getDistance()
                    e.copy(center = (a + b) / 2f, size = if (horizontal) Size(len, t) else Size(t, len))
                }
            }
            else -> null
        }
    }

    private fun DrawingElement.withNewId(): DrawingElement = when (this) {
        is DrawingElement.Line -> copy(id = UUID.randomUUID().toString())
        is DrawingElement.Symbol -> copy(id = UUID.randomUUID().toString())
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

        val otherEdges = _state.value.elements.filter { it.id != target.element.id }.flatMap { it.edgeSegments() }
        // t is parametrized along fixedPoint->movingPoint: t=1 is the current (un-extended)
        // endpoint, so t > 1 means genuinely further out in the direction the line already
        // points, not doubling back over itself.
        val nearestT = otherEdges.mapNotNull { (p3, p4) ->
            val (t, u) = lineIntersectionParams(fixedPoint, movingPoint, p3, p4) ?: return@mapNotNull null
            if (t > 1.0001f && u in -0.0001f..1.0001f) t else null
        }.minOrNull() ?: return // nothing ahead to extend to

        val newEndpoint = fixedPoint + dir * nearestT
        val updated = if (extendingStart) target.rebuild(newEndpoint, target.end) else target.rebuild(target.start, newEndpoint)

        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements.map { if (it.id == target.element.id) updated else it },
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
        val explodable = element is DrawingElement.Rectangle || element is DrawingElement.Triangle || element is DrawingElement.Symbol
        if (!explodable) return

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
            s.elements.filterIsInstance<DrawingElement.Symbol>(), s.inchesToPx(1f)
        )
        pushUndoSnapshot()
        _state.value = _state.value.copy(elements = _state.value.elements + walls, selectedElementId = null)
        triggerAutoSave()
    }

    /** Room: [insideCorner] is the top-left INSIDE corner; its walls go around the outside. */
    private fun placeRoom(insideCorner: Offset, room: Placement.Room) {
        val s = _state.value
        val t = wallThicknessPx(room.wallName)
        val w = room.width * s.scale.ratio
        val d = room.depth * s.scale.ratio
        // Butting up to an existing wall: share it rather than standing a few inches off it.
        val corner = FloorPlan.magnetRoomCorner(
            insideCorner, w, d, s.elements.filterIsInstance<DrawingElement.Symbol>(), s.inchesToPx(FloorPlan.ROOM_MAGNET_IN)
        )
        val inside = Rect(corner, Size(w, d))
        val outer = Rect(inside.left - t, inside.top - t, inside.right + t, inside.bottom + t)
        val walls = FloorPlan.wallsAround(
            outer, t, room.wallName, s.currentColor,
            s.elements.filterIsInstance<DrawingElement.Symbol>(), s.inchesToPx(1f)
        )
        val label = FloorPlan.roomLabel(room, inside, s)
        pushUndoSnapshot()
        _state.value = _state.value.copy(elements = _state.value.elements + walls + label, selectedElementId = null)
        triggerAutoSave()
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
        rotation: Float = 0f, sizeOverride: Size? = null
    ) {
        val ratio = _state.value.scale.ratio
        var properties = emptyMap<String, Float>()
        
        var size = when {
            category in PlanSymbols.categories -> {
                val (wIn, dIn) = PlanSymbols.footprintInches[name] ?: Pair(24f, 24f)
                properties = mapOf("widthInches" to wIn, "depthInches" to dIn)
                Size(_state.value.inchesToPx(wIn), _state.value.inchesToPx(dIn))
            }
            category == "Lumber" -> {
                val actual = when (name) {
                    "2x4" -> Pair(1.5f, 3.5f)
                    "2x6" -> Pair(1.5f, 5.5f)
                    "2x8" -> Pair(1.5f, 7.25f)
                    "2x10" -> Pair(1.5f, 9.25f)
                    "2x12" -> Pair(1.5f, 11.25f)
                    "1x4" -> Pair(0.75f, 3.5f)
                    "1x6" -> Pair(0.75f, 5.5f)
                    "1x8" -> Pair(0.75f, 7.25f)
                    else -> Pair(1.5f, 3.5f)
                }
                properties = mapOf("widthInches" to actual.first, "heightInches" to actual.second)
                Size((actual.first / 12f) * ratio, (actual.second / 12f) * ratio)
            }
            category == "Plywood" -> {
                val thicknessInches = when (name) {
                    "Plywood 7/16\"" -> 0.4375f
                    "Plywood 1/2\"" -> 0.5f
                    "Plywood 5/8\"" -> 0.625f
                    "Plywood 3/4\"" -> 0.75f
                    else -> 0.5f
                }
                properties = mapOf("thicknessInches" to thicknessInches, "lengthFeet" to 2f)
                Size((thicknessInches / 12f) * ratio, 2f * ratio)
            }
            name == "Tree" -> Size(120f, 120f)
            name == "Bush" -> Size(80f, 80f)
            name == "Light Pole" -> Size(60f, 60f)
            // Weld glyphs (fillet triangle, V-groove, etc.) span up to +-40 world units in
            // their own drawing code - much more of their 100x100 box than icons like Outlet
            // (radius 15) or Junction Box (+-20) use of theirs - so at the same default size
            // they read as noticeably larger. Placing them smaller brings them in line with
            // the other schematic icons instead of dwarfing the lumber/plywood they annotate.
            category == "Welding" -> Size(50f, 50f)
            else -> Size(100f, 100f)
        }
        
        if (name == "I-Beam" || name.contains("W-Beam")) {
            properties = when (name) {
                "W-Beam (W12x26)" -> mapOf("flangeWidth" to 6.49f, "webHeight" to 12.2f, "flangeThickness" to 0.38f, "webThickness" to 0.23f)
                "W-Beam (W10x49)" -> mapOf("flangeWidth" to 10.0f, "webHeight" to 10.0f, "flangeThickness" to 0.56f, "webThickness" to 0.34f)
                "W-Beam (W8x31)" -> mapOf("flangeWidth" to 8.0f, "webHeight" to 8.0f, "flangeThickness" to 0.435f, "webThickness" to 0.285f)
                "W-Beam (W6x15)" -> mapOf("flangeWidth" to 6.0f, "webHeight" to 6.0f, "flangeThickness" to 0.26f, "webThickness" to 0.23f)
                else -> mapOf("flangeWidth" to 6.0f, "webHeight" to 6.0f, "flangeThickness" to 0.38f, "webThickness" to 0.25f)
            }
            size = calculateBeamPixelSize(properties, _state.value.scale.ratio, _state.value.unitSystem)
        }
        
        val symbol = DrawingElement.Symbol(
            symbolName = name,
            category = category,
            center = center,
            size = sizeOverride ?: size,
            color = _state.value.currentColor,
            rotation = rotation,
            properties = properties
        )
        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements + symbol,
            selectedElementId = symbol.id
        )
        triggerAutoSave()
    }

    private fun updateElementProperty(id: String, property: String, value: String) {
        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements.map { element ->
                if (element.id == id) {
                    when (property) {
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
                            element.rotate(newValue - element.rotation)
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
        triggerAutoSave()
    }

    private fun importImage(bitmap: Bitmap) {
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

    private fun importPdf(bitmaps: List<Bitmap>) {
        if (bitmaps.isEmpty()) return
        val element = DrawingElement.Pdf(
            pages = bitmaps,
            topLeft = Offset(50f, 50f),
            size = Size(bitmaps[0].width.toFloat(), bitmaps[0].height.toFloat())
        )
        pushUndoSnapshot()
        _state.value = _state.value.copy(
            elements = _state.value.elements + element
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

    private fun saveProject(context: android.content.Context) {
        try {
            val projectData = json.encodeToString(CanvasState.serializer(), _state.value)
            val fileName = "project_${_state.value.projectName.replace(" ", "_")}.json"
            val dir = context.filesDir ?: return // null under JVM unit tests (stub Application)
            val file = File(dir, fileName)
            file.writeText(projectData)
            
            // Also update a project list file
            val listFile = File(dir, "project_list.txt")
            val currentList = if (listFile.exists()) listFile.readLines().toSet() else emptySet()
            val newList = (currentList + _state.value.projectName).sorted()
            listFile.writeText(newList.joinToString("\n"))
            File(dir, LAST_PROJECT_FILE).writeText(_state.value.projectName)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun refreshProjectList(context: android.content.Context) {
        val listFile = File(context.filesDir, "project_list.txt")
        val list = if (listFile.exists()) listFile.readLines().sorted() else emptyList()
        _state.value = _state.value.copy(availableProjects = list)
    }

    private fun loadProject(context: android.content.Context) {
        // This is a generic load, usually we want to load a SPECIFIC project
        loadProjectByName(context, _state.value.projectName)
    }

    fun loadProjectByName(context: android.content.Context, name: String) {
        try {
            val fileName = "project_${name.replace(" ", "_")}.json"
            val dir = context.filesDir ?: return // null under JVM unit tests (stub Application)
            val file = File(dir, fileName)
            if (file.exists()) {
                val projectData = file.readText()
                val loadedState = json.decodeFromString(CanvasState.serializer(), projectData)
                // When loading, preserve the available projects list from current session
                _state.value = loadedState.copy(availableProjects = _state.value.availableProjects)
                File(dir, LAST_PROJECT_FILE).writeText(name)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }



    private fun exportToPdf(context: android.content.Context) {
        val elements = _state.value.elements

        // Page orientation follows the drawing, and the drawing is fit to the page with a
        // margin. The old export used a fixed 1080x1920 page and copied canvas coordinates
        // straight onto it, so anything drawn after panning away from the origin, or larger
        // than one phone screen, simply fell off the page.
        val bounds = elements.map { it.boundingRect() }
            .reduceOrNull { acc, r ->
                Rect(minOf(acc.left, r.left), minOf(acc.top, r.top), maxOf(acc.right, r.right), maxOf(acc.bottom, r.bottom))
            }
        val landscape = bounds != null && bounds.width > bounds.height
        val pageWidth = if (landscape) 1920 else 1080
        val pageHeight = if (landscape) 1080 else 1920
        val margin = 60f

        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdfDocument.startPage(pageInfo)

        // Fit transform, expressed as the same pan/zoom pair the on-screen canvas uses. Never
        // scale UP: a drawing smaller than the page keeps its on-screen size so the project's
        // "1' = N px" scale survives into the PDF; larger drawings shrink to fit.
        var exportScale = 1f
        var exportPan = Offset.Zero
        if (bounds != null) {
            val fitScale = minOf(
                (pageWidth - 2f * margin) / maxOf(bounds.width, 1f),
                (pageHeight - 2f * margin) / maxOf(bounds.height, 1f)
            )
            exportScale = minOf(1f, fitScale)
            val offsetX = (pageWidth - bounds.width * exportScale) / 2f
            val offsetY = (pageHeight - bounds.height * exportScale) / 2f
            exportPan = Offset(offsetX - bounds.left * exportScale, offsetY - bounds.top * exportScale)
        }

        // Render through the SAME code path as the on-screen canvas (drawDocumentContents in
        // DrawingCanvas.kt), so the PDF shows exactly what the screen shows - symbols,
        // dimensions, text, rotation, images - instead of the old hand-rolled subset that
        // silently dropped all of those. zoom/pan carry the fit transform so screen-constant
        // sizes (dimension text, grid line weights) come out at their normal on-screen size;
        // interactive-only state (selection, in-progress element, snap and precise-move
        // markers) is cleared so none of it leaks into the document.
        val exportState = _state.value.copy(
            zoom = exportScale,
            pan = exportPan,
            selectedElementId = null,
            selectedElementIds = emptySet(),
            currentDrawingElement = null,
            lastSnapPoint = null,
            boxSelectStart = null,
            boxSelectEnd = null,
            preciseMoveFromPoint = null
        )
        // Device density, not 1f: symbol icon strokes are specified in dp, and the PDF should
        // match what the user sees on this device.
        val density = Density(context.resources.displayMetrics.density)
        CanvasDrawScope().draw(
            density,
            LayoutDirection.Ltr,
            ComposeCanvas(page.canvas),
            Size(pageWidth.toFloat(), pageHeight.toFloat())
        ) {
            drawRect(exportState.backgroundColor)
            withTransform(
                transformBlock = {
                    translate(exportPan.x, exportPan.y)
                    scale(exportScale, exportScale, Offset.Zero)
                }
            ) {
                drawDocumentContents(exportState)
            }
        }
        pdfDocument.finishPage(page)
        val file = File(context.getExternalFilesDir(null), "SiteDraft_Export.pdf")
        try {
            FileOutputStream(file).use { pdfDocument.writeTo(it) }
        } catch (e: Exception) {
            pdfDocument.close()
            android.widget.Toast.makeText(context, "PDF export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        pdfDocument.close()
        shareExportedFile(context, file, "application/pdf")
    }

    // Confirms an export and opens the system share sheet for the file. Until this, the file
    // landed silently in the app's external files dir - not visible in Files/Downloads without
    // a file manager - with no indication that anything had happened. The FileProvider declared
    // in the manifest ("<applicationId>.fileprovider", paths in res/xml/file_paths.xml) is what
    // lets the receiving app read it.
    private fun shareExportedFile(context: android.content.Context, file: File, mimeType: String) {
        android.widget.Toast.makeText(context, "Exported ${file.name}", android.widget.Toast.LENGTH_SHORT).show()
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = android.content.Intent.createChooser(send, "Share ${file.name}").apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Could not open share sheet: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun exportToDxf(context: android.content.Context) {
        val dxf = StringBuilder()
        dxf.append("0\nSECTION\n2\nENTITIES\n")
        
        _state.value.elements.forEach { element ->
            when (element) {
                is DrawingElement.Line -> addLineToDxf(dxf, element.start, element.end)
                is DrawingElement.Rectangle -> {
                    val tr = Offset(element.topLeft.x + element.size.width, element.topLeft.y)
                    val br = Offset(element.topLeft.x + element.size.width, element.topLeft.y + element.size.height)
                    val bl = Offset(element.topLeft.x, element.topLeft.y + element.size.height)
                    addLineToDxf(dxf, element.topLeft, tr)
                    addLineToDxf(dxf, tr, br)
                    addLineToDxf(dxf, br, bl)
                    addLineToDxf(dxf, bl, element.topLeft)
                }
                is DrawingElement.Circle -> {
                    dxf.append("0\nCIRCLE\n8\n0\n")
                    dxf.append("10\n${element.center.x}\n20\n${element.center.y}\n")
                    dxf.append("40\n${element.radius}\n")
                }
                is DrawingElement.Freehand -> {
                    for (i in 0 until element.points.size - 1) {
                        addLineToDxf(dxf, element.points[i].offset, element.points[i+1].offset)
                    }
                }
                is DrawingElement.Oval, is DrawingElement.Triangle,
                is DrawingElement.Image, is DrawingElement.Pdf, is DrawingElement.Dxf, 
                is DrawingElement.Symbol,
                is DrawingElement.Dimension,
                is DrawingElement.Text -> {
                    // Not supported for DXF export in this simple version
                }
            }
        }
        
        dxf.append("0\nENDSEC\n0\nEOF\n")
        val file = File(context.getExternalFilesDir(null), "SiteDraft_Export.dxf")
        file.writeText(dxf.toString())
    }

    private fun addLineToDxf(sb: StringBuilder, start: Offset, end: Offset) {
        sb.append("0\nLINE\n8\n0\n")
        sb.append("10\n${start.x}\n20\n${start.y}\n")
        sb.append("11\n${end.x}\n21\n${end.y}\n")
    }
}

sealed class DrawingEvent {
    data class Start(val offset: Offset, val pressure: Float = 1f, val resizeThreshold: Float = 40f) : DrawingEvent()
    data class Move(val offset: Offset, val pressure: Float = 1f) : DrawingEvent()
    object End : DrawingEvent()
    object ToggleEraser : DrawingEvent()
    // S Pen side button held (true) / released (false) - see DrawingCanvas.
    data class StylusButtonErase(val active: Boolean) : DrawingEvent()
    data class SelectTool(val tool: DrawingTool) : DrawingEvent()
    data class SelectColor(val color: Color) : DrawingEvent()
    data class SelectStrokeWidth(val width: Float) : DrawingEvent()
    data class SelectBackground(val background: DrawingBackground) : DrawingEvent()
    data class SelectBackgroundColor(val color: Color) : DrawingEvent()
    data class SelectDimensionFormat(val format: DimensionFormat) : DrawingEvent()
    data class UpdateZoom(val delta: Float) : DrawingEvent()
    data class UpdatePan(val delta: Offset) : DrawingEvent()
    data class SelectSymbol(val name: String, val category: String) : DrawingEvent()
    /** Arms a building outline or room from the floor-plan panel. */
    data class StartPlacement(val placement: Placement) : DrawingEvent()
    /** Puts whatever is pending at this canvas point (snapped). */
    data class PlaceAt(val point: Offset) : DrawingEvent()
    /** Pen hovering or dragging while something is pending: show where it would land. */
    data class PreviewPlacementAt(val point: Offset) : DrawingEvent()
    object CancelPlacement : DrawingEvent()
    /** Turns the selection (group or single element) clockwise by [degrees] about its centre. */
    data class RotateSelection(val degrees: Float) : DrawingEvent()
    /** Selects exactly these elements (one = single selection, several = group). */
    data class SelectElements(val ids: Set<String>) : DrawingEvent()
    data class UpdateElementProperty(val id: String, val property: String, val value: String) : DrawingEvent()
    data class UpdateScale(val ratio: Float, val label: String) : DrawingEvent()
    data class SelectUnitSystem(val unit: UnitSystem) : DrawingEvent()
    data class ImportImage(val bitmap: Bitmap) : DrawingEvent()
    // A finished line placed programmatically (laser wall mode).
    data class AddLine(val start: Offset, val end: Offset) : DrawingEvent()
    // Laser wall mode.
    data class SetWallMode(val active: Boolean) : DrawingEvent()
    data class SetWallDirection(val direction: WallDirection) : DrawingEvent()
    data class SetWallCursor(val offset: Offset) : DrawingEvent()
    data class AddWall(val lengthPx: Float) : DrawingEvent()
    object CloseRoom : DrawingEvent()
    object NewRoom : DrawingEvent()
    object ContinueWallsFromSelectedLine : DrawingEvent()
    data class ImportPdf(val bitmaps: List<Bitmap>) : DrawingEvent()
    data class ImportDxf(val elements: List<DrawingElement>) : DrawingEvent()
    object Undo : DrawingEvent()
    object Redo : DrawingEvent()
    object Clear : DrawingEvent()
    object ResetZoom : DrawingEvent()
    data class SaveProject(val context: android.content.Context) : DrawingEvent()
    data class LoadProject(val context: android.content.Context) : DrawingEvent()
    data class RefreshProjectList(val context: android.content.Context) : DrawingEvent()
    data class ExportPdf(val context: android.content.Context) : DrawingEvent()
    data class ExportDxf(val context: android.content.Context) : DrawingEvent()
    data class OffsetSelectedElement(val distance: Float, val direction: Int = 1) : DrawingEvent()
    data class ToggleStylusOnlyDrawing(val enabled: Boolean) : DrawingEvent()
    object DuplicateSelectedElement : DrawingEvent()
    data class RenameProject(val name: String) : DrawingEvent()
    object NewProject : DrawingEvent()
    data class ToggleSnap(val enabled: Boolean) : DrawingEvent()
    data class ToggleGridSnap(val enabled: Boolean) : DrawingEvent()
    object DeleteSelectedElement : DrawingEvent()
    object DeselectElement : DrawingEvent()
    object ExplodeSelectedElement : DrawingEvent()
}
