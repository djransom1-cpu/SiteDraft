@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import com.example.sitedraft.ui.canvas.isLockedPlan
import com.example.sitedraft.ui.canvas.selectionHasGroup
import com.example.sitedraft.ui.canvas.selectionIsOneGroup
import com.example.sitedraft.ui.canvas.activeLayer
import com.example.sitedraft.ui.canvas.boundingRect
import androidx.compose.ui.layout.onSizeChanged
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
    // Context cards (Dan, Nov 27): tapping a symbol opens its small card by the item; All settings opens the full panel.
    var fullProperties by remember { mutableStateOf(false) }
    // Where the card was dragged to (screen px); once moved it stays there for the next items too.
    var cardPinned by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(state.selectedElementId) { fullProperties = false }
    val selectedElement = state.elements.find { it.id == state.selectedElementId }
    val selectedSymbol = selectedElement as? DrawingElement.Symbol
    // A floor hatch has its own card (Nov 27).
    val selectedHatch = (selectedElement as? DrawingElement.Rectangle)?.takeIf { it.fill.pattern != com.example.sitedraft.ui.canvas.FillPattern.NONE && it.strokeWidth == 0f }
    val showCard = (selectedSymbol != null || selectedHatch != null) && !fullProperties && !isMoveToolActive && state.selectedElementIds.isEmpty()
    val isPropertiesVisible = (state.selectedElementId != null && !isMoveToolActive && !showCard) || (isExpanded && (currentNav == "Tools"))
    val isSettingsVisible = (currentNav == "Settings") && (!isPropertiesVisible)
    // Laser stays up even with a selection: applying a reading to the selected element is the point.
    val isLaserVisible = isExpanded && currentNav == "Laser"
    val isPlanVisible = isExpanded && currentNav == "Plan"
    val isLayersVisible = isExpanded && currentNav == "Layers"
    // Paper space: the Print dialog opened straight on the sheets.
    var sheetsOnly by remember { mutableStateOf(false) }
    // Paper space: the Sheets screen (SheetsScreen), over the drawing.
    var showSheets by remember { mutableStateOf(false) }
    var showJobSpecs by remember { mutableStateOf(false) }
    var jobSpecsNew by remember { mutableStateOf(false) }
    // The sheet the Sheets screen opens on (a tab tapped on the drawing); null = the first.
    var sheetsStart by remember { mutableStateOf<String?>(null) }



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
                icon = { Icon(Icons.Default.Widgets, contentDescription = "Library") },
                label = { Text("Library") },
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
            NavigationRailItem(
                colors = railColors,
                selected = currentNav == "Layers",
                onClick = { currentNav = if (currentNav == "Layers") "Drawing" else "Layers" },
                icon = { Icon(Icons.Default.Layers, contentDescription = "Layers") },
                label = { Text("Layers") },
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
                selected = currentNav == "Help",
                onClick = { currentNav = if (currentNav == "Help") "Drawing" else "Help" },
                icon = { Icon(Icons.AutoMirrored.Filled.Help, contentDescription = "Help") },
                label = { Text("Help") },
            )
        }

        // Side-by-side layout for tablets
        Row(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // Left Panel (Symbol Library) - Permanent on tablet
            if (isExpanded && (currentNav == "Symbols")) {
                SymbolLibraryPanel(
                    activeTrade = state.activeLayer.trade,
                    state = state,
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
            
            if (isExpanded && currentNav == "Help") {
                com.example.sitedraft.ui.help.HelpPanel(
                    modifier = Modifier
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
                    onOpenJobSpecs = { jobSpecsNew = it; showJobSpecs = true },
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
                            .padding(16.dp),
                        hasLockedPlans = state.elements.any { it.isLockedPlan }
                    )

                    // Model and sheet tabs along the bottom, like AutoCAD's Model / Layout tabs.
                    SpaceTabs(
                        sheets = state.sheets,
                        current = null,
                        onModel = {},
                        onSheet = { sheetsStart = it; showSheets = true },
                        onNewSheet = { sheetsStart = NEW_SHEET; showSheets = true },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, bottom = 16.dp, end = 200.dp)
                    )

                    // The job that's open, always on screen (Dan, Nov 27: "so we know what job we are on"), just above the tabs.
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, bottom = 64.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        shadowElevation = 2.dp
                    ) {
                        Text(
                            text = "Job: " + state.projectName,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).widthIn(max = 320.dp)
                        )
                    }

                    // Quick layer on/off under the toolbar. Hidden while a symbol waits for its
                    // spot: the "tap to place" banner sits there.
                    if (state.pendingPlacement == null) {
                        LayerQuickBar(
                            state = state,
                            onEvent = viewModel::onEvent,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 104.dp, start = 16.dp, end = 16.dp)
                        )
                    }

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

                    // Both calibration points picked on a PDF plan: ask for the real length.
                    state.pendingCalibration?.let { pending ->
                        CalibrationLengthDialog(
                            pending = pending,
                            state = state,
                            onApply = { viewModel.onEvent(DrawingEvent.ApplyCalibration(it)) },
                            onPickAgain = { viewModel.onEvent(DrawingEvent.StartCalibration(pending.pdfId)) },
                            onCancel = { viewModel.onEvent(DrawingEvent.CancelCalibration) }
                        )
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
                                activeTrade = state.activeLayer.trade,
                                state = state,
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
                                onOpenJobSpecs = { jobSpecsNew = it; showJobSpecs = true },
                                onClose = { currentNav = "Drawing" }
                            )
                        }
                    }
                    
                    if (!isExpanded && currentNav == "Help") {
                        com.example.sitedraft.ui.help.HelpPanel(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .safeDrawingPadding()
                                .padding(16.dp),
                            onClose = { currentNav = "Drawing" }
                        )
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

                    // The selected item's context card, beside it on screen (Nov 27).
                    if (showCard && selectedElement != null) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val density = LocalDensity.current
                            val r = selectedElement.boundingRect()
                            val left = r.left * state.zoom + state.pan.x
                            val right = r.right * state.zoom + state.pan.x
                            val top = r.top * state.zoom + state.pan.y
                            val bottom = r.bottom * state.zoom + state.pan.y
                            val maxW = constraints.maxWidth.toFloat()
                            val maxH = constraints.maxHeight.toFloat()
                            val cardW = with(density) { 300.dp.toPx() }
                            val gap = with(density) { 24.dp.toPx() }
                            val topBand = with(density) { 72.dp.toPx() } // under the top bar and the move bar
                            var cardH by remember { mutableStateOf(with(density) { 420.dp.toPx() }) }
                            fun clampX(v: Float) = v.coerceIn(8f, (maxW - cardW - 8f).coerceAtLeast(8f))
                            fun clampY(v: Float) = v.coerceIn(8f, (maxH - cardH - 8f).coerceAtLeast(8f))
                            // Dan, Nov 27: it covered the item. Beside it with room to spare, else on the far side of the
                            // screen from it; above or below it only when neither side clears it. Dragged: where he put it.
                            val pos = cardPinned?.let { Offset(clampX(it.x), clampY(it.y)) } ?: run {
                                val x = when {
                                    right + gap + cardW <= maxW -> right + gap
                                    left - gap - cardW >= 0f -> left - gap - cardW
                                    (left + right) / 2f < maxW / 2f -> maxW - cardW - 8f
                                    else -> 8f
                                }
                                val clearSide = x >= right || x + cardW <= left
                                val y = if (clearSide) clampY(maxOf(topBand, top)) else if (top - gap - cardH >= 8f) top - gap - cardH else bottom + gap
                                Offset(clampX(x), clampY(y))
                            }
                            val cardModifier = Modifier
                                .offset { androidx.compose.ui.unit.IntOffset(pos.x.toInt(), pos.y.toInt()) }
                                .onSizeChanged { cardH = it.height.toFloat() }
                            val onCardDrag: (Offset) -> Unit = { d -> cardPinned = Offset(clampX(pos.x + d.x), clampY(pos.y + d.y)) }
                            if (selectedSymbol != null) ContextCard(
                                element = selectedSymbol, state = state, onEvent = viewModel::onEvent,
                                onAllSettings = { fullProperties = true }, onDrag = onCardDrag, modifier = cardModifier
                            ) else if (selectedHatch != null) HatchCard(
                                hatch = selectedHatch, state = state, onEvent = viewModel::onEvent, onDrag = onCardDrag, modifier = cardModifier
                            )
                        }
                    }

                    // Naming a room (Nov 28): opened by tapping its red "Name this room" tag.
                    state.namingRoom?.let { at ->
                        var roomName by remember(at) { mutableStateOf("") }
                        AlertDialog(
                            onDismissRequest = { viewModel.onEvent(DrawingEvent.CloseNameRoom) },
                            title = { Text("Name this room") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = roomName, onValueChange = { roomName = it }, label = { Text("Room name") }, singleLine = true)
                                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("Bedroom", "Bath", "Closet", "Kitchen", "Living", "Dining", "Laundry", "Hall", "Pantry", "Office", "Mudroom", "Garage").forEach { n ->
                                            FilterChip(selected = roomName == n, onClick = { roomName = n }, label = { Text(n) })
                                        }
                                    }
                                }
                            },
                            confirmButton = { TextButton(enabled = roomName.isNotBlank(), onClick = { viewModel.onEvent(DrawingEvent.NameRoom(at.x, at.y, roomName)) }) { Text("Set") } },
                            dismissButton = { TextButton(onClick = { viewModel.onEvent(DrawingEvent.CloseNameRoom) }) { Text("Cancel") } }
                        )
                    }

                    // A room's electrical card (Nov 27): opened by tapping a room after "Room electrical".
                    state.electricalRoom?.let { roomAt ->
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val density = LocalDensity.current
                            val maxW = constraints.maxWidth.toFloat()
                            val cardW = with(density) { 300.dp.toPx() }
                            val pos = cardPinned ?: Offset(maxW - cardW - with(density) { 16.dp.toPx() }, with(density) { 80.dp.toPx() })
                            RoomElectricalCard(at = roomAt, state = state, onEvent = viewModel::onEvent,
                                onDrag = { d -> cardPinned = Offset((pos.x + d.x).coerceIn(8f, (maxW - cardW - 8f).coerceAtLeast(8f)), (pos.y + d.y).coerceAtLeast(8f)) },
                                modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(pos.x.toInt(), pos.y.toInt()) })
                        }
                    }

                    // Floating Action Bar for Selected Element (or a box-selected group - it
                    // only offers Copy/Delete/Close for a group since Move/Pt-to-Pt there are
                    // single-element tools; the group itself is moved by dragging it directly
                    // with Select, same as any single element).
                    // The card carries these buttons while it's open (Nov 27: the bar sat on top of it).
                    if ((state.selectedElementId != null || state.selectedElementIds.isNotEmpty()) && currentNav == "Drawing" && !showCard) {
                        val explodeCandidate = state.elements.find { it.id == state.selectedElementId }
                        FloatingActionBar(
                            currentTool = state.currentTool,
                            canExplode = explodeCandidate is DrawingElement.Rectangle ||
                                explodeCandidate is DrawingElement.Triangle ||
                                explodeCandidate is DrawingElement.Symbol ||
                                explodeCandidate is DrawingElement.ElevationView,
                            canGroup = state.selectedElementIds.size > 1 && !state.selectionIsOneGroup(),
                            canUngroup = state.selectionHasGroup(),
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

                    // After a Move drag: "make it exactly ___" (Nov 27).
                    com.example.sitedraft.ui.components.LastMoveBar(
                        state = state, onEvent = viewModel::onEvent,
                        modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 72.dp)
                    )

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
                if (isLayersVisible) {
                    BuildingPanel(
                        state = state,
                        onEvent = viewModel::onEvent,
                        onOpenSheets = { sheetsStart = null; showSheets = true },
                        onOpenTools = { currentNav = "Symbols" },
                        modifier = Modifier
                            .width(340.dp)
                            .fillMaxHeight()
                            .safeDrawingPadding()
                            .padding(8.dp),
                        onClose = { currentNav = "Drawing" }
                    )
                } else                 if (isPlanVisible) {
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
            onSheetsChange = { viewModel.onEvent(DrawingEvent.UpdateSheets(it)) },
            startOnSheets = sheetsOnly,
            onOpenSheets = { sheetsStart = null; showSheets = true },
            onDismiss = { printDialog = null; sheetsOnly = false }
        )
    }

    if (showJobSpecs) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showJobSpecs = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            JobSpecsWizard(
                state = state,
                onEvent = viewModel::onEvent,
                onClose = { showJobSpecs = false },
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                newJob = jobSpecsNew
            )
        }
    }

    if (showSheets) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showSheets = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            SheetsScreen(
                state = state,
                onEvent = viewModel::onEvent,
                density = density,
                onClose = { showSheets = false },
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                initialSelected = sheetsStart
            )
        }
    }
}
