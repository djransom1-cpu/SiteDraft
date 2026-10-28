package com.example.sitedraft.ui.canvas

import com.example.sitedraft.platform.nowMillis
import com.example.sitedraft.util.format
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.cos
import kotlin.concurrent.Volatile
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.atan2
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import com.example.sitedraft.ui.canvas.DrawingElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.sitedraft.data.PALM_REJECTION_AUTO_WINDOW_MS
import com.example.sitedraft.data.PalmRejectionMode
import com.example.sitedraft.platform.stylusButtonHeld
import androidx.compose.ui.text.rememberTextMeasurer

@Composable
fun DrawingCanvas(
    state: CanvasState,
    onEvent: (DrawingEvent) -> Unit,
    modifier: Modifier = Modifier,
    palmRejection: PalmRejectionMode = PalmRejectionMode.AUTO,
) {
    val density = LocalDensity.current
    val resizeThreshold = with(density) { 48.dp.toPx() }

    // Always-current copies for the pointerInput block below (see the note on its key).
    val latestState = rememberUpdatedState(state)
    val latestOnEvent = rememberUpdatedState(onEvent)
    val latestPalmRejection = rememberUpdatedState(palmRejection)
    // Text on the canvas (labels, dimensions, text elements) is laid out with Compose text.
    TextDrawing.measurer = rememberTextMeasurer()
    val documentLayer = rememberGraphicsLayer()
    val documentCache = remember { DocumentLayerCache() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // Keyed on Unit deliberately. This used to be keyed on (currentTool, pan, zoom), which
            // tears the whole handler down and restarts it every time any of those change - and
            // pan/zoom change on EVERY frame of a pinch. The restart discards isDrawing /
            // isTransforming and abandons the in-flight awaitEachGesture, so a pinch (or a tool
            // toggle from the S Pen button) mid-gesture killed whatever was being drawn and the
            // rest of that gesture was handled by a fresh block with no memory of it. The block
            // now reads live state through latestState/latestOnEvent instead.
            .pointerInput(Unit) {
                var isDrawing = false
                var wallDragging = false
                var isTransforming = false
                // A pending symbol/room is being positioned: ghost follows the pen, lift places it.
                var placing = false
                // Survives across gestures (the block is keyed on Unit) - AUTO palm rejection
                // needs to remember the S Pen between strokes, not just within one.
                var lastStylusSeenAt = 0L
                var stylusButtonErasing = false
                
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        // Palm rejection (Stylus Only Drawing) was only ever applied below, to
                        // the single-pointer drawing branch - a stray touch (palm, other hand)
                        // landing alongside an active stylus was still counted as a genuine
                        // second pointer here, immediately ending whatever was being drawn/
                        // resized (`onEvent(End)`) and hijacking the rest of the gesture into
                        // pinch-zoom/pan math using the stylus position and the accidental touch
                        // position as the two "fingers" - which is exactly what a fluctuating,
                        // seemingly-random zoom % during an otherwise single-point drag turned
                        // out to be (confirmed via a field recording: 1100% -> 1029% -> 1071%
                        // while the user believed they were only dragging with the S Pen). When
                        // Stylus Only Drawing is on and a stylus is actually present, touch
                        // pointers are excluded here so they can never register as a second
                        // transform pointer in the first place.
                        val hasStylus = event.changes.any { it.type == PointerType.Stylus || it.type == PointerType.Eraser }
                        // Palm rejection. AUTO only rejects finger touch while an S Pen has been
                        // seen (touching OR hovering) within PALM_REJECTION_AUTO_WINDOW_MS, so a
                        // finger-only user, or a capacitive aftermarket pen (which reports as plain
                        // touch), is never locked out - while a palm resting beside an S Pen is.
                        val now = nowMillis()
                        if (hasStylus) lastStylusSeenAt = now
                        val rejectTouch = when (latestPalmRejection.value) {
                            PalmRejectionMode.OFF -> false
                            PalmRejectionMode.STYLUS_ONLY -> true
                            PalmRejectionMode.AUTO -> now - lastStylusSeenAt < PALM_REJECTION_AUTO_WINDOW_MS
                        }
                        val relevantChanges = if (rejectTouch && hasStylus) {
                            event.changes.filter { it.type == PointerType.Stylus || it.type == PointerType.Eraser }
                        } else {
                            event.changes
                        }
                        val pointersCount = relevantChanges.size

                        // S Pen side button = momentary eraser, as in Samsung Notes: hold it while
                        // drawing and that stroke erases; let go and the previous tool comes back.
                        // It used to TOGGLE the eraser on every button press, so the stroke AFTER a
                        // button-erase (button released) erased too, and the next one flipped back.
                        // StylusButton.held is set by the Android Activity from Samsung's raw pen actions;
                        // stylusButtonHeld() reads the button bits the platform reports for this event.
                        val buttonHeld = StylusButton.held || event.stylusButtonHeld()
                        // Press with the button already held, OR the button pressed partway through a stroke.
                        if ((event.type == PointerEventType.Press || isDrawing) && buttonHeld && !stylusButtonErasing) {
                            stylusButtonErasing = true
                            latestOnEvent.value(DrawingEvent.StylusButtonErase(true))
                        }

                        if (pointersCount >= 2) {
                            if (isDrawing) {
                                latestOnEvent.value(DrawingEvent.End)
                                isDrawing = false
                            }
                            isTransforming = true

                            val p1 = relevantChanges[0]
                            val p2 = relevantChanges[1]
                            val prevDist = (p1.previousPosition - p2.previousPosition).getDistance()
                            val currDist = (p1.position - p2.position).getDistance()
                            val zoomFactor = if (prevDist > 0f) currDist / prevDist else 1f
                            
                            val panOffset = ((p1.position - p1.previousPosition) + (p2.position - p2.previousPosition)) / 2f
                            
                            event.changes.forEach { it.consume() }
                            
                            if (zoomFactor != 1f) {
                                latestOnEvent.value(DrawingEvent.UpdateZoom(zoomFactor))
                            }
                            if (panOffset != Offset.Zero) {
                                latestOnEvent.value(DrawingEvent.UpdatePan(panOffset))
                            }
                        } else if (!isTransforming) {
                            // Prioritize stylus input for palm rejection
                            val stylusPointer = event.changes.find { (it.type == PointerType.Stylus) || (it.type == PointerType.Eraser) }
                            val touchPointer = event.changes.find { it.type == PointerType.Touch }
                            
                            // If Stylus Only Drawing is enabled, ignore touch for drawing completely
                            val pointer = if (rejectTouch) {
                                touchPointer?.let { if (stylusPointer == null) it.consume() } // Consume touch to prevent further processing if no stylus
                                stylusPointer
                            } else {
                                stylusPointer ?: touchPointer
                            }
                            
                            if (pointer != null) {
                                if ((stylusPointer != null) && (touchPointer != null)) {
                                    touchPointer.consume()
                                }

                                when (event.type) {
                                    PointerEventType.Press -> {
                                        // Final check: only start drawing if we're allowed to draw with this pointer type
                                        val canDraw = !rejectTouch || pointer.type != PointerType.Touch
                                        if (canDraw) {
                                            val transformedOffset = (pointer.position - latestState.value.pan) / latestState.value.zoom
                                            if (latestState.value.pendingPlacement != null) {
                                                // A symbol, outline or room picked from a panel: show its ghost
                                                // here, follow the pen while it drags, place it when it lifts.
                                                placing = true
                                                latestOnEvent.value(DrawingEvent.PreviewPlacementAt(transformedOffset))
                                            } else
                                            // Laser wall mode owns taps near its on-canvas cursor: tap to place the
                                            // first point, tap an arrow to choose the direction, drag the marker.
                                            when (val hit = wallModeHit(latestState.value, transformedOffset)) {
                                                null -> {
                                                    isDrawing = true
                                                    latestOnEvent.value(DrawingEvent.Start(transformedOffset, pointer.pressure, resizeThreshold / latestState.value.zoom))
                                                }
                                                WallHit.Place -> latestOnEvent.value(DrawingEvent.SetWallCursor(transformedOffset))
                                                is WallHit.Arrow -> latestOnEvent.value(DrawingEvent.SetWallDirection(hit.direction))
                                                WallHit.Cursor -> wallDragging = true
                                            }
                                            pointer.consume()
                                        }
                                    }
                                    PointerEventType.Move -> {
                                        if (latestState.value.pendingPlacement != null) {
                                            // Hovering S Pen or a drag: keep the ghost under the pen.
                                            val transformedOffset = (pointer.position - latestState.value.pan) / latestState.value.zoom
                                            latestOnEvent.value(DrawingEvent.PreviewPlacementAt(transformedOffset))
                                            if (pointer.pressed) pointer.consume()
                                        } else if (wallDragging && pointer.pressed) {
                                            val transformedOffset = (pointer.position - latestState.value.pan) / latestState.value.zoom
                                            latestOnEvent.value(DrawingEvent.SetWallCursor(transformedOffset))
                                            pointer.consume()
                                        } else if (isDrawing && pointer.pressed) {
                                            // Replay the intermediate samples Android coalesced into this frame
                                            // BEFORE the current one. Without them a fast pen stroke is built from
                                            // one point per frame (~60/s) and comes out as visible straight
                                            // segments - the "not fluid" S Pen feel.
                                            pointer.historical.forEach { h ->
                                                val hOffset = (h.position - latestState.value.pan) / latestState.value.zoom
                                                latestOnEvent.value(DrawingEvent.Move(hOffset, pointer.pressure)) // historical samples carry no pressure here
                                            }
                                            val transformedOffset = (pointer.position - latestState.value.pan) / latestState.value.zoom
                                            latestOnEvent.value(DrawingEvent.Move(transformedOffset, pointer.pressure))
                                            pointer.consume()
                                        }
                                    }
                                    PointerEventType.Release -> {
                                        wallDragging = false
                                        if (placing) {
                                            placing = false
                                            val transformedOffset = (pointer.position - latestState.value.pan) / latestState.value.zoom
                                            latestOnEvent.value(DrawingEvent.PlaceAt(transformedOffset))
                                            pointer.consume()
                                        } else if (isDrawing) {
                                            latestOnEvent.value(DrawingEvent.End)
                                            isDrawing = false
                                            pointer.consume()
                                        }
                                    }
                                }
                            }
                        }
                        
                        if (event.changes.all { !it.pressed }) {
                            isTransforming = false
                            isDrawing = false
                            if (stylusButtonErasing) {
                                // Stroke is over (End was sent from the Release branch above):
                                // hand the previous tool back.
                                stylusButtonErasing = false
                                latestOnEvent.value(DrawingEvent.StylusButtonErase(false))
                            }
                        }
                    }
                }
            },
    ) {
        // Committed content (background, grid, every element) is recorded into a GraphicsLayer
        // and only re-recorded when something it depends on changes. While a stroke is in
        // progress none of that changes, so each frame costs one drawLayer plus the live
        // stroke, instead of re-drawing the whole grid and every element behind the pen.
        // A zero-sized layer cannot be recorded (transient during layout changes such as rotation).
        if (size.width >= 1f && size.height >= 1f && !documentCache.matches(state, size)) {
            documentLayer.record(this, layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) {
                drawRect(state.backgroundColor)
                withTransform(
                    transformBlock = {
                        translate(state.pan.x, state.pan.y)
                        scale(state.zoom, state.zoom, Offset.Zero)
                    }
                ) {
                    drawDocumentContents(state, selectedElementId = state.selectedElementId)
                }
            }
            documentCache.update(state, size)
        }
        drawLayer(documentLayer)

        withTransform(
            transformBlock = {
                translate(state.pan.x, state.pan.y)
                scale(state.zoom, state.zoom, Offset.Zero)
            }
        ) {

            // Laser wall mode: cursor marker with direction arrows (screen-constant size).
            state.wallMode.takeIf { it.active }?.let { wall -> drawWallCursor(wall, state.zoom) }

            // Ghost of the symbol / room / outline being positioned, under the hovering or
            // dragging pen, so the user sees where it lands before lifting.
            state.pendingPlacement?.let { placement ->
                state.placementPreview?.let { at -> drawPlacementGhost(placement, at, state) }
            }

            // Box-selected group: a lighter-weight outline per member (no individual resize/
            // rotate handles - the group only supports moving/deleting/duplicating as a whole).
            if (state.selectedElementIds.isNotEmpty()) {
                state.elements.forEach { element ->
                    if (element.id in state.selectedElementIds) {
                        val r = element.boundingRect()
                        drawRect(
                            color = Color.Blue,
                            topLeft = r.topLeft,
                            size = r.size,
                            style = Stroke(width = 3f / state.zoom)
                        )
                    }
                }
            }

            state.currentDrawingElement?.let { element ->
                drawElement(element, state = state)
                
                // AUTO-DIMENSIONING: Draw dimensions WHILE drawing Line or Rectangle
                if (element is DrawingElement.Line) {
                    val start = element.start
                    val end = element.end
                    if (start != end) {
                        val dist = (end - start).getDistance()
                        val realValue = dist / state.scale.ratio
                        val text = formatDimension(realValue, state.unitSystem, state.dimensionFormat)
                        
                        val mid = (start + end) / 2f
                        // Divided by zoom so the label keeps a constant on-screen size inside the zoomed transform.
                        val labelColor = if (state.backgroundColor == Color.White) Color.Black else Color.White
                        drawLabel(text, mid - Offset(0f, 20f / state.zoom), 32f / state.zoom, labelColor)
                    }
                } else if (element is DrawingElement.Rectangle) {
                    val w = element.size.width / state.scale.ratio
                    val h = element.size.height / state.scale.ratio
                    
                    val wText = "W: " + formatDimension(w, state.unitSystem, state.dimensionFormat)
                    val hText = "H: " + formatDimension(h, state.unitSystem, state.dimensionFormat)
                    
                    val labelColor = if (state.backgroundColor == Color.White) Color.Black else Color.White
                    drawLabel(wText, Offset(element.topLeft.x + element.size.width / 2, element.topLeft.y - 10f / state.zoom), 28f / state.zoom, labelColor)
                    drawLabel(hText, Offset(element.topLeft.x - 10f / state.zoom, element.topLeft.y + element.size.height / 2), 28f / state.zoom, labelColor, rotationDegrees = -90f)
                }
            }

            state.lastSnapPoint?.let { snapPt ->
                drawCircle(
                    color = Color.Yellow,
                    radius = 8f / state.zoom,
                    center = snapPt,
                    style = Stroke(width = 2f / state.zoom)
                )
            }

            val boxStart = state.boxSelectStart
            val boxEnd = state.boxSelectEnd
            if (boxStart != null && boxEnd != null) {
                val r = Rect(
                    minOf(boxStart.x, boxEnd.x), minOf(boxStart.y, boxEnd.y),
                    maxOf(boxStart.x, boxEnd.x), maxOf(boxStart.y, boxEnd.y)
                )
                drawRect(color = Color.Blue.copy(alpha = 0.15f), topLeft = r.topLeft, size = r.size)
                drawRect(color = Color.Blue, topLeft = r.topLeft, size = r.size, style = Stroke(width = 2f / state.zoom))
            }
        }
    }
}

