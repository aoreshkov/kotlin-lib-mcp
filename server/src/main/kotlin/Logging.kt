package app.oreshkov.kotlinlibmcp.server

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
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
