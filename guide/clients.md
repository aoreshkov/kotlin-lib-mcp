# Client setup

The README's [Quick start](../README.md#quick-start-no-build-required) covers the Claude Code plugin,
the release zip and Docker. This page covers the other ways to connect.

## IntelliJ IDEA and Android Studio

JetBrains IDEs are MCP clients too — which is where
most Kotlin gets written. Open **Settings | Tools | AI Assistant | Model Context Protocol (MCP)**,
click **Add**, pick the **stdio** transport and paste:

```json
{
  "mcpServers": {
    "kotlin-lib": {
      "command": "docker",
      "args": ["run", "-i", "--rm", "-v", "kotlin-lib-mcp-cache:/home/mcp/.cache", "ghcr.io/aoreshkov/kotlin-lib-mcp"]
    }
  }
}
```

Choose global or project level, **Apply**, and the tools appear in the AI Assistant chat. Junie
takes the same JSON in its own MCP settings. Swap `command`/`args` for the release-zip launcher
(`bin/server --transport stdio`) if you'd rather not use Docker.

## MCP registries

The server is published to the
[official MCP registry](https://registry.modelcontextprotocol.io) as
`io.github.aoreshkov/kotlin-lib-mcp`, and listed in the
[GitHub MCP Registry](https://github.com/mcp/aoreshkov/kotlin-lib-mcp); registry-aware clients can
install it from there. The entry declares two optional arguments a client can ask you for when it
installs: `--repo`, for each extra Maven repository (a private one, or `https://maven.google.com`
for AndroidX), and `--max-concurrent-fetches`.

## `.mcp.json` and Claude Desktop

Point the client at an unpacked release zip:

```json
{
  "mcpServers": {
    "kotlin-lib": {
      "command": "C:/path/to/kotlin-lib-mcp-server-<version>/bin/server.bat",
      "args": ["--transport", "stdio"]
    }
  }
}
```
