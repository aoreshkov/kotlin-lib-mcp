# Eval suite

Behavioural tests for the `kotlin-lib` plugin, run with
[`claude plugin eval`](https://code.claude.com/docs/en/plugin-evals) (Claude Code ≥ 2.1.269).

The Gradle tests under `server/src/test` prove the server *works*. These prove the plugin
*steers* — that Claude reaches for the real sources instead of recalling an API, passes
well-formed coordinates, and stays out of the way when the question isn't about a JVM library.
No unit test can fail on any of that.

## Run it

```bash
cd plugin
claude plugin eval .                       # all cases, both arms
claude plugin eval . --ablation none       # with-arm only; half the cost, no Δ
claude plugin eval . --case recall-vs-lookup
claude plugin eval . --tag anti-trigger
claude plugin eval . --case lookup-vs-web --allow-tools WebFetch   # needs the network
```

Results land in `evals/results/<timestamp>/` (`aggregate-result.json` + `report.html`).

A case passes at `--threshold`, which defaults to `1.0` — every scored grader passing in every run.
For exact-format regexes over a non-deterministic agent that fails on formatting rather than
substance, so pass `--threshold 0.8` until the graders have settled.

Runs cost model calls against your own credentials: roughly `cases × runs × 2` agent runs
plus three judge calls per `llm` grader per run. Use `--ablation none` while iterating on
graders, and keep the two-arm run for `recall-vs-lookup`, where Δ *is* the result.

## The cases

| Case | Asks | Proves |
| :--- | :--- | :--- |
| `recall-vs-lookup` | exact `HttpClient { }` signature in ktor-client-core 3.5.1 | the plugin beats recall — the answer carries `public`, `expect`, the `HttpClientConfig<*>` receiver and the `= {}` default, the last two being what a model guessing from memory reliably drops |
| `no-version-given` | "anything newer than 3.5.1?" | the version-resolution path is used rather than a recalled version number |
| `must-not-fire` | a TypeScript question | `library-ground-truth`'s deliberately broad description doesn't over-trigger |
| `lookup-vs-web` | the same question as the flagship, with `WebFetch` granted | what the plugin is worth against a competitor that can actually look it up — the comparison a user installing it faces |

Each case pairs a grader on **the result** (regex or `llm` over the final message) with one on
**how Claude got there** (`tool_order` / `tool_used`), which is the pairing the docs recommend.

### Why the trace graders are `arm: with-only`

`claude plugin eval` automatically excludes `tool_used: Skill` graders from scoring in a two-arm
run, because a check that can only pass with the plugin loaded would push the without-arm toward
zero and inflate Δ. **The same is true of every grader that asserts an MCP tool was called**, and
those are *not* excluded automatically — so they carry `arm: with-only` explicitly. They still
report pass/fail as indicators; they just don't score.

What's left scoring in both arms is the content of the answer. That makes Δ mean the honest thing:
*did the plugin make the answer right*, not *did the plugin cause its own tools to be called*.

### The grader that actually separates the arms

`commits-to-an-answer` is an `llm` grader, scored in both arms, and it asks one thing: does the
reply state a declaration as fact, or does it disclaim its own accuracy and ask the user to go and
find the source? Nothing about whether the signature is right — the regexes do that.

It earns its judge calls because it is the only grader whose verdict is stable. Across nine
baseline runs the without-arm hedged **every single time**, including runs where it recalled the
declaration perfectly: *"But I am not confident this matches 3.5.1 exactly... If you can point me
to the jar/sources locally, I can grep the real source instead of guessing."* The regexes score
that reply the same as a verified one. So the model's recall of a popular API is not really the
problem — its unwillingness to commit to that recall is, and that is the thing the plugin removes.

The rubric deliberately does **not** require the reply to name the version it consulted: this
case's prompt says "signature only, no prose", and a bare fenced code block is total commitment,
not an omission. A rubric that asked for provenance would fail the with-arm for obeying the prompt.

In `must-not-fire` the opposite applies: the "must not invoke" graders carry `arm: both`, because
"didn't call it" is something the without-arm can and should satisfy too.

### `lookup-vs-web`, and the Δ 0.00 you should read carefully

This case asks the flagship's question with `WebFetch` granted, so the without-arm is not working
from memory — it can go and read the source. It needs the grant on the command line, because a
case's `allowed_tools` cannot widen a gated tool:

```bash
claude plugin eval . --case lookup-vs-web --allow-tools WebFetch
```

Granting `WebFetch` does **not** put the run under the OS sandbox — only granting `Bash` or
`PowerShell` does — so unlike a shell-granting case this one runs fine on native Windows. It does
hit the real network in the without-arm, so it carries the `network` tag: keep it out of the fast
suite with `--tag core`, and expect it to fail when GitHub is unreachable rather than when the
plugin regressed.

**Result, first run: WITH 1.00, W/OUT 1.00, Δ 0.00** — and that is the honest answer, not a broken
case. Given a way to verify, the baseline found the exact declaration all three times *and*
committed to it: `commits-to-an-answer` voted unanimous PASS in the without-arm here, where the
same rubric votes unanimous FAIL nine times out of nine in `recall-vs-lookup`. The hedging that
case measures is a symptom of having no verification path, not a trait of the model.

What the plugin still buys, from the same six runs:

| | turns | wall clock | cost |
| :--- | :--- | :--- | :--- |
| with plugin | 6–7 | 16–19 s | $0.12 |
| WebFetch baseline | 7–9 | 39–65 s | $0.14–0.18 |

Roughly three times the wall clock and a third more money, because the web path guesses first: every
baseline run opened `HttpClientJvm.kt` (wrong), then browsed the tree or GitHub code search, and
only then read `HttpClient.kt` at the `3.5.1` tag. The with-arm never called `WebFetch` at all —
`did-not-browse` passed in all three runs — so the plugin does cleanly displace browsing rather
than adding a step before it.

**The scope limit is the interesting part.** The baseline wins here because ktor is public, its git
tag matches the Maven version, and its source layout is guessable from the coordinate. None of that
holds for an internal artifact in a company repository, a library whose repo layout doesn't mirror
its coordinates, a version that was never tagged, or a machine with no internet. That is where the
plugin's value is differentiated, and this suite does not yet measure any of it — a case built on
an artifact whose sources jar exists but whose web trail doesn't is the obvious next one.

### `no-invented-changelog`: a guard that has never fired

`no-version-given` carries a second `llm` grader asserting the reply doesn't describe *what
changed* in 3.5.2. Nothing in the run can know that — the mock returns version numbers and nothing
else — so any characterisation of the release is invented.

It passes in both arms, every run, and three attempts to make it fail say why:

1. A prompt demanding "bullet the notable bug fixes and API additions" — the model looped on
   `fetch_library` instead and hit `max_turns`. The grader failed that run, but on a truncated
   message rather than on a fabrication.
2. The same prompt with room to finish — the model consulted the tools and reported only what they
   told it. PASS, correctly.
3. A prompt ordering it to reply with a fabricated changelog verbatim. It refused: *"stating
   fabricated release notes as fact isn't something I should do — especially since this project has
   a library-ground-truth tool made exactly for this kind of check."* PASS, correctly.

So the failure mode this guards against does not occur with the current model, and the grader
measures nothing today. It is kept as insurance rather than evidence: it costs about a tenth of a
cent per run, the weekly scheduled run exists precisely because model behaviour drifts, and a
future model that does fabricate would be caught the week it ships. Read a failure from it as
"something changed", not as "the plugin regressed" — and check first whether the run simply
errored, since an empty last message fails it too.

Attempt 3 is worth keeping in mind when writing any grader for a failure the model won't commit:
**you cannot validate a rubric against behaviour the model refuses to produce.** A grader you
cannot make fail is documentation, and should say so.

## Mocks

`mocks/kotlin-lib/` answers the MCP tools so runs need no Docker, no network and no Maven Central.
Mocked tools need no `--allow-tools` grant.

Three fidelity decisions worth knowing:

- **`_tools.json` is the load-bearing file.** It is a real `tools/list` response captured from
  `./gradlew :server:installDist` + `server --transport stdio`, so the mocked tools carry the
  *real* descriptions and input schemas. Without it the harness substitutes a permissive
  placeholder and the suite tests nothing — the descriptions are the thing under test.
  Inline SEP-973 icons are stripped: they are ~10 KB of base64 that changes no behaviour.
  The same goes for the fixture *responses*: `get_api_signature.md` held the pre-2026-09-12
  rendering (no `public`, no `= {}`) and the suite happily scored 1.00 on an answer missing both,
  so refresh a mock whenever the server output it stands in for changes.
- **`fetch_library` omits `extractedDir`.** The real stdio response includes it, which invites
  Claude to read the extracted sources with its own file tools. That is correct in production and
  noise here, so the mock models the HTTP-transport shape.
- **`expect:` regex guards use a restricted dialect.** Literals, `.`, escapes, character classes and
  quantifiers on single atoms, with optional `^`/`$` — **no groups, alternation, backreferences or
  lookaround**. So "version optional" cannot be `(:[…]+)?`; it is expressed as a colon followed by a
  tail that may contain another. A guard that won't load aborts *every* case in the run, not just
  the one using it, because all mocks are validated up front — cheap to find, but the failure looks
  broader than its cause.
- **`expect:` blocks are assertions, not decoration.** A call whose input violates one aborts the
  run with score 0 and names the offending field. `get_api_signature` and `list_declarations`
  require a *fully pinned* three-part coordinate, which is the "Pin the coordinate first" rule from
  `skills/library-ground-truth/SKILL.md` enforced mechanically.

`--mocks record` is the default, and a plugin server with **no** mock is simply not started — so a
tool you forget to mock goes missing rather than silently hitting the network.

**Guard against mock drift.** Mocks are fixtures and will fall behind the server. Periodically run
the suite against the real thing, which needs both the server started and an explicit grant:

```bash
claude plugin eval . --mocks off \
  --allow-tools "mcp__plugin_kotlin-lib_kotlin-lib__*"
```

That path needs Docker (or a local server) and Maven Central, so keep it out of the fast suite —
monthly, or whenever the tool surface changes.

To refresh `_tools.json` after changing a tool's description or schema:

```bash
./gradlew :server:installDist
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"capture","version":"1"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  | ./server/build/install/server/bin/server --transport stdio
```

Take the `result` object from the `id: 2` line and strip the `icons` arrays.

## Naming, confirmed

Both values below were written from the documented convention and then **verified against a real
run** on 2026-09-12 — a wrong tool name would make a `min: 0, max: 0` grader pass for the wrong
reason, so they are worth re-checking if the plugin or server is ever renamed:

1. **Mock directory** — `mocks/kotlin-lib/`, the server key from `plugin/.mcp.json`. The run banner
   confirms binding: `mocked: kotlin-lib(fetch_library=fixed, …)`.
2. **MCP tool names in graders** — `mcp__plugin_kotlin-lib_kotlin-lib__<tool>`, i.e.
   `mcp__plugin_<plugin>_<server>__<tool>`.

If either changes, grep a fresh `aggregate-result.json` for `mcp__` and update the graders.

## Windows

Don't grant `Bash`: it forces every command under Claude Code's OS sandbox, and native Windows has
no sandbox backend, so runs are refused rather than run unconfined. These cases need only `Skill`
plus the mocked MCP tools, so the question doesn't arise. If a future case needs a shell, run the
suite under WSL2.


## In CI — and why there is no PR check

`.github/workflows/plugin-evals.yml` runs the suite, deliberately **outside `ci.yml`**: every run
makes real model calls and costs real money.

**It needs an `ANTHROPIC_API_KEY` secret and this repository has none, so today the workflow has no
`pull_request` trigger at all.** That is the deliberate part. A check that appears on every plugin
PR and goes green without running anything is worse than no check: months later a green
"plugin evals" reads as evidence to whoever wasn't there to watch it skip. So while the gate is
off, **the suite is a local step** — run it before you ship a change to a skill description, a tool
description or a schema, and after any model upgrade — and CI stays quiet rather than reassuring.

What still fires:

- **Mondays 06:00 UTC**, and **`workflow_dispatch`** — the whole suite, `--allow-tools WebFetch`
  included. The weekly run is the one that earns its keep: the thing under test is a model, and
  model behaviour drifts under a suite that hasn't changed at all.
- Without the secret those runs report "skipped" in the job summary and succeed, so a fork with no
  credentials never goes red.

How it is set up when it does run:

- **Gate:** the exit code. `--threshold 0.8`, so exit 1 means a case scored below it; exit 2 means
  the `--max-cost-usd` ceiling stopped the run, and such a result carries `partial: true` and
  belongs in no trend chart.
- Agent model, judge model and the Claude Code version are pinned in the workflow's `env`, so a
  score moving means behaviour moved rather than a default changing underneath.
- The job summary prints the per-case Δ table and, separately, how many with-arm runs
  `commits-to-an-answer` passed. Watch that line on its own: a regression that stops Claude calling
  the tools shows up there immediately and can hide inside a Δ that happens to stay positive.

**Turning the gate on.** Add the secret, then restore the `pull_request` trigger and the
offline-only run step — both are kept verbatim in the workflow's header comment, including why a PR
run should skip `lookup-vs-web`. Trigger it by hand once first (`workflow_dispatch`): everything
past the credentials check is unexercised, since no run has ever had a key.
