# Runtime-only image: the distribution is built by CI (or locally) first with
#   ./gradlew :server:installDist
# The dist is pure JVM (arch-independent), so one COPY serves amd64 and arm64.
# Tag + digest: the digest pins the exact multi-arch image (Dependabot's docker
# ecosystem bumps it); the tag documents the intent.
FROM eclipse-temurin:21-jre@sha256:d2b9f8f12212cadcfdf889461531784e8fd097feade954d65b31ee7a71c473ec

LABEL org.opencontainers.image.source="https://github.com/aoreshkov/kotlin-lib-mcp" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.description="MCP server exposing the sources, public API and KDoc of Maven-published Kotlin/Java libraries" \
      io.modelcontextprotocol.server.name="io.github.aoreshkov/kotlin-lib-mcp"

RUN useradd --create-home mcp
USER mcp

COPY --chown=mcp server/build/install/server /app

# Library downloads + parsed indexes; mount a volume here to persist across runs.
VOLUME ["/home/mcp/.cache"]

ENTRYPOINT ["/app/bin/server"]
# stdio by default (docker run -i …); override with: --transport http --port 3000
CMD ["--transport", "stdio"]
