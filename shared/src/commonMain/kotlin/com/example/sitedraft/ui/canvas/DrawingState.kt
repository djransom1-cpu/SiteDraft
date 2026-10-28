package com.example.sitedraft.ui.canvas

import com.example.sitedraft.platform.randomId
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.example.sitedraft.platform.decodeImage
import com.example.sitedraft.platform.encodePng
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.abs
import kotlin.math.roundToInt

@Serializable
enum class DrawingTool {
    PEN, ERASER, LINE, RECTANGLE, CIRCLE, SELECT, SYMBOL, DIMENSION, TEXT, OVAL, TRIANGLE, OFFSET, MARKER, PENCIL, MOVE, MOVE_PRECISE, TRIM, EXTEND
}

@Serializable
enum class DimensionFormat {
    ARCHITECTURAL, ENGINEERING
}

@Serializable
enum class UnitSystem {
    METRIC, IMPERIAL
}

@Serializable
data class DrawingScale(
    val ratio: Float = 50f, // 1 foot = 50px
    val label: String = "1' = 50px"
)

@Serializable
enum class TextListType {
    NONE, BULLET, NUMBER
}

@Serializable
enum class DrawingBackground {
    GRID, ISOMETRIC, PLAIN
}

object OffsetSerializer : KSerializer<Offset> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Offset", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Offset) = encoder.encodeString("${value.x},${value.y}")
    override fun deserialize(decoder: Decoder): Offset {
        val (x, y) = decoder.decodeString().split(",").map { it.toFloat() }
        return Offset(x, y)
    }
}

object SizeSerializer : KSerializer<Size> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Size", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Size) = encoder.encodeString("${value.width},${value.height}")
    override fun deserialize(decoder: Decoder): Size {
        val (w, h) = decoder.decodeString().split(",").map { it.toFloat() }
        return Size(w, h)
    }
}

object ColorSerializer : KSerializer<Color> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Color", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Color) = encoder.encodeLong(value.value.toLong())
    override fun deserialize(decoder: Decoder): Color = Color(decoder.decodeLong().toULong())
}

/** Images are stored in the project file as base64 PNG, the same on every platform. */
@OptIn(ExperimentalEncodingApi::class)
object BitmapSerializer : KSerializer<ImageBitmap> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Bitmap", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ImageBitmap) {
        encoder.encodeString(Base64.Mime.encode(encodePng(value)))
    }
    override fun deserialize(decoder: Decoder): ImageBitmap {
        val text = decoder.decodeString()
        val bytes = runCatching { Base64.Mime.decode(text) }.getOrElse { Base64.decode(text.filterNot { it.isWhitespace() }) }
        return decodeImage(bytes) ?: ImageBitmap(1, 1)
    }
}

@Serializable
data class DrawingPoint(
    @Serializable(with = OffsetSerializer::class)
    val offset: Offset,
    val pressure: Float = 1.0f
)

@Serializable
sealed class DrawingElement {
    abstract val id: String
    abstract val color: Color
    abstract val strokeWidth: Float
    abstract val rotation: Float

    @Serializable
    data class Freehand(
        override val id: String = randomId(),
        val points: List<DrawingPoint>,
        @Serializable(with = ColorSerializer::class)
        override val color: Color,
        override val strokeWidth: Float,
        override val rotation: Float = 0f,
        val isHighlighter: Boolean = false,
        val fill: Fill = Fill()
    ) : DrawingElement()

    @Serializable
    data class Line(
        override val id: String = randomId(),
        @Serializable(with = OffsetSerializer::class)
        val start: Offset,
        @Serializable(with = OffsetSerializer::class)
        val end: Offset,
        @Serializable(with = ColorSerializer::class)
        override val color: Color,
        override val strokeWidth: Float,
        override val rotation: Float = 0f
    ) : DrawingElement()

