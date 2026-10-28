package com.example.sitedraft.data

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.sitedraft.ui.canvas.parseHexColor
import com.example.sitedraft.ui.canvas.toHexString
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// Device-level preferences. These are deliberately NOT part of CanvasState: that gets
// serialized into the project file, so anything in it is per-project and travels with the
// drawing. Recent colours belong to the user, not the drawing, so they live here.
private val Context.userPrefsDataStore by preferencesDataStore(name = "user_prefs")

private val RECENT_COLORS = stringPreferencesKey("recent_colors")
private const val MAX_RECENT_COLORS = 12

class RecentColors(val colors: List<Color>, val add: (Color) -> Unit)

/** How long after the S Pen was last seen (touching or hovering) AUTO keeps ignoring finger touch. */
const val PALM_REJECTION_AUTO_WINDOW_MS = 10_000L

enum class PalmRejectionMode(val label: String, val description: String) {
    OFF("Off", "Finger, capacitive pen and S Pen all draw."),
    AUTO("Auto", "Ignores finger touch for ${PALM_REJECTION_AUTO_WINDOW_MS / 1000}s after the S Pen was last seen, so a resting palm is ignored but finger-only users are never blocked."),
    STYLUS_ONLY("Stylus only", "Only the S Pen draws. Finger touch is pan and zoom only."),
}

private val PALM_REJECTION = stringPreferencesKey("palm_rejection")

class PalmRejectionSetting(val mode: PalmRejectionMode, val set: (PalmRejectionMode) -> Unit)

/**
 * Device-level palm rejection mode. This used to be `CanvasState.stylusOnlyDrawing`, a per-project
 * flag that reset to off with every new project and every launch; whether you draw with an S Pen
 * is a property of the device and its owner, not of the drawing.
 */
@Composable
fun rememberPalmRejection(): PalmRejectionSetting {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val mode by remember(context) {
        context.userPrefsDataStore.data.map { prefs ->
            PalmRejectionMode.entries.firstOrNull { it.name == prefs[PALM_REJECTION] } ?: PalmRejectionMode.AUTO
        }
    }.collectAsState(initial = PalmRejectionMode.AUTO)
    val set: (PalmRejectionMode) -> Unit = { m ->
        scope.launch { context.userPrefsDataStore.edit { prefs -> prefs[PALM_REJECTION] = m.name } }
    }
    return PalmRejectionSetting(mode, set)
}

/** Most-recent-first list of colours the user has picked, plus a function to record another. */
@Composable
fun rememberRecentColors(): RecentColors {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val colors by remember(context) {
        context.userPrefsDataStore.data.map { prefs ->
            prefs[RECENT_COLORS].orEmpty().split(",").mapNotNull { parseHexColor(it) }
        }
    }.collectAsState(initial = emptyList())
    val add: (Color) -> Unit = { color ->
        scope.launch {
            context.userPrefsDataStore.edit { prefs ->
                val hex = color.toHexString()
                val existing = prefs[RECENT_COLORS].orEmpty().split(",").filter { it.isNotBlank() && it != hex }
                prefs[RECENT_COLORS] = (listOf(hex) + existing).take(MAX_RECENT_COLORS).joinToString(",")
            }
        }
    }
    return RecentColors(colors, add)
}
