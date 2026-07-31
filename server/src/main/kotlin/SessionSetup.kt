package app.oreshkov.kotlinlibmcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs [configure] exactly once for every session this server serves — those already connected, and
 * every one that connects later.
 *
 * **Why it exists.** The SDK wires tools, prompts and resources into each session itself, but
 * anything *we* register per session — the `completion/complete` handler, the `tasks/…` surface —
 * has to be installed by us. `mcpStreamableHttp` builds its sessions internally and `createSession`
 * is not `open`, so the only hook is `Server.onConnect`, which takes no argument saying **which**
 * session connected.
 *
 * **Sweep, don't guess.** Reaching for `sessions.values.last()` looks equivalent and is not: the
 * session is added to the registry before `onConnect` fires, so two concurrent `createSession` calls
 * can interleave as
 * ```
 * T1: addSession(A)
 * T2: addSession(B)
 * T2: onConnect() -> last = B -> configures B
 * T1: onConnect() -> last = B -> already configured -> A is never configured
 * ```
 * and A then serves a client for its whole lifetime with the handler missing. Sweeping every session
 * on each callback cannot lose one that way.
 *
 * Sessions are claimed through a concurrent set, so overlapping sweeps configure each exactly once,
 * and the claim is released on close rather than accumulating for the life of the process.
 *
 * **Ordering is safe.** `Server.createSession` fires `onConnect` after `session.connect(transport)`
 * but *before* the Streamable HTTP route feeds the POST body to the transport, so [configure] has
 * run before the session's first frame is dispatched — not merely before its first real request.
 */
fun Server.onEachSession(configure: (ServerSession) -> Unit) {
    val configured = ConcurrentHashMap.newKeySet<String>()

    fun configureNewSessions() {
        sessions.values.forEach { session ->
            // add() is the claim: whichever sweep wins configures, so concurrent ones cannot
            // double-register, and no session is left unconfigured.
            if (configured.add(session.sessionId)) {
                configure(session)
                session.onClose { configured.remove(session.sessionId) }
            }
        }
    }

    onConnect { configureNewSessions() }
    // Also covers anything already connected, so callers need not be wired before the first client.
    configureNewSessions()
}
