package com.example.sitedraft.ui.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.sitedraft.cloud.AccessCodeInfo
import com.example.sitedraft.cloud.AccessCodes
import com.example.sitedraft.cloud.AccessGate
import com.example.sitedraft.cloud.AccountSummary
import com.example.sitedraft.cloud.CloudException
import com.example.sitedraft.cloud.Sharing
import com.example.sitedraft.platform.nowMillis
import kotlinx.coroutines.launch

/**
 * The owner's screen: every account (email, subscription, last seen, how they got in), the
 * beta email allowlist, and the access codes. Only reachable when [CloudSession.isAdmin]; the
 * Firestore rules refuse all of this to anyone else. It shows accounts, never their drawings.
 */
@Composable
fun AdminDialog(session: CloudSession, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Admin") },
        text = {
            Column(Modifier.widthIn(min = 320.dp, max = 560.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Accounts", "Allowlist", "Codes").forEachIndexed { i, label ->
                        FilterChip(
                            selected = tab == i,
                            onClick = { tab = i },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    when (tab) {
                        0 -> AccountsTab(session)
                        1 -> AllowlistTab(session)
                        else -> CodesTab(session)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Loads with [load], shows a spinner meanwhile and the error if it fails; [reloadKey] reloads. */
@Composable
private fun <T> Loaded(reloadKey: Int, load: suspend () -> T, content: @Composable (T) -> Unit) {
    var value by remember { mutableStateOf<T?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reloadKey) {
        error = null
        try {
            value = load()
        } catch (e: Exception) {
            error = (e as? CloudException)?.message ?: e.message ?: "Could not load."
        }
    }
    val v = value
    when {
        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        v == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else -> content(v)
    }
}

@Composable
private fun AccountsTab(session: CloudSession) {
    Loaded(0, { session.cloud.store.listAccounts().sortedByDescending { it.user.lastSeenAt } }) { accounts ->
        Text("${accounts.size} account${if (accounts.size == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        accounts.forEach { AccountRow(it) }
    }
}

@Composable
private fun AccountRow(account: AccountSummary) {
    val u = account.user
    val now = nowMillis()
    val access = when {
        AccessGate.isSubscriptionGood(u.subscription, now) -> "Subscribed (${u.subscription?.status})"
        u.subscription != null -> "Subscription ${u.subscription.status}"
        u.accessCode != null -> "Code ${u.accessCode.code}"
        else -> "No subscription"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(u.email ?: account.uid, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
            "$access · last seen ${ago(u.lastSeenAt, now)}${u.lastDevice?.let { " on $it" } ?: ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** "never", "just now", "5 min ago", "3 h ago", "2 days ago". */
fun ago(then: Long, now: Long): String {
    if (then <= 0L) return "never"
    val minutes = (now - then).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

@Composable
private fun AllowlistTab(session: CloudSession) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        busy = true
        message = null
        scope.launch {
            try {
                block()
                reload++
            } catch (e: Exception) {
                message = (e as? CloudException)?.message ?: e.message
            } finally {
                busy = false
            }
        }
    }

    Text(
        "These emails use SiteDraft without a subscription (beta testers).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; message = null },
            label = { Text("Email") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.size(8.dp))
        Button(
            onClick = {
                if (!Sharing.isValidEmail(email)) message = "That does not look like an email address."
                else run { session.cloud.store.setAllowlisted(email, true); email = "" }
            },
            enabled = !busy && email.isNotBlank()
        ) { Text("Add") }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Spacer(Modifier.height(8.dp))
    Loaded(reload, { session.cloud.store.listAllowlist() }) { emails ->
        if (emails.isEmpty()) Text("Nobody yet.", style = MaterialTheme.typography.bodySmall)
        emails.forEach { e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(e, modifier = Modifier.weight(1f))
                IconButton(onClick = { run { session.cloud.store.setAllowlisted(e, false) } }, enabled = !busy) {
                    Icon(Icons.Default.Close, contentDescription = "Remove $e")
                }
            }
        }
    }
}

@Composable
private fun CodesTab(session: CloudSession) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        busy = true
        message = null
        scope.launch {
            try {
                block()
                reload++
            } catch (e: Exception) {
                message = (e as? CloudException)?.message ?: e.message
            } finally {
                busy = false
            }
        }
    }

    Text(
        "Anyone who types an active code on the Subscribe screen gets in. Switch a code off to shut everyone who used it out again. Expiry dates are set in the Firebase console (a timestamp field named expires).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it; message = null },
        label = { Text("New code, e.g. BETA2026") },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = note,
        onValueChange = { note = it },
        label = { Text("Note (who it is for)") },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            val c = AccessCodes.normalize(code)
            if (!AccessCodes.isWellFormed(c)) message = "Codes are 3 to 32 letters, numbers or dashes."
            else run { session.cloud.store.saveAccessCode(c, true, note.ifBlank { null }); code = ""; note = "" }
        },
        enabled = !busy && code.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Create code") }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Spacer(Modifier.height(8.dp))
    Loaded(reload, {
        val codes = session.cloud.store.listAccessCodes()
        val uses = session.cloud.store.listAccounts().mapNotNull { it.user.accessCode?.code }.groupingBy { it }.eachCount()
        codes to uses
    }) { (codes, uses) ->
        if (codes.isEmpty()) Text("No codes yet.", style = MaterialTheme.typography.bodySmall)
        codes.forEach { c -> CodeRow(c, uses[c.code] ?: 0, enabled = !busy) { active -> run { session.cloud.store.saveAccessCode(c.code, active, c.note) } } }
    }
}

@Composable
private fun CodeRow(info: AccessCodeInfo, uses: Int, enabled: Boolean, onActive: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(info.code, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(
                    info.note,
                    "used by $uses",
                    if (info.hasExpiry) "has an expiry date" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = info.active, onCheckedChange = onActive, enabled = enabled)
    }
}
