package app.oreshkov.kotlinlibmcp.dashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import app.oreshkov.kotlinlibmcp.dashboard.ServerStatus

/** Start/stop + port controls for the in-process Streamable HTTP server. */
@Composable
fun ControlBar(state: DashboardState) {
    val status by state.serverStatus.collectAsState()
    var portText by remember { mutableStateOf("3000") }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it },
            label = { Text("Port") },
            singleLine = true,
            enabled = status is ServerStatus.Stopped,
            modifier = Modifier.width(120.dp),
        )
        when (val s = status) {
            is ServerStatus.Stopped -> {
                Button(
                    onClick = { portText.toIntOrNull()?.takeIf { it in 1..65535 }?.let(state::startServer) },
                ) { Text("Start server") }
                Text("stopped", style = MaterialTheme.typography.body2)
            }
            is ServerStatus.Running -> {
                Button(onClick = state::stopServer) { Text("Stop server") }
                Text("running at ${s.endpoint}", style = MaterialTheme.typography.body2)
            }
        }
    }
}
