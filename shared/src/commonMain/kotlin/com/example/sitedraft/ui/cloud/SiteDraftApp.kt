package com.example.sitedraft.ui.cloud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.sitedraft.platform.LocalPlatform
import com.example.sitedraft.ui.MainScreen
import com.example.sitedraft.ui.canvas.DrawingManager

/**
 * The app's root: sign-in, then the subscription gate, then [MainScreen]. Every host (Android,
 * desktop, web, iOS) shows this instead of MainScreen directly, so the public web URL is no
 * more usable without an account than the installed apps are.
 */
@Composable
fun SiteDraftApp(manager: DrawingManager, modifier: Modifier = Modifier) {
    val platform = LocalPlatform.current
    val cloud = platform.cloud
    val session = remember(cloud, manager) { CloudSession(platform, cloud, manager) }
    val user by cloud.user.collectAsState()

    CompositionLocalProvider(LocalCloudSession provides session) {
        val current = user
        if (current == null) {
            LaunchedEffect(Unit) { session.reset() }
            SignInScreen(cloud, modifier)
        } else {
            LaunchedEffect(current.uid) { session.check(current) }
            val access = session.access
            when {
                access?.allowed == true -> MainScreen(viewModel = manager, modifier = modifier)
                session.checking && access == null && session.error == null -> CheckingScreen(modifier)
                else -> SubscribeScreen(session, modifier)
            }
        }
    }
}

@Composable
private fun CheckingScreen(modifier: Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Checking your account...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
