package app.oreshkov.kotlinlibmcp.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.oreshkov.kotlinlibmcp.dashboard.ui.CacheBrowser
import app.oreshkov.kotlinlibmcp.dashboard.ui.ControlBar
import app.oreshkov.kotlinlibmcp.dashboard.ui.LogPaneView
import app.oreshkov.kotlinlibmcp.dashboard.ui.PrewarmForm
import androidx.compose.ui.unit.DpSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Compose Desktop control panel: runs the MCP server in-process (Streamable HTTP), shows live
 * logs and the on-disk cache, and pre-warms coordinates. The UI only orchestrates
 * `core`/`server` via [DashboardState] — no fetch/analyze logic lives here.
 */
fun main() {
    LogPane.install() // before the factory so startup logs land in the pane too

    application {
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        val state = remember { DashboardState(scope) }
        DisposableEffect(Unit) {
            onDispose {
                state.shutdown()
                scope.cancel()
            }
        }

        Window(
            onCloseRequest = ::exitApplication,
            title = "kotlin-lib-mcp dashboard",
            state = rememberWindowState(size = DpSize(1000.dp, 720.dp)),
        ) {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ControlBar(state)
                        PrewarmForm(state)
                        Divider()
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CacheBrowser(state, modifier = Modifier.weight(1f))
                            LogPaneView(modifier = Modifier.weight(1.4f))
                        }
                    }
                }
            }
        }
    }
}
