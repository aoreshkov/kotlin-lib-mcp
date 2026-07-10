package app.oreshkov.kotlinlibmcp.dashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.oreshkov.kotlinlibmcp.dashboard.DashboardState

/** Cached libraries with per-row clear, total size, refresh and clear-all. */
@Composable
fun CacheBrowser(state: DashboardState, modifier: Modifier = Modifier) {
    val entries by state.cacheEntries.collectAsState()
    val sizeBytes by state.cacheSizeBytes.collectAsState()

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Cache — ${entries.size} entries, ${formatSize(sizeBytes)}", style = MaterialTheme.typography.subtitle1)
            Row {
                TextButton(onClick = state::refreshCache) { Text("Refresh") }
                TextButton(onClick = state::clearAll) { Text("Clear all") }
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries, key = { it.toString() }) { coordinate ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        coordinate.toString(),
                        style = MaterialTheme.typography.body2,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { state.clear(coordinate) }) { Text("Clear") }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MiB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}
