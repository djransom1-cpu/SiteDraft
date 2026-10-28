package com.example.sitedraft.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.example.sitedraft.platform.KeyValuePrefs
import com.example.sitedraft.platform.LocalPlatform
import com.example.sitedraft.ui.canvas.parseHexColor
import com.example.sitedraft.ui.canvas.toHexString
import kotlinx.coroutines.flow.MutableStateFlow

// Device-level preferences. These are deliberately NOT part of CanvasState: that gets
// serialized into the project file, so anything in it is per-project and travels with the
// drawing. Recent colours belong to the user, not the drawing, so they live here, in the
// platform's key/value store (SharedPreferences, Java preferences, localStorage, NSUserDefaults).

private const val RECENT_COLORS = "recent_colors"
private const val PALM_REJECTION = "palm_rejection"
private const val MAX_RECENT_COLORS = 12

class RecentColors(val colors: List<Color>, val add: (Color) -> Unit)

/** How long after the S Pen was last seen (touching or hovering) AUTO keeps ignoring finger touch. */
const val PALM_REJECTION_AUTO_WINDOW_MS = 10_000L

enum class PalmRejectionMode(val label: String, val description: String) {
    OFF("Off", "Finger, capacitive pen and S Pen all draw."),
    AUTO("Auto", "Ignores finger touch for ${PALM_REJECTION_AUTO_WINDOW_MS / 1000}s after the S Pen was last seen, so a resting palm is ignored but finger-only users are never blocked."),
    STYLUS_ONLY("Stylus only", "Only the S Pen draws. Finger touch is pan and zoom only."),
}

class PalmRejectionSetting(val mode: PalmRejectionMode, val set: (PalmRejectionMode) -> Unit)

// In-memory mirrors of the stored values so every composable sees a change at once.
private val palmFlow = MutableStateFlow<PalmRejectionMode?>(null)
private val recentFlow = MutableStateFlow<List<Color>?>(null)

private fun loadPalm(prefs: KeyValuePrefs): PalmRejectionMode =
    PalmRejectionMode.entries.firstOrNull { it.name == prefs.getString(PALM_REJECTION) } ?: PalmRejectionMode.AUTO

private fun loadRecent(prefs: KeyValuePrefs): List<Color> =
    prefs.getString(RECENT_COLORS).orEmpty().split(",").mapNotNull { parseHexColor(it) }

/**
 * Device-level palm rejection mode. Whether you draw with an S Pen is a property of the
 * device and its owner, not of the drawing, so it is not in the project file.
 */
@Composable
fun rememberPalmRejection(): PalmRejectionSetting {
    val prefs = LocalPlatform.current.prefs
    val mode by palmFlow.collectAsState()
    LaunchedEffect(prefs) { if (palmFlow.value == null) palmFlow.value = loadPalm(prefs) }
    return PalmRejectionSetting(mode ?: PalmRejectionMode.AUTO) { m ->
        prefs.putString(PALM_REJECTION, m.name)
        palmFlow.value = m
    }
}

/** Most-recent-first list of colours the user has picked, plus a function to record another. */
@Composable
fun rememberRecentColors(): RecentColors {
    val prefs = LocalPlatform.current.prefs
    val colors by recentFlow.collectAsState()
    LaunchedEffect(prefs) { if (recentFlow.value == null) recentFlow.value = loadRecent(prefs) }
    return RecentColors(colors ?: emptyList()) { color ->
        val hex = color.toHexString()
        val existing = (recentFlow.value ?: loadRecent(prefs)).map { it.toHexString() }.filter { it != hex }
        val updated = (listOf(hex) + existing).take(MAX_RECENT_COLORS)
        prefs.putString(RECENT_COLORS, updated.joinToString(","))
        recentFlow.value = updated.mapNotNull { parseHexColor(it) }
    }
}
