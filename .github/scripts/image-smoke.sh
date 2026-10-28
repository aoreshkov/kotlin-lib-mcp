#!/usr/bin/env bash
# Smoke test of a built Docker image: the shipped distribution in the image's own Temurin 25 JRE,
# with its AOT cache, rather than the toolchain JDK the unit tests run on.
#
# Usage: image-smoke.sh <image>
#
# CI runs it on every change (ci.yml, docker-smoke) and the release runs it on the image it is
# about to push (release.yml, docker), so the gate that protects GHCR is the same one CI exercises.
set -euo pipefail

image="${1:?usage: image-smoke.sh <image>}"
here="$(cd "$(dirname "$0")" && pwd)"

# Guards the VOLUME ownership: with an anonymous volume at the declared path, the mount point must
# be owned by the runtime user, or the non-root process cannot create the cache. Docker seeds a
# fresh volume from the image's directory, so if the `install -d -o mcp` before VOLUME regresses,
# the volume comes back root-owned and every fetch_library fails with AccessDenied on the user's
# machine, not here.
echo "::group::Non-root user can write the cache volume"
docker run --rm --entrypoint /bin/sh "$image" -eu -c '
  test "$(id -un)" = mcp || { echo "not running as mcp: $(id -un)"; exit 1; }
  owner=$(stat -c "%U" /home/mcp/.cache)
  test "$owner" = mcp || { echo "cache mount point owned by ${owner}, expected mcp"; exit 1; }
  f=/home/mcp/.cache/smoke.txt
  printf ok > "$f"
  test -s "$f" || { echo "could not write ${f}"; exit 1; }
  echo "OK: $(id -un) wrote ${f} on the anonymous volume"
'
echo "::endgroup::"

# Covers tool registration, which is where a classpath conflict with kotlin-compiler's shaded jars
# surfaces: as a NoSuchMethodError at startup, invisible to compilation and to the unit tests.
echo "::group::Server completes an MCP handshake and lists its tools"
out=$( { printf '%s\n' \
    '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"ci-smoke","version":"0"}}}' \
    '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
    '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
  sleep 15; } | docker run --rm -i "$image" )
echo "$out"
grep -q '"serverInfo"' <<< "$out" || { echo "initialize returned no serverInfo"; exit 1; }
grep -q '"fetch_library"' <<< "$out" || { echo "tools/list did not list fetch_library"; exit 1; }
echo "OK: MCP handshake and tools/list answered on the image's JDK"
echo "::endgroup::"

# The Analysis API fails at runtime, never at compile time (a coupled pin out of step shows up as
# NoSuchMethodError or as silently lost type resolution), and the unit tests only analyze fixtures.
# So: fetch and analyze a real library on the shipped JDK, require a signature that was resolved
# rather than rendered from text, and require the server to exit by itself once the client closes
# stdin (the Analysis API's non-daemon threads used to keep it alive). `-XX:AOTMode=on` makes the
# JVM refuse to start unless the image's AOT cache is usable, where the default would quietly start
# without it and lose ~0.8 s on every launch.
echo "::group::Server analyzes a real library and exits when the client disconnects"
python3 "$here/analyze-smoke.py" \
  docker run --rm -i -e SERVER_OPTS="-XX:AOTCache=/app/server.aot -XX:AOTMode=on" "$image"
echo "::endgroup::"
