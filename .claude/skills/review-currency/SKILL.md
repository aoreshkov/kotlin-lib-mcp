---
name: review-currency
description: Reviews how current kotlin-lib-mcp is versus the latest official releases and recommended practices, by launching a fixed panel of expert reviewer subagents (Kotlin/Analysis API, MCP, build & dependencies, CI/publishing, security, Claude Code setup) that research today's state of the art on official sources and compare it with the repo. Use when asked to review project currency, check for outdated dependencies or toolchain drift, or audit against current best practices.
disable-model-invocation: true
argument-hint: "[optional focus: kotlin | mcp | deps | ci | security | claude-code]"
---

# Project currency review — expert panel

Run a currency review of this repository: how far its toolchain, dependencies, and
practices have drifted from the **latest official releases and recommendations as of
today's date**. Never judge currency from memory — every claim about "latest" must be
verified by the expert subagents against official sources on the web today.

## Workflow

### 1. Snapshot project state

Read these files and collect the facts the experts will need (versions, pins, action
refs, base images). Note today's date — all research anchors to it.

- `gradle/libs.versions.toml` — every version pin and its comments (comments mark deliberate pins)
- `gradle/wrapper/gradle-wrapper.properties` — Gradle version
- `.github/workflows/ci.yml`, `.github/workflows/codeql.yml`, `.github/workflows/release.yml`
- `Dockerfile` and `server.json`
- `CLAUDE.md` — project conventions and gotchas the experts must honor
- `.claude/` — agents, skills, hooks, `settings.json` (the Claude Code setup itself)

### 2. Select the panel

The panel is six dedicated currency subagents (defined in `.claude/agents/`):
`kotlin-currency`, `mcp-currency`, `deps-currency`, `ci-currency`, `security-currency`,
`claude-code-currency`. Each agent carries its own persona, inspection list, official
sources, project gotchas, and output contract — they are the single source of truth.
(`references/experts.md` is the human-readable copy of those briefs, kept in sync for
reviewers.)

If `$ARGUMENTS` is non-empty, launch only the experts whose keys it names (`kotlin`, `mcp`,
`deps`, `ci`, `security`, `claude-code` → the matching `*-currency` agent); otherwise launch
all six.

### 3. Launch the experts in parallel

Launch **one subagent per selected expert, all in a single message**, using the matching
`*-currency` agent type. Each agent already knows its brief and output contract, so the
prompt only needs to supply:

1. The relevant snapshot facts from step 1 (versions, pins, action refs, base images).
2. The absolute repo path and today's date.
3. A reminder to return the standard findings table (**Area | Current in repo | Latest
   official | Severity | Recommendation | Source URL**) followed by ≤5 sentences of
   practice-level commentary — the agent's own definition spells this out in full.

### 4. Synthesize the report

When all experts have returned, merge their findings:

- Dedupe overlaps (e.g. deps and security both flagging the same artifact) — keep the
  higher severity and both perspectives.
- Rank by severity: blocker, high, medium, low, info.
- Write the full report to `docs/reviews/currency-<YYYY-MM-DD>.md` (create the
  directory if needed) — `docs/` is the repo's private, gitignored analysis dir (see
  CLAUDE.md), so reports never land in the public history — with these sections:
  1. **TL;DR** — overall currency verdict and a merged top-findings table
  2. One section per expert with their full findings table and commentary
  3. **Prioritized action list** — concrete next steps, highest severity first

### 5. Report back

End with a short chat summary: the overall verdict (one sentence), the top 3–5 actions
with severities, and the path to the full report. Do not paste the whole report into chat.
