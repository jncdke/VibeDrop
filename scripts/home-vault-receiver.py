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
import urllib.parse
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


def load_json_file(path: pathlib.Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def find_latest_android_payload(
    vault_root: pathlib.Path,
    device_id: str = "",
) -> tuple[dict[str, Any], pathlib.Path]:
    android_inbox = vault_root / "inbox" / "android"
    if not android_inbox.exists():
        raise FileNotFoundError("Android inbox 不存在")

    candidate_groups: list[list[pathlib.Path]] = []
    if device_id:
        candidate_groups.append(list((android_inbox / safe_segment(device_id)).glob("*.json")))
    candidate_groups.append(list(android_inbox.glob("*/*.json")))

    seen_paths: set[pathlib.Path] = set()
    for candidates in candidate_groups:
        unique_candidates = [path for path in candidates if path not in seen_paths]
        seen_paths.update(unique_candidates)
        unique_candidates.sort(key=lambda path: (path.stat().st_mtime, path.name), reverse=True)
        for path in unique_candidates:
            try:
                payload = normalize_payload(load_json_file(path))
            except Exception:
                continue
            return payload, path

    raise FileNotFoundError("没有找到 Android 历史快照")


def compact_history_item(item: Any) -> dict[str, Any]:
    if not isinstance(item, dict):
        return {"fileName": str(item)}
    allowed_keys = (
        "kind",
        "fileName",
        "mimeType",
        "sizeBytes",
        "saveTarget",
        "durationMs",
        "width",
        "height",
    )
    return {key: item[key] for key in allowed_keys if item.get(key) not in (None, "")}


def compact_history_entry(entry: Any) -> dict[str, Any]:
    if not isinstance(entry, dict):
        return {
            "timestamp": iso_now(),
            "text": str(entry),
            "status": "success",
            "kind": "text",
        }

    allowed_keys = (
        "id",
        "timestamp",
        "timestamp_iso",
        "text",
        "status",
        "target",
        "targetHost",
        "targetAlias",
        "targetName",
        "targetDeviceName",
        "targetServerId",
        "serverId",
        "direction",
        "kind",
        "saveTarget",
        "fileName",
        "mimeType",
        "itemCount",
    )
    result = {key: entry[key] for key in allowed_keys if entry.get(key) not in (None, "")}
    items = entry.get("items")
    if isinstance(items, list) and items:
        compact_items = [compact_history_item(item) for item in items]
        result["items"] = compact_items
        result.setdefault("itemCount", len(compact_items))

    result.setdefault("text", entry.get("fileName") or "")
    result.setdefault("status", "success")
    result.setdefault("kind", "text")
    return result


def clamp_limit(value: str, default: int, maximum: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    if parsed <= 0:
        return default
    return min(parsed, maximum)


def prepare_restore_history(history: list[Any], mode: str, limit: int) -> list[Any]:
    selected = history[:limit] if limit else history
    if mode == "full":
        return selected
    return [compact_history_entry(entry) for entry in selected]


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
            parsed_path = urllib.parse.urlparse(self.path)
            path = parsed_path.path.rstrip("/") or "/"
            if path == "/health":
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
                return

            if path == "/api/android-history/latest":
                if token and self.headers.get("X-VibeDrop-Token") != token:
                    self.send_json(401, {"ok": False, "error": "unauthorized"})
                    return
                try:
                    query = urllib.parse.parse_qs(parsed_path.query)
                    device_id = (query.get("deviceId") or query.get("device_id") or [""])[0]
                    mode = ((query.get("mode") or ["compact"])[0] or "compact").lower()
                    if mode not in {"compact", "full"}:
                        raise ValueError("mode 只能是 compact 或 full")
                    limit = clamp_limit((query.get("limit") or ["0"])[0], 0, 10000)
                    payload, source_path = find_latest_android_payload(vault_root, device_id)
                    history = payload["history"]
                    restored_history = prepare_restore_history(history, mode, limit)
                    self.send_json(
                        200,
                        {
                            "ok": True,
                            "schemaVersion": 1,
                            "app": "VibeDrop",
                            "deviceId": payload.get("deviceId") or "",
                            "deviceName": payload.get("deviceName") or "",
                            "exportedAt": payload.get("exportedAt") or "",
                            "sourcePath": source_path.relative_to(vault_root).as_posix(),
                            "mode": mode,
                            "historyCount": len(history),
                            "returnedCount": len(restored_history),
                            "history": restored_history,
                        },
                    )
                except Exception as exc:
                    self.send_json(500, {"ok": False, "error": str(exc)})
                return

            self.send_json(404, {"ok": False, "error": "not_found"})

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
    parser.add_argument("--viewer-url", default="http://192.168.3.2:8787/viewer/")
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