    @Serializable
    data class Rectangle(
        override val id: String = randomId(),
        @Serializable(with = OffsetSerializer::class)
        val topLeft: Offset,
        @Serializable(with = SizeSerializer::class)
        val size: Size,
        @Serializable(with = ColorSerializer::class)
        override val color: Color,
        override val strokeWidth: Float,
        override val rotation: Float = 0f,
        val fill: Fill = Fill()
    ) : DrawingElement()

    @Serializable
    data class Oval(
        override val id: String = randomId(),
        @Serializable(with = OffsetSerializer::class)
        val topLeft: Offset,
        @Serializable(with = SizeSerializer::class)
        val size: Size,
        @Serializable(with = ColorSerializer::class)
        override val color: Color,
        override val strokeWidth: Float,
        override val rotation: Float = 0f,
        val fill: Fill = Fill()
    ) : DrawingElement()

    @Serializable
    data class Triangle(
        override val id: String = randomId(),
        @Serializable(with = OffsetSerializer::class)
        val topLeft: Offset,
        @Serializable(with = SizeSerializer::class)
        val size: Size,
        @Serializable(with = ColorSerializer::class)
        override val color: Color,
        override val strokeWidth: Float,
        override val rotation: Float = 0f,
        val triangleType: String = "Isosceles",
        val fill: Fill = Fill()
    ) : DrawingElement() {
        fun getVertices(): Triple<Offset, Offset, Offset> {
            return if (triangleType == "Right-Angled") {
                Triple(
                    Offset(topLeft.x, topLeft.y),
                    Offset(topLeft.x, topLeft.y + size.height),
                    Offset(topLeft.x + size.width, topLeft.y + size.height)
                )
            } else {
                Triple(
                    Offset(topLeft.x + size.width / 2f, topLeft.y),
                    Offset(topLeft.x, topLeft.y + size.height),
                    Offset(topLeft.x + size.width, topLeft.y + size.height)
                )
            }
        }
    }

    @Serializable
    data class Circle(
        override val id: String = randomId(),
        @Serializable(with = OffsetSerializer::class)
        val center: Offset,
        val radius: Float,
        @Serializable(with = ColorSerializer::class)
        override val color: Color,
        override val strokeWidth: Float,
        override val rotation: Float = 0f,
        val fill: Fill = Fill()
    ) : DrawingElement()

    @Serializable
    data class Symbol(
        override val id: String = randomId(),
        val symbolName: String,
        val category: String,
        @Serializable(with = OffsetSerializer::class)
        val center: Offset,
        @Serializable(with = SizeSerializer::class)
        val size: Size,
        @Serializable(with = ColorSerializer::class)
        override val color: Color,
        override val rotation: Float = 0f,
        val properties: Map<String, Float> = emptyMap()
    ) : DrawingElement() {
        override val strokeWidth: Float = 2f
    }

    @Serializable
    data class Dimension(
        override val id: String = randomId(),
        @Serializable(with = OffsetSerializer::class)
        val start: Offset,
        @Serializable(with = OffsetSerializer::class)
        val end: Offset,
        val value: Float, // Real world value
        val unit: UnitSystem,
        val format: DimensionFormat = DimensionFormat.ARCHITECTURAL,
        @Serializable(with = ColorSerializer::class)
        override val color: Color,
        override val strokeWidth: Float = 1f,
        override val rotation: Float = 0f,
        // Perpendicular signed distance (world units) from the start-end segment (the true
        // measured points, aka extension line origins) to where the dimension line itself is
        // drawn. 0 means the dimension line sits directly on the measured points (no offset
        // placed yet - the state mid-way through placing a new dimension). AutoCAD-style:
        // extension lines connect the real points out to the offset dimension line/text so the
        // label can be read clear of the drawing without moving what's actually being measured.
        val offset: Float = 0f
    ) : DrawingElement()

