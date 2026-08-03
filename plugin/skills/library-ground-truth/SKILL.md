---
description: Look up the real API of a Maven-published Kotlin or Java library — type-resolved signatures, KDoc, dependency tree and source — instead of recalling it. Use before writing or reviewing code against a third-party JVM dependency, when a symbol's existence, signature, nullability, generics or overloads are uncertain, when an import or call doesn't compile, or when the user asks what a library class or function does.
---

# Library ground truth

Model memory of third-party JVM APIs is unreliable: names drift between versions, overloads are
invented, nullability and generics get lost. The `kotlin-lib` MCP server answers from the
**published sources jar** for the exact version the project depends on. Prefer it over recall.

## 1. Pin the coordinate first

Never guess the version. Read it from the project:

- `gradle/libs.versions.toml` — version catalog (`[libraries]` / `[versions]`)
- `build.gradle.kts` / `build.gradle` — `implementation("group:artifact:version")`
- `pom.xml` — `<dependency>` blocks
- `gradle.lockfile` / `gradle/verification-metadata.xml` if present — these carry the *resolved*
  version, which is what actually ends up on the classpath

If the project pins no version, or you're evaluating a library it doesn't use yet, pass
`group:artifact` (or `group:artifact:latest`) and the server resolves the latest stable release.

## 2. Warm the cache

Call `fetch_library` with the coordinate **once** per library and version. It downloads, extracts
and analyzes the sources; every other tool then answers from the on-disk cache, offline and fast.
All other tools fail until it has run for that coordinate.

## 3. Ask the narrow question

| You need | Tool |
|---|---|
| What's in this library at all | `list_packages` |
| What's in this package | `list_declarations` (`package`, `visibility`, `maxResults`, `offset`) |
| The exact signature of one symbol | `get_api_signature` (`fqName`) |
| What it does / params / `@throws` | `get_kdoc` (`fqName`) |
| How it's actually implemented | `get_source` (`fqName` or `path`) |
| Where a thing is used or defined | `search_source` (`query`, `regex`) |
| What it drags onto the classpath | `get_dependencies` (`depth` 1–5) |
| Which versions exist / newest stable | `list_versions`, `get_latest_version` |

Start narrow. `list_declarations` on a large package is a wall of text; a `search_source` for the
concept, then `get_api_signature` on the two or three hits, is usually the cheaper path.

## 4. Report what you found, with the version

State the version you consulted — "in `io.ktor:ktor-client-core:3.5.1`, `HttpClient.config` takes
…". A signature marked `bestEffort` came from PSI without full type resolution (a transitive
dependency was missing), so flag it as approximate rather than presenting it as resolved.

If a symbol genuinely isn't there, say so and offer what *is* there (a renamed replacement found
via `search_source`, or the same symbol in a neighbouring version via `list_versions`). Do not
paper over the gap by writing code against the API you expected to find.

## Notes

- Kotlin Multiplatform libraries publish per-target sources jars; declarations come back tagged
  with their targets. Check the target list before assuming a symbol exists on the platform you
  are compiling for.
- The first `fetch_library` for a large library takes a while (download + full analysis). It is
  cached afterwards, keyed by `group/artifact/version`, and later calls are instant.
- Only publicly published Maven artifacts are reachable. A private or unpublished module is not
  something this server can answer for — read that source from the workspace instead.
