---
name: release
description: Cut a new kotlin-lib-mcp release — bump the version in all three tag-guarded files consistently, update the changelog, run the pre-flight checks, and (on request) tag. Use when preparing a release or when a release tag failed the version-match guard.
disable-model-invocation: true
argument-hint: "[new version, e.g. 0.3.0]"
---

# Cut a release

The release workflow (`.github/workflows/release.yml`) is triggered by a `v*` tag and its
**first step is a hard version-match guard**: if the tag doesn't match the version in all
three source-of-truth files, the whole release (GitHub release + GHCR image + MCP registry
publish) fails. This skill makes the three files agree *before* the tag is pushed.

## 1. Determine the new version

Use `$ARGUMENTS` if given. Otherwise read the current version from `gradle.properties`
(`version=`), decide the bump with the user (semver), and confirm. Call the new value
`<VERSION>` (no `v` prefix inside files; the git tag is `v<VERSION>`).

## 2. Bump the version in ALL THREE guarded files

The tag guard checks these exact patterns (keep the formatting identical):

1. `gradle.properties` — the line `version=<VERSION>` (guard: `^version=<VERSION>$`).
2. `server.json` — the top-level `"version": "<VERSION>"` (guard: `jq .version == <VERSION>`).
   Note: the workflow later also rewrites `server.json` `.version` and `.packages[0].identifier`
   from the tag, but the guard still requires the committed value to match, so set it here.
3. `server/src/main/kotlin/McpServerFactory.kt` — the constant
   `SERVER_VERSION: String = "<VERSION>"` (guard: exact string match).

Miss any one and the release job aborts at the guard step. Grep to confirm all three:

```
grep -n "version=" gradle.properties
grep -n '"version"' server.json
grep -n "SERVER_VERSION" server/src/main/kotlin/McpServerFactory.kt
```

## 3. Update the changelog

Add a `<VERSION>` section to `CHANGELOG.md` (follow the existing format): summarize
user-facing changes since the last release. Use `git log <last-tag>..HEAD` to gather them.

## 4. Pre-flight checks (catch the known release-pipeline gotchas)

- **MCP registry description cap:** the server description in `server.json` must be **≤ 100
  characters** or the registry publish rejects it. Verify:
  `jq -r '.description | length' server.json` → must be ≤ 100.
- **`gradlew` executable bit:** the wrapper must stay executable in git or the CI build fails
  on a fresh checkout. Verify: `git ls-files -s gradlew` shows mode `100755`.
- **No private paths leak:** confirm nothing under `docs/` (private) is referenced from
  released files, and `docs/` is gitignored.
- **Build is green:** `./gradlew build` passes, and `./gradlew :server:installDist` produces
  the distribution the release zip is built from.

## 5. Simulate the tag guard locally

Before tagging, run the guard's own logic so you don't discover a mismatch in CI:

```
VERSION="<VERSION>"
grep -q "^version=${VERSION}$" gradle.properties && \
jq -e --arg v "$VERSION" '.version == $v' server.json >/dev/null && \
grep -q "SERVER_VERSION: String = \"${VERSION}\"" server/src/main/kotlin/McpServerFactory.kt && \
echo "guard OK" || echo "guard WOULD FAIL"
```

## 6. Commit and (on explicit request) tag

Commit the version bump + changelog. **Only if the user explicitly asks**, create and push the
`v<VERSION>` tag — pushing the tag triggers the live release, GHCR push, and MCP registry
publish. Otherwise stop after the commit and report that the tag is ready to push.

## 7. Report

Summarize: old → new version, the three files updated, changelog entry, pre-flight results
(description length, gradlew mode, build status, local guard simulation), and whether a tag
was pushed or is pending.