    @Serializable
    data class Text(
        override val id: String = randomId(),
        val text: String,
        @Serializable(with = OffsetSerializer::class)
        val topLeft: Offset,
        val fontSize: Float,
        @Serializable(with = ColorSerializer::class)
        override val color: Color,
        override val rotation: Float = 0f,
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val textAlign: String = "Left", // Left, Center, Right
        val listType: TextListType = TextListType.NONE,
        val fontFamily: String = "SansSerif"
    ) : DrawingElement() {
        override val strokeWidth: Float = 0f
    }

    @Serializable
    data class Image(
        override val id: String = randomId(),
        @Serializable(with = BitmapSerializer::class)
        val bitmap: ImageBitmap,
        @Serializable(with = OffsetSerializer::class)
        val topLeft: Offset,
        @Serializable(with = SizeSerializer::class)
        val size: Size,
        override val rotation: Float = 0f
    ) : DrawingElement() {
        @Serializable(with = ColorSerializer::class)
        override val color: Color = Color.Transparent
        override val strokeWidth: Float = 0f
    }

    @Serializable
    data class Pdf(
        override val id: String = randomId(),
        val pages: List<@Serializable(with = BitmapSerializer::class) ImageBitmap>,
        @Serializable(with = OffsetSerializer::class)
        val topLeft: Offset,
        @Serializable(with = SizeSerializer::class)
        val size: Size,
        val currentPage: Int = 0,
        override val rotation: Float = 0f
    ) : DrawingElement() {
        @Serializable(with = ColorSerializer::class)
        override val color: Color = Color.Transparent
        override val strokeWidth: Float = 0f
    }

    @Serializable
    data class Dxf(
        override val id: String = randomId(),
        val elements: List<DrawingElement>,
        @Serializable(with = OffsetSerializer::class)
        val topLeft: Offset,
        val scale: Float = 1f,
        override val rotation: Float = 0f
    ) : DrawingElement() {
        @Serializable(with = ColorSerializer::class)
        override val color: Color = Color.Transparent
        override val strokeWidth: Float = 0f
    }

    /**
     * A live interior elevation of one plan wall. Nothing is stored but the recipe: which wall,
     * the heights, where its floor line starts ([origin], bottom-left) and its current frame
     * [size]; the drawing itself is rebuilt from the plan every time it is drawn, so moving a
     * cabinet or adding a window on that wall shows up here at once. Explode turns it into plain
     * lines and rectangles when hand editing is wanted.
     */
    @Serializable
    data class ElevationView(
        override val id: String = randomId(),
        val spec: ElevationSpec,
        @Serializable(with = OffsetSerializer::class)
        val origin: Offset,
        @Serializable(with = SizeSerializer::class)
        val size: Size,
        @Serializable(with = ColorSerializer::class)
        override val color: Color,
        override val rotation: Float = 0f
    ) : DrawingElement() {
        override val strokeWidth: Float = 2f
        /** The frame: floor line at the bottom, wall height up from it. */
        val frame: Rect get() = Rect(Offset(origin.x, origin.y - size.height), size)
    }
}

