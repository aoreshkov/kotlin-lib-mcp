## What & why

<!-- What does this PR change, and what problem does it solve? Link the issue if one exists. -->

## How was it tested?

<!-- Unit tests? Ran the server against a real library coordinate? Which client? -->

## Checklist

- [ ] `./gradlew build` passes locally (includes tests and `apiCheck`)
- [ ] Ran `./gradlew apiDump` and committed `core/api/core.api` (only if the public API of `core` changed intentionally)
- [ ] `CHANGELOG.md` updated under `[Unreleased]` (user-visible changes only)
- [ ] No stdout writes on the stdio path (logging goes to stderr)
