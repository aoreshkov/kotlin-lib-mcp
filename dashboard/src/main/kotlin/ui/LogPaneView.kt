package app.oreshkov.kotlinlibmcp.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.oreshkov.kotlinlibmcp.dashboard.LogPane

private const val MAX_LINES = 2000

/** Scrolling live view of the Kermit/Logback stream captured by [LogPane]. */
@Composable
fun LogPaneView(modifier: Modifier = Modifier) {
    val lines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        LogPane.lines.collect { line ->
            lines += line
            if (lines.size > MAX_LINES) lines.removeRange(0, lines.size - MAX_LINES)
            listState.scrollToItem(lines.lastIndex)
        }
    }

    Column(modifier = modifier) {
        Text("Logs", style = MaterialTheme.typography.subtitle1)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E)).padding(4.dp),
        ) {
            itemsIndexed(lines) { _, line ->
                Text(
                    line,
                    color = Color(0xFFD4D4D4),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
