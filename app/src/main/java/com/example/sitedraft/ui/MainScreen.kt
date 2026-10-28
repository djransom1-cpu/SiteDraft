package com.example.sitedraft.ui

import com.example.sitedraft.data.rememberPalmRejection
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import androidx.core.graphics.createBitmap
import com.example.sitedraft.ui.canvas.DrawingCanvas
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingTool
import androidx.compose.ui.geometry.Offset
import com.example.sitedraft.ui.components.*
import com.example.sitedraft.ui.components.SettingsPanel
import androidx.compose.material.icons.automirrored.filled.Help
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: DrawingManager = viewModel(),
) {
    val state by viewModel.state
    // rememberSaveable: with configChanges handled in the manifest the Activity is no longer
    // recreated on rotation, but keep these safe across process death too.
    var showPropertiesDialog by rememberSaveable { mutableStateOf(value = false) }
    var currentNav by rememberSaveable { mutableStateOf("Home") }
    
    val context = LocalContext.current
    
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val isExpanded = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            loadBitmapFromUri(context, it)?.let { bitmap ->
                viewModel.onEvent(DrawingEvent.ImportImage(bitmap))
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            val bitmaps = loadPdfPagesFromUri(context, it)
            if (bitmaps.isNotEmpty()) {
                viewModel.onEvent(DrawingEvent.ImportPdf(bitmaps))
            }
        }
    }

    val dxfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            val elements = loadDxfFromUri(context, it)
            if (elements.isNotEmpty()) {
                viewModel.onEvent(DrawingEvent.ImportDxf(elements))
            }
        }
    }

    val isMoveToolActive = state.currentTool == DrawingTool.MOVE || state.currentTool == DrawingTool.MOVE_PRECISE
    val isPropertiesVisible = (state.selectedElementId != null && !isMoveToolActive) || (isExpanded && (currentNav == "Tools"))
    val isSettingsVisible = (currentNav == "Settings") && (!isPropertiesVisible)
    // Laser stays up even with a selection: applying a reading to the selected element is the point.
    val isLaserVisible = isExpanded && currentNav == "Laser"
    val isPlanVisible = isExpanded && currentNav == "Plan"



    Row(modifier = modifier.fillMaxSize()) {
        // Navigation Rail
        NavigationRail(
            modifier = Modifier.fillMaxHeight(),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            NavigationRailItem(
                selected = currentNav == "Home",
                onClick = { currentNav = if (currentNav == "Home") "Drawing" else "Home" },
                icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                label = { Text("Home") },
            )
            NavigationRailItem(
                selected = currentNav == "Symbols",
                onClick = { currentNav = if (currentNav == "Symbols") "Drawing" else "Symbols" },
                icon = { Icon(Icons.Default.Widgets, contentDescription = "Symbols") },
                label = { Text("Symbols") },
            )
            NavigationRailItem(
                selected = currentNav == "Tools",
                onClick = { currentNav = if (currentNav == "Tools") "Drawing" else "Tools" },
                icon = { Icon(Icons.Default.Build, contentDescription = "Tools") },
                label = { Text("Tools") },
            )
            NavigationRailItem(
                selected = currentNav == "Settings",
                onClick = { currentNav = if (currentNav == "Settings") "Drawing" else "Settings" },
                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                label = { Text("Settings") },
            )
            Spacer(modifier = Modifier.weight(1f))
            NavigationRailItem(
                selected = currentNav == "Plan",
                onClick = { currentNav = if (currentNav == "Plan") "Drawing" else "Plan" },
                icon = { Icon(Icons.Default.Architecture, contentDescription = "Floor plan") },
                label = { Text("Plan") },
            )
            NavigationRailItem(
                selected = currentNav == "Laser",
                onClick = { currentNav = if (currentNav == "Laser") "Drawing" else "Laser" },
                icon = { Icon(Icons.Default.Straighten, contentDescription = "Laser") },
                label = { Text("Laser") },
            )
            NavigationRailItem(
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
                        onImportImage = { imageLauncher.launch("image/*") },
                        onImportPdf = { pdfLauncher.launch("application/pdf") },
                        onImportDxf = { dxfLauncher.launch("*/*") },
                        onSaveProject = { viewModel.onEvent(DrawingEvent.SaveProject(context)) },
                        onLoadProject = { viewModel.onEvent(DrawingEvent.LoadProject(context)) },
                        onExportPdf = { viewModel.onEvent(DrawingEvent.ExportPdf(context)) },
                        onExportDxf = { viewModel.onEvent(DrawingEvent.ExportDxf(context)) },
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
                                explodeCandidate is DrawingElement.Symbol,
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
}

private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        BitmapFactory.decodeStream(inputStream)
    } catch (_: Exception) {
        null
    }
}

private fun loadPdfPagesFromUri(context: Context, uri: Uri): List<Bitmap> {
    val bitmaps = mutableListOf<Bitmap>()
    try {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        if (pfd != null) {
            val renderer = PdfRenderer(pfd)
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val bitmap = createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmaps.add(bitmap)
                page.close()
            }
            renderer.close()
            pfd.close()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return bitmaps
}

private fun loadDxfFromUri(context: Context, uri: Uri): List<DrawingElement> {
    val elements = mutableListOf<DrawingElement>()
    try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val reader = inputStream?.bufferedReader()
        var line = reader?.readLine()
        while (line != null) {
            if (line.trim() == "LINE") {
                var x1 = 0f; var y1 = 0f; var x2 = 0f; var y2 = 0f
                while ((line != null) && (line.trim() != "0")) {
                    line = reader?.readLine()
                    when (line?.trim()) {
                        "10" -> x1 = reader?.readLine()?.toFloatOrNull() ?: 0f
                        "20" -> y1 = reader?.readLine()?.toFloatOrNull() ?: 0f
                        "11" -> x2 = reader?.readLine()?.toFloatOrNull() ?: 0f
                        "21" -> y2 = reader?.readLine()?.toFloatOrNull() ?: 0f
                    }
                }
                elements.add(DrawingElement.Line(start = Offset(x1, y1), end = Offset(x2, y2), color = Color.Gray, strokeWidth = 1f))
                if (line == null) break
                continue
            }
            line = reader?.readLine()
        }
        inputStream?.close()
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return elements
}
