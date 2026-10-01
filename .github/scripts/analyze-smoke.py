#!/usr/bin/env python3
"""End-to-end smoke test of the stdio server: a real fetch, real source analysis, a clean exit.

Usage: analyze-smoke.py <command that starts the server over stdio> [args...]
  e.g. analyze-smoke.py docker run --rm -i kotlin-lib-mcp:smoke

Unit tests run the Analysis API on fixtures only, and the Analysis API fails at runtime rather than
at compile time (a coupled pin out of step shows up as NoSuchMethodError or as silent loss of type
resolution), so this drives the shipped server over MCP the way a client does:

1. fetch_library on a real artifact from Maven Central (download, extract, analyze, cache);
2. get_api_signature on a declaration whose rendering proves the type was *resolved* — a
   fully-qualified `kotlin.Boolean`, not the `bestEffort` text fallback's bare `Boolean`;
3. close stdin and require the process to exit by itself, as MCP's stdio shutdown expects.

Server stderr passes through to this script's stderr, so a failure shows the server's own log.
"""
import json
import subprocess
import sys
import threading
import time

COORDINATE = "io.ktor:ktor-client-core:3.5.1"
FQ_NAME = "io.ktor.client.HttpClientConfig.expectSuccess"
EXPECTED_SIGNATURE = "public var expectSuccess: kotlin.Boolean"
FETCH_TIMEOUT_S = 300
EXIT_TIMEOUT_S = 60


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    if len(sys.argv) < 2:
        fail("usage: analyze-smoke.py <server command...>")
    server = subprocess.Popen(sys.argv[1:], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    responses: dict = {}
    arrived = threading.Condition()

    def read_stdout() -> None:
        for line in server.stdout:
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                # stdout is the protocol channel: anything else on it is itself a bug.
                with arrived:
                    responses["corrupt"] = line
                    arrived.notify_all()
                return
            if "id" in message:
                with arrived:
                    responses[message["id"]] = message
                    arrived.notify_all()

    threading.Thread(target=read_stdout, daemon=True).start()

    def send(message: dict) -> None:
        server.stdin.write(json.dumps(message) + "\n")
        server.stdin.flush()

    def request(request_id: int, method: str, params: dict, timeout: float = 60) -> dict:
        send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        with arrived:
            if not arrived.wait_for(lambda: request_id in responses or "corrupt" in responses, timeout):
                fail(f"no response to {method} within {timeout:.0f}s")
        if "corrupt" in responses:
            fail(f"non-JSON-RPC output on stdout: {responses['corrupt']!r}")
        response = responses[request_id]
        if "error" in response:
            fail(f"{method} returned a JSON-RPC error: {response['error']}")
        return response["result"]

    def call_tool(request_id: int, name: str, arguments: dict, timeout: float = 60) -> dict:
        result = request(request_id, "tools/call", {"name": name, "arguments": arguments}, timeout)
        if result.get("isError"):
            fail(f"{name} failed: {result['content'][0]['text']}")
        return result["structuredContent"]

    request(1, "initialize", {
        "protocolVersion": "2025-11-25",
        "capabilities": {},
        "clientInfo": {"name": "analyze-smoke", "version": "1"},
    })
    send({"jsonrpc": "2.0", "method": "notifications/initialized"})

    started = time.monotonic()
    summary = call_tool(2, "fetch_library", {"coordinate": COORDINATE}, timeout=FETCH_TIMEOUT_S)
    print(f"fetched {COORDINATE} in {time.monotonic() - started:.0f}s: "
          f"{summary['sourceFileCount']} files, {summary['packageCount']} packages")

    symbol = call_tool(3, "get_api_signature", {"coordinate": COORDINATE, "fqName": FQ_NAME})["symbol"]
    if symbol.get("bestEffort"):
        fail(f"{FQ_NAME} fell back to the PSI text rendering: type resolution is not working")
    if symbol["signature"] != EXPECTED_SIGNATURE:
        fail(f"{FQ_NAME} rendered as {symbol['signature']!r}, expected {EXPECTED_SIGNATURE!r}")
    print(f"resolved: {symbol['signature']}")

    server.stdin.close()
    try:
        code = server.wait(timeout=EXIT_TIMEOUT_S)
    except subprocess.TimeoutExpired:
        server.kill()
        fail(f"the server did not exit within {EXIT_TIMEOUT_S}s of its stdin closing")
    if code != 0:
        fail(f"the server exited with status {code}")
    print("OK: analyzed a real library and exited cleanly when the client disconnected")


if __name__ == "__main__":
    main()