@Serializable
data class CanvasState(
    val elements: List<DrawingElement> = emptyList(),
    // Full-elements-list snapshots, taken right before each mutating action (drawing, deleting,
    // moving, resizing, trimming, exploding, property edits, everything) - NOT one entry per
    // element. This is what makes Undo/Redo actually undo "whatever the last action was" rather
    // than only ever being able to un-add the most recently created element (the old design:
    // see git history for `redoStack: List<DrawingElement>`, which could only pop/push single
    // elements off the end of the list and had no idea a delete/move/edit had even happened).
    // @Transient: undo/redo are session state. Serializing them put a full copy of every element
    // per snapshot into the project file, which made autosave grow quadratically with the drawing.
    @kotlinx.serialization.Transient
    val undoHistory: List<List<DrawingElement>> = emptyList(),
    @kotlinx.serialization.Transient
    val redoHistory: List<List<DrawingElement>> = emptyList(),
    val currentDrawingElement: DrawingElement? = null,
    val selectedElementId: String? = null,
    val currentTool: DrawingTool = DrawingTool.PEN,
    @Serializable(with = ColorSerializer::class)
    val currentColor: Color = Color.White,
    val currentStrokeWidth: Float = 2f,
    val background: DrawingBackground = DrawingBackground.GRID,
    @Serializable(with = ColorSerializer::class)
    val backgroundColor: Color = Color(0xFF003366), // Blueprint Blue
    val dimensionFormat: DimensionFormat = DimensionFormat.ARCHITECTURAL,
    val zoom: Float = 1f,
    @Serializable(with = OffsetSerializer::class)
    val pan: Offset = Offset.Zero,
    val unitSystem: UnitSystem = UnitSystem.IMPERIAL,
    val scale: DrawingScale = DrawingScale(),
    // Legacy: palm rejection is now a device preference (data/UserPrefs.kt). Kept so older
    // project files still deserialize; nothing reads it any more.
    val stylusOnlyDrawing: Boolean = false,
    val projectName: String = "Untitled Project",
    val availableProjects: List<String> = emptyList(),
    val snapEnabled: Boolean = true,
    val snapToGrid: Boolean = true,
    @Serializable(with = OffsetSerializer::class)
    val lastSnapPoint: Offset? = null,
    @Serializable(with = OffsetSerializer::class)
    val preciseMoveFromPoint: Offset? = null,
    // True once the two points of a new dimension have been picked (currentDrawingElement holds
    // a Dimension with a real start/end) and the tool is now waiting for a third
    // touch to place the dimension line's offset - see DrawingTool.DIMENSION handling.
    val awaitingDimensionOffset: Boolean = false,
    // Multiple objects selected via box-select (drag a rectangle over several at once, like
    // AutoCAD's window select). selectedElementId stays the single-selection path (resize/
    // rotate/individual grips); selectedElementIds is only ever non-empty while a group of 2+
    // is selected, and group actions (move/delete/duplicate) key off it instead.
    val selectedElementIds: Set<String> = emptySet(),
    // Live rubber-band rectangle while a box-select drag is in progress; null the rest of the
    // time. Both corners kept (not a Rect) since which corner is "start" matters for future
    // window-vs-crossing selection semantics.
    @Serializable(with = OffsetSerializer::class)
    val boxSelectStart: Offset? = null,
    @Serializable(with = OffsetSerializer::class)
    val boxSelectEnd: Offset? = null,
    // Laser wall mode (cursor, direction, room start). Session UI state, never saved with the project.
    @kotlinx.serialization.Transient
    val wallMode: WallMode = WallMode(),
    // Symbol, building outline or room waiting for a tap that says where it goes.
    @kotlinx.serialization.Transient
    val pendingPlacement: Placement? = null,
    // Where the pending placement would land right now (snapped), while the pen hovers or drags.
    @kotlinx.serialization.Transient
    @Serializable(with = OffsetSerializer::class)
    val placementPreview: Offset? = null
)

