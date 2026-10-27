package app.oreshkov.kotlinlibmcp.server

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] that lets cancellation through: every other [Throwable] becomes a failed [Result],
 * exactly as `runCatching` would make it, but a [CancellationException] is re-thrown.
 *
 * `runCatching` catches `Throwable`, and coroutine cancellation *is* a throwable, so wrapping a
 * suspend call in it turns a withdrawn request into an ordinary failure: the handler carries on, the
 * cancelled `tools/call` builds a result nobody asked for, and a `completion/complete` the client
 * gave up on still answers. kotlinx.coroutines' rule is that cancellation must never be swallowed.
 *
 * **Server code uses this everywhere instead of `runCatching`**, including around calls that cannot
 * suspend: re-throwing a cancellation that cannot occur costs nothing, and one rule is easier to keep
 * than a judgement per call site. CI fails on a bare `runCatching` under `server/src/main`; the rule
 * had been broken twice before it was enforced.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
