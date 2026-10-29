package com.example.sitedraft.ui.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The "Account" block of the Settings panel: email, subscription state, billing, sign out. */
@Composable
fun AccountSection(modifier: Modifier = Modifier) {
    val session = LocalCloudSession.current ?: return
    val user = session.user ?: return
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var showAdmin by remember { mutableStateOf(false) }

    Column(modifier) {
        Text("Account", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        Text(user.email ?: user.displayName ?: user.uid, style = MaterialTheme.typography.bodyLarge)
        val access = session.access
        val status = session.userDoc?.subscription?.status
        Text(
            when {
                access == null -> "Subscription: not checked"
                access.reason == "admin" -> "Admin (owner)"
                access.reason == "allowlist" -> "Beta access (no subscription needed)"
                access.reason == "code" -> "Access code ${session.userDoc?.accessCode?.code ?: ""}"
                access.reason == "offline-cache" -> "Subscription: last verified offline"
                status != null -> "Subscription: $status"
                else -> "Subscription: none"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session.userDoc?.stripeCustomerId != null) {
                OutlinedButton(onClick = { scope.launch { message = session.openBillingPortal() } }, enabled = !session.busy) {
                    Text("Manage subscription")
                }
            }
            if (session.isAdmin) {
                OutlinedButton(onClick = { showAdmin = true }) { Text("Admin") }
            }
            TextButton(onClick = { scope.launch { session.signOut() } }, enabled = !session.busy) { Text("Sign out") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    if (showAdmin) AdminDialog(session) { showAdmin = false }
}
