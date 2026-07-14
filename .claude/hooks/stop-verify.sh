#!/usr/bin/env bash
# Stop hook — lightweight compile gate.
#
# Runs a fast Kotlin compile before Claude ends a turn, but ONLY when Kotlin
# sources changed in the working tree. Doc-only / question turns short-circuit
# immediately so they never pay for a Gradle invocation.
#
# Exit codes (Claude Code Stop-hook semantics):
#   0  -> allow the turn to end
#   2  -> block the turn; stderr is fed back to Claude so it fixes the break
#
# This is a safety net, not a wall. To bypass a known-good stop, disable the
# hook via /hooks for the session. Portable: needs `bash` on PATH (Git Bash on
# Windows, the system bash on macOS/Linux).

set -uo pipefail

# Repo root is two levels up from .claude/hooks/. Fail open if we can't cd.
cd "$(dirname "$0")/../.." || exit 0

# Collect changed Kotlin sources: tracked (staged + unstaged vs HEAD) and new
# untracked files. If none, there is nothing to compile.
changed="$(
  {
    git diff HEAD --name-only -- '*.kt' '*.kts'
    git ls-files --others --exclude-standard -- '*.kt' '*.kts'
  } 2>/dev/null
)"

if [ -z "${changed//[$'\t\r\n ']/}" ]; then
  exit 0
fi

# Compiling :server pulls in :core transitively, so one task covers both the
# KMP library and the JVM server. --offline keeps it snappy on a warm cache;
# drop it if a dependency refresh is genuinely needed.
if ./gradlew :server:compileKotlin -q --offline; then
  exit 0
fi

echo "Stop hook: Kotlin compile failed (see the Gradle output above). Fix the compile error before finishing this turn." >&2
exit 2
