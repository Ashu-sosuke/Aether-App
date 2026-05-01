package com.aether.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ActionLogList(
    entries: List<String>,
    emptyText: String,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) {
        Text(
            text = emptyText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier.padding(12.dp)
        )
        return
    }
    LazyColumn(modifier = modifier) {
        items(entries) { entry ->
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val timestamp = entry.substringBefore("] ").plus("]")
                    val message = entry.substringAfter("] ", entry)
                    Text(
                        text = timestamp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
