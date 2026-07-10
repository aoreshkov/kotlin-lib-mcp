package app.oreshkov.kotlinlibmcp.dashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.oreshkov.kotlinlibmcp.dashboard.DashboardState

/** Coordinate field + Fetch button: pre-warms the cache through `core` directly. */
@Composable
fun PrewarmForm(state: DashboardState) {
    val status by state.prewarmStatus.collectAsState()
    var coordinate by remember { mutableStateOf("io.ktor:ktor-client-core:3.5.1") }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = coordinate,
            onValueChange = { coordinate = it },
            label = { Text("group:artifact:version") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { state.prewarm(coordinate) }) { Text("Fetch") }
        Text(status, style = MaterialTheme.typography.body2, modifier = Modifier.weight(1f))
    }
}
