---
description: Summarize the public API of a Maven-published Kotlin or Java library from its real sources. Usage:/kotlin-lib:api group:artifact[:version] [package]
disable-model-invocation: true
---

# Public API summary

Arguments: `$ARGUMENTS` — a Maven coordinate, optionally followed by a package to scope to.
The version may be omitted (or `latest`) to use the latest stable release. If no coordinate was
given, ask for one instead of guessing.

Steps:

1. If the arguments name a library the current project depends on, resolve the version from
   `gradle/libs.versions.toml`, the build script, or `pom.xml` rather than taking the latest.
2. `fetch_library` on the coordinate.
3. `list_packages` to see the shape of the library and pick the packages worth covering — unless
   a package was given, in which case use it.
4. `list_declarations` per package (public visibility). Page with `maxResults`/`offset` rather
   than dumping everything at once.
5. `get_api_signature` + `get_kdoc` for the declarations that matter — entry points, factories,
   the types a caller has to name. Skip the long tail.

Report as:

- **One paragraph** on what the library is for and how a caller enters it, grounded in the KDoc.
- **A table or short list per package**: declaration, kind, one-line purpose from the KDoc summary.
- **Signatures verbatim** for the entry points, in Kotlin, in a fenced block.
- **The version you consulted**, always. Mark any `bestEffort` signature as approximate.
- For a Kotlin Multiplatform library, note the targets each symbol is available on.

Close with what you'd read next (`get_source` on an implementation, `get_dependencies` for the
classpath cost) rather than padding the summary.
