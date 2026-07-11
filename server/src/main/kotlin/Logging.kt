package app.oreshkov.kotlinlibmcp.server

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Routes `core`'s Kermit logging into SLF4J so Logback is the single sink. Critical for the stdio
 * transport: Kermit's default JVM writer prints to **stdout**, which would corrupt the protocol
 * stream — `logback.xml` sends everything to stderr instead. The MCP SDK's kotlin-logging facade
 * also prints a startup banner to stdout; it must be silenced before the SDK creates its first
 * logger, which is why the factory calls this before constructing the `Server`.
 */
fun routeKermitToSlf4j() {
    KotlinLoggingConfiguration.logStartupMessage = false
    Logger.setLogWriters(Slf4jLogWriter)
}

private object Slf4jLogWriter : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val logger = LoggerFactory.getLogger(tag.ifEmpty { "kermit" })
        when (severity) {
            Severity.Verbose -> logger.trace(message, throwable)
            Severity.Debug -> logger.debug(message, throwable)
            Severity.Info -> logger.info(message, throwable)
            Severity.Warn -> logger.warn(message, throwable)
            Severity.Error, Severity.Assert -> logger.error(message, throwable)
        }
    }
}

/**
 * Mirrors the app's Kermit logs to every connected MCP client as `notifications/message`
 * (the `logging` capability) — on stdio, clients often discard stderr, so this is the only
 * log channel a client reliably sees. `Info`+ is forwarded; the SDK then applies the
 * per-session `logging/setLevel` filter. Only the app logs through Kermit (the SDK logs via
 * kotlin-logging straight to SLF4J), so the forwarder cannot feed back into itself.
 *
 * [log] must never block a caller: records go through a bounded drop-oldest channel and a
 * single drainer coroutine on [scope]; sends to mid-handshake or closing sessions are ignored.
 */
fun attachMcpLogForwarder(server: Server, scope: CoroutineScope) {
    val channel = Channel<LoggingMessageNotification>(
        capacity = FORWARDER_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    scope.launch {
        for (notification in channel) {
            server.sessions.values.forEach { session ->
                runCatching { session.sendLoggingMessage(notification) }
            }
        }
    }
    Logger.addLogWriter(McpLogWriter(channel))
}

private const val FORWARDER_BUFFER = 256

private class McpLogWriter(private val channel: Channel<LoggingMessageNotification>) : LogWriter() {
    override fun isLoggable(tag: String, severity: Severity): Boolean = severity >= Severity.Info

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        channel.trySend(
            LoggingMessageNotification(
                LoggingMessageNotificationParams(
                    level = severity.toMcpLevel(),
                    data = JsonPrimitive(if (throwable == null) message else "$message — $throwable"),
                    logger = tag.ifEmpty { null },
                )
            )
        )
    }
}

internal fun Severity.toMcpLevel(): LoggingLevel = when (this) {
    Severity.Verbose, Severity.Debug -> LoggingLevel.Debug
    Severity.Info -> LoggingLevel.Info
    Severity.Warn -> LoggingLevel.Warning
    Severity.Error -> LoggingLevel.Error
    Severity.Assert -> LoggingLevel.Critical
}
