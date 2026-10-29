package com.example.sitedraft.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.sitedraft.platform.DesktopPlatform
import com.example.sitedraft.platform.LocalPlatform
import com.example.sitedraft.ui.cloud.SiteDraftApp
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.theme.SiteDraftTheme

fun main() = application {
    val platform = remember { DesktopPlatform() }
    val manager = remember { DrawingManager(platform) }
    DisposableEffect(Unit) { onDispose { manager.dispose() } }
    Window(
        onCloseRequest = ::exitApplication,
        title = "SiteDraft",
        // Title bar / taskbar icon (the installer's own icon comes from build.gradle.kts).
        icon = painterResource("icon.png"),
        // Open maximized: a fixed 1400x900 window was taller than a laptop screen at 125-150%
        // display scaling, so the title bar (close, minimize, maximize) sat above the top edge
        // and could not be reached. The restore size fits a 1366x768 laptop at 125%.
        state = rememberWindowState(
            placement = WindowPlacement.Maximized,
            position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
            size = DpSize(1000.dp, 560.dp)
        )
    ) {
        CompositionLocalProvider(LocalPlatform provides platform) {
            SiteDraftTheme {
                SiteDraftApp(manager = manager)
            }
        }
    }
}
