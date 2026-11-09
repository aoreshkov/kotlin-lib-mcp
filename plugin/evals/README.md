# Eval suite

Behavioural tests for the `kotlin-lib` plugin, run with
[`claude plugin eval`](https://code.claude.com/docs/en/plugin-evals) (Claude Code ≥ 2.1.269).

The Gradle tests under `server/src/test` prove the server *works*. These prove the plugin
*steers* — that Claude reaches for the real sources instead of recalling an API, passes
well-formed coordinates, and stays out of the way when the question isn't about a JVM library.
No unit test can fail on any of that.

## Run it

The suite takes **two commands, and they must stay two**:

```bash
cd plugin
claude plugin eval . --tag core --tag anti-trigger          # the offline cases, no grant
claude plugin eval . --tag network --allow-tools WebFetch   # lookup-vs-web, needs the network
```

> **Never run the whole suite with `--allow-tools WebFetch`.** The grant applies to *every case in
> the run*, not only to cases that list the tool in their own `allowed_tools` — so one flag hands
> `WebFetch` to `recall-vs-lookup`'s **baseline** arm, which is the arm that is supposed to be
> working from memory, and quietly turns the flagship into a second copy of `lookup-vs-web`.
> Measured on 2026-09-13, same suite, same models, minutes apart:
>
> | `recall-vs-lookup` | baseline | `commits-to-an-answer` | Δ |
> | :--- | ---: | :--- | ---: |
> | granted suite-wide | 1.00 | PASS 3/3 | **0.00** |
> | no grant | 0.53 | FAIL 3/3 | **+0.47** |
>
> Both runs are green at `--threshold 0.8`, so nothing warns you: the suite just reports that the
> plugin adds nothing. A grant that a case's baseline must not have is a grant that case must not
> share a run with.

Other useful shapes:

```bash
claude plugin eval . --ablation none --tag core   # with-arm only; half the cost, no Δ
claude plugin eval . --case recall-vs-lookup      # one case
claude plugin eval . --case recall-vs-lookup --keep-temp   # keep transcripts (out/trace.jsonl)
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

Why the graders are shaped this way, what the results so far mean, and the tool names to re-check
if the plugin or server is ever renamed are in [DESIGN.md](DESIGN.md).

## Mocks

`mocks/kotlin-lib/` answers the MCP tools so runs need no server, no network and no Maven Central.
Mocked tools need no `--allow-tools` grant.

Four fidelity decisions worth knowing:

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

That path needs a JDK 25+ (or a local server) and Maven Central, so keep it out of the fast suite —
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

- **Mondays 06:00 UTC**, and **`workflow_dispatch`** — the whole suite, as **two separate
  invocations**: the offline cases with no grant, then `--tag network --allow-tools WebFetch` on
  its own. The weekly run is the one that earns its keep: the thing under test is a model, and
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
