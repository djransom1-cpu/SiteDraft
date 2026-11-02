package com.example.sitedraft.ui.help

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The Help button's panel: the user guide as topics that open and close, with a search box.
 * Typing shows only the topics that contain every word typed, opened, with the words marked.
 */
@Composable
fun HelpPanel(modifier: Modifier = Modifier, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var openTopics by remember { mutableStateOf(setOf<String>()) }
    val words = remember(query) { HelpSearch.words(query) }
    val results = remember(query) { HelpSearch.search(query) }
    val searching = words.isNotEmpty()
    val mark = MaterialTheme.colorScheme.primary

    Surface(
        modifier = modifier.fillMaxHeight().widthIn(max = 360.dp).fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        // Explicit, as in the Projects panel: a translucent colour matches no theme colour.
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Help", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search help") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            if (searching) {
                Text(
                    text = if (results.isEmpty()) "Nothing found for \"${query.trim()}\". Try another word."
                    else "${results.size} topic${if (results.size == 1) "" else "s"} found",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results, key = { it.title }) { topic ->
                    val expanded = searching || topic.title in openTopics
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !searching) {
                                    openTopics = if (topic.title in openTopics) openTopics - topic.title else openTopics + topic.title
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                highlighted(topic.title, words, mark),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (!searching) Icon(
                                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (expanded) "Close topic" else "Open topic"
                            )
                        }
                        if (expanded) {
                            for (line in topic.lines) {
                                val isStep = line.firstOrNull()?.isDigit() == true
                                Text(
                                    highlighted(line, words, mark),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = if (isStep) 8.dp else 0.dp, bottom = 6.dp)
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** [text] with every searched word in bold on a tinted background. */
private fun highlighted(text: String, words: List<String>, mark: Color): AnnotatedString {
    if (words.isEmpty()) return AnnotatedString(text)
    val ranges = HelpSearch.matchRanges(text, words)
    return buildAnnotatedString {
        append(text)
        for (r in ranges) addStyle(SpanStyle(fontWeight = FontWeight.Bold, background = mark.copy(alpha = 0.35f)), r.first, r.last + 1)
    }
}
