package com.example.sitedraft.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sitedraft.ui.canvas.CanvasState
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.cloud.CurrentProjectSharing
import com.example.sitedraft.ui.cloud.LocalCloudSession
import com.example.sitedraft.ui.cloud.SharedWithMeSection
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.sitedraft.cloud.DeletedProject
import com.example.sitedraft.cloud.ProjectSync
import kotlinx.coroutines.launch

@Composable
fun ProjectManagerPanel(
    state: CanvasState,
    viewModel: DrawingManager,
    modifier: Modifier = Modifier,
    /** Opens the job specs (setup wizard); [newJob] when a job was just made. */
    onOpenJobSpecs: (newJob: Boolean) -> Unit = {},
    onClose: () -> Unit
) {
    
    val session = LocalCloudSession.current
    LaunchedEffect(Unit) {
        viewModel.onEvent(DrawingEvent.RefreshProjectList)
        // Opening the panel fetches what other devices and other people saved since.
        session?.sync?.syncAll()
    }
    val sync = session?.sync
    val scope = rememberCoroutineScope()
    // Delete Project (Dan, Nov 27): confirm by typing the job's name; it waits 30 days in Recently deleted.
    var toDelete by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var deleted by remember { mutableStateOf<List<DeletedProject>>(emptyList()) }
    var showDeleted by remember { mutableStateOf(false) }
    suspend fun refreshDeleted() { deleted = sync?.recentlyDeleted() ?: emptyList() }
    LaunchedEffect(sync, state.availableProjects) { refreshDeleted() }
    val sharedRefs = session?.sync?.shared?.collectAsState()?.value ?: emptyList()
    val sharedNames = sharedRefs.map { it.localName }.toSet()

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(320.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        // Explicit: a translucent colour matches no theme colour, so contentColorFor() fell back to
        // black and the panel's titles were black on blue.
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
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
                    CurrentProjectSharing(state.projectName, session)
                }
            }

            // Everything the drawing is built from, on one set of pages (Dan, Nov 22).
            OutlinedButton(onClick = { onOpenJobSpecs(false) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text("Job specs: foundation, roof, roofing, siding, grade")
            }

            // The job's details for the title block on every sheet.
            ProjectInfoCard(
                info = state.projectInfo,
                onChange = { viewModel.onEvent(DrawingEvent.UpdateProjectInfo(it)) },
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Button(
                onClick = { 
                    viewModel.onEvent(DrawingEvent.NewProject)
                    onClose()
                    onOpenJobSpecs(true)
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

            // A plain Column: the panel itself scrolls now that Shared with me sits below.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.availableProjects.filter { it !in sharedNames }.forEach { projectName ->
                    ListItem(
                        headlineContent = { Text(projectName) },
                        leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                        // The open job can't be deleted: open another one first.
                        trailingContent = if (sync != null && projectName != state.projectName) {
                            { IconButton(onClick = { toDelete = projectName }) { Icon(Icons.Default.Delete, contentDescription = "Delete $projectName") } }
                        } else null,
                        modifier = Modifier.clickable { 
                            viewModel.loadProjectByName(projectName)
                            onClose()
                        }
                    )
                }
            }
            
            if (state.availableProjects.none { it !in sharedNames }) {
                Text(
                    "No saved jobs found.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

            if (sync != null && deleted.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { showDeleted = !showDeleted }) {
                    Text("Recently deleted (${deleted.size})" + if (showDeleted) "  ▲" else "  ▼")
                }
                if (showDeleted) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Kept ${ProjectSync.KEEP_DELETED_MS / 86_400_000L} days after deleting, then removed for good.", style = MaterialTheme.typography.bodySmall)
                    deleted.forEach { d ->
                        ListItem(
                            headlineContent = { Text(d.name) },
                            supportingContent = { Text("${d.daysLeft()} day(s) left") },
                            trailingContent = {
                                TextButton(onClick = {
                                    scope.launch {
                                        message = sync.restoreProject(d.name)
                                        viewModel.onEvent(DrawingEvent.RefreshProjectList)
                                        refreshDeleted()
                                    }
                                }) { Text("Restore") }
                            }
                        )
                    }
                }
            }

            if (session != null) {
                Spacer(Modifier.height(24.dp))
                SharedWithMeSection(session, onOpen = { name ->
                    viewModel.loadProjectByName(name)
                    onClose()
                })
            }
        }
    }

    toDelete?.let { name ->
        var typed by remember(name) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete \"$name\"?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("It comes off the job list on all your devices. It stays in Recently deleted for 30 days, where Restore brings it back; after that it's gone.")
                    Text("Type the job name to delete it:", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true, placeholder = { Text(name) })
                }
            },
            confirmButton = {
                TextButton(
                    enabled = typed.trim() == name,
                    onClick = {
                        toDelete = null
                        scope.launch {
                            message = sync?.deleteProject(name)
                            viewModel.onEvent(DrawingEvent.RefreshProjectList)
                            refreshDeleted()
                        }
                    }
                ) { Text("Delete", color = if (typed.trim() == name) MaterialTheme.colorScheme.error else LocalContentColor.current.copy(alpha = 0.4f)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
        )
    }
}
