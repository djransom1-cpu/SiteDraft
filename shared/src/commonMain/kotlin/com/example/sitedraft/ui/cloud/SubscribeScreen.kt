package com.example.sitedraft.ui.cloud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The gate, shut. Shown to a signed-in user with no active subscription and no allowlist
 * entry, and also when the subscription could not be checked and nothing is cached.
 */
@Composable
fun SubscribeScreen(session: CloudSession, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val email = session.user?.email ?: ""
    val couldNotCheck = session.access == null && session.error != null
    val status = session.userDoc?.subscription?.status

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (couldNotCheck) "Could not check your subscription" else "Subscribe to use SiteDraft",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            couldNotCheck -> session.error ?: ""
                            status == "past_due" || status == "unpaid" -> "Your last payment did not go through. Update your card to keep drawing."
                            status == "canceled" || status == "incomplete_expired" -> "Your subscription has ended. Start a new one to keep drawing."
                            else -> "SiteDraft is a monthly subscription. Your drawings sync between tablet, desktop and web and you can cancel any time."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    if (email.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Signed in as $email", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(24.dp))

                    if (!couldNotCheck) {
                        Button(
                            onClick = { scope.launch { message = session.startCheckout() } },
                            enabled = !session.busy && !session.checking,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (session.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else Text(if (status == "past_due" || status == "unpaid") "Update payment" else "Subscribe")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedButton(
                        onClick = { scope.launch { message = null; session.refresh() } },
                        enabled = !session.checking && !session.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (session.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text(if (couldNotCheck) "Try again" else "I have subscribed, check again")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { scope.launch { session.signOut() } }, enabled = !session.busy) { Text("Sign out") }

                    message?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
