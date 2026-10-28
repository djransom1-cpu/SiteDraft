package com.example.sitedraft.ui.theme

import androidx.compose.ui.graphics.Color
import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = SafetyOrange,
    onPrimary = Color.Black,
    primaryContainer = SafetyOrangeContainer,
    onPrimaryContainer = OnSafetyOrangeContainer,
    secondary = ArchitecturalBlue,
    onSecondary = Color.White,
    secondaryContainer = ArchitecturalBlueContainer,
    onSecondaryContainer = OnArchitecturalBlueContainer,
    background = ArchitecturalBlue,
    surface = ArchitecturalBlueContainer,
    surfaceVariant = ArchitecturalBlueContainer.copy(alpha = 0.8f),
    onBackground = BlueprintWhite,
    onSurface = BlueprintWhite,
    onSurfaceVariant = BlueprintWhite
)

private val LightColorScheme = lightColorScheme(
    primary = SafetyOrange,
    onPrimary = Color.White,
    primaryContainer = SafetyOrangeContainer,
    onPrimaryContainer = OnSafetyOrangeContainer,
    secondary = ArchitecturalBlue,
    onSecondary = Color.White,
    secondaryContainer = ArchitecturalBlueContainer,
    onSecondaryContainer = OnArchitecturalBlueContainer,
    background = ArchitecturalBlue,
    surface = ArchitecturalBlue,
    onBackground = BlueprintWhite,
    onSurface = BlueprintWhite,
    surfaceVariant = ArchitecturalBlueContainer,
    onSurfaceVariant = BlueprintWhite
)


@Composable
fun SiteDraftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is DISABLED for this architectural app
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}