// Everything the canvas shows in world space - the grid and every element - minus the
// interactive overlays (box-select outlines, in-progress element, snap marker). Shared by the
// on-screen canvas above and the PDF exporter (DrawingManager.exportToPdf) so the export can
// never drift from what the screen shows: before this the exporter had its own hand-rolled
// subset that silently dropped symbols, dimensions, text and rotation. Callers set up the
// pan/zoom transform themselves; `state.zoom` must match it since the renderer divides
// screen-constant sizes (label text, handle radii, grid line weights) by it.
fun DrawScope.drawDocumentContents(state: CanvasState, selectedElementId: String? = null) {
    drawCanvasBackground(state.background, state)
    state.elements.forEach { element ->
        drawElement(element, isSelected = element.id == selectedElementId, state = state)
    }
}

private fun DrawScope.drawElement(element: DrawingElement, isSelected: Boolean = false, state: CanvasState) {
    val zoom = state.zoom
    rotate(
        element.rotation,
        pivot = when (element) {
            is DrawingElement.Rectangle -> element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
            is DrawingElement.Oval -> element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
            is DrawingElement.Triangle -> element.topLeft + Offset(element.size.width / 2, element.size.height / 2)
            is DrawingElement.Symbol -> element.center
            is DrawingElement.Circle -> element.center
            is DrawingElement.Line -> element.start + (element.end - element.start) / 2f
            else -> Offset.Zero
        },
    ) {
        // Fill goes under the outline, inside the same rotation. Only closed shapes have one.
        element.fillOrNull()?.takeIf { it.pattern != FillPattern.NONE }?.let { fill ->
            element.closedOutline()?.let { (outline, bounds) -> drawShapeFill(outline, bounds, fill, element.color) }
        }
        when (element) {
            is DrawingElement.Freehand -> {
                if (element.points.size < 2) return@rotate
                val drawColor = if (element.isHighlighter) element.color.copy(alpha = 0.4f) else element.color
                val pts = element.points
                // Pressure is smoothed over a 5-sample window: raw S Pen pressure jitters from
                // sample to sample, and drawing each segment at its own raw width made strokes
                // look lumpy and uneven.
                val smoothed = FloatArray(pts.size) { i ->
                    var sum = 0f
                    var n = 0
                    for (k in maxOf(0, i - 2)..minOf(pts.size - 1, i + 2)) { sum += pts[k].pressure; n++ }
                    sum / n
                }
                for (i in 0 until pts.size - 1) {
                    val p1 = pts[i]
                    val p2 = pts[i + 1]
                    val width = element.strokeWidth * ((smoothed[i] + smoothed[i + 1]) / 2f)
                    drawLine(
                        color = drawColor,
                        start = p1.offset,
                        end = p2.offset,
                        strokeWidth = maxOf(1f, width),
                        cap = StrokeCap.Round
                    )
                }
            }
            is DrawingElement.Line -> {
                drawLine(
                    color = element.color,
                    start = element.start,
                    end = element.end,
                    strokeWidth = element.strokeWidth,
                    cap = StrokeCap.Round
                )
            }
            is DrawingElement.Rectangle -> {
                drawRect(
                    color = element.color,
                    topLeft = element.topLeft,
                    size = element.size,
                    style = Stroke(width = element.strokeWidth)
                )
            }
            is DrawingElement.Circle -> {
                drawCircle(
                    color = element.color,
                    center = element.center,
                    radius = element.radius,
                    style = Stroke(width = element.strokeWidth)
                )
            }
            is DrawingElement.Oval -> {
                drawOval(
                    color = element.color,
                    topLeft = element.topLeft,
                    size = element.size,
                    style = Stroke(width = element.strokeWidth)
                )
            }
            is DrawingElement.Triangle -> {
                val path = Path().apply {
                    if (element.triangleType == "Right-Angled") {
                        moveTo(element.topLeft.x, element.topLeft.y)
                        lineTo(element.topLeft.x, element.topLeft.y + element.size.height)
                        lineTo(element.topLeft.x + element.size.width, element.topLeft.y + element.size.height)
                    } else {
                        moveTo(element.topLeft.x + element.size.width / 2f, element.topLeft.y)
                        lineTo(element.topLeft.x, element.topLeft.y + element.size.height)
                        lineTo(element.topLeft.x + element.size.width, element.topLeft.y + element.size.height)
                    }
                    close()
                }
                drawPath(
                    path = path,
                    color = element.color,
                    style = Stroke(width = element.strokeWidth)
                )
            }
            is DrawingElement.Symbol -> {
                val cyanColor = Color(0xFF80E0FF)
                val whiteColor = if (state.backgroundColor == Color.White) Color.Black else Color.White
                
                // Use the standard stroke weight for parametric symbols
                val stroke = Stroke(width = element.strokeWidth, cap = StrokeCap.Round)
                
                if (element.category in PlanSymbols.categories) {
                    drawPlanSymbol(element, state, lineColor = whiteColor, accentColor = cyanColor)
                } else if (element.category == "Lumber" || element.category == "Plywood") {
                    withTransform({
                        translate(element.center.x, element.center.y)
                    }) {
                        val w = element.size.width
                        val h = element.size.height
                        val hw = w / 2f
                        val hh = h / 2f
                        
                        if (element.category == "Lumber") {
                            // Lumber's cross-section is small (a 2x4 is ~6 world units thick
                            // at default scale, a 1x4 barely half that), so the standard 2px
                            // symbol stroke - meant for full-size outlines - reads as a heavy,
                            // near-solid block once it's drawn on both the rect outline and
                            // both diagonal X lines. Scale it down for Lumber specifically.
                            val lumberStrokeWidth = stroke.width * 0.4f
                            drawRect(color = whiteColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = Stroke(width = lumberStrokeWidth, cap = StrokeCap.Round))
                            // Draw the X
                            drawLine(color = whiteColor, start = Offset(-hw, -hh), end = Offset(hw, hh), strokeWidth = lumberStrokeWidth)
                            drawLine(color = whiteColor, start = Offset(-hw, hh), end = Offset(hw, -hh), strokeWidth = lumberStrokeWidth)
                        } else {
                            // Plywood - opaque fill first so an overlapping/adjacent sheet
                            // (e.g. two sheets snapped corner-to-corner via Pt-to-Pt) is fully
                            // occluded by whichever sheet is drawn on top, instead of both
                            // sheets' outlines and grain lines showing through at the seam.
                            //
                            // Plywood's cross-section is just as thin as Lumber's (a 1/2" sheet
                            // is ~2 world units at default scale) but never got Lumber's stroke
                            // scale-down above. A full-width Stroke draws centered on the path,
                            // so it bleeds ~1 world unit past BOTH sides of the true edge - on a
                            // ~2-unit-wide board that's almost the entire half-width, so the
                            // rendered sheet reads as a solid, over-thick bar whose apparent
                            // edges overshoot the real (flush-snapped) edge that handles/butting
                            // actually use. Scale it down the same way Lumber does.
                            val plywoodStrokeWidth = stroke.width * 0.4f
                            drawRect(color = state.backgroundColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = Fill)
                            drawRect(color = cyanColor, topLeft = Offset(-hw, -hh), size = Size(w, h), style = Stroke(width = plywoodStrokeWidth, cap = StrokeCap.Round))
                            // Simple wood grain - horizontal lines spaced by 2" (world units)
                            val grainStep = (2f / 12f) * state.scale.ratio
                            if (grainStep > 2f / state.zoom) { // Only draw if visible
                                var yPos = -hh + grainStep
                                while (yPos < hh) {
                                    drawLine(color = cyanColor.copy(alpha = 0.5f), start = Offset(-hw, yPos), end = Offset(hw, yPos), strokeWidth = plywoodStrokeWidth / 2)
                                    yPos += grainStep
                                }
                            }
                        }
                    }
                } else {
                    val iconStrokeWidth = 2.5f.dp.toPx() / (element.size.width / 100f).coerceAtLeast(0.1f)
                    val iconStroke = Stroke(width = iconStrokeWidth, cap = StrokeCap.Round)
                    
                    withTransform({
                        translate(element.center.x, element.center.y)
                        scale(element.size.width / 100f, element.size.height / 100f, pivot = Offset.Zero)
                    }) {
                        when (element.symbolName) {
                            // --- ELECTRICAL ---
                            "Outlet", "Duplex Outlet" -> {
                                drawCircle(color = cyanColor, radius = 15f, center = Offset.Zero, style = iconStroke)
                                drawLine(color = whiteColor, start = Offset(-25f, -6f), end = Offset(25f, -6f), strokeWidth = iconStrokeWidth, cap = StrokeCap.Round)
                                drawLine(color = whiteColor, start = Offset(-25f, 6f), end = Offset(25f, 6f), strokeWidth = iconStrokeWidth, cap = StrokeCap.Round)
                            }
                            "Switch" -> {
                                drawCircle(color = cyanColor, radius = 12f, center = Offset.Zero, style = iconStroke)
                                drawLine(color = whiteColor, start = Offset(-15f, 15f), end = Offset(15f, -15f), strokeWidth = iconStrokeWidth, cap = StrokeCap.Round)
                                // Small ticks for S
                                drawLine(color = whiteColor, start = Offset(10f, -15f), end = Offset(15f, -15f), strokeWidth = iconStrokeWidth)
                                drawLine(color = whiteColor, start = Offset(-15f, 15f), end = Offset(-10f, 15f), strokeWidth = iconStrokeWidth)
                            }
                            "Light Fixture", "Ceiling Light" -> {
                                drawCircle(color = cyanColor, radius = 15f, center = Offset.Zero, style = iconStroke)
                                drawLine(color = whiteColor, start = Offset(-10.6f, -10.6f), end = Offset(10.6f, 10.6f), strokeWidth = iconStrokeWidth)
                                drawLine(color = whiteColor, start = Offset(-10.6f, 10.6f), end = Offset(10.6f, -10.6f), strokeWidth = iconStrokeWidth)
                            }
                            "Junction Box" -> {
                                drawRect(color = cyanColor, topLeft = Offset(-20f, -20f), size = Size(40f, 40f), style = iconStroke)
                                drawLine(color = whiteColor, start = Offset(-20f, -20f), end = Offset(20f, 20f), strokeWidth = iconStrokeWidth)
                                drawLine(color = whiteColor, start = Offset(-20f, 20f), end = Offset(20f, -20f), strokeWidth = iconStrokeWidth)
                            }
                            
                            // --- WELDING SYMBOLS ---
                            "Fillet Weld" -> {
                                val path = Path().apply {
                                    moveTo(0f, 0f)
                                    lineTo(0f, -40f)
                                    lineTo(40f, 0f)
                                    close()
                                }
                                drawPath(path = path, color = cyanColor, style = iconStroke)
                            }
                            "V-Groove Weld" -> {
                                val path = Path().apply {
                                    moveTo(-30f, -40f)
                                    lineTo(0f, 0f)
                                    lineTo(30f, -40f)
                                }
                                drawPath(path = path, color = cyanColor, style = iconStroke)
                            }
                            "Bevel Weld" -> {
                                drawLine(color = cyanColor, start = Offset(0f, 0f), end = Offset(0f, -40f), strokeWidth = iconStrokeWidth)
                                drawLine(color = cyanColor, start = Offset(0f, 0f), end = Offset(30f, -40f), strokeWidth = iconStrokeWidth)
                            }
                            "Square Weld" -> {
                                drawLine(color = cyanColor, start = Offset(-10f, 0f), end = Offset(-10f, -40f), strokeWidth = iconStrokeWidth)
                                drawLine(color = cyanColor, start = Offset(10f, 0f), end = Offset(10f, -40f), strokeWidth = iconStrokeWidth)
                            }
                            "Field Weld" -> {
                                drawLine(color = whiteColor, start = Offset.Zero, end = Offset(0f, -40f), strokeWidth = iconStrokeWidth)
                                val path = Path().apply {
                                    moveTo(0f, -40f)
                                    lineTo(20f, -30f)
                                    lineTo(0f, -20f)
                                    close()
                                }
                                drawPath(path = path, color = Color.Red, style = iconStroke)
                            }
                            "Plug/Slot Weld" -> {
                                drawRect(color = cyanColor, topLeft = Offset(-20f, -30f), size = Size(40f, 30f), style = iconStroke)
                            }
                            "Spot Weld" -> {
                                drawCircle(color = cyanColor, radius = 20f, center = Offset(0f, -20f), style = iconStroke)
                            }
                            "Weld All Around" -> {
                                drawLine(color = whiteColor, start = Offset.Zero, end = Offset(0f, -40f), strokeWidth = iconStrokeWidth)
                                drawCircle(color = cyanColor, radius = 9f, center = Offset.Zero, style = iconStroke)
                            }

                            // --- PLUMBING ---
                            "Sink" -> {
                                drawRoundRect(color = cyanColor, topLeft = Offset(-40f, -40f), size = Size(80f, 80f), cornerRadius = CornerRadius(12f, 12f), style = iconStroke)
                                drawRoundRect(color = whiteColor, topLeft = Offset(-30f, -30f), size = Size(60f, 55f), cornerRadius = CornerRadius(8f, 8f), style = iconStroke)
                                drawCircle(color = cyanColor, radius = 4f, center = Offset(0f, -35f), style = iconStroke)
                                drawLine(color = cyanColor, start = Offset(0f, -35f), end = Offset(0f, -25f), strokeWidth = iconStrokeWidth, cap = StrokeCap.Round)
                                drawCircle(color = whiteColor, radius = 2.5f, center = Offset(-10f, -35f), style = iconStroke)
                                drawCircle(color = whiteColor, radius = 2.5f, center = Offset(10f, -35f), style = iconStroke)
                            }
                            "Toilet" -> {
                                drawRect(color = cyanColor, topLeft = Offset(-30f, -45f), size = Size(60f, 20f), style = iconStroke)
                                drawOval(color = whiteColor, topLeft = Offset(-18f, -25f), size = Size(36f, 70f), style = iconStroke)
                            }
                            "Valve" -> {
                                val path = Path().apply {
                                    moveTo(-30f, -20f)
                                    lineTo(0f, 0f)
                                    lineTo(-30f, 20f)
                                    close()
                                    moveTo(30f, -20f)
                                    lineTo(0f, 0f)
                                    lineTo(30f, 20f)
                                    close()
                                }
                                drawPath(path = path, color = cyanColor, style = iconStroke)
                                drawLine(color = whiteColor, start = Offset(0f, 0f), end = Offset(0f, -30f), strokeWidth = iconStrokeWidth, cap = StrokeCap.Round)
                                drawLine(color = whiteColor, start = Offset(-15f, -30f), end = Offset(15f, -30f), strokeWidth = iconStrokeWidth, cap = StrokeCap.Round)
                            }
                            "Faucet" -> {
                                drawLine(color = cyanColor, start = Offset(-30f, 0f), end = Offset(10f, 0f), strokeWidth = iconStrokeWidth)
                                drawLine(color = cyanColor, start = Offset(10f, 0f), end = Offset(10f, 15f), strokeWidth = iconStrokeWidth)
                                drawLine(color = whiteColor, start = Offset(-10f, -15f), end = Offset(-10f, 15f), strokeWidth = iconStrokeWidth)
                            }
                            
                            // --- HVAC ---
                            "Vent", "Register/Vent" -> {
                                drawRect(color = cyanColor, topLeft = Offset(-35f, -35f), size = Size(70f, 70f), style = iconStroke)
                                drawLine(color = whiteColor, start = Offset(-35f, -35f), end = Offset(35f, 35f), strokeWidth = iconStrokeWidth)
                                drawLine(color = whiteColor, start = Offset(-35f, 35f), end = Offset(35f, -35f), strokeWidth = iconStrokeWidth)
                            }
                            "AC Unit" -> {
                                drawRect(color = cyanColor, topLeft = Offset(-40f, -40f), size = Size(80f, 80f), style = Stroke(width = iconStrokeWidth * 1.5f, cap = StrokeCap.Round))
                                drawCircle(color = whiteColor, radius = 30f, center = Offset.Zero, style = iconStroke)
                                drawCircle(color = whiteColor, radius = 6f, center = Offset.Zero, style = iconStroke)
                                drawLine(color = whiteColor, start = Offset(4.2f, 4.2f), end = Offset(21.2f, 21.2f), strokeWidth = iconStrokeWidth, cap = StrokeCap.Round)
                                drawLine(color = whiteColor, start = Offset(-4.2f, 4.2f), end = Offset(-21.2f, 21.2f), strokeWidth = iconStrokeWidth, cap = StrokeCap.Round)
                                drawLine(color = whiteColor, start = Offset(4.2f, -4.2f), end = Offset(21.2f, -21.2f), strokeWidth = iconStrokeWidth, cap = StrokeCap.Round)
                                drawLine(color = whiteColor, start = Offset(-4.2f, -4.2f), end = Offset(-21.2f, -21.2f), strokeWidth = iconStrokeWidth, cap = StrokeCap.Round)
                            }
                            "Thermostat" -> {
                                drawCircle(color = cyanColor, radius = 30f, center = Offset.Zero, style = iconStroke)
                                drawCircle(color = whiteColor, radius = 22f, center = Offset.Zero, style = iconStroke)
                                drawLine(color = whiteColor, start = Offset(-12f, 0f), end = Offset(12f, 0f), strokeWidth = iconStrokeWidth)
                                drawLine(color = whiteColor, start = Offset(0f, -12f), end = Offset(0f, 12f), strokeWidth = iconStrokeWidth)
                            }
                            
                            // --- STRUCTURAL ---
                            "I-Beam", "W-Beam (W12x26)", "W-Beam (W10x49)", "W-Beam (W8x31)", "W-Beam (W6x15)", "Custom W-Beam" -> {
                                val properties = element.properties.ifEmpty {
                                    when (element.symbolName) {
                                        "W-Beam (W12x26)" -> mapOf("flangeWidth" to 6.49f, "webHeight" to 12.2f, "flangeThickness" to 0.38f, "webThickness" to 0.23f)
                                        "W-Beam (W10x49)" -> mapOf("flangeWidth" to 10.0f, "webHeight" to 10.0f, "flangeThickness" to 0.56f, "webThickness" to 0.34f)
                                        "W-Beam (W8x31)" -> mapOf("flangeWidth" to 8.0f, "webHeight" to 8.0f, "flangeThickness" to 0.435f, "webThickness" to 0.285f)
                                        "W-Beam (W6x15)" -> mapOf("flangeWidth" to 6.0f, "webHeight" to 6.0f, "flangeThickness" to 0.26f, "webThickness" to 0.23f)
                                        else -> mapOf("flangeWidth" to 6.0f, "webHeight" to 6.0f, "flangeThickness" to 0.38f, "webThickness" to 0.25f)
                                    }
                                }
                                
                                val fWidth = properties["flangeWidth"] ?: 6.0f
                                val wHeight = properties["webHeight"] ?: 6.0f
                                val fThickness = properties["flangeThickness"] ?: 0.38f
                                val wThickness = properties["webThickness"] ?: 0.25f
                                
                                val tf = 100f * (fThickness / wHeight)
                                val tw = 100f * (wThickness / fWidth)
                                
                                val path = Path().apply {
                                    moveTo(-50f, -50f)
                                    lineTo(50f, -50f)
                                    lineTo(50f, -50f + tf)
                                    lineTo(tw / 2f, -50f + tf)
                                    lineTo(tw / 2f, 50f - tf)
                                    lineTo(50f, 50f - tf)
                                    lineTo(50f, 50f)
                                    lineTo(-50f, 50f)
                                    lineTo(-50f, 50f - tf)
                                    lineTo(-tw / 2f, 50f - tf)
                                    lineTo(-tw / 2f, -50f + tf)
                                    lineTo(-50f, -50f + tf)
                                    close()
                                }
                                drawPath(path = path, color = cyanColor, style = iconStroke)
                                drawPath(path = path, color = cyanColor.copy(alpha = 0.2f))
                            }
                            "L-Channel" -> {
                                val path = Path().apply {
                                    moveTo(-30f, -35f)
                                    lineTo(-15f, -35f)
                                    lineTo(-15f, 20f)
                                    lineTo(30f, 20f)
                                    lineTo(30f, 35f)
                                    lineTo(-30f, 35f)
                                    close()
                                }
                                drawPath(path = path, color = cyanColor, style = iconStroke)
                            }
                            "Column" -> {
                                drawRect(color = cyanColor, topLeft = Offset(-25f, -25f), size = Size(50f, 50f), style = iconStroke)
                                drawRect(color = whiteColor, topLeft = Offset(-15f, -15f), size = Size(30f, 30f), style = iconStroke)
                            }
                            "Footing" -> {
                                drawRect(color = whiteColor, topLeft = Offset(-15f, -15f), size = Size(30f, 30f), style = iconStroke)
                                drawRect(color = cyanColor, topLeft = Offset(-35f, -35f), size = Size(70f, 70f), style = iconStroke)
                            }
                            
                            // --- SITE LAYOUT (NEW) ---
                            "Tree" -> {
                                // Trunk asterisk
                                val trunkLength = 10f
                                drawLine(color = whiteColor, start = Offset(-trunkLength, 0f), end = Offset(trunkLength, 0f), strokeWidth = iconStrokeWidth * 0.8f)
                                drawLine(color = whiteColor, start = Offset(0f, -trunkLength), end = Offset(0f, trunkLength), strokeWidth = iconStrokeWidth * 0.8f)
                                drawLine(color = whiteColor, start = Offset(-trunkLength * 0.7f, -trunkLength * 0.7f), end = Offset(trunkLength * 0.7f, trunkLength * 0.7f), strokeWidth = iconStrokeWidth * 0.8f)
                                drawLine(color = whiteColor, start = Offset(-trunkLength * 0.7f, trunkLength * 0.7f), end = Offset(trunkLength * 0.7f, -trunkLength * 0.7f), strokeWidth = iconStrokeWidth * 0.8f)
                                
                                // Canopy - 8 lobes circular cloud
                                for (angle in listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)) {
                                    val angleRad = (angle * PI / 180f).toFloat()
                                    val cx = 24f * cos(angleRad)
                                    val cy = 24f * sin(angleRad)
                                    drawCircle(color = cyanColor, radius = 15f, center = Offset(cx, cy), style = iconStroke)
                                }
                            }
                            "Bush" -> {
                                // Inner 5 lobes
                                for (angle in listOf(0f, 72f, 144f, 216f, 288f)) {
                                    val angleRad = (angle * PI / 180f).toFloat()
                                    val cx = 10f * cos(angleRad)
                                    val cy = 10f * sin(angleRad)
                                    drawCircle(color = whiteColor, radius = 9f, center = Offset(cx, cy), style = iconStroke)
                                }
                                // Outer 6 lobes
                                for (angle in listOf(0f, 60f, 120f, 180f, 240f, 300f)) {
                                    val angleRad = (angle * PI / 180f).toFloat()
                                    val cx = 22f * cos(angleRad)
                                    val cy = 22f * sin(angleRad)
                                    drawCircle(color = cyanColor, radius = 12f, center = Offset(cx, cy), style = iconStroke)
                                }
                            }
                            "Light Pole" -> {
                                // Base
                                drawRect(color = whiteColor, topLeft = Offset(-8f, -8f), size = Size(16f, 16f), style = iconStroke)
                                // Arm
                                val path = Path().apply {
                                    moveTo(0f, -8f)
                                    lineTo(0f, -32f)
                                    lineTo(22f, -32f)
                                }
                                drawPath(path = path, color = cyanColor, style = iconStroke)
                                // Bulb
                                drawRect(color = whiteColor, topLeft = Offset(14f, -38f), size = Size(16f, 12f), style = iconStroke)
                            }
                            else -> {
                                // Fallback standard rectangle
                                drawRect(
                                    color = element.color,
                                    topLeft = Offset(-50f, -50f),
                                    size = Size(100f, 100f),
                                    style = iconStroke
                                )
                            }
                        }
                    }
                }
            }
            is DrawingElement.Dimension -> {
                val start = element.start
                val end = element.end
                val color = element.color
                val strokeWidth = element.strokeWidth
                val (dimStart, dimEnd) = element.dimLineEndpoints()

                // Extension lines: connect the true measured points out to the offset dimension
                // line, architectural-style - a small gap off the real point, a small overshoot
                // past the dimension line. Skipped when offset is ~0 (mid-placement, before the
                // dimension line has been pulled away yet) since there's nothing to bridge.
                fun drawExtensionLine(truePoint: Offset, dimPoint: Offset) {
                    val extVec = dimPoint - truePoint
                    val extLen = extVec.getDistance()
                    if (extLen > 0.5f) {
                        val extUnit = extVec / extLen
                        val gap = 6f / zoom
                        val overshoot = 6f / zoom
                        val from = truePoint + extUnit * minOf(gap, extLen * 0.4f)
                        val to = dimPoint + extUnit * overshoot
                        drawLine(color, from, to, strokeWidth = strokeWidth * 0.75f)
                    }
                }
                drawExtensionLine(start, dimStart)
                drawExtensionLine(end, dimEnd)

                drawLine(color, dimStart, dimEnd, strokeWidth = strokeWidth)

                // Tick marks (slanted lines common in architectural drawings)
                val dist = (dimEnd - dimStart).getDistance()
                if (dist > 1f) {
                    val dir = (dimEnd - dimStart) / dist
                    val perp = Offset(-dir.y, dir.x) * (15f / zoom)
                    val slant = (dir + perp) * 0.7f

                    drawLine(color, dimStart - slant, dimStart + slant, strokeWidth = strokeWidth * 1.5f)
                    drawLine(color, dimEnd - slant, dimEnd + slant, strokeWidth = strokeWidth * 1.5f)
                }

                // Text value
                val mid = (dimStart + dimEnd) / 2f
                val text = formatDimension(element.value, element.unit, element.format)

                var angle = (atan2((dimEnd.y - dimStart.y).toDouble(), (dimEnd.x - dimStart.x).toDouble()) * 180.0 / PI).toFloat()
                // Keep the label upright and readable whichever way the dimension was drawn.
                if (angle > 90f) angle -= 180f
                if (angle < -90f) angle += 180f
                val labelColor = if (state.backgroundColor == Color.White) Color.Black else color
                // 15 world units above the line in the label's own (rotated) frame; size divided by
                // zoom so it stays readable at any zoom.
                val rad = angle * PI / 180.0
                val labelCenter = mid + Offset(sin(rad).toFloat(), -cos(rad).toFloat()) * (15f / zoom)
                drawLabel(text, labelCenter, 36f / zoom, labelColor, rotationDegrees = angle)
            }
            is DrawingElement.Text -> drawTextElement(element)
            is DrawingElement.Image -> {
                drawImage(
                    image = element.bitmap,
                    dstOffset = IntOffset(element.topLeft.x.toInt(), element.topLeft.y.toInt()),
                    dstSize = IntSize(element.size.width.toInt(), element.size.height.toInt())
                )
            }
            is DrawingElement.Pdf -> {
                if (element.pages.isNotEmpty()) {
                    drawImage(
                        image = element.pages[element.currentPage],
                        dstOffset = IntOffset(element.topLeft.x.toInt(), element.topLeft.y.toInt()),
                        dstSize = IntSize(element.size.width.toInt(), element.size.height.toInt())
                    )
                }
            }
            is DrawingElement.Dxf -> {
                withTransform({
                    translate(element.topLeft.x, element.topLeft.y)
                }) {
                    element.elements.forEach { drawElement(it, isSelected = false, state = state) }
                }
            }
            is DrawingElement.ElevationView -> {
                // Live: rebuilt from the plan on every draw, so plan edits show up at once.
                Elevation.partsOf(element, state).forEach { drawElement(it, isSelected = false, state = state) }
            }
            else -> {}
        }
    }

    if (isSelected) {
        val rect = when (element) {
            is DrawingElement.Freehand -> {
                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = Float.MIN_VALUE
                var maxY = Float.MIN_VALUE
                element.points.forEach {
                    minX = minOf(minX, it.offset.x)
                    minY = minOf(minY, it.offset.y)
                    maxX = maxOf(maxX, it.offset.x)
                    maxY = maxOf(maxY, it.offset.y)
                }
                Rect(minX, minY, maxX, maxY)
            }
            is DrawingElement.Line -> {
                Rect(
                    minOf(element.start.x, element.end.x),
                    minOf(element.start.y, element.end.y),
                    maxOf(element.start.x, element.end.x),
                    maxOf(element.start.y, element.end.y)
                )
            }
            is DrawingElement.Rectangle -> Rect(element.topLeft, element.size)
            is DrawingElement.Oval -> Rect(element.topLeft, element.size)
            is DrawingElement.Triangle -> Rect(element.topLeft, element.size)
            is DrawingElement.Symbol -> Rect(element.center - Offset(element.size.width / 2, element.size.height / 2), element.size)
            is DrawingElement.Dimension -> {
                val (dimStart, dimEnd) = element.dimLineEndpoints()
                val xs = listOf(element.start.x, element.end.x, dimStart.x, dimEnd.x)
                val ys = listOf(element.start.y, element.end.y, dimStart.y, dimEnd.y)
                Rect(xs.min(), ys.min(), xs.max(), ys.max())
            }
            is DrawingElement.Circle -> Rect(element.center.x - element.radius, element.center.y - element.radius, element.center.x + element.radius, element.center.y + element.radius)
            is DrawingElement.Image -> Rect(element.topLeft, element.size)
            is DrawingElement.Pdf -> Rect(element.topLeft, element.size)
            is DrawingElement.Dxf -> Rect(element.topLeft, Size(100f, 100f)) // Placeholder
            is DrawingElement.ElevationView -> element.frame
            is DrawingElement.Text -> Rect(element.topLeft, Size(100f, 50f))
        }

        rotate(
            element.rotation,
            pivot = when (element) {
                is DrawingElement.Rectangle -> element.topLeft + Offset(element.size.width / 2f, element.size.height / 2f)
                is DrawingElement.Oval -> element.topLeft + Offset(element.size.width / 2f, element.size.height / 2f)
                is DrawingElement.Triangle -> element.topLeft + Offset(element.size.width / 2f, element.size.height / 2f)
                is DrawingElement.Symbol -> element.center
                else -> rect.center
            },
        ) {
            // Selection Border - Exactly on bounds
            drawRect(
                color = Color.Blue.copy(alpha = 0.4f),
                topLeft = rect.topLeft,
                size = rect.size,
                style = Stroke(width = 2f / zoom)
            )

            if (element is DrawingElement.Dimension) {
                // Both measured points are independently draggable (unlike every other element
                // type, which exposes one handle) - redefine what's being measured from either
                // end. A third, differently-colored grip sits on the dimension line itself:
                // dragging IT slides the line/text clear of the drawing without touching what's
                // actually measured - the "pull it away for a clear view" interaction.
                drawCircle(color = Color.Blue, radius = 12f / zoom, center = element.start)
                drawCircle(color = Color.Blue, radius = 12f / zoom, center = element.end)
                val (dimStart, dimEnd) = element.dimLineEndpoints()
                drawCircle(color = Color(0xFFFFA500), radius = 12f / zoom, center = (dimStart + dimEnd) / 2f)
            } else {
                val handlePos = when (element) {
                    is DrawingElement.Rectangle, is DrawingElement.Image, is DrawingElement.Pdf -> rect.bottomRight
                    is DrawingElement.Oval, is DrawingElement.Triangle -> rect.bottomRight
                    is DrawingElement.Symbol -> rect.bottomRight
                    is DrawingElement.Line -> element.end
                    is DrawingElement.Circle -> element.center + Offset(element.radius, 0f)
                    else -> null
                }

                handlePos?.let {
                    drawCircle(
                        color = Color.Blue,
                        radius = 12f / zoom,
                        center = it
                    )
                }
            }
        } // End of rotate block

        // Move handles (Vertices/KeyPoints) drawn in WORLD SPACE (outside rotation block)
        if (state.currentTool == DrawingTool.MOVE || state.currentTool == DrawingTool.MOVE_PRECISE) {
            getElementKeyPoints(element).forEach { pt ->
                val isFromPoint = state.preciseMoveFromPoint != null && (pt - state.preciseMoveFromPoint).getDistance() < 1f / state.zoom
                // For thin objects like lumber, reduce handle size if they overlap
                val handleSize = if (element is DrawingElement.Symbol && (element.category == "Lumber" || element.category == "Plywood") && (element.size.width < 10f || element.size.height < 10f)) {
                     14f / zoom
                } else {
                     24f / zoom
                }
                
                drawRect(
                    color = if (isFromPoint) Color.Green else Color.Blue,
                    topLeft = pt - Offset(handleSize / 2f, handleSize / 2f),
                    size = Size(handleSize, handleSize),
                    style = Stroke(width = 2f / zoom)
                )
            }
        }

        rotate(
            element.rotation,
            pivot = when (element) {
                is DrawingElement.Rectangle -> element.topLeft + Offset(element.size.width / 2f, element.size.height / 2f)
                is DrawingElement.Oval -> element.topLeft + Offset(element.size.width / 2f, element.size.height / 2f)
                is DrawingElement.Triangle -> element.topLeft + Offset(element.size.width / 2f, element.size.height / 2f)
                is DrawingElement.Symbol -> element.center
                else -> rect.center
            },
        ) {
            
            // Move handle (Center)
            val moveHandlePos = when (element) {
                is DrawingElement.Line -> element.start + (element.end - element.start) / 2f
                is DrawingElement.Circle -> element.center
                else -> rect.center
            }
            
            drawCircle(
                color = Color.Blue.copy(alpha = 0.5f),
                radius = 20f / zoom,
                center = moveHandlePos,
                style = Stroke(width = 2f / zoom)
            )
            drawLine(
                color = Color.Blue.copy(alpha = 0.5f),
                start = moveHandlePos - Offset(10f / zoom, 0f),
                end = moveHandlePos + Offset(10f / zoom, 0f),
                strokeWidth = 2f / zoom
            )
            drawLine(
                color = Color.Blue.copy(alpha = 0.5f),
                start = moveHandlePos - Offset(0f, 10f / zoom),
                end = moveHandlePos + Offset(0f, 10f / zoom),
                strokeWidth = 2f / zoom
            )

            // Rotation handle
            val rotationHandlePos = when (element) {
                is DrawingElement.Rectangle, is DrawingElement.Image, is DrawingElement.Pdf -> rect.topCenter - Offset(0f, 40f / zoom)
                is DrawingElement.Oval, is DrawingElement.Triangle -> rect.topCenter - Offset(0f, 40f / zoom)
                is DrawingElement.Symbol -> rect.topCenter - Offset(0f, 40f / zoom)
                else -> null
            }

            rotationHandlePos?.let {
                drawLine(Color.Blue, rect.topCenter, it, strokeWidth = 2f / zoom)
                drawCircle(
                    color = Color.Blue,
                    radius = 15f / zoom,
                    center = it
                )
            }
        }
    }
}

