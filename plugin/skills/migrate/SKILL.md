---
description: Diff a library's public API between two versions and find what breaks in this codebase. Usage:/kotlin-lib:migrate group:artifact from-version to-version
disable-model-invocation: true
---

# Upgrade impact report

Arguments: `$ARGUMENTS` — `group:artifact` plus the two versions to compare. If the target
version is missing, use `get_latest_version`. If the source version is missing, read the one the
project currently depends on from `gradle/libs.versions.toml`, the build script, or `pom.xml`.

Steps:

1. `fetch_library` **both** coordinates: `group:artifact:<from>` and `group:artifact:<to>`.
   `diff_api` compares the two cached indexes and downloads nothing, so it fails on a version
   that was not fetched.
2. `diff_api` with `fromVersion` and `toVersion`. Fields that are zero, `false` or empty are
   left out of the result, so a missing `removed` means none and a missing `truncated` means the
   page is complete. Read the summary before the page:
   - `added` / `removed` / `changed` / `deprecated` count the **whole** comparison, so they are
     the shape of the release in four numbers.
   - `packagesAdded` / `packagesRemoved` are the loudest signal — report them first.
     `packagesTruncated: true` means those lists were capped.
   - Then page `changes` with `maxResults: 500` and `offset` while `truncated` is `true`. On a
     large release, pass `change` (`removed`, then `changed`, then `deprecated`) rather than paging
     through `added` entries you will not report; the summary counts are unaffected by it. Do not
     truncate silently — if you stopped early, say where.
3. Classify every entry:
   - **`removed`** — breaking. Its `deprecation` is the one it carried *before* it went, which is
     where the author named the replacement.
   - **`changed`** — same FQ name, `fromSignature` → `toSignature`. Breaking if the parameter
     list, receiver, nullability or generics moved. A new trailing parameter with a default still
     compiles for Kotlin callers; say so rather than flag it.
   - **`deprecated`** — newly deprecated, or at a stricter level. `error` and `hidden` break the
     build like a removal; `warning` compiles and is a follow-up, not a blocker.
   - **`added`** — worth mentioning only where it replaces something removed.
   - A `removed` and an `added` with the **same `fqName` and `kind`** are usually one overload whose
     signature changed: overloads are matched by signature, so changing several at once reads as
     removals plus additions. Report the pair as one change.
   - **`approximate: true`** — one side was read from unresolved declaration text, so the
     difference may be formatting alone. Compare `get_api_signature` on both versions before
     calling it breaking.
   - A signature carrying an opt-in marker (`@InternalAPI`, or any annotation the library
     declares with `@RequiresOptIn`) was never supported API. `diff_api` does not filter these, so
     report a change to one only where this codebase opted in to it.
4. For each removed, changed or deprecated symbol, search the workspace for real usages (Grep on
   the simple name, then confirm the import — or a same-package or star import). A break nobody
   calls is a footnote, not a task.
5. Suggest a replacement for each break that has usages:
   - **`deprecation.replaceWith` first** — the library author's own migration. It may name the
     old declaration's parameters (`newGreet(name)`); substitute the call site's arguments, and
     add `deprecation.replaceWithImports`.
   - Only without one: `get_kdoc` on the old symbol in the *from* version (deprecation notes,
     `@see`), then `search_source` in the *to* version for the renamed symbol. Mark a replacement
     found this way as inferred.
6. `get_dependencies` on both, so a transitive bump that comes along for the ride is not a
   surprise.

If the summary has no counts at all for a release you know changed the API, the declarations were most
likely Java — `diff_api` sees Kotlin declarations only. Fall back to `diff_versions` for the source
diff and say that the report is built from text, not from the API.

Report as:

- **Verdict line** — how many breaking changes actually touch this codebase.
- **Table**: symbol · what changed · files affected · suggested replacement (author's or
  inferred).
- **Notes**: API additions worth adopting, `warning`-level deprecations to schedule,
  dependency-tree changes, packages added/removed.
- **What you did not check** — pages you stopped short of, `approximate` entries left
  unconfirmed, and what `diff_api` does not model: `@PublishedApi internal` declarations (which
  inline functions compile into callers), binary-only changes, and anything reachable only through
  reflection or a service loader.

Do not edit any code as part of this report. Land the analysis first; the user decides what to
change.
