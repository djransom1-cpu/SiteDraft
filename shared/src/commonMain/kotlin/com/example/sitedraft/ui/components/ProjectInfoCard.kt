package com.example.sitedraft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sitedraft.platform.todayDateText
import com.example.sitedraft.ui.canvas.ProjectInfo
import com.example.sitedraft.ui.canvas.Revision

/**
 * The job's details for the title block on every sheet (Dan, Nov 22 2026): company, client,
 * address, the date the drawings were started, who drew them, and the revisions. Every change is
 * saved with the project as it is typed.
 */
@Composable
fun ProjectInfoCard(info: ProjectInfo, onChange: (ProjectInfo) -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Project info (title block)", style = MaterialTheme.typography.titleSmall)
            Text("Shows on every sheet.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(info.company, { onChange(info.copy(company = it)) }, label = { Text("Company") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(info.clientName, { onChange(info.copy(clientName = it)) }, label = { Text("Client name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(info.address, { onChange(info.copy(address = it)) }, label = { Text("Job address") },
                minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(info.startDate, { onChange(info.copy(startDate = it)) }, label = { Text("Drawings started") },
                    singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(info.copy(startDate = todayDateText())) }) { Text("Today") }
            }
            OutlinedTextField(info.drawnBy, { onChange(info.copy(drawnBy = it)) }, label = { Text("Drawn by") },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Revisions", style = MaterialTheme.typography.labelLarge)
            if (info.revisions.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
            info.revisions.forEachIndexed { i, rev ->
                fun set(r: Revision) = onChange(info.copy(revisions = info.revisions.mapIndexed { j, old -> if (j == i) r else old }))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(rev.number, { set(rev.copy(number = it)) }, label = { Text("No.") },
                        singleLine = true, modifier = Modifier.weight(0.7f))
                    OutlinedTextField(rev.date, { set(rev.copy(date = it)) }, label = { Text("Date") },
                        singleLine = true, modifier = Modifier.weight(1.4f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(rev.description, { set(rev.copy(description = it)) }, label = { Text("What changed") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onChange(info.copy(revisions = info.revisions.filterIndexed { j, _ -> j != i })) }) { Text("Remove") }
                }
            }
            OutlinedButton(
                onClick = { onChange(info.copy(revisions = info.revisions + Revision(number = info.nextRevisionNumber(), date = todayDateText()))) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add revision") }
        }
    }
}
