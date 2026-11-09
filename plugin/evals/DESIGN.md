# Eval suite design

Why the suite is shaped the way it is, and what its results so far mean. To run it, see
[README.md](README.md).

## Why the trace graders are `arm: with-only`

`claude plugin eval` automatically excludes `tool_used: Skill` graders from scoring in a two-arm
run, because a check that can only pass with the plugin loaded would push the without-arm toward
zero and inflate Δ. **The same is true of every grader that asserts an MCP tool was called**, and
those are *not* excluded automatically — so they carry `arm: with-only` explicitly. They still
report pass/fail as indicators; they just don't score.

What's left scoring in both arms is the content of the answer. That makes Δ mean the honest thing:
*did the plugin make the answer right*, not *did the plugin cause its own tools to be called*.

## The grader that actually separates the arms

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

## `lookup-vs-web`, and the Δ 0.00 you should read carefully

This case asks the flagship's question with `WebFetch` granted, so the without-arm is not working
from memory — it can go and read the source. It needs the grant on the command line, because a
case's `allowed_tools` cannot widen a gated tool:

```bash
claude plugin eval . --tag network --allow-tools WebFetch
```

On its own, always — never folded into a whole-suite run. See the warning under **Run it** in [README.md](README.md): the
grant reaches every case in the run, and `recall-vs-lookup`'s baseline is supposed to have no way
to verify anything.

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

## `no-invented-changelog`: a guard that has never fired

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

## Naming, confirmed

Both values below were written from the documented convention and then **verified against a real
run** on 2026-09-12 — a wrong tool name would make a `min: 0, max: 0` grader pass for the wrong
reason, so they are worth re-checking if the plugin or server is ever renamed:

1. **Mock directory** — `mocks/kotlin-lib/`, the server key from `plugin/.mcp.json`. The run banner
   confirms binding: `mocked: kotlin-lib(fetch_library=fixed, …)`.
2. **MCP tool names in graders** — `mcp__plugin_kotlin-lib_kotlin-lib__<tool>`, i.e.
   `mcp__plugin_<plugin>_<server>__<tool>`.

If either changes, grep a fresh `aggregate-result.json` for `mcp__` and update the graders.
