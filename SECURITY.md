# Security Policy

## Supported versions

| Version | Supported |
|---------|-----------|
| Latest release (0.x) | ✅ |
| Older releases | ❌ |

## Reporting a vulnerability

Please **do not open a public issue** for security problems.

Instead, use GitHub's private vulnerability reporting: go to the repository's **Security**
tab → **Report a vulnerability** (or use
[this link](https://github.com/aoreshkov/kotlin-lib-mcp/security/advisories/new)).

You can expect an initial response within a few days. Please include reproduction steps and
the affected version. Once a fix is released, the advisory will be published with credit to
the reporter (unless you prefer to stay anonymous).

## Scope notes

This server downloads and parses artifacts from Maven repositories (Maven Central by
default) and writes them to a local cache directory. Reports about path traversal during
zip extraction, cache poisoning, or protocol-stream injection over the stdio/HTTP
transports are particularly relevant.