fun DrawingElement.contains(offset: Offset, threshold: Float = 20f): Boolean {
    // Basic implementation without full rotation support for now
    // A robust implementation would use a transformation matrix or rotated Rect
    return when (this) {
        is DrawingElement.Freehand -> {
            points.any { (it.offset - offset).getDistance() < threshold }
        }
        is DrawingElement.Line -> {
            distanceFromPointToLineSegment(offset, start, end) < threshold
        }
        is DrawingElement.Rectangle -> {
            val rect = Rect(topLeft, size)
            rect.contains(offset) ||
                distanceFromPointToLineSegment(offset, topLeft, Offset(topLeft.x + size.width, topLeft.y)) < threshold ||
                distanceFromPointToLineSegment(offset, topLeft, Offset(topLeft.x, topLeft.y + size.height)) < threshold ||
                distanceFromPointToLineSegment(offset, Offset(topLeft.x + size.width, topLeft.y), Offset(topLeft.x + size.width, topLeft.y + size.height)) < threshold ||
                distanceFromPointToLineSegment(offset, Offset(topLeft.x, topLeft.y + size.height), Offset(topLeft.x + size.width, topLeft.y + size.height)) < threshold
        }
        is DrawingElement.Oval -> {
            val rect = Rect(topLeft, size)
            rect.contains(offset)
        }
        is DrawingElement.Triangle -> {
            val (p1, p2, p3) = getVertices()
            
            fun sign(pt: Offset, v1: Offset, v2: Offset): Float {
                return (pt.x - v2.x) * (v1.y - v2.y) - (v1.x - v2.x) * (pt.y - v2.y)
            }
            val d1 = sign(offset, p1, p2)
            val d2 = sign(offset, p2, p3)
            val d3 = sign(offset, p3, p1)
            val hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0)
            val hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0)
            val inside = !(hasNeg && hasPos)
            
            inside || 
                distanceFromPointToLineSegment(offset, p1, p2) < threshold ||
                distanceFromPointToLineSegment(offset, p2, p3) < threshold ||
                distanceFromPointToLineSegment(offset, p3, p1) < threshold
        }
        is DrawingElement.Circle -> {
            val dist = (center - offset).getDistance()
            abs(dist - radius) < threshold || dist < radius
        }
        is DrawingElement.Symbol -> {
            val rect = Rect(center - Offset(size.width / 2, size.height / 2), size)
            rect.contains(offset)
        }
        is DrawingElement.Dimension -> {
            val (dimStart, dimEnd) = dimLineEndpoints()
            distanceFromPointToLineSegment(offset, dimStart, dimEnd) < threshold ||
                distanceFromPointToLineSegment(offset, start, dimStart) < threshold ||
                distanceFromPointToLineSegment(offset, end, dimEnd) < threshold
        }
        is DrawingElement.Text -> {
            Rect(topLeft, Size(100f, 50f)).contains(offset) // Placeholder size
        }
        is DrawingElement.Image -> {
            Rect(topLeft, size).contains(offset)
        }
        is DrawingElement.Pdf -> {
            Rect(topLeft, size).contains(offset)
        }
        is DrawingElement.Dxf -> {
            elements.any { it.move(topLeft).contains(offset, threshold) }
        }
        is DrawingElement.ElevationView -> frame.inflate(threshold).contains(offset)
    }
}

// The dimension line's actual endpoints - start/end offset perpendicular to the measured
// segment by `offset` world units. Shared by rendering, hit-testing, and the offset-drag grip
// so they can never disagree about where the visible dimension line actually is.
fun DrawingElement.Dimension.dimLineEndpoints(): Pair<Offset, Offset> {
    val segDir = end - start
    val len = segDir.getDistance()
    if (len < 0.0001f) return Pair(start, end)
    val unit = segDir / len
    val perp = Offset(-unit.y, unit.x)
    return Pair(start + perp * offset, end + perp * offset)
}

// Unrotated axis-aligned bounding box, used for box-select intersection and the multi-select
// outline. Ignores rotation, same simplification the single-select bounding rect already makes.
fun DrawingElement.boundingRect(): Rect {
    return when (this) {
        is DrawingElement.Freehand -> {
            if (points.isEmpty()) Rect.Zero else {
                val xs = points.map { it.offset.x }
                val ys = points.map { it.offset.y }
                Rect(xs.min(), ys.min(), xs.max(), ys.max())
            }
        }
        is DrawingElement.Line -> Rect(minOf(start.x, end.x), minOf(start.y, end.y), maxOf(start.x, end.x), maxOf(start.y, end.y))
        is DrawingElement.Rectangle -> Rect(topLeft, size)
        is DrawingElement.Oval -> Rect(topLeft, size)
        is DrawingElement.Triangle -> Rect(topLeft, size)
        is DrawingElement.Circle -> Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
        is DrawingElement.Symbol -> Rect(center - Offset(size.width / 2, size.height / 2), size)
        is DrawingElement.Dimension -> {
            val (dimStart, dimEnd) = dimLineEndpoints()
            val xs = listOf(start.x, end.x, dimStart.x, dimEnd.x)
            val ys = listOf(start.y, end.y, dimStart.y, dimEnd.y)
            Rect(xs.min(), ys.min(), xs.max(), ys.max())
        }
        is DrawingElement.Text -> Rect(topLeft, Size(100f, 50f))
        is DrawingElement.Image -> Rect(topLeft, size)
        is DrawingElement.Pdf -> Rect(topLeft, size)
        is DrawingElement.Dxf -> Rect(topLeft, Size(100f, 100f))
        is DrawingElement.ElevationView -> frame
    }
}

