package com.example.sitedraft.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * A library symbol at its stock size and with its stock settings (moved out of DrawingManager.addSymbol, Nov 28),
 * so the canvas and the library's icons make it the same way.
 */
object SymbolFactory {
    fun build(
        name: String, category: String, state: CanvasState, center: Offset = Offset(500f, 500f),
        rotation: Float = 0f, sizeOverride: Size? = null, widthIn: Float? = null
    ): DrawingElement.Symbol {
        val ratio = state.scale.ratio
        var properties = emptyMap<String, Float>()
        
        var size = when {
            category in PlanSymbols.categories -> {
                val (wIn, dIn) = PlanSymbols.footprintAt(name, widthIn) ?: Pair(24f, 24f)
                properties = mapOf("widthInches" to wIn, "depthInches" to dIn)
                // Doors and windows carry their own heights (and a window its sill), editable
                // in Properties; elevations read these instead of the wall-wide defaults.
                // A shower carries its glass, curb, tile and fixture settings.
                if (category == PlanSymbols.SHOWERS) properties = properties + Shower.defaults(name)
                if (category == PlanSymbols.STAIRS) properties = properties + Stairs.defaults(name)
                if (category == PlanSymbols.RAILINGS) properties = properties + Guards.defaults(name)
                if (category in Structure.categories) properties = properties + Structure.defaults(name)
                if (category == PlanSymbols.DOORS_WINDOWS) {
                    properties = properties + if (PlanSymbols.isWindow(name)) {
                        // The head on the job's door line (Dan, Nov 25).
                        val head = state.building.doorHeadIn
                        mapOf(PlanSymbols.PROP_SILL_IN to (head - PlanSymbols.NEW_WINDOW_HEIGHT_IN).coerceAtLeast(0f), PlanSymbols.PROP_HEIGHT_IN to PlanSymbols.NEW_WINDOW_HEIGHT_IN)
                    } else {
                        mapOf(PlanSymbols.PROP_HEIGHT_IN to state.building.doorHeadIn)
                    }
                }
                Size(state.inchesToPx(wIn), state.inchesToPx(dIn))
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
            // Section and detail callouts: a 8' cut line or leader, the bubble past its end.
            // A porch, 8' wide and 5' out (Dan's usual), dragged against an exterior wall.
            // A garage, 24' x 24' unless resized, dragged against the house (attached) or set apart (detached).
            // A wing, 14' wide and 20' out unless resized, against a house wall.
            category == Wings.CATEGORY -> {
                properties = Wings.defaults() + mapOf("widthInches" to 168f, "depthInches" to 240f)
                Size(state.inchesToPx(168f), state.inchesToPx(240f))
            }
            category == Garages.CATEGORY -> {
                properties = Garages.defaults() + mapOf("widthInches" to 288f, "depthInches" to 288f)
                Size(state.inchesToPx(288f), state.inchesToPx(288f))
            }
            category == Porches.CATEGORY -> {
                properties = Porches.defaults() + mapOf("widthInches" to 96f, "depthInches" to 60f)
                Size(state.inchesToPx(96f), state.inchesToPx(60f))
            }
            category == Callouts.CATEGORY -> {
                properties = Callouts.defaults(name)
                Size(state.inchesToPx(96f), state.inchesToPx(24f))
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
            size = calculateBeamPixelSize(properties, state.scale.ratio, state.unitSystem)
        }
        
        return DrawingElement.Symbol(
            symbolName = name,
            category = category,
            center = center,
            size = sizeOverride ?: size,
            color = state.currentColor,
            rotation = rotation,
            properties = properties
        )
    }
}
