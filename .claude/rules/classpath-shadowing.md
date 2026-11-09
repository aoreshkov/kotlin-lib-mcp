---
paths:
  - "build-logic/**"
  - "**/build.gradle.kts"
  - "gradle/libs.versions.toml"
---

# Classpath shadowing by `kotlin-compiler`

**Classpath-shadowing gotcha:** `kotlin-compiler` (via `core`) is a fat jar bundling old,
*unrelocated* copies of libraries we also depend on for real, and the first copy on the classpath
wins. Gradle sorts the genuine artifacts well behind it (shared transitives sort late, so
declaration order does **not** fix it), so the real jars are hoisted to the front of every
classpath a JVM gets: `hoistOverKotlinCompiler` and `shadowedByKotlinCompiler` live in build-logic
(`KotlinCompilerShadowing.kt`); `server/build.gradle.kts` applies them to `test`, `run` and
`startScripts`, `dashboard/build.gradle.kts` to `test` and Compose's application classpath. Compose
keeps the order for `run` and the uber jar, but **jpackage sorts the packaged app's jars by file
name**, so the dashboard also copies the hoisted jars under a `0-` prefix (`kotlin-compiler-…`
otherwise sorts ahead of `kotlinx-…` and `opentelemetry-…`); check
`dashboard/build/compose/binaries/main/app/dashboard/app/dashboard.cfg` after touching it. Two families
are known:

- `kotlinx.collections.immutable` — 127 classes of a pre-0.5 release. Since kotlin-sdk 0.15.0 built
  `Protocol` and `FeatureRegistry` on the newer `PersistentMap.putting`/`removing`, this took out
  `Server.addTool` itself; before that it only surfaced inside the SDK's `PathSegmentTemplateMatcher`,
  which is why `resources/SegmentTemplateMatcher.kt` exists and why `ServerOptions` passes
  `segmentTemplateMatcherFactory`. The hoist likely makes the SDK's own matcher viable again, but the
  custom one is tested and in use — don't swap it without re-verifying at runtime, not just in tests.
- `io.opentelemetry.api` 1.41.0 — see below.

When adding any dependency `kotlin-compiler` might also bundle, check with
`unzip -l <kotlin-compiler.jar> | grep <package-path>` before trusting the classpath.

**Same trap, OpenTelemetry:** `kotlin-compiler` also bundles ~83 *stripped stubs* of an old
unrelocated `io.opentelemetry.api` (1.41.0 — `Attributes`, `OpenTelemetry`, `Tracer`,
`TracerProvider` are empty shells), so without the hoist above `--otel` dies with
`NoSuchMethodError: Attributes.empty()`. Keep both prefixes in `shadowedByKotlinCompiler`'s list, and
keep that list *local to the function* — it once lived in a build script, where a script-level `val`
captured in the filter is a Gradle script object reference the configuration cache refuses to
serialize.
