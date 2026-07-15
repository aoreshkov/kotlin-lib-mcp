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
