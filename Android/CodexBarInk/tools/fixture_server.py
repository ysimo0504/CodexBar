#!/usr/bin/env python3
"""Snapshot-only HTTP server for the redacted BOOX fixture spike."""

from __future__ import annotations

import argparse
import http.server
import os
from pathlib import Path


SNAPSHOT_PATH = "/dashboard/v1/snapshot"
DEFAULT_TOKEN = "codexbar-ink-fixture-token"


class SnapshotHandler(http.server.BaseHTTPRequestHandler):
    fixture_bytes: bytes
    expected_token: str

    def do_GET(self) -> None:
        if self.path != SNAPSHOT_PATH:
            self._respond(404, b'{"error":"not-found"}')
            return
        if self.headers.get("Authorization") != f"Bearer {self.expected_token}":
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Bearer realm="codexbar-ink-fixture"')
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self._respond(200, self.fixture_bytes)

    def do_POST(self) -> None:
        self.send_response(405)
        self.send_header("Allow", "GET")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _respond(self, status: int, body: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format_string: str, *args: object) -> None:
        del format_string
        status = args[1] if len(args) > 1 else "?"
        print(f"{self.command} {self.path} -> {status}", flush=True)


def parse_args() -> argparse.Namespace:
    project_root = Path(__file__).resolve().parents[3]
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument(
        "--fixture",
        type=Path,
        default=project_root / "docs" / "fixtures" / "dashboard-snapshot-v1-canonical.json",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    SnapshotHandler.fixture_bytes = args.fixture.read_bytes()
    SnapshotHandler.expected_token = os.environ.get("CODEXBAR_INK_FIXTURE_TOKEN", DEFAULT_TOKEN)
    server = http.server.ThreadingHTTPServer((args.host, args.port), SnapshotHandler)
    print(f"Fixture snapshot server listening on port {args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
