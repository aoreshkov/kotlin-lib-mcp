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
2. `list_packages` on both. Packages that appear or disappear are the loudest signal — report
   them first.
3. `list_declarations` (public) per shared package on both versions. Page through with
   `maxResults`/`offset`; do not truncate silently — if you stopped early, say where.
4. Classify every difference:
   - **Removed** — gone in the new version. Breaking.
   - **Changed** — same FQ name, different signature (`get_api_signature` on both). Breaking if
     the parameter list, receiver, nullability or generics moved.
   - **Added** — new surface. Worth mentioning only where it replaces something removed.
5. For each removed or changed symbol, search the workspace for real usages (Grep on the simple
   name, then confirm the import). A break nobody calls is a footnote, not a task.
6. `get_kdoc` on the replacement candidates — deprecation notices and `@since`/`@see` tags in the
   new version usually name the intended migration path. `search_source` on the old name in the
   new version often finds the renamed symbol directly.
7. `get_dependencies` on both, so a transitive bump that comes along for the ride is not a
   surprise.

Report as:

- **Verdict line** — how many breaking changes actually touch this codebase.
- **Table**: symbol · what changed · files affected · suggested replacement.
- **Notes**: API additions worth adopting, dependency-tree changes, packages added/removed.
- **What you did not check** — pages you stopped short of, packages skipped, anything only
  reachable through reflection or a service loader, which this diff cannot see.

Do not edit any code as part of this report. Land the analysis first; the user decides what to
change.
