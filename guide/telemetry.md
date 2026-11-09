# Telemetry

Pass **`--otel`** to export a trace span for every MCP request (`tools/call`, `resources/read`,
`prompts/get`, `completion/complete`) over **OTLP/HTTP**. It is off by default, and off means
inert: no SDK, no exporter threads, no network.

Configuration is the standard OpenTelemetry environment surface — there are no bespoke flags:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318   # '/v1/traces' is appended for you
export OTEL_SERVICE_NAME=kotlin-lib-mcp                    # this is also the default
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment=dev
server --transport stdio --otel
```

The protocol defaults to `http/protobuf`, the endpoint to `http://localhost:4318`, and the
exporter uses the JDK's built-in HTTP client (no OkHttp on the classpath). Everything is
overridable: `OTEL_EXPORTER_OTLP_HEADERS` for a hosted collector's API key, `OTEL_TRACES_EXPORTER`,
`OTEL_BSP_SCHEDULE_DELAY`, and so on.

With the generic `OTEL_EXPORTER_OTLP_ENDPOINT`, `/v1/traces` is appended
automatically. With the per-signal `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, the URL is used
**as-is** — you must spell out the path yourself. This is the most common OTLP misconfiguration.

Spans follow the [MCP semantic conventions][mcp-semconv]: named `{method} {target}` (e.g.
`tools/call fetch_library`), `SpanKind.SERVER`, and carrying `mcp.method.name`, `gen_ai.tool.name`,
`mcp.session.id`, and `network.transport` (`pipe` for stdio, `tcp` for http). A tool that returns
`isError` is marked `error.type=tool_error`. Inbound trace context is picked up from the JSON-RPC
`params._meta` bag (`traceparent`/`tracestate`, per [SEP-414][sep-414]), so a client that traces
its own work gets one connected trace.

Those `mcp.*` and `gen_ai.*` attributes are still **Development** status upstream and may be
renamed — one more reason the whole feature is opt-in.

[mcp-semconv]: https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/mcp.md
[sep-414]: https://modelcontextprotocol.io/community/seps/414-request-meta
