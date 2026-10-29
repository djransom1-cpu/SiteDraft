package com.example.sitedraft

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.example.sitedraft.platform.IosPlatform
import com.example.sitedraft.platform.LocalPlatform
import com.example.sitedraft.ui.cloud.SiteDraftApp
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.theme.SiteDraftTheme
import platform.UIKit.UIViewController

/** Entry point for the iOS app: the Swift side shows this view controller full screen. */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val platform = remember { IosPlatform() }
    val manager = remember { DrawingManager(platform) }
    CompositionLocalProvider(LocalPlatform provides platform) {
        SiteDraftTheme {
            SiteDraftApp(manager = manager)
        }
    }
}
