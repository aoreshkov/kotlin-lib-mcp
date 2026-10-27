---
paths:
  - "core/src/**/*.kt"
---

# Core parsing & fetch gotchas

- **Isolate the Analysis API** behind the `SourceAnalyzer` interface — it is version-fragile;
  degrade gracefully (fall back to PSI-only signature text) when type resolution fails.
- **KMP source jars are per-target.** Libraries publish `<artifact>-jvm-<v>-sources.jar`
  (and a common `<artifact>-<v>-sources.jar`), not a single usable root sources jar. Resolve
  variants via the `.module` Gradle metadata; fall back to filename heuristics.
- **The `ForSource` renderer preset is not source-faithful.** `KaDeclarationRendererForSource`
  drops parameter default values (`NO_DEFAULT_VALUE`) and the `public` keyword
  (`NO_IMPLICIT_VISIBILITY`), which makes an optional parameter read as required. `SignatureRenderer`
  overrides both; keep the overrides when the Analysis API is bumped, and check the preset hasn't
  quietly dropped something else.
- **`deprecationStatus` has no message in K2.** `KaFirSymbolInformationProvider` passes `null` on
  purpose (KT-67823), so `Deprecations.kt` takes only the *level* from it and reads message and
  `ReplaceWith` from the resolved `kotlin.Deprecated` annotation. `status.message` compiles and
  silently drops every message. `readsAConstantDeprecationMessageThroughResolution` is the test that
  tells the resolved path from the PSI fallback.
- **Effective visibility is computed in the analyzer; don't re-filter downstream.**
  `ApiSymbol.visibility` is the narrowest visibility out through the enclosing classes, from the
  *resolved* symbol (`EffectiveVisibility.kt`) — `KaDeclarationSymbol.visibility` alone reports an
  `internal class`'s members as PUBLIC, and only resolution sees an `override` inherit `protected`.
  `list_declarations` and `diff_api` both read the field as-is, so they cannot disagree.
- **A rendering change must invalidate the cache.** `index.json` holds rendered strings, and a
  coordinate is immutable, so a warm cache would answer with the old rendering forever. Bump
  `CacheLayout.INDEX_FILE` (`index-v4.json` today) whenever signature rendering or the index shape
  changes, and list the old name in `LEGACY_INDEX_FILES`; re-analysis is cheap because the sources
  are already on disk.
