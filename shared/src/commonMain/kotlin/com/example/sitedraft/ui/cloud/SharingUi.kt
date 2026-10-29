package com.example.sitedraft.ui.cloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.sitedraft.cloud.CloudException
import com.example.sitedraft.cloud.ProjectSharing
import com.example.sitedraft.cloud.ProjectSync
import com.example.sitedraft.cloud.SharedRef
import com.example.sitedraft.cloud.Sharing
import kotlinx.coroutines.launch

/**
 * Who the user's own project is shared with: add an email as editor or viewer, change a role,
 * remove someone. The other person sees it under "Shared with me" once they sign in with that
 * email and have verified it. Nobody is emailed; tell them yourself.
 */
@Composable
fun ShareProjectDialog(projectName: String, sync: ProjectSync, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf<ProjectSharing?>(null) }
    var email by remember { mutableStateOf("") }
    var canEdit by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(projectName) {
        try {
            sharing = sync.sharingFor(projectName)
        } catch (e: Exception) {
            error = (e as? CloudException)?.message ?: "Could not load the sharing: ${e.message}"
        } finally {
            busy = false
        }
    }

    fun save(next: ProjectSharing, then: () -> Unit = {}) {
        busy = true
        error = null
        scope.launch {
            try {
                sync.updateSharing(projectName, next)
                sharing = next
                then()
            } catch (e: Exception) {
                error = (e as? CloudException)?.message ?: "Could not save: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share \"$projectName\"") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "People you add see this project under Shared with me after they sign in to SiteDraft with that email and verify it. They need their own subscription.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                val current = sharing
                if (current != null) {
                    if (current.memberEmails.isEmpty()) {
                        Text("Not shared with anyone yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        current.memberEmails.forEach { member ->
                            val editor = Sharing.canEdit(current, member)
                            ListItem(
                                headlineContent = { Text(member) },
                                supportingContent = { Text(if (editor) "Can edit" else "View only") },
                                leadingContent = { Icon(if (editor) Icons.Default.Edit else Icons.Default.Visibility, contentDescription = null) },
                                trailingContent = {
                                    Row {
                                        TextButton(onClick = { save(Sharing.add(current, member, !editor)) }, enabled = !busy) {
                                            Text(if (editor) "Make viewer" else "Make editor")
                                        }
                                        IconButton(onClick = { save(Sharing.remove(current, member)) }, enabled = !busy) {
                                            Icon(Icons.Default.Close, contentDescription = "Stop sharing with $member")
                                        }
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; error = null },
                        label = { Text("Email") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = !busy) { canEdit = !canEdit }) {
                        Checkbox(checked = canEdit, onCheckedChange = { canEdit = it }, enabled = !busy)
                        Text("Can edit (otherwise view only)")
                    }
                    Button(
                        onClick = {
                            try {
                                save(Sharing.add(current, email, canEdit)) { email = "" }
                            } catch (e: IllegalArgumentException) {
                                error = e.message
                            }
                        },
                        enabled = !busy && email.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Share") }
                }
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.CenterHorizontally), strokeWidth = 2.dp)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/**
 * The "Shared with me" list for the Projects panel. Tapping a project downloads the newest
 * copy and opens it; [onOpen] receives its local name.
 */
@Composable
fun SharedWithMeSection(session: CloudSession, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val sync = session.sync ?: return
    val refs by sync.shared.collectAsState()
    val user by session.cloud.user.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }

    Column(modifier) {
        Text("Shared with me", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        if (user != null && user?.emailVerified != true) {
            Text(
                "Verify your email to see projects other people share with you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { scope.launch { message = session.resendVerification() } }) { Text("Send email") }
                TextButton(onClick = { scope.launch { message = session.recheckVerification() } }) { Text("I've verified") }
            }
        } else if (refs.isEmpty()) {
            Text("Nothing shared with you yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        refs.forEach { ref ->
            SharedItem(ref, opening == ref.localName) {
                if (opening != null) return@SharedItem
                opening = ref.localName
                scope.launch {
                    val error = sync.fetchShared(ref.localName)
                    opening = null
                    if (error == null) onOpen(ref.localName) else message = error
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun SharedItem(ref: SharedRef, opening: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(ref.cloudName) },
        supportingContent = { Text("${ref.ownerEmail ?: "Someone"} · ${if (ref.canEdit) "can edit" else "view only"}") },
        leadingContent = {
            if (opening) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.FolderShared, contentDescription = null)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/** One line under the current project's name: who shared it, or a Share button for your own. */
@Composable
fun CurrentProjectSharing(projectName: String, session: CloudSession?) {
    val sync = session?.sync ?: return
    val refs by sync.shared.collectAsState()
    val ref = refs.firstOrNull { it.localName == projectName }
    var showDialog by remember { mutableStateOf(false) }
    if (ref != null) {
        Text(
            "Shared by ${ref.ownerEmail ?: "another account"} · ${if (ref.canEdit) "you can edit" else "view only, changes stay on this device"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    } else {
        TextButton(onClick = { showDialog = true }) {
            Icon(Icons.Default.FolderShared, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Share")
        }
    }
    if (showDialog) ShareProjectDialog(projectName, sync) { showDialog = false }
}
