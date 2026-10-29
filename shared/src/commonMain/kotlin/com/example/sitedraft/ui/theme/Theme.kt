package com.example.sitedraft.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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
    onSurfaceVariant = BlueprintWhite,
    surfaceContainerLowest = BlueDeep,
    surfaceContainerLow = ArchitecturalBlue,
    surfaceContainer = BlueMid,
    surfaceContainerHigh = ArchitecturalBlueContainer,
    surfaceContainerHighest = BlueRaised,
    surfaceBright = BlueRaised,
    surfaceDim = BlueDeep,
    inverseSurface = BlueprintWhite,
    inverseOnSurface = ArchitecturalBlue,
    outline = BlueOutline,
    outlineVariant = BlueOutlineVariant,
    error = ErrorOnBlue,
    onError = OnErrorOnBlue,
    errorContainer = ErrorContainerOnBlue,
    onErrorContainer = OnErrorContainerOnBlue
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
    onSurfaceVariant = BlueprintWhite,
    surfaceContainerLowest = BlueDeep,
    surfaceContainerLow = ArchitecturalBlue,
    surfaceContainer = BlueMid,
    surfaceContainerHigh = ArchitecturalBlueContainer,
    surfaceContainerHighest = BlueRaised,
    surfaceBright = BlueRaised,
    surfaceDim = BlueDeep,
    inverseSurface = BlueprintWhite,
    inverseOnSurface = ArchitecturalBlue,
    outline = BlueOutline,
    outlineVariant = BlueOutlineVariant,
    error = ErrorOnBlue,
    onError = OnErrorOnBlue,
    errorContainer = ErrorContainerOnBlue,
    onErrorContainer = OnErrorContainerOnBlue
)


@Composable
fun SiteDraftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is DISABLED for this architectural app
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // dynamicColor is accepted for source compatibility; the architectural palette is always used.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}