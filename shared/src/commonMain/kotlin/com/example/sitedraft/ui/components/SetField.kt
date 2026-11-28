package com.example.sitedraft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * A text field with its Set button beside it, not inside it (Nov 28, the finish fields: a long entry wrapped and
 * carried the Set button down with it, easy to miss). The field wraps to show the whole entry; the button stays at
 * the top right where it always is. Enter also sets it.
 */
@Composable
fun SetField(text: String, onText: (String) -> Unit, label: String, onSet: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value = text, onValueChange = { v -> if (v.contains('\n')) onSet() else onText(v) },
            label = { Text(label) }, modifier = Modifier.weight(1f),
            singleLine = false, maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSet() })
        )
        FilledTonalButton(onClick = onSet, modifier = Modifier.padding(top = 8.dp)) { Text("Set") }
    }
}
