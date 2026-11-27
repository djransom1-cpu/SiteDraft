package com.example.sitedraft.ui.canvas

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.sitedraft.platform.randomId
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.roundToInt

/** A picture kept in the project for art and wallpaper (shrunk so saves stay quick). */
@Serializable
data class Picture(
    val id: String = randomId(),
    val name: String = "",
    @Serializable(with = BitmapSerializer::class)
    val bitmap: ImageBitmap
)

/**
 * Art, wallpaper or paint on one face of a wall (Dan, Nov 25; paint Nov 26), kept on the wall (not drawn on an elevation) so every
 * elevation of that face shows it and it survives a rebuild. Lengths are inches; [xIn] is measured along the whole
 * wall from the viewer's left end.
 */
@Serializable
data class WallDecor(
    val id: String = randomId(),
    val wallId: String,
    val side: ViewerSide,
    /** [ART], [WALLPAPER] or [PAINT]. */
    val kind: Int,
    val pictureId: String = "",
    /** Art: its left edge along the wall and its bottom off the floor; size; frame width (0 = none). */
    val xIn: Float = 0f,
    val bottomIn: Float = 48f,
    val widthIn: Float = 30f,
    val heightIn: Float = 24f,
    val frameIn: Float = 1.5f,
    /** Wallpaper: the swatch's repeat width (its height follows the picture's shape). */
    val repeatIn: Float = 21f,
    /** Paint: its colour as 0xRRGGBB, its maker's number ("SW 7036") and name ("Accessible Beige"). */
    val rgb: Int = 0xFFFFFF,
    val colorCode: String = "",
    val colorName: String = "",
    /** Paint: which rooms along the wall face it covers, bit i = the i-th stretch between walls butting in (Wainscot.roomSpans); 0 = all. */
    val rooms: Int = 0,
) {
    companion object {
        const val ART = 0
        const val WALLPAPER = 1
        const val PAINT = 2
    }

    /** "SW 7036 Accessible Beige", or the hex for a colour with no name. */
    val paintLabel: String get() = listOf(colorCode, colorName).filter { it.isNotBlank() }.joinToString(" ")
        .ifBlank { "#" + rgb.toString(16).padStart(6, '0').uppercase() }
}

object WallDecorations {
    const val MAX_PX = 600
    const val ART_CENTER_IN = 60f
    const val ART_WIDTH_IN = 30f
    const val WALLPAPER_REPEAT_IN = 21f

    /** A picture no bigger than [MAX_PX] on its long side, so the project file stays small. */
    fun shrink(b: ImageBitmap, maxPx: Int = MAX_PX): ImageBitmap {
        val long = max(b.width, b.height)
        if (long <= maxPx) return b
        val k = maxPx.toFloat() / long
        val out = ImageBitmap((b.width * k).roundToInt().coerceAtLeast(1), (b.height * k).roundToInt().coerceAtLeast(1))
        Canvas(out).drawImageRect(b, IntOffset.Zero, IntSize(b.width, b.height), IntOffset.Zero, IntSize(out.width, out.height), Paint())
        return out
    }

    fun on(state: CanvasState, wallId: String, side: ViewerSide) = state.wallDecor.filter { it.wallId == wallId && it.side == side }
    fun picture(state: CanvasState, id: String) = state.pictures.firstOrNull { it.id == id }

    /**
     * New art for a wall face: [widthIn] wide (height from the picture's shape), centred on [centerAlongIn] (along
     * the whole wall) with its centre at 60" off the floor.
     */
    fun newArt(wallId: String, side: ViewerSide, picture: Picture, centerAlongIn: Float, widthIn: Float = ART_WIDTH_IN): WallDecor {
        val h = widthIn * picture.bitmap.height / max(1, picture.bitmap.width)
        return WallDecor(wallId = wallId, side = side, kind = WallDecor.ART, pictureId = picture.id,
            xIn = centerAlongIn - widthIn / 2f, bottomIn = ART_CENTER_IN - h / 2f, widthIn = widthIn, heightIn = h)
    }

    /** Paint on a wall face (one per face): [rgb] 0xRRGGBB, with the maker's number and name. */
    fun newPaint(wallId: String, side: ViewerSide, rgb: Int, code: String, name: String, rooms: Int = 0) =
        WallDecor(wallId = wallId, side = side, kind = WallDecor.PAINT, rgb = rgb and 0xFFFFFF, colorCode = code.trim(), colorName = name.trim(), rooms = rooms)

    /** The rooms (bit mask) an elevation shows of its wall face: the stretches its length overlaps; 0 = the whole face. */
    fun roomsShown(spans: List<Pair<Float, Float>>, fromIn: Float, toIn: Float, lengthIn: Float): Int {
        if (spans.size <= 1 || (fromIn <= 1f && toIn >= lengthIn - 1f)) return 0
        var m = 0
        spans.forEachIndexed { i, (a, b) -> if (minOf(b, toIn) - maxOf(a, fromIn) > 2f) m = m or (1 shl i) }
        return if (m == (1 shl spans.size) - 1) 0 else m
    }

    /**
     * Paints on a face after a new one covering [rooms] (0 = all) goes on: each old one keeps only the rooms the new one
     * doesn't cover, and drops out when none are left.
     */
    fun paintsAfter(old: List<WallDecor>, rooms: Int, roomCount: Int): List<WallDecor> {
        val all = if (roomCount >= 31) -1 else (1 shl roomCount) - 1
        val cover = if (rooms == 0) all else rooms
        return old.mapNotNull { p ->
            val left = (if (p.rooms == 0) all else p.rooms) and cover.inv()
            if (left == 0) null else p.copy(rooms = left)
        }
    }

    /** A hex colour ("#D1C7B8", "d1c7b8") as 0xRRGGBB; null when it isn't one. */
    fun parseHex(hex: String): Int? = hex.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)

    fun newWallpaper(wallId: String, side: ViewerSide, picture: Picture, repeatIn: Float = WALLPAPER_REPEAT_IN) =
        WallDecor(wallId = wallId, side = side, kind = WallDecor.WALLPAPER, pictureId = picture.id, repeatIn = repeatIn)

    /** A picture's height for a given width, inches. */
    fun heightFor(picture: Picture, widthIn: Float) = widthIn * picture.bitmap.height / max(1, picture.bitmap.width)

    /**
     * Wallpaper tiles over [fromIn]..[toIn] along the view and [bottomIn]..[topIn] up: (left, bottom, width,
     * height, fraction of the swatch's width used, fraction of its height used), the last row and column cut short.
     */
    fun tiles(fromIn: Float, toIn: Float, bottomIn: Float, topIn: Float, repeatW: Float, repeatH: Float): List<FloatArray> {
        if (repeatW <= 0.5f || repeatH <= 0.5f || toIn <= fromIn || topIn <= bottomIn) return emptyList()
        val out = mutableListOf<FloatArray>()
        var y = bottomIn
        while (y < topIn - 0.01f) {
            val h = minOf(repeatH, topIn - y)
            var x = fromIn
            while (x < toIn - 0.01f) {
                val w = minOf(repeatW, toIn - x)
                out += floatArrayOf(x, y, w, h, w / repeatW, h / repeatH)
                x += repeatW
            }
            y += repeatH
        }
        return out
    }
}
