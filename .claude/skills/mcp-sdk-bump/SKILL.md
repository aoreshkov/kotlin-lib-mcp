---
name: mcp-sdk-bump
description: Upgrade the MCP Kotlin SDK (`kotlin-sdk-server`) using the map of which upcoming SDK milestone touches which file here. Use when bumping `mcpKotlinSdk`, when asked about adopting MCP spec revision 2026-07-28, or when planning around an SDK release.
disable-model-invocation: true
argument-hint: "[target SDK version, e.g. 0.17.0]"
---

# Bump the MCP Kotlin SDK

## 1. Check where the SDK actually is

Look up the newest `io.modelcontextprotocol:kotlin-sdk-server` release and read the SDK's releases
and milestones on GitHub. Then read the new version's **sources and control flow**, not only its
release notes: 0.15.0's real breakage here was a classpath collision the notes could not mention
(`.claude/rules/classpath-shadowing.md`).

## 2. Where things stood

We target **2025-11-25**, because that is what the Kotlin SDK declares
(`LATEST_PROTOCOL_VERSION`). Spec revision **2026-07-28** is stable, but the SDK has released none
of it on the wire: as of **2026-10-28** the newest release is 0.16.0 (2026-10-27), which still
declares 2025-11-25 and adds only experimental, unused SEP-2575 discovery types. This repo is on
0.16.0, whose one decision for us is HTTP idle-session expiry: `SESSION_IDLE_TIMEOUT` in
`transport/HttpTransport.kt` is tied to the task TTL ceiling, because tasks are session-owned.
Work has started upstream — the stateless lifecycle is an open PR
(`modelcontextprotocol/kotlin-sdk#985`) — and the SDK's milestones set the order: **0.17.0** puts
2026-07-28 on the wire (per-request `_meta` envelope, `subscriptions/listen`, SEP-2243 headers),
**0.18.0** brings MRTR, **0.20.0** moves the Ktor integration (`mcpStreamableHttp`) into a separate
`kotlin-sdk-server-ktor` artifact, **0.23.0** ships the tasks extension, and **0.24.0** typed
JSON Schema 2020-12 tool schemas. **There is nothing here to adopt yet, and adopting it early would
mean inventing wire surface ahead of the SDK.** Re-check the SDK's releases and milestones before
assuming otherwise — this paragraph carries a date precisely because it goes stale.

## 3. Decisions to keep

Three decisions in this repo are load-bearing for that migration. Keep them:

- **`elicitation/VersionElicitation.kt` stays one file.** 2026-07-28 replaces the nested
  request/response with MRTR (`InputRequiredResult` + a client retry), so a single-file rewrite is
  the whole migration.
- **`--tasks` stays opt-in.** Tasks become an `io.modelcontextprotocol/tasks` *extension*, gaining
  `tasks/update`, dropping `tasks/result` (clients poll `tasks/get`) and `tasks/list`, and moving
  notifications to one `subscriptions/listen` stream. Opt-in bounds the blast radius to operators
  who asked for it.
- **`logging` stays behind `--forward-logs-to-client`, stderr stays primary.** 2026-07-28 deprecates
  Logging (alongside Roots and Sampling) on a ≥12-month window.

## 4. What each milestone touches

What each milestone touches here, so a release lands on a map rather than a search:

- **0.17.0** (2026-07-28 wire): session identity — below; plus `ttlMs`/`cacheScope` on list and
  `resources/read` results, which the SDK should set rather than us.
- **0.18.0** (MRTR): `elicitation/VersionElicitation.kt`, and nothing else.
- **0.20.0** (Ktor split): `transport/HttpTransport.kt`'s `mcpStreamableHttp` import, plus a
  `kotlin-sdk-server-ktor` catalog entry.
- **0.23.0** (tasks extension): `tasks/`, all behind `--tasks`.
- **0.24.0** (typed schemas, `kotlin-sdk#300`, which adds `additionalProperties`): declare
  `additionalProperties: false` on every input schema. Keep `addTool`'s unknown-argument check
  unless the SDK starts validating arguments itself.

The exposure to look at first, when 0.17.0 lands, is **session identity** — 2026-07-28 retires
`initialize`/`initialized` and `Mcp-Session-Id`, moving identity and capabilities into per-request
`_meta`. Two things here rest on a session existing: the elicitation gate reads
`server.sessions[sessionId]?.clientCapabilities`, and task records are *owned* by the session that
created them. So the first two questions are what replaces that capability lookup, and what the task
ownership model keys on instead. `SessionSetup.kt` goes with it. Note it; do not pre-emptively
rewrite it.

## 5. Apply and verify

- Change `mcpKotlinSdk` in `gradle/libs.versions.toml` only.
- `./gradlew build`. `ConcurrentDispatchTest`, `TaskDispatchTest` and `HttpSessionLifetimeTest` pin
  the SDK behaviour this server relies on; a failure there is a semantic change, not a flaky test.
- `./gradlew :server:installDist`, then run the MCP conformance suite (`CONTRIBUTING.md`).
- Update section 2 above with the new date and version.

Report old and new versions, what the release changed for this repo, and the verification results.
Do not commit unless asked.
