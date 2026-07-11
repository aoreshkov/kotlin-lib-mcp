package app.oreshkov.kotlinlibmcp.server

import co.touchlab.kermit.Severity
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class LoggingTest {

    @Test
    fun kermitSeveritiesMapToMcpLevels() {
        assertEquals(LoggingLevel.Debug, Severity.Verbose.toMcpLevel())
        assertEquals(LoggingLevel.Debug, Severity.Debug.toMcpLevel())
        assertEquals(LoggingLevel.Info, Severity.Info.toMcpLevel())
        assertEquals(LoggingLevel.Warning, Severity.Warn.toMcpLevel())
        assertEquals(LoggingLevel.Error, Severity.Error.toMcpLevel())
        assertEquals(LoggingLevel.Critical, Severity.Assert.toMcpLevel())
    }

    @Test
    fun mappingNeverLosesSeverityOrdering() {
        val mapped = Severity.entries.map { it.toMcpLevel() }
        assertEquals(mapped.sorted(), mapped, "MCP levels must be monotonic in Kermit severity")
    }
}