// Straight edges an element offers as a Trim/Extend boundary. Curved types (Circle, Oval) and
// freeform/raster types (Freehand, Text, Image, Pdf, Dxf) aren't supported yet - trim/extend only
// reason about straight-line intersections for now.
fun DrawingElement.edgeSegments(): List<Pair<Offset, Offset>> = when (this) {
    is DrawingElement.Line -> listOf(start to end)
    is DrawingElement.Rectangle -> {
        val tl = topLeft
        val tr = topLeft + Offset(size.width, 0f)
        val br = topLeft + Offset(size.width, size.height)
        val bl = topLeft + Offset(0f, size.height)
        listOf(tl to tr, tr to br, br to bl, bl to tl)
    }
    is DrawingElement.Triangle -> {
        val (p1, p2, p3) = getVertices()
        listOf(p1 to p2, p2 to p3, p3 to p1)
    }
    is DrawingElement.Symbol -> {
        val hw = size.width / 2f
        val hh = size.height / 2f
        val tl = center + Offset(-hw, -hh)
        val tr = center + Offset(hw, -hh)
        val br = center + Offset(hw, hh)
        val bl = center + Offset(-hw, hh)
        listOf(tl to tr, tr to br, br to bl, bl to tl)
    }
    is DrawingElement.Dimension -> {
        val (dimStart, dimEnd) = dimLineEndpoints()
        listOf(dimStart to dimEnd)
    }
    else -> emptyList()
}

fun DrawingElement.move(delta: Offset): DrawingElement {
    return when (this) {
        is DrawingElement.Freehand -> copy(points = points.map { it.copy(offset = it.offset + delta) })
        is DrawingElement.Line -> copy(start = start + delta, end = end + delta)
        is DrawingElement.Rectangle -> copy(topLeft = topLeft + delta)
        is DrawingElement.Oval -> copy(topLeft = topLeft + delta)
        is DrawingElement.Triangle -> copy(topLeft = topLeft + delta)
        is DrawingElement.Circle -> copy(center = center + delta)
        is DrawingElement.Symbol -> copy(center = center + delta)
        is DrawingElement.Dimension -> copy(start = start + delta, end = end + delta)
        is DrawingElement.Text -> copy(topLeft = topLeft + delta)
        is DrawingElement.Image -> copy(topLeft = topLeft + delta)
        is DrawingElement.Pdf -> copy(topLeft = topLeft + delta)
        is DrawingElement.Dxf -> copy(topLeft = topLeft + delta)
        is DrawingElement.ElevationView -> copy(origin = origin + delta)
    }
}

fun calculateBeamPixelSize(properties: Map<String, Float>, ratio: Float, unitSystem: UnitSystem): Size {
    val flangeWidth = properties["flangeWidth"] ?: 6.0f
    val webHeight = properties["webHeight"] ?: 6.0f
    
    val widthInBaseUnits = if (unitSystem == UnitSystem.METRIC) {
        flangeWidth * 0.0254f
    } else {
        flangeWidth / 12f
    }
    
    val heightInBaseUnits = if (unitSystem == UnitSystem.METRIC) {
        webHeight * 0.0254f
    } else {
        webHeight / 12f
    }
    
    return Size(widthInBaseUnits * ratio, heightInBaseUnits * ratio)
}

