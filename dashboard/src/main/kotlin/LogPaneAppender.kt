package app.oreshkov.kotlinlibmcp.dashboard

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Bridges the existing logging path (Kermit → SLF4J → Logback) into the UI: a Logback appender
 * attached to the root logger pushes formatted lines into a [SharedFlow] the log pane collects.
 * Headless mode is untouched — this appender only exists when the dashboard installs it.
 */
object LogPane {

    private val timestamp = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private val _lines = MutableSharedFlow<String>(
        replay = 200,
        extraBufferCapacity = 800,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val lines: SharedFlow<String> get() = _lines

    /** Attaches the flow appender to Logback's root logger. Idempotent per process. */
    fun install() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        if (root.getAppender(APPENDER_NAME) != null) return
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                val time = timestamp.format(Instant.ofEpochMilli(event.timeStamp))
                _lines.tryEmit("$time ${event.level} ${event.loggerName.substringAfterLast('.')} - ${event.formattedMessage}")
            }
        }
        appender.name = APPENDER_NAME
        appender.context = root.loggerContext as LoggerContext
        appender.start()
        root.addAppender(appender)
    }

    private const val APPENDER_NAME = "dashboard-log-pane"
}
