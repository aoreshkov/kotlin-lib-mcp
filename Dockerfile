# Runtime-only image: the distribution is built by CI (or locally) first with
#   ./gradlew :server:installDist
# The dist is pure JVM (arch-independent), so one COPY serves amd64 and arm64.
# Tag + digest: the digest pins the exact multi-arch image (Dependabot's docker
# ecosystem bumps it); the tag documents the intent.
FROM eclipse-temurin:25-jre@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112

LABEL org.opencontainers.image.source="https://github.com/aoreshkov/kotlin-lib-mcp" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.description="MCP server exposing the sources, public API and KDoc of Maven-published Kotlin/Java libraries" \
      io.modelcontextprotocol.server.name="io.github.aoreshkov/kotlin-lib-mcp"

# The cache directory must exist *and* be owned by `mcp` before the VOLUME below: Docker
# seeds a fresh volume from the image's directory, permissions included, but auto-creates
# the mount point root-owned when the path is missing — which would leave the non-root
# process unable to write its own cache. Guarded by the docker-smoke CI job.
RUN useradd --create-home mcp \
 && install -d -o mcp -g mcp /home/mcp/.cache
USER mcp

COPY --chown=mcp server/build/install/server /app

# Library downloads + parsed indexes; mount a volume here to persist across runs.
VOLUME ["/home/mcp/.cache"]

ENTRYPOINT ["/app/bin/server"]
# stdio by default (docker run -i …); override with: --transport http --port 3000
CMD ["--transport", "stdio"]