fun DrawingElement.resize(delta: Offset, ratio: Float = 1f, unit: UnitSystem = UnitSystem.IMPERIAL): DrawingElement {
    return when (this) {
        is DrawingElement.Rectangle -> copy(size = Size(maxOf(10f, size.width + delta.x), maxOf(10f, size.height + delta.y)))
        is DrawingElement.Oval -> copy(size = Size(maxOf(10f, size.width + delta.x), maxOf(10f, size.height + delta.y)))
        is DrawingElement.Triangle -> copy(size = Size(maxOf(10f, size.width + delta.x), maxOf(10f, size.height + delta.y)))
        is DrawingElement.Circle -> copy(radius = maxOf(5f, radius + delta.x))
        is DrawingElement.Line -> copy(end = end + delta)
        is DrawingElement.Symbol -> {
            val newSize = Size(maxOf(10f, size.width + delta.x), maxOf(10f, size.height + delta.y))
            if (symbolName == "I-Beam" || symbolName.contains("W-Beam")) {
                val flangeWidthInBaseUnits = newSize.width / ratio
                val webHeightInBaseUnits = newSize.height / ratio
                
                val fWidthInInches = if (unit == UnitSystem.METRIC) {
                    flangeWidthInBaseUnits / 0.0254f
                } else {
                    flangeWidthInBaseUnits * 12f
                }
                
                val wHeightInInches = if (unit == UnitSystem.METRIC) {
                    webHeightInBaseUnits / 0.0254f
                } else {
                    webHeightInBaseUnits * 12f
                }
                
                val fThickness = properties["flangeThickness"] ?: 0.38f
                val wThickness = properties["webThickness"] ?: 0.25f
                
                copy(
                    size = newSize,
                    properties = mapOf(
                        "flangeWidth" to fWidthInInches,
                        "webHeight" to wHeightInInches,
                        "flangeThickness" to fThickness,
                        "webThickness" to wThickness
                    )
                )
            } else {
                copy(size = newSize)
            }
        }
        is DrawingElement.Dimension -> {
            val newEnd = end + delta
            val newValue = (newEnd - start).getDistance() / ratio
            copy(end = newEnd, value = newValue)
        }
        is DrawingElement.Image -> copy(size = Size(maxOf(10f, size.width + delta.x), maxOf(10f, size.height + delta.y)))
        is DrawingElement.Pdf -> copy(size = Size(maxOf(10f, size.width + delta.x), maxOf(10f, size.height + delta.y)))
        else -> this
    }
}

fun DrawingElement.rotate(deltaAngle: Float): DrawingElement {
    return when (this) {
        is DrawingElement.Freehand -> copy(rotation = rotation + deltaAngle)
        is DrawingElement.Line -> copy(rotation = rotation + deltaAngle)
        is DrawingElement.Rectangle -> copy(rotation = rotation + deltaAngle)
        is DrawingElement.Oval -> copy(rotation = rotation + deltaAngle)
        is DrawingElement.Triangle -> copy(rotation = rotation + deltaAngle)
        is DrawingElement.Circle -> copy(rotation = rotation + deltaAngle)
        is DrawingElement.Symbol -> copy(rotation = rotation + deltaAngle)
        is DrawingElement.Dimension -> copy(rotation = rotation + deltaAngle)
        is DrawingElement.Text -> copy(rotation = rotation + deltaAngle)
        is DrawingElement.Image -> copy(rotation = rotation + deltaAngle)
        is DrawingElement.Pdf -> copy(rotation = rotation + deltaAngle)
        is DrawingElement.Dxf -> this // DXF rotation not supported in this simple version
        is DrawingElement.ElevationView -> this // an elevation always reads upright
    }
}

/**
 * Turns the element [degrees] clockwise about [pivot]: its position swings around the pivot and
 * it turns with it. Lines, freehand strokes and dimensions turn their points; text only moves,
 * so labels stay readable; walls, windows and sliding doors turned by a right angle swap width
 * and height instead of taking a rotation, so they stay axis-aligned for snapping; images keep
 * their orientation. This is what "rotate the whole floor plan" does to each piece.
 */
