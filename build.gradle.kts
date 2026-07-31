// Root build: module wiring lives in the `build-logic` convention plugins, which each
// module applies. Per-module plugins (kover, Kotlin — with its built-in ABI validation) are
// applied there, so the root project itself carries no plugins or dependencies.
