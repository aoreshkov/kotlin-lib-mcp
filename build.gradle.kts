// Root build: module wiring lives in the `build-logic` convention plugins, which each
// module applies. Per-module plugins (kover, binary-compatibility-validator, Kotlin) are
// applied there, so the root project itself carries no plugins or dependencies.
