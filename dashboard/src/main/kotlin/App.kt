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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DefaultWindowExceptionHandlerFactory
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.oreshkov.kotlinlibmcp.dashboard.ui.CacheBrowser
import app.oreshkov.kotlinlibmcp.dashboard.ui.ControlBar
import app.oreshkov.kotlinlibmcp.dashboard.ui.LogPaneView
import app.oreshkov.kotlinlibmcp.dashboard.ui.PrewarmForm
import androidx.compose.ui.unit.DpSize
import co.touchlab.kermit.Logger
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Compose Desktop control panel: runs the MCP server in-process (Streamable HTTP), shows live
 * logs and the on-disk cache, and pre-warms coordinates. The UI only orchestrates
 * `core`/`server` via [DashboardState] — no fetch/analyze logic lives here.
 *
 * Shutdown runs after [application] returns, i.e. once the window is gone: stopping the engine can
 * take its whole timeout while an SSE stream is open, and on the UI thread that froze the window.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    LogPane.install() // before the factory so startup logs land in the pane too
    val log = Logger.withTag("Dashboard")

    // Backstops: DashboardState handles its own failures, so anything reaching these is a bug — log it.
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> log.e(e) { "Unhandled failure in a dashboard task" } },
    )
    val windowExceptions = WindowExceptionHandlerFactory { window ->
        val default = DefaultWindowExceptionHandlerFactory.exceptionHandler(window)
        WindowExceptionHandler { e ->
            log.e(e) { "Uncaught exception in the dashboard window" }
            default.onException(e) // Compose's own: an error dialog, then the window closes
        }
    }
    val state = DashboardState(scope)

    application(exitProcessOnExit = false) {
        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides windowExceptions) {
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

    runBlocking { state.shutdown() }
    scope.cancel()
    exitProcess(0)
}
