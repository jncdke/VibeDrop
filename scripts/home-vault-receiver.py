#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


DEFAULT_VAULT_ROOT = "/Volumes/SN850X/VibeDropVault"
DEFAULT_PORT = 8788
MAX_REQUEST_BYTES = 80 * 1024 * 1024


sync_lock = threading.Lock()


def iso_now() -> str:
    return dt.datetime.now(dt.timezone.utc).astimezone().isoformat(timespec="seconds")


def safe_segment(value: str, fallback: str = "android") -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "_", str(value or "").strip()).strip("._-")
    return cleaned[:80] or fallback


def read_json_body(handler: BaseHTTPRequestHandler, max_bytes: int) -> Any:
    raw_length = handler.headers.get("Content-Length", "0")
    try:
        length = int(raw_length)
    except ValueError as exc:
        raise ValueError("Content-Length 无效") from exc
    if length <= 0:
        raise ValueError("请求体为空")
    if length > max_bytes:
        raise ValueError(f"请求体过大: {length} bytes")
    payload = handler.rfile.read(length)
    return json.loads(payload.decode("utf-8"))


def normalize_payload(payload: Any) -> dict[str, Any]:
    if isinstance(payload, list):
        history = payload
        envelope: dict[str, Any] = {
            "schemaVersion": 1,
            "app": "VibeDrop",
            "deviceId": "android",
            "deviceName": "Android",
            "exportedAt": iso_now(),
            "history": history,
        }
        return envelope
    if not isinstance(payload, dict):
        raise ValueError("JSON 必须是对象或历史数组")
    history = payload.get("history")
    if not isinstance(history, list):
        raise ValueError("history 必须是数组")
    return {
        **payload,
        "schemaVersion": payload.get("schemaVersion") or 1,
        "app": payload.get("app") or "VibeDrop",
        "deviceId": payload.get("deviceId") or payload.get("clientId") or "android",
        "deviceName": payload.get("deviceName") or payload.get("clientName") or "Android",
        "exportedAt": payload.get("exportedAt") or iso_now(),
        "history": history,
    }


def save_payload(vault_root: pathlib.Path, payload: dict[str, Any]) -> pathlib.Path:
    device_id = safe_segment(str(payload.get("deviceId") or payload.get("deviceName") or "android"))
    now = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    digest = hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()[:12]
    inbox_dir = vault_root / "inbox" / "android" / device_id
    inbox_dir.mkdir(parents=True, exist_ok=True)
    path = inbox_dir / f"{now}-{digest}.json"
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def run_sync(sync_script: pathlib.Path, vault_root: pathlib.Path, viewer_url: str, timeout: int) -> dict[str, Any]:
    cmd = [
        sys.executable,
        str(sync_script),
        "--local-vault-root",
        str(vault_root),
        "--skip-local-history",
        "--skip-android",
        "--include-vault-inbox",
        "--viewer-url",
        viewer_url,
    ]
    result = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode != 0:
        raise RuntimeError(f"sync failed ({result.returncode}): {result.stderr.strip() or result.stdout.strip()}")
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"sync returned non-json output: {result.stdout[:500]}") from exc


def make_handler(config: argparse.Namespace) -> type[BaseHTTPRequestHandler]:
    vault_root = pathlib.Path(config.vault_root).expanduser().resolve()
    sync_script = pathlib.Path(config.sync_script).expanduser().resolve()
    viewer_url = config.viewer_url
    max_bytes = config.max_bytes
    token = config.token
    sync_timeout = config.sync_timeout

    class HomeVaultHandler(BaseHTTPRequestHandler):
        server_version = "VibeDropHomeVault/1.0"

        def log_message(self, fmt: str, *args: Any) -> None:
            sys.stderr.write(f"{self.log_date_time_string()} {self.address_string()} {fmt % args}\n")

        def send_json(self, status: int, payload: dict[str, Any]) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type, X-VibeDrop-Token")
            self.end_headers()
            self.wfile.write(body)

        def do_OPTIONS(self) -> None:
            self.send_json(204, {})

        def do_GET(self) -> None:
            if self.path.rstrip("/") != "/health":
                self.send_json(404, {"ok": False, "error": "not_found"})
                return
            self.send_json(
                200,
                {
                    "ok": True,
                    "service": "vibedrop-home-vault-receiver",
                    "vaultRoot": str(vault_root),
                    "viewerUrl": viewer_url,
                    "time": iso_now(),
                },
            )

        def do_POST(self) -> None:
            if self.path.rstrip("/") != "/api/android-history":
                self.send_json(404, {"ok": False, "error": "not_found"})
                return
            if token and self.headers.get("X-VibeDrop-Token") != token:
                self.send_json(401, {"ok": False, "error": "unauthorized"})
                return
            try:
                payload = normalize_payload(read_json_body(self, max_bytes))
                history_count = len(payload["history"])
                saved_path = save_payload(vault_root, payload)
                with sync_lock:
                    sync_report = run_sync(sync_script, vault_root, viewer_url, sync_timeout)
                self.send_json(
                    200,
                    {
                        "ok": True,
                        "historyCount": history_count,
                        "savedPath": saved_path.relative_to(vault_root).as_posix(),
                        "syncReport": sync_report,
                    },
                )
            except Exception as exc:
                self.send_json(500, {"ok": False, "error": str(exc)})

    return HomeVaultHandler


def main() -> int:
    parser = argparse.ArgumentParser(description="Receive Android VibeDrop history uploads into Home Vault.")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--vault-root", default=DEFAULT_VAULT_ROOT)
    parser.add_argument(
        "--sync-script",
        default=str(pathlib.Path(__file__).with_name("sync-home-vault.py")),
    )
    parser.add_argument("--viewer-url", default="http://minideMac-mini.local:8787/viewer/")
    parser.add_argument("--max-bytes", type=int, default=MAX_REQUEST_BYTES)
    parser.add_argument("--sync-timeout", type=int, default=180)
    parser.add_argument("--token", default=os.environ.get("VIBEDROP_VAULT_TOKEN", ""))
    args = parser.parse_args()

    pathlib.Path(args.vault_root).expanduser().resolve().mkdir(parents=True, exist_ok=True)
    handler = make_handler(args)
    server = ThreadingHTTPServer((args.host, args.port), handler)
    print(f"VibeDrop Home Vault receiver listening on {args.host}:{args.port}", flush=True)
    print(f"Vault root: {pathlib.Path(args.vault_root).expanduser().resolve()}", flush=True)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
