# Runtime-only image: the distribution is built by CI (or locally) first with
#   ./gradlew :server:installDist
# The dist is pure JVM (arch-independent), so one COPY serves amd64 and arm64.
FROM eclipse-temurin:21-jre

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