private fun DrawScope.drawCanvasBackground(background: DrawingBackground, state: CanvasState) {
    val left = -state.pan.x / state.zoom
    val right = (size.width - state.pan.x) / state.zoom
    val top = -state.pan.y / state.zoom
    val bottom = (size.height - state.pan.y) / state.zoom

    when (background) {
        DrawingBackground.GRID -> {
            val colorBase = if (state.backgroundColor == Color.White) Color.Black else Color.White
            val minorColor = colorBase.copy(alpha = 0.12f)
            val majorColor = colorBase.copy(alpha = 0.3f)

            val step = if (state.unitSystem == UnitSystem.IMPERIAL) {
                1.0f * state.scale.ratio
            } else {
                val screenDistPointOne = 0.1f * state.scale.ratio * state.zoom
                if (screenDistPointOne >= 15f) {
                    0.1f * state.scale.ratio
                } else {
                    1.0f * state.scale.ratio
                }
            }
            
            val majorStep = 5.0f * state.scale.ratio
            val stepsPerMajor = kotlin.math.round(majorStep / step).toInt().coerceAtLeast(1)

            val minorWidth = 1f / state.zoom
            val majorWidth = 2.5f / state.zoom

            val screenStep = step * state.zoom
            val drawMinor = screenStep >= 10f
            val drawMajor = (majorStep * state.zoom) >= 5f

            if (drawMajor) {
                // Vertical lines
                val startXIndex = kotlin.math.floor(left / step).toInt()
                var xIndex = startXIndex
                while (true) {
                    val x = xIndex * step
                    if (x > right) break

                    val isMajor = (xIndex % stepsPerMajor) == 0
                    if (isMajor || drawMinor) {
                        val c = if (isMajor) majorColor else minorColor
                        val w = if (isMajor) majorWidth else minorWidth
                        drawLine(c, Offset(x, top), Offset(x, bottom), strokeWidth = w)
                    }
                    xIndex++
                }

                // Horizontal lines
                val startYIndex = kotlin.math.floor(top / step).toInt()
                var yIndex = startYIndex
                while (true) {
                    val y = yIndex * step
                    if (y > bottom) break

                    val isMajor = (yIndex % stepsPerMajor) == 0
                    if (isMajor || drawMinor) {
                        val c = if (isMajor) majorColor else minorColor
                        val w = if (isMajor) majorWidth else minorWidth
                        drawLine(c, Offset(left, y), Offset(right, y), strokeWidth = w)
                    }
                    yIndex++
                }
            }
        }
        DrawingBackground.ISOMETRIC -> {
            val step = 40f
            val color = Color.LightGray.copy(alpha = 0.5f)
            val angle = 30f * (kotlin.math.PI / 180f).toFloat()
            val cos = cos(angle.toDouble()).toFloat()
            val sin = sin(angle.toDouble()).toFloat()

            // Simplify isometric grid for now: just two sets of diagonal lines
            // Set 1: /
            var i = -size.height
            while (i < size.width) {
                drawLine(color, Offset(i, 0f), Offset(i + size.height * (cos/sin), size.height), strokeWidth = 1f)
                i += step * 2
            }
            
            // Set 2: \
            i = 0f
            while (i < size.width + size.height) {
                drawLine(color, Offset(i, 0f), Offset(i - size.height * (cos/sin), size.height), strokeWidth = 1f)
                i += step * 2
            }
        }
        DrawingBackground.PLAIN -> {
            // Draw nothing or a solid color
        }
    }
}

