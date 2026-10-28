package app.oreshkov.kotlinlibmcp.server.transport

import app.oreshkov.kotlinlibmcp.server.tasks.MAX_TTL_MS
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A task is reachable only from the session that created it, and since kotlin-sdk 0.16.0 an idle
 * Streamable HTTP session expires. Its idle timeout must therefore cover the longest TTL a task can
 * be given, or a client that waits out part of a long TTL loses the task with its session. The SDK's
 * own default (30 minutes) is shorter than that ceiling, so this fails if the override is dropped.
 */
class HttpSessionLifetimeTest {

    @Test
    fun anIdleSessionOutlivesEveryTaskItCanOwn() {
        assertTrue(
            SESSION_IDLE_TIMEOUT >= MAX_TTL_MS.milliseconds,
            "session idle timeout $SESSION_IDLE_TIMEOUT is shorter than the task TTL ceiling ${MAX_TTL_MS.milliseconds}",
        )
    }
}