fun DrawingElement.rotateAround(pivot: Offset, degrees: Float): DrawingElement {
    val rad = com.example.sitedraft.util.toRadians(degrees.toDouble())
    val c = kotlin.math.cos(rad).toFloat()
    val s = kotlin.math.sin(rad).toFloat()
    fun rp(p: Offset): Offset {
        val d = p - pivot
        return Offset(pivot.x + d.x * c - d.y * s, pivot.y + d.x * s + d.y * c)
    }
    fun half(size: Size) = Offset(size.width / 2f, size.height / 2f)
    val quarterTurns = kotlin.math.round(degrees / 90f).toInt()
    val rightAngle = abs(degrees - quarterTurns * 90f) < 0.01f
    return when (this) {
        is DrawingElement.Freehand -> copy(points = points.map { it.copy(offset = rp(it.offset)) })
        is DrawingElement.Line -> copy(start = rp(start), end = rp(end))
        is DrawingElement.Rectangle -> copy(topLeft = rp(topLeft + half(size)) - half(size), rotation = rotation + degrees)
        is DrawingElement.Oval -> copy(topLeft = rp(topLeft + half(size)) - half(size), rotation = rotation + degrees)
        is DrawingElement.Triangle -> copy(topLeft = rp(topLeft + half(size)) - half(size), rotation = rotation + degrees)
        is DrawingElement.Circle -> copy(center = rp(center))
        is DrawingElement.Symbol ->
            if (rightAngle && rotation == 0f && PlanSymbols.turnsBySwappingSize(this))
                copy(center = rp(center), size = if (quarterTurns % 2 != 0) Size(size.height, size.width) else size)
            else copy(center = rp(center), rotation = rotation + degrees)
        is DrawingElement.Dimension -> copy(start = rp(start), end = rp(end))
        is DrawingElement.Text -> copy(topLeft = rp(topLeft))
        else -> {
            val b = boundingRect()
            move(rp(b.center) - b.center)
        }
    }
}

private fun distanceFromPointToLineSegment(p: Offset, a: Offset, b: Offset): Float {
    val l2 = (a - b).getDistanceSquared()
    if (l2 == 0f) return (p - a).getDistance()
    var t = ((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / l2
    t = maxOf(0f, minOf(1f, t))
    return (p - Offset(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))).getDistance()
}

// Whole feet plus remaining inches, for display.
data class FeetInches(val feet: Int, val inches: Float)

// Splits a length in feet into whole feet + inches, rounding the inches to `inchResolution`
// (1/8" for drawing labels, 0.01" for the Properties fields) BEFORE the split. Rounding after
// splitting is what produced "2 ft 12.00 in" and "2' - 11 7/8"" for values a hair under 3':
// floor() took the feet from the unrounded value while the inches rounded up to a full 12.
fun splitFeetInches(valueInFeet: Float, inchResolution: Float): FeetInches {
    val unitsPerFoot = (12f / inchResolution).roundToInt()
    val totalUnits = (valueInFeet * 12f / inchResolution).roundToInt()
    val feet = totalUnits / unitsPerFoot
    val inches = (totalUnits % unitsPerFoot) * inchResolution
    return FeetInches(feet, inches)
}

// The same element in a different colour. Image/Pdf/Dxf carry no colour of their own.
fun DrawingElement.withColor(color: Color): DrawingElement = when (this) {
    is DrawingElement.Freehand -> copy(color = color)
    is DrawingElement.Line -> copy(color = color)
    is DrawingElement.Rectangle -> copy(color = color)
    is DrawingElement.Oval -> copy(color = color)
    is DrawingElement.Triangle -> copy(color = color)
    is DrawingElement.Circle -> copy(color = color)
    is DrawingElement.Dimension -> copy(color = color)
    is DrawingElement.Symbol -> copy(color = color)
    is DrawingElement.Text -> copy(color = color)
    else -> this
}