private fun formatDimension(value: Float, unit: UnitSystem, format: DimensionFormat): String {
    return if (unit == UnitSystem.METRIC) {
        "%.2f m".format(value)
    } else {
        if (format == DimensionFormat.ENGINEERING) {
            "%.2f'".format(value)
        } else {
            // Round to the nearest 1/8" BEFORE splitting - see splitFeetInches.
            val (feet, inchesF) = splitFeetInches(value, 1f / 8f)
            val inches = kotlin.math.floor(inchesF).toInt()
            val fraction = inchesF - kotlin.math.floor(inchesF)
            
            val fractionText = when {
                fraction >= 0.875f -> " 7/8"
                fraction >= 0.75f -> " 3/4"
                fraction >= 0.625f -> " 5/8"
                fraction >= 0.5f -> " 1/2"
                fraction >= 0.375f -> " 3/8"
                fraction >= 0.25f -> " 1/4"
                fraction >= 0.125f -> " 1/8"
                else -> ""
            }
            
            if (feet > 0) {
                if (inches > 0 || fractionText.isNotEmpty()) {
                    "$feet' - $inches$fractionText\""
                } else {
                    "$feet'"
                }
            } else {
                if (inches > 0 || fractionText.isNotEmpty()) {
                    "$inches$fractionText\""
                } else {
                    "0\""
                }
            }
        }
    }
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
        is DrawingElement.Dimension -> listOf(element.start, element.end)
        is DrawingElement.Text -> listOf(element.topLeft)
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

// Everything drawDocumentContents reads, so the recorded layer is reused only while all of it
// is unchanged. `elements` is compared by identity on purpose: the list is replaced wholesale
// on every edit and never mutated in place, and a deep equals would walk every point of every
// stroke on every frame.
private class DocumentLayerCache {
    private var elements: List<DrawingElement>? = null
    private var pan = Offset.Zero
    private var zoom = Float.NaN
    private var background: DrawingBackground? = null
    private var backgroundColor: Color? = null
    private var selectedElementId: String? = null
    private var scale: DrawingScale? = null
    private var unitSystem: UnitSystem? = null
    private var currentTool: DrawingTool? = null
    private var preciseMoveFromPoint: Offset? = null
    private var size = Size.Zero

    fun matches(state: CanvasState, size: Size): Boolean =
        elements === state.elements && pan == state.pan && zoom == state.zoom &&
            background == state.background && backgroundColor == state.backgroundColor &&
            selectedElementId == state.selectedElementId && scale == state.scale &&
            unitSystem == state.unitSystem && currentTool == state.currentTool &&
            preciseMoveFromPoint == state.preciseMoveFromPoint && this.size == size

    fun update(state: CanvasState, size: Size) {
        elements = state.elements
        pan = state.pan
        zoom = state.zoom
        background = state.background
        backgroundColor = state.backgroundColor
        selectedElementId = state.selectedElementId
        scale = state.scale
        unitSystem = state.unitSystem
        currentTool = state.currentTool
        preciseMoveFromPoint = state.preciseMoveFromPoint
        this.size = size
    }
}

// Set by MainActivity.dispatchTouchEvent for the duration of a stroke made with the S Pen
// side button held, because that information never survives into the Compose pointer stream
// on Samsung hardware (see the note in MainActivity). Read synchronously by the canvas press
// handler above, which runs inside the same dispatch call that set it.
object StylusButton {
    @Volatile var held: Boolean = false
}

// Laser wall mode marker: the point the next wall starts from, with the four direction arrows
// around it (the active one in the accent colour) and, if set, a faint dot at the room start.
private fun DrawScope.drawPlacementGhost(placement: Placement, at: Offset, state: CanvasState) {
    val accent = Color(0xFFF47B20)
    val zoom = state.zoom
    val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f / zoom, 6f / zoom))
    val rects = FloorPlan.previewRects(placement, at, state)
    rects.forEachIndexed { i, r ->
        // Outer rectangle solid-ish, inner (room inside / outline inside) dashed.
        drawRect(color = accent.copy(alpha = 0.12f), topLeft = r.topLeft, size = r.size)
        drawRect(
            color = accent, topLeft = r.topLeft, size = r.size,
            style = Stroke(width = (if (i == 0) 2.5f else 1.5f) / zoom, pathEffect = if (i == 0) null else dash)
        )
    }
    // The anchor the pen is holding (the tapped corner or centre).
    drawCircle(color = accent, radius = 5f / zoom, center = at)
    drawCircle(color = Color.White, radius = 2f / zoom, center = at)
}

private fun DrawScope.drawWallCursor(wall: WallMode, zoom: Float) {
    val c = wall.cursor ?: return
    val accent = Color(0xFFF47B20)
    wall.roomStart?.takeIf { it != c }?.let { drawCircle(accent.copy(alpha = 0.6f), 5f / zoom, it) }
    WallDirection.entries.forEach { d ->
        val active = d == wall.direction
        val col = if (active) accent else Color.White.copy(alpha = 0.75f)
        val w = (if (active) 4f else 2.5f) / zoom
        val tip = c + d.unit * (WALL_ARROW_DISTANCE / zoom)
        val from = c + d.unit * (16f / zoom)
        val perp = Offset(-d.unit.y, d.unit.x)
        drawLine(col, from, tip, w, cap = StrokeCap.Round)
        drawLine(col, tip, tip - d.unit * (10f / zoom) + perp * (7f / zoom), w, cap = StrokeCap.Round)
        drawLine(col, tip, tip - d.unit * (10f / zoom) - perp * (7f / zoom), w, cap = StrokeCap.Round)
    }
    drawCircle(Color.White, 9f / zoom, c, style = Stroke(2f / zoom))
    drawCircle(accent, 6f / zoom, c)
}
