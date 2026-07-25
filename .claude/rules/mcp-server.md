---
paths:
  - "server/src/**/*.kt"
---

# MCP tool authoring & resource-template gotcha

Every tool declares a `title`, behavior annotations (`readOnlyHint`/`openWorldHint`;
`fetch_library` additionally `destructiveHint: false`, `idempotentHint: true`), and an
`outputSchema` derived from its response DTO's serial descriptor
(`server/.../tools/OutputSchemas.kt`); `toolResult` returns JSON text **and** matching
`structuredContent`. When adding a tool, pass all three to `addTool` — use the shared
`LOCAL_READ_ONLY`/`REPOSITORY_READ_ONLY` annotation constants in `ToolSupport.kt`.

**Resource templates gotcha:** the SDK's default `PathSegmentTemplateMatcher` throws
`NoSuchMethodError` at runtime — `kotlin-compiler` (via `core`) bundles an old unrelocated
`kotlinx.collections.immutable` that shadows the SDK's. `ServerOptions` must keep the custom
`segmentTemplateMatcherFactory` (`server/.../resources/SegmentTemplateMatcher.kt`).

**Same trap, OpenTelemetry:** `kotlin-compiler` also bundles ~83 *stripped stubs* of an old
unrelocated `io.opentelemetry.api` (1.41.0 — `Attributes`, `OpenTelemetry`, `Tracer`,
`TracerProvider` are empty shells). Gradle sorts the real `opentelemetry-api` well behind that fat
jar on the runtime classpath, so the stubs win and `--otel` dies with
`NoSuchMethodError: Attributes.empty()`. Declaration order does **not** fix it; `server/build.gradle.kts`
hoists the genuine OTel jars to the front of the `test`, `run` and `startScripts` classpaths
(`hoistOpenTelemetry`). Keep that in place, and when adding any dependency that `kotlin-compiler`
might also bundle, check with
`unzip -l <kotlin-compiler.jar> | grep <package-path>` before trusting the classpath.

**Tasks gotcha:** `tasks/TaskHandlers.kt` **replaces** the SDK's `tools/call` handler (that is the
only way to answer with a `CreateTaskResult` — `Server.handleCallTool` is hard-typed to
`CallToolResult` and ignores `params.task`). The replacement therefore applies to *every* tool, so
its non-task branch must reproduce the SDK's semantics exactly: unknown tool → `isError` result,
`CancellationException` and `UrlElicitationRequiredException` re-thrown, anything else flattened to
`isError`. `TaskDispatchTest` pins this — if you touch `dispatchToolCall`, keep those tests green.
The `tasks` capability and the handlers are both driven by `ServerConfig.tasksEnabled` so they can
never disagree; advertising the capability without handlers makes the SDK route methods that answer
nothing.

**Telemetry authoring:** every MCP entry point opens its span through the helpers in
`server/.../telemetry/Telemetry.kt` (`guarded` for tools — it is a `ClientConnection` extension so
`mcp.session.id` comes free — plus `resourceSpan`/`promptSpan`/`completionSpan`). Attribute names
are `AttributeKey` constants at the top of that file; do not inline `mcp.*`/`gen_ai.*` strings at
call sites, since the upstream conventions are still Development status. Nothing in the exporter
path may write to stdout.
