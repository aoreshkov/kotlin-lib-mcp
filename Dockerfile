# Runtime-only image: the distribution is built by CI (or locally) first with
#   ./gradlew :server:installDist
# The dist is pure JVM (arch-independent), so one COPY serves amd64 and arm64.
# Tag + digest: the digest pins the exact multi-arch image (Dependabot's docker
# ecosystem bumps it); the tag documents the intent.
FROM eclipse-temurin:25-jre@sha256:bb036ed6cfdc57e3da7c22634d15f1b840d2caf76183861c80e81ca4b5104abb

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

# AOT cache (JDK 25: JEP 483/514). A training run records the classes a client session loads and
# links; the cache built from it lets every later start skip that work. Measured on Temurin 25, a
# client's `initialize` is answered in ~0.3 s instead of ~1.1 s. A cache is specific to the JVM build
# and the CPU architecture, so it is built here, on the pinned base image, once per platform.
#
# The training session is what a client does on connect — the handshake, then the list calls — so it
# needs no network. It waits for the last reply rather than sleeping a fixed time, because under QEMU
# (the arm64 build) startup is many times slower; the server exits once its stdin closes.
RUN set -eu; \
    mkfifo /tmp/aot-training.in; \
    SERVER_OPTS="-XX:AOTMode=record -XX:AOTConfiguration=/tmp/server.aotconf" \
      /app/bin/server --cache-dir /tmp/aot-training.cache < /tmp/aot-training.in > /tmp/aot-training.out & \
    exec 3> /tmp/aot-training.in; \
    printf '%s\n' \
      '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"aot-training","version":"0"}}}' \
      '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
      '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
      '{"jsonrpc":"2.0","id":3,"method":"resources/list"}' \
      '{"jsonrpc":"2.0","id":4,"method":"resources/templates/list"}' \
      '{"jsonrpc":"2.0","id":5,"method":"prompts/list"}' >&3; \
    waited=0; \
    until grep -q '"id":5' /tmp/aot-training.out; do \
      waited=$((waited + 1)); \
      [ "$waited" -le 600 ] || { echo "the AOT training session got no answer"; exit 1; }; \
      sleep 1; \
    done; \
    exec 3>&-; \
    wait; \
    SERVER_OPTS="-XX:AOTMode=create -XX:AOTConfiguration=/tmp/server.aotconf -XX:AOTCache=/app/server.aot" \
      /app/bin/server > /dev/null; \
    rm -rf /tmp/aot-training.* /tmp/server.aotconf

# Every start uses the cache. If one ever cannot be (another JVM, a changed classpath), the JVM says
# so on stderr — the distribution routes all JVM logging there, never to the stdout protocol channel —
# and starts without it. JAVA_OPTS is left free for the operator's own flags.
ENV SERVER_OPTS="-XX:AOTCache=/app/server.aot"

# Library downloads + parsed indexes; mount a volume here to persist across runs.
VOLUME ["/home/mcp/.cache"]

ENTRYPOINT ["/app/bin/server"]
# stdio by default (docker run -i …); override with: --transport http --port 3000
CMD ["--transport", "stdio"]
