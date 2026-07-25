---
paths:
  - "server/src/**/*.kt"
---

# MCP tool authoring & resource-template gotcha

Every tool declares a `title`, behavior annotations (`readOnlyHint`/`openWorldHint`;
`fetch_library` additionally `destructiveHint: false`, `idempotentHint: true`), an
`outputSchema` derived from its response DTO's serial descriptor
(`server/.../tools/OutputSchemas.kt`), and an SEP-973 `icon`; `toolResult` returns JSON text **and**
matching `structuredContent`. When adding a tool, pass all four to `addTool` — use the shared
`LOCAL_READ_ONLY`/`REPOSITORY_READ_ONLY` annotation constants in `ToolSupport.kt`, and add a new
`Glyph` entry (plus its PNG, via `assets/icons/GenerateIcons.java`) in `server/.../icons/Icons.kt`.

**Icons gotcha:** `icons` exists only on the `Tool`/`Prompt`/`Resource`/`ResourceTemplate` types,
never on the SDK's `addTool(name, …)`/`addPrompt(name, …)`/`addResource(uri, …)` convenience
overloads — and there is no `addResource(Resource, handler)` at all, so a resource with icons has
to go through `addResources(listOf(RegisteredResource(…)))` (`addAll` fires the same `listChanged`
listeners, so `resources/list` stays live). The trap is `ToolSupport.kt`'s `addTool` extension:
drop its `icon` argument and the call silently resolves back to the SDK's iconless *member*
overload and still compiles. `ToolRegistrationTest.everyToolDeclaresADistinctIcon` pins that.

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
