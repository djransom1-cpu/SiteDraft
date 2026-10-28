package com.example.sitedraft.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.sitedraft.R
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingTool

private data class ToolInfo(
    val tool: DrawingTool,
    val icon: ImageVector,
    val label: String
)

@Composable
fun ToolBar(
    selectedTool: DrawingTool,
    currentColor: Color,
    onPaletteClick: () -> Unit,
    onImportImage: () -> Unit,
    onImportPdf: () -> Unit,
    onImportDxf: () -> Unit,
    onSaveProject: () -> Unit,
    onLoadProject: () -> Unit,
    onExportPdf: () -> Unit,
    onExportDxf: () -> Unit,
    onEvent: (DrawingEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolItem(
                icon = Icons.Rounded.NearMe,
                label = "Select",
                isSelected = selectedTool == DrawingTool.SELECT,
                onClick = { onEvent(DrawingEvent.SelectTool(DrawingTool.SELECT)) }
            )

            ToolGroupDropdown(
                tools = remember {
                    listOf(
                        ToolInfo(DrawingTool.MOVE, Icons.Rounded.OpenWith, "Move"),
                        ToolInfo(DrawingTool.MOVE_PRECISE, Icons.Rounded.AdsClick, "Pt-to-Pt")
                    )
                },
                selectedTool = selectedTool,
                onSelect = { onEvent(DrawingEvent.SelectTool(it)) }
            )

            VerticalDivider(modifier = Modifier.height(32.dp))

            ToolGroupDropdown(
                tools = remember {
                    listOf(
                        ToolInfo(DrawingTool.PEN, Icons.Rounded.Gesture, "Pen"),
                        ToolInfo(DrawingTool.PENCIL, Icons.Rounded.Edit, "Pencil"),
                        ToolInfo(DrawingTool.MARKER, Icons.Rounded.Brush, "Marker"),
                        ToolInfo(DrawingTool.ERASER, Icons.Rounded.CleaningServices, "Eraser"),
                        ToolInfo(DrawingTool.LINE, Icons.Rounded.ShowChart, "Line")
                    )
                },
                selectedTool = selectedTool,
                onSelect = { onEvent(DrawingEvent.SelectTool(it)) }
            )

            ToolGroupDropdown(
                tools = remember {
                    listOf(
                        ToolInfo(DrawingTool.RECTANGLE, Icons.Rounded.Rectangle, "Rectangle"),
                        ToolInfo(DrawingTool.CIRCLE, Icons.Rounded.Circle, "Circle"),
                        ToolInfo(DrawingTool.OVAL, Icons.Rounded.Lens, "Oval"),
                        ToolInfo(DrawingTool.TRIANGLE, Icons.Rounded.ChangeHistory, "Triangle")
                    )
                },
                selectedTool = selectedTool,
                onSelect = { onEvent(DrawingEvent.SelectTool(it)) }
            )

            ToolGroupDropdown(
                tools = remember {
                    listOf(
                        ToolInfo(DrawingTool.TEXT, Icons.Rounded.Title, "Text"),
                        ToolInfo(DrawingTool.DIMENSION, Icons.Rounded.Straighten, "Dimension")
                    )
                },
                selectedTool = selectedTool,
                onSelect = { onEvent(DrawingEvent.SelectTool(it)) }
            )

            VerticalDivider(modifier = Modifier.height(32.dp))

            ToolGroupDropdown(
                tools = remember {
                    listOf(
                        ToolInfo(DrawingTool.TRIM, Icons.Rounded.ContentCut, "Trim"),
                        ToolInfo(DrawingTool.EXTEND, Icons.Rounded.UnfoldMore, "Extend")
                    )
                },
                selectedTool = selectedTool,
                onSelect = { onEvent(DrawingEvent.SelectTool(it)) }
            )

            VerticalDivider(modifier = Modifier.height(32.dp))

            // Color indicator / Palette
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(currentColor)
                    .clickable { onPaletteClick() }
            ) {
                Icon(
                    Icons.Rounded.Palette,
                    contentDescription = stringResource(R.string.select_color),
                    tint = if (currentColor == Color.Black) Color.White else Color.Black,
                    modifier = Modifier.align(Alignment.Center).size(20.dp)
                )
            }

            VerticalDivider(modifier = Modifier.height(32.dp))

            IconButton(onClick = { onEvent(DrawingEvent.Undo) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.undo))
            }
            IconButton(onClick = { onEvent(DrawingEvent.Redo) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = "Redo")
            }

            Spacer(modifier = Modifier.weight(1f))

            // Import was wired all the way through MainScreen (file launchers, PDF page
            // rendering, DXF parsing) but the toolbar never exposed a control for it - the
            // three callbacks were accepted here and never called.
            var showImportMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showImportMenu = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.FileUpload, contentDescription = stringResource(R.string.import_menu))
                }
                DropdownMenu(
                    expanded = showImportMenu,
                    onDismissRequest = { showImportMenu = false },
                    // Same reason as DrawingPropertiesDialog: the default light menu surface makes the
                    // theme's white text unreadable.
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_image)) },
                        onClick = { showImportMenu = false; onImportImage() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_pdf)) },
                        onClick = { showImportMenu = false; onImportPdf() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_dxf)) },
                        onClick = { showImportMenu = false; onImportDxf() }
                    )
                }
            }
            IconButton(onClick = onSaveProject, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.save_project))
            }
            IconButton(onClick = onExportPdf, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.FileDownload, contentDescription = stringResource(R.string.export_pdf))
            }
        }
    }
}

// A single toolbar slot that stands in for a whole group of related tools (e.g. Pen/Pencil/
// Marker/Line, or Trim/Extend) - shows whichever one in the group was used most recently, and
// tapping it again (rather than switching away first) opens a dropdown to pick a different one
// from the group. Keeps the top bar from growing a new icon for every tool added over time.
@Composable
private fun ToolGroupDropdown(
    tools: List<ToolInfo>,
    selectedTool: DrawingTool,
    onSelect: (DrawingTool) -> Unit
) {
    var lastActive by remember(tools) { mutableStateOf(tools.first().tool) }
    var expanded by remember { mutableStateOf(false) }

    val isGroupSelected = selectedTool in tools.map { it.tool }
    if (isGroupSelected) {
        lastActive = selectedTool
    }
    val activeInfo = remember(lastActive, tools) { tools.find { it.tool == lastActive } ?: tools.first() }

    Box {
        ToolItem(
            icon = activeInfo.icon,
            label = activeInfo.label,
            isSelected = isGroupSelected,
            onClick = {
                if (isGroupSelected) {
                    expanded = !expanded
                } else {
                    onSelect(lastActive)
                }
            }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        ) {
            tools.forEach { info ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = info.icon,
                                contentDescription = info.label,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(info.label)
                        }
                    },
                    onClick = {
                        onSelect(info.tool)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ToolItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
    }
}
