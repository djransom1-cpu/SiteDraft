package com.example.sitedraft.ui

import com.example.sitedraft.data.rememberPalmRejection
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.example.sitedraft.platform.LocalPlatform
import com.example.sitedraft.platform.rememberFileImporter
import com.example.sitedraft.ui.canvas.DrawingCanvas
import com.example.sitedraft.ui.canvas.parseDxf
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingTool
import androidx.compose.ui.geometry.Offset
import com.example.sitedraft.ui.components.*
import com.example.sitedraft.ui.components.SettingsPanel
import androidx.compose.material.icons.automirrored.filled.Help
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    viewModel: DrawingManager,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state
    // rememberSaveable: with configChanges handled in the manifest the Activity is no longer
    // recreated on rotation, but keep these safe across process death too.
    var showPropertiesDialog by rememberSaveable { mutableStateOf(value = false) }
    // Which toolbar button opened the print/export sheet dialog; null = closed.
    var printDialog by remember { mutableStateOf<PrintAction?>(null) }
    var currentNav by rememberSaveable { mutableStateOf("Home") }

    val platform = LocalPlatform.current
    val density = LocalDensity.current.density
    // Tablet / desktop / wide browser layout from 600dp (Material's medium width class).
    val windowWidthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val isExpanded = windowWidthDp >= 600.dp
    val importer = rememberFileImporter()

    val isMoveToolActive = state.currentTool == DrawingTool.MOVE || state.currentTool == DrawingTool.MOVE_PRECISE
    val isPropertiesVisible = (state.selectedElementId != null && !isMoveToolActive) || (isExpanded && (currentNav == "Tools"))
    val isSettingsVisible = (currentNav == "Settings") && (!isPropertiesVisible)
    // Laser stays up even with a selection: applying a reading to the selected element is the point.
    val isLaserVisible = isExpanded && currentNav == "Laser"
    val isPlanVisible = isExpanded && currentNav == "Plan"



    // Selected rail item in safety orange: the default selected label colour is the theme's
    // "secondary" (navy 0xFF003366), which was dark blue text on the blue rail.
    val railColors = NavigationRailItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = MaterialTheme.colorScheme.primary,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(modifier = modifier.fillMaxSize()) {
        // Navigation Rail
        NavigationRail(
            modifier = Modifier.fillMaxHeight(),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            NavigationRailItem(
                colors = railColors,
                selected = currentNav == "Home",
                onClick = { currentNav = if (currentNav == "Home") "Drawing" else "Home" },
                icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                label = { Text("Home") },
            )
            NavigationRailItem(
                colors = railColors,
                selected = currentNav == "Symbols",
                onClick = { currentNav = if (currentNav == "Symbols") "Drawing" else "Symbols" },
                icon = { Icon(Icons.Default.Widgets, contentDescription = "Symbols") },
                label = { Text("Symbols") },
            )
            NavigationRailItem(
                colors = railColors,
                selected = currentNav == "Tools",
                onClick = { currentNav = if (currentNav == "Tools") "Drawing" else "Tools" },
                icon = { Icon(Icons.Default.Build, contentDescription = "Tools") },
                label = { Text("Tools") },
            )
            NavigationRailItem(
                colors = railColors,
                selected = currentNav == "Settings",
                onClick = { currentNav = if (currentNav == "Settings") "Drawing" else "Settings" },
                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                label = { Text("Settings") },
            )
            Spacer(modifier = Modifier.weight(1f))
            NavigationRailItem(
                colors = railColors,
                selected = currentNav == "Plan",
                onClick = { currentNav = if (currentNav == "Plan") "Drawing" else "Plan" },
                icon = { Icon(Icons.Default.Architecture, contentDescription = "Floor plan") },
                label = { Text("Plan") },
            )
            if (platform.supportsLaser) NavigationRailItem(
                colors = railColors,
                selected = currentNav == "Laser",
                onClick = { currentNav = if (currentNav == "Laser") "Drawing" else "Laser" },
                icon = { Icon(Icons.Default.Straighten, contentDescription = "Laser") },
                label = { Text("Laser") },
            )
            NavigationRailItem(
                colors = railColors,
                selected = false,
                onClick = { },
                icon = { Icon(Icons.AutoMirrored.Filled.Help, contentDescription = "Help") },
                label = { Text("Help") },
            )
        }

        // Side-by-side layout for tablets
        Row(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // Left Panel (Symbol Library) - Permanent on tablet
            if (isExpanded && (currentNav == "Symbols")) {
                SymbolLibraryPanel(
                    onEvent = { event ->
                        viewModel.onEvent(event)
                        // Picking a symbol closes the library so the canvas is free for the placing tap.
                        if (event is DrawingEvent.SelectSymbol) currentNav = "Drawing"
                    },
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                        .safeDrawingPadding()
                        .padding(8.dp),
                    onClose = { currentNav = "Drawing" }
                )
            }
            
            if (isExpanded && currentNav == "Home") {
                ProjectManagerPanel(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .safeDrawingPadding()
                        .padding(8.dp),
                    onClose = { currentNav = "Drawing" }
                )
            }

            // Main Drawing Area
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    DrawingCanvas(
                        state = state,
                        onEvent = viewModel::onEvent,
                        palmRejection = rememberPalmRejection().mode,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Top ToolBar Overlay
                    ToolBar(
                        selectedTool = state.currentTool,
                        currentColor = state.currentColor,
                        onPaletteClick = { showPropertiesDialog = true },
                        onImportImage = { importer.pickImage { viewModel.onEvent(DrawingEvent.ImportImage(it)) } },
                        onImportPdf = { importer.pickPdf { viewModel.onEvent(DrawingEvent.ImportPdf(it)) } },
                        onImportDxf = { importer.pickText { text -> parseDxf(text, state).takeIf { it.isNotEmpty() }?.let { viewModel.onEvent(DrawingEvent.ImportDxf(it)) } } },
                        onSaveProject = { viewModel.onEvent(DrawingEvent.SaveProject) },
                        onLoadProject = { viewModel.onEvent(DrawingEvent.LoadProject) },
                        onExportPdf = { printDialog = PrintAction.EXPORT_PDF },
                        onPrint = { printDialog = PrintAction.PRINT },
                        onExportDxf = { viewModel.onEvent(DrawingEvent.ExportDxf) },
                        onEvent = { event ->
                            viewModel.onEvent(event)
                            // Auto-close panels when a tool is selected or an action is taken
                            if (event is DrawingEvent.SelectTool || event is DrawingEvent.SelectSymbol) {
                                currentNav = "Drawing"
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                    )

                    // "Tap to place" banner while a library symbol is waiting for its spot.
                    state.pendingPlacement?.let { pending ->
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 104.dp, start = 16.dp, end = 16.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shadowElevation = 6.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Drag on the canvas to position ${pending.label}, lift to place",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 2,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                TextButton(onClick = { viewModel.onEvent(DrawingEvent.CancelPlacement) }) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }

                    // Mobile/Compact Overlay Scrim (Easier closing)
                    if (!isExpanded && (currentNav != "Drawing" || isPropertiesVisible)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.05f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { 
                                    currentNav = "Drawing"
                                    viewModel.onEvent(DrawingEvent.SelectTool(state.currentTool)) // Clears selection
                                }
                        )
                    }
                    
                    // Mobile/Compact Symbol Overlay
                    if (!isExpanded) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = currentNav == "Symbols",
                            enter = slideInHorizontally() + fadeIn(),
                            exit = slideOutHorizontally() + fadeOut(),
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) {
                            SymbolLibraryPanel(
                                onEvent = { event ->
                                    viewModel.onEvent(event)
                                    if (event is DrawingEvent.SelectSymbol) currentNav = "Drawing"
                                },
                                modifier = Modifier
                                    .safeDrawingPadding()
                                    .padding(16.dp),
                                onClose = { currentNav = "Drawing" }
                            )
                        }
                    }

                    if (!isExpanded && currentNav == "Home") {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = true,
                            enter = slideInHorizontally() + fadeIn(),
                            exit = slideOutHorizontally() + fadeOut(),
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) {
                            ProjectManagerPanel(
                                state = state,
                                viewModel = viewModel,
                                modifier = Modifier
                                    .safeDrawingPadding()
                                    .padding(16.dp),
                                onClose = { currentNav = "Drawing" }
                            )
                        }
                    }
                    
                    // Mobile/Compact Properties/Settings Overlay
                    if (!isExpanded) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isSettingsVisible,
                            enter = slideInHorizontally { it } + fadeIn(),
                            exit = slideOutHorizontally { it } + fadeOut(),
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            SettingsPanel(
                                state = state,
                                onEvent = viewModel::onEvent,
                                modifier = Modifier
                                    .safeDrawingPadding()
                                    .padding(16.dp),
                                onClose = { currentNav = "Drawing" }
                            )
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = isPropertiesVisible,
                            enter = slideInHorizontally { it } + fadeIn(),
                            exit = slideOutHorizontally { it } + fadeOut(),
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            PropertiesPanel(
                                state = state,
                                onEvent = viewModel::onEvent,
                                modifier = Modifier
                                    .safeDrawingPadding()
                                    .padding(16.dp),
                                onClose = {
                                    currentNav = "Drawing"
                                    viewModel.onEvent(DrawingEvent.DeselectElement)
                                }
                            )
                        }
                    }

                    // Floating Action Bar for Selected Element (or a box-selected group - it
                    // only offers Copy/Delete/Close for a group since Move/Pt-to-Pt there are
                    // single-element tools; the group itself is moved by dragging it directly
                    // with Select, same as any single element).
                    if ((state.selectedElementId != null || state.selectedElementIds.isNotEmpty()) && currentNav == "Drawing") {
                        val explodeCandidate = state.elements.find { it.id == state.selectedElementId }
                        FloatingActionBar(
                            currentTool = state.currentTool,
                            canExplode = explodeCandidate is DrawingElement.Rectangle ||
                                explodeCandidate is DrawingElement.Triangle ||
                                explodeCandidate is DrawingElement.Symbol ||
                                explodeCandidate is DrawingElement.ElevationView,
                            onEvent = viewModel::onEvent,
                            onClose = { viewModel.onEvent(DrawingEvent.DeselectElement) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                // Clear of ZoomControls (BottomEnd, ~16-80dp band) - this bar has
                                // grown wider as tools were added (Explode is the newest), and at
                                // that width its right side reaches into ZoomControls' corner, so
                                // the two need real vertical separation, not just enough to avoid
                                // touching at rest.
                                .padding(bottom = 150.dp)
                        )
                    }

                    // Zoom Controls
                    ZoomControls(
                        currentZoom = state.zoom,
                        onEvent = viewModel::onEvent,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .safeDrawingPadding()
                            .padding(16.dp)
                    )
                }
            }

            // Right Panel (Properties/Settings) - Permanent on tablet
            if (isExpanded) {
                if (isPlanVisible) {
                    FloorPlanPanel(
                        state = state,
                        onEvent = viewModel::onEvent,
                        modifier = Modifier
                            .width(320.dp)
                            .fillMaxHeight()
                            .safeDrawingPadding()
                            .padding(8.dp),
                        onClose = { currentNav = "Drawing" }
                    )
                } else if (isLaserVisible) {
                    LaserPanel(
                        state = state,
                        laser = viewModel.laser,
                        onEvent = viewModel::onEvent,
                        modifier = Modifier
                            .width(320.dp)
                            .fillMaxHeight()
                            .safeDrawingPadding()
                            .padding(8.dp),
                        onClose = { currentNav = "Drawing" }
                    )
                } else if (isSettingsVisible) {
                    SettingsPanel(
                        state = state,
                        onEvent = viewModel::onEvent,
                        modifier = Modifier
                            .width(320.dp)
                            .fillMaxHeight()
                            .safeDrawingPadding()
                            .padding(8.dp),
                        onClose = { currentNav = "Drawing" }
                    )
                } else if (isPropertiesVisible) {
                    PropertiesPanel(
                        state = state,
                        onEvent = viewModel::onEvent,
                        modifier = Modifier
                            .width(320.dp)
                            .fillMaxHeight()
                            .safeDrawingPadding()
                            .padding(8.dp),
                        onClose = {
                            currentNav = "Drawing"
                            viewModel.onEvent(DrawingEvent.DeselectElement)
                        }
                    )
                }
            }
        }
    }

    if (showPropertiesDialog) {
        DrawingPropertiesDialog(
            currentColor = state.currentColor,
            currentWeight = state.currentStrokeWidth,
            currentBackground = state.background,
            currentCanvasColor = state.backgroundColor,
            onColorSelect = { viewModel.onEvent(DrawingEvent.SelectColor(it)) },
            onWeightSelect = { viewModel.onEvent(DrawingEvent.SelectStrokeWidth(it)) },
            onBackgroundSelect = { viewModel.onEvent(DrawingEvent.SelectBackground(it)) },
            onCanvasColorSelect = { viewModel.onEvent(DrawingEvent.SelectBackgroundColor(it)) },
            onDismiss = { showPropertiesDialog = false }
        )
    }

    printDialog?.let { action ->
        PrintDialog(
            state = state,
            primary = action,
            onPrint = { viewModel.onEvent(DrawingEvent.Print(it, density)) },
            onExportPdf = { viewModel.onEvent(DrawingEvent.ExportPdf(it, density)) },
            onDismiss = { printDialog = null }
        )
    }
}
