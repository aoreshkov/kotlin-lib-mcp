---
expect:
  # Three segments, no optional part — "Pin the coordinate first", enforced.
  coordinate: /^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+$/
  fqName: string
---
{"symbol":{"fqName":"{{input.fqName}}","kind":"function","visibility":"public","signature":"public expect fun HttpClient(block: io.ktor.client.HttpClientConfig<*>.() -> kotlin.Unit = {}): io.ktor.client.HttpClient","modifiers":["expect"],"bestEffort":false,"sourceRef":{"file":{"path":"common/commonMain/io/ktor/client/HttpClient.kt","packageName":"io.ktor.client","target":"common"},"line":333}}}
