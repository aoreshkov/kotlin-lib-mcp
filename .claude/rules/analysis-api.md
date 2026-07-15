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
