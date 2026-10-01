---
name: analysis-api-bump
description: Bump the Kotlin version together with the version-locked Analysis API `-for-ide` artifacts and their runtime-dep pins, then verify source analysis still works. Use when upgrading Kotlin, when the Analysis API fails to resolve types after a bump, or when auditing whether the coupled pins are still correct.
disable-model-invocation: true
argument-hint: "[target Kotlin version, e.g. 2.4.20]"
---

# Bump Kotlin + Analysis API (version-locked)

The Analysis API (standalone K2/FIR) is the single most version-fragile part of this repo.
Kotlin and every `*-for-ide` artifact **must share the exact same version**, and two runtime
deps (`caffeine`, `intellijCoroutines`) are pinned to match what the bundled IntelliJ core
expects. Getting this wrong shows up as `NoSuchMethodError` / `NoClassDefFoundError` at
runtime or as silent loss of type resolution — never a clean compile error. Follow the steps;
do not eyeball it.

## 1. Snapshot the current pins

Read `gradle/libs.versions.toml` and record:

- `kotlin` — the shared version ref used by **all** of: `analysisApi-standalone`,
  `analysisApi-highLevel`, `analysisApi-k2`, `analysisApi-lowLevelFir`,
  `analysisApi-implBase`, `analysisApi-platform`, `analysisApi-symbolLightClasses`,
  `analysisApi-compilerCommon`, `kotlin-compiler`, plus the `kotlin-*` / `composeCompiler-*` plugin artifacts.
- `caffeine` (comment: "runtime dep of the Analysis API `-for-ide` jars; matches Kotlin's own pin").
- `intellijCoroutines` (comment: "JetBrains coroutines fork the bundled IJ core expects (KT-81457)").
- `compose` (comment: "aligned to Kotlin X.Y.Z") — Compose is Kotlin-coupled too.

Because every Analysis-API artifact uses `version.ref = "kotlin"`, bumping the single
`kotlin` entry moves them all. The risk is **not** forgetting one artifact — it's the three
coupled satellites (`caffeine`, `intellijCoroutines`, `compose`) and undocumented API breaks.

## 2. Confirm the target and its coupled expectations (official sources)

For `$ARGUMENTS` (or the intended target Kotlin version), verify against official sources:

- kotlinlang.org release notes / Kotlin GitHub release: is this a stable release? Any
  Analysis API / standalone breaking changes called out?
- The `-for-ide` artifacts exist for this version in the IntelliJ dependencies repo
  (`https://www.jetbrains.com/intellij-repository/`), not just on Maven Central.
- Does the new Kotlin change the **caffeine** version its `-for-ide` jars expect? (Check the
  Kotlin build's own bundled version.) If so, update the `caffeine` pin to match and update
  the comment.
- Did the **`kotlin-compiler` fat jar drop packages** the `-for-ide` jars rely on? Kotlin moves
  modules between that jar and the `-for-ide` set (`prepare/compiler` vs
  `prepare/ide-plugin-dependencies` in its build): 2.4.20 dropped the decompiler
  (`org.jetbrains.kotlin.analysis.decompiler.*`), which is why `analysisApi-compilerCommon` exists.
  Diff the two versions' package lists (`unzip -Z1 … | sed 's|/[^/]*$||' | sort -u`); a
  package that vanished without the analyzer losing a feature still has to come from somewhere.
- Is **KT-81457** fixed in this version, making the `intellijCoroutines` fork pin
  unnecessary? If yes, that's a chance to drop the fork; if not, keep the pin.
- Compose Multiplatform ↔ Kotlin compatibility: pick the `compose` version aligned to the
  new Kotlin (JetBrains compatibility table) and update the `# aligned to Kotlin …` comment.

Delegate this research to the `kotlin-currency` and `deps-currency` subagents if a broad check
is wanted — they research official sources and respect the documented pins.

## 3. Apply the bump

In `gradle/libs.versions.toml` **only** (never inline versions):

- Set `kotlin = "<target>"`.
- Update `caffeine`, `intellijCoroutines`, `compose` only if step 2 showed the constraint
  moved — and update their explanatory comments to reflect the new constraint.

## 4. Rebuild and verify source analysis actually works

A green compile is **not** sufficient — the Analysis API fails at runtime, not compile time.

- `./gradlew :core:build` — compiles and runs `core` tests, including `SourceAnalyzer` tests.
- Run the analyzer end-to-end on a real library so type resolution is exercised:
  `./gradlew :server:installDist` then
  `python3 .github/scripts/analyze-smoke.py server/build/install/server/bin/server`. It fetches
  `ktor-client-core`, requires a signature resolved to `kotlin.Boolean` rather than the PSI text
  fallback, and requires a clean exit. CI's `Docker image smoke` job runs the same script on the
  shipped image, so a bump PR is checked even where the `-for-ide` jars cannot be downloaded.
- Watch for `NoSuchMethodError` / `NoClassDefFoundError` / immutable-collections shadowing —
  those signal a coupled-pin mismatch, not a code bug. Revisit step 2.

## 5. Report

Summarize: old → new Kotlin version, which coupled pins moved (and why, with the official
source), whether the `intellijCoroutines` fork is still needed, and the verification evidence
(test result + a resolved signature from a fetched library). Note anything that degraded to
PSI-only fallback. Do not commit unless asked.
