package com.example.sitedraft.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager

@Composable
fun ProjectManagerPanel(
    state: CanvasState,
    viewModel: DrawingManager,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    
    LaunchedEffect(Unit) {
        viewModel.onEvent(DrawingEvent.RefreshProjectList)
    }

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(320.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        shape = MaterialTheme.shapes.large,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Projects / Jobs",
                    style = MaterialTheme.typography.headlineSmall
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            // Current Project Info
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Current Active Project", style = MaterialTheme.typography.labelSmall)
                    var nameText by remember(state.projectName) { mutableStateOf(state.projectName) }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = nameText,
                            onValueChange = { nameText = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        TextButton(onClick = { 
                            viewModel.onEvent(DrawingEvent.RenameProject(nameText))
                            viewModel.onEvent(DrawingEvent.SaveProject)
                        }) {
                            Text("Save")
                        }
                    }
                }
            }

            Button(
                onClick = { 
                    viewModel.onEvent(DrawingEvent.NewProject)
                    onClose()
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Create New Job")
            }

            Text(
                text = "Recent Jobs",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.availableProjects) { projectName ->
                    ListItem(
                        headlineContent = { Text(projectName) },
                        leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                        modifier = Modifier.clickable { 
                            viewModel.loadProjectByName(projectName)
                            onClose()
                        }
                    )
                }
            }
            
            if (state.availableProjects.isEmpty()) {
                Text(
                    "No saved jobs found.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}
