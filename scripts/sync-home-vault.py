#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import mimetypes
import os
import pathlib
import shutil
import shlex
import sqlite3
import subprocess
import sys
import tempfile
import uuid
from typing import Any


DEFAULT_REMOTE = "mini@minideMac-mini.local"
DEFAULT_REMOTE_ROOT = "/Volumes/SN850X/VibeDropVault"
DEFAULT_ANDROID_PACKAGE = "com.vibedrop.mobile"
DEFAULT_VIEWER_MDNS_URL = "http://minideMac-mini.local:8787/viewer/"
DEFAULT_VIEWER_IP_URL = "http://192.168.3.2:8787/viewer/"
DEFAULT_VIEWER_URL = DEFAULT_VIEWER_IP_URL
LOCAL_VIEWER_URL = "http://localhost:8787/viewer/"
OBJECT_BUCKETS = [f"{i:02x}" for i in range(256)]


def iso_now() -> str:
    return dt.datetime.now(dt.timezone.utc).astimezone().isoformat(timespec="seconds")


def run(cmd: list[str], *, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess[str]:
    kwargs: dict[str, Any] = {"text": True}
    if capture:
        kwargs.update({"stdout": subprocess.PIPE, "stderr": subprocess.PIPE})
    result = subprocess.run(cmd, **kwargs)
    if check and result.returncode != 0:
        rendered = " ".join(shlex.quote(part) for part in cmd)
        detail = ""
        if capture:
            detail = f"\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}"
        raise RuntimeError(f"command failed ({result.returncode}): {rendered}{detail}")
    return result


def remote_shell(remote: str, command: str, *, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess[str]:
    return run(["ssh", remote, command], check=check, capture=capture)


def sha256_file(path: pathlib.Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            size += len(chunk)
            digest.update(chunk)
    return digest.hexdigest(), size


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8", errors="replace")).hexdigest()


def build_webloc(url: str) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>URL</key>
  <string>{url}</string>
</dict>
</plist>
"""


def build_viewer_launcher_html(
    primary_url: str,
    alternate_urls: list[tuple[str, str]],
    note: str,
    *,
    prefer_http_origin: bool = False,
) -> str:
    links = "\n".join(
        f'      <a href="{url}">{label}</a>'
        for label, url in alternate_urls
        if url and url != primary_url
    )
    redirect_script = f"""
  <script>
    (() => {{
      const configuredPrimary = {json.dumps(primary_url)};
      const target = {'window.location.protocol.startsWith("http") ? `${window.location.origin}/viewer/` : configuredPrimary' if prefer_http_origin else 'configuredPrimary'};
      window.location.replace(target);
    }})();
  </script>
"""
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>打开 VibeDrop Home Vault</title>
{redirect_script.rstrip()}
</head>
<body>
  <noscript>
    <p><a href="{primary_url}">打开 VibeDrop 历史查看器</a></p>
{links}
    <p>{note}</p>
  </noscript>
</body>
</html>
"""


def record_entry_id(record: dict[str, Any]) -> str:
    entry = record["entry"]
    source = str(record.get("source") or "")
    exported_id = entry.get("id")
    if source.startswith("android") and exported_id is not None and str(exported_id).strip():
        return sha256_text(f"android-entry:{exported_id}")
    return sha256_text(record["raw_line"])


def safe_ext(path: pathlib.Path, mime_type: str | None = None) -> str:
    suffix = path.suffix.lower()
    if suffix and len(suffix) <= 16 and all(ch.isalnum() or ch == "." for ch in suffix):
        return suffix
    guessed = mimetypes.guess_extension(mime_type or "") if mime_type else None
    if guessed:
        return guessed.lower()
    return ".bin"


def pick(mapping: dict[str, Any], *names: str) -> Any:
    for name in names:
        value = mapping.get(name)
        if value is not None and value != "":
            return value
    return None


def normalize_kind(entry: dict[str, Any], fallback: str = "text") -> str:
    kind = pick(entry, "kind", "type")
    if isinstance(kind, str) and kind.strip():
        return kind.strip()
    mime_type = pick(entry, "mime_type", "mimeType")
    if isinstance(mime_type, str):
        if mime_type.startswith("image/"):
            return "image"
        if mime_type.startswith("video/"):
            return "video"
    return fallback


def clean_label(value: Any) -> str:
    return str(value or "").strip()


def source_receiver_device(source: str) -> dict[str, str]:
    source = str(source or "")
    if source.startswith("macbook:"):
        return {"id": "macbook", "name": "MacBook", "host": "", "server_id": ""}
    return {"id": "", "name": "", "host": "", "server_id": ""}


def derive_device_roles(entry: dict[str, Any], source: str = "") -> dict[str, str]:
    direction = clean_label(pick(entry, "direction"))
    client_id = clean_label(pick(entry, "client_id", "clientId", "deviceId"))
    client_name = clean_label(pick(entry, "client_name", "clientName", "deviceName")) or client_id
    target_id = clean_label(pick(entry, "target_id", "targetId", "target"))
    target_host = clean_label(pick(entry, "targetHost", "targetDeviceName", "hostname"))
    target_server_id = clean_label(pick(entry, "targetServerId", "serverId", "target_server_id"))
    target_name = (
        target_host
        or clean_label(pick(entry, "targetName", "targetAlias", "target"))
        or target_server_id
        or target_id
    )
    source_receiver = source_receiver_device(source)

    client = {"id": client_id, "name": client_name, "host": "", "server_id": ""}
    target = {"id": target_id, "name": target_name, "host": target_host, "server_id": target_server_id}

    if direction == "desktop_to_mobile":
        sender = target if target["name"] or target["id"] else source_receiver
        receiver = client
    else:
        sender = client
        receiver = target if target["name"] or target["id"] else source_receiver

    return {
        "sender_id": sender.get("id", ""),
        "sender_name": sender.get("name", "") or sender.get("id", "") or "未知发送端",
        "receiver_id": receiver.get("id", ""),
        "receiver_name": receiver.get("name", "") or receiver.get("id", "") or "未知接收端",
        "receiver_host": receiver.get("host", ""),
        "receiver_server_id": receiver.get("server_id", ""),
    }


def expand_local_path(raw_path: str | None) -> tuple[pathlib.Path | None, str | None]:
    if not raw_path:
        return None, "no-path"
    text = str(raw_path).strip()
    if not text:
        return None, "no-path"
    if text.startswith("content://"):
        return None, "android-content-uri"
    if text.startswith("file://"):
        from urllib.parse import unquote, urlparse

        parsed = urlparse(text)
        text = unquote(parsed.path)
    path = pathlib.Path(os.path.expanduser(text))
    if not path.is_absolute():
        return None, "relative-path"
    return path, None


def ensure_vault_dirs(root: pathlib.Path) -> None:
    for relative in ["objects", "db", "viewer/data", "manifests", "logs"]:
        (root / relative).mkdir(parents=True, exist_ok=True)
    for bucket in OBJECT_BUCKETS:
        (root / "objects" / bucket).mkdir(parents=True, exist_ok=True)


def init_db(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        PRAGMA foreign_keys = ON;

        CREATE TABLE IF NOT EXISTS snapshots (
            snapshot_id TEXT PRIMARY KEY,
            created_at TEXT NOT NULL,
            host TEXT NOT NULL,
            source_root TEXT NOT NULL,
            history_line_count INTEGER NOT NULL,
            parsed_count INTEGER NOT NULL,
            unique_entry_count INTEGER NOT NULL,
            media_reference_count INTEGER NOT NULL,
            media_object_count INTEGER NOT NULL,
            missing_media_count INTEGER NOT NULL,
            android_status TEXT NOT NULL,
            notes TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS history_entries (
            entry_id TEXT PRIMARY KEY,
            first_seen_at TEXT NOT NULL,
            last_seen_at TEXT NOT NULL,
            timestamp TEXT,
            source TEXT,
            source_file TEXT,
            line_no INTEGER,
            text TEXT,
            client_ip TEXT,
            client_id TEXT,
            client_name TEXT,
            sender_id TEXT,
            sender_name TEXT,
            receiver_id TEXT,
            receiver_name TEXT,
            receiver_host TEXT,
            receiver_server_id TEXT,
            kind TEXT,
            file_name TEXT,
            direction TEXT,
            status TEXT,
            session_id TEXT,
            item_count INTEGER,
            save_target TEXT,
            thumbnail_data_url TEXT,
            raw_json TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS snapshot_entries (
            snapshot_id TEXT NOT NULL,
            entry_id TEXT NOT NULL,
            PRIMARY KEY (snapshot_id, entry_id),
            FOREIGN KEY (snapshot_id) REFERENCES snapshots(snapshot_id) ON DELETE CASCADE,
            FOREIGN KEY (entry_id) REFERENCES history_entries(entry_id) ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS media_objects (
            object_hash TEXT PRIMARY KEY,
            object_path TEXT NOT NULL,
            size_bytes INTEGER NOT NULL,
            mime_type TEXT,
            extension TEXT NOT NULL,
            first_seen_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS history_media (
            entry_id TEXT NOT NULL,
            item_index INTEGER NOT NULL,
            kind TEXT,
            file_name TEXT,
            mime_type TEXT,
            original_path TEXT,
            saved_path TEXT,
            object_hash TEXT,
            object_path TEXT,
            thumbnail_data_url TEXT,
            status TEXT,
            missing_reason TEXT,
            PRIMARY KEY (entry_id, item_index),
            FOREIGN KEY (entry_id) REFERENCES history_entries(entry_id) ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS source_files (
            snapshot_id TEXT NOT NULL,
            source_file TEXT NOT NULL,
            line_count INTEGER NOT NULL,
            parsed_count INTEGER NOT NULL,
            bad_count INTEGER NOT NULL,
            sha256 TEXT,
            size_bytes INTEGER,
            mtime TEXT,
            PRIMARY KEY (snapshot_id, source_file),
            FOREIGN KEY (snapshot_id) REFERENCES snapshots(snapshot_id) ON DELETE CASCADE
        );
        """
    )
    existing_columns = {row[1] for row in conn.execute("PRAGMA table_info(history_entries)")}
    for column_name, column_type in [
        ("sender_id", "TEXT"),
        ("sender_name", "TEXT"),
        ("receiver_id", "TEXT"),
        ("receiver_name", "TEXT"),
        ("receiver_host", "TEXT"),
        ("receiver_server_id", "TEXT"),
    ]:
        if column_name not in existing_columns:
            conn.execute(f"ALTER TABLE history_entries ADD COLUMN {column_name} {column_type}")
    conn.commit()


def source_file_stat(path: pathlib.Path) -> dict[str, Any]:
    if not path.exists():
        return {
            "source_file": str(path),
            "line_count": 0,
            "parsed_count": 0,
            "bad_count": 0,
            "sha256": None,
            "size_bytes": None,
            "mtime": None,
        }
    digest, size = sha256_file(path)
    stat = path.stat()
    return {
        "source_file": str(path),
        "line_count": 0,
        "parsed_count": 0,
        "bad_count": 0,
        "sha256": digest,
        "size_bytes": size,
        "mtime": dt.datetime.fromtimestamp(stat.st_mtime).astimezone().isoformat(timespec="seconds"),
    }


def read_jsonl(path: pathlib.Path, source: str) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    stat = source_file_stat(path)
    records: list[dict[str, Any]] = []
    if not path.exists():
        return records, stat
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        for line_no, raw in enumerate(handle, start=1):
            line = raw.strip()
            if not line:
                continue
            stat["line_count"] += 1
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                stat["bad_count"] += 1
                continue
            if not isinstance(entry, dict):
                stat["bad_count"] += 1
                continue
            stat["parsed_count"] += 1
            records.append(
                {
                    "entry": entry,
                    "raw_line": json.dumps(entry, ensure_ascii=False, separators=(",", ":")),
                    "source": source,
                    "source_file": str(path),
                    "line_no": line_no,
                }
            )
    return records, stat


def android_history_stat(source_file: str) -> dict[str, Any]:
    return {
        "source_file": source_file,
        "line_count": 0,
        "parsed_count": 0,
        "bad_count": 0,
        "sha256": None,
        "size_bytes": None,
        "mtime": None,
    }


def parse_android_history_payload(payload: str, source: str, source_file: str) -> tuple[list[dict[str, Any]], dict[str, Any], str]:
    stat = android_history_stat(source_file)
    stat["sha256"] = sha256_text(payload)
    stat["size_bytes"] = len(payload.encode("utf-8", errors="replace"))
    records: list[dict[str, Any]] = []
    try:
        data = json.loads(payload)
    except json.JSONDecodeError:
        stat["bad_count"] = 1
        return records, stat, "bad_json"
    envelope: dict[str, Any] = {}
    if isinstance(data, dict):
        envelope = {
            "clientId": clean_label(data.get("deviceId") or data.get("clientId")),
            "clientName": clean_label(data.get("deviceName") or data.get("clientName")),
        }
        for key in ["history", "entries", "items", "data"]:
            if isinstance(data.get(key), list):
                data = data[key]
                break
    if not isinstance(data, list):
        stat["bad_count"] = 1
        return records, stat, "unexpected_shape"
    for index, entry in enumerate(data, start=1):
        stat["line_count"] += 1
        if not isinstance(entry, dict):
            stat["bad_count"] += 1
            continue
        entry = dict(entry)
        if envelope.get("clientId") and not pick(entry, "client_id", "clientId", "deviceId"):
            entry["clientId"] = envelope["clientId"]
        if envelope.get("clientName") and not pick(entry, "client_name", "clientName", "deviceName"):
            entry["clientName"] = envelope["clientName"]
        raw_line = json.dumps(entry, ensure_ascii=False, separators=(",", ":"))
        stat["parsed_count"] += 1
        records.append(
            {
                "entry": entry,
                "raw_line": raw_line,
                "source": source,
                "source_file": source_file,
                "line_no": index,
            }
        )
    return records, stat, f"imported:{stat['parsed_count']}"


def find_android_public_exports(adb: str, serial: str) -> list[str]:
    find_command = (
        "find /sdcard/Download /sdcard/Documents -maxdepth 3 -type f "
        "\\( -iname '*vibedrop*history*.json' -o -iname '*voicedrop*history*.json' -o -iname 'history.json' \\) "
        "2>/dev/null"
    )
    result = run([adb, "-s", serial, "shell", find_command], check=False, capture=True)
    paths = [line.strip() for line in result.stdout.splitlines() if line.strip().startswith("/")]
    return sorted(set(paths), reverse=True)


def load_android_public_exports(adb: str, serial: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]], str]:
    paths = find_android_public_exports(adb, serial)
    records: list[dict[str, Any]] = []
    stats: list[dict[str, Any]] = []
    statuses: list[str] = []
    for path in paths:
        result = run([adb, "-s", serial, "exec-out", "cat", path], check=False, capture=True)
        source_file = f"android-public:{serial}:{path}"
        if result.returncode != 0 or not result.stdout.strip():
            stat = android_history_stat(source_file)
            stat["bad_count"] = 1
            stats.append(stat)
            statuses.append(f"{path}:read_failed")
            continue
        parsed_records, stat, status = parse_android_history_payload(
            result.stdout,
            f"android-public:{serial}",
            source_file,
        )
        records.extend(parsed_records)
        stats.append(stat)
        statuses.append(f"{path}:{status}")
    if not paths:
        return records, stats, "public_exports:none"
    parsed_total = sum(stat["parsed_count"] for stat in stats)
    return records, stats, f"public_exports:{len(paths)}:parsed:{parsed_total}"


def load_android_histories(package: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]], str]:
    adb = shutil.which("adb")
    if not adb:
        return [], [], "not_imported:adb_not_found"
    devices_result = run([adb, "devices"], check=False, capture=True)
    devices: list[str] = []
    for raw in devices_result.stdout.splitlines()[1:]:
        parts = raw.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    if not devices:
        return [], [], "not_imported:no_adb_device"

    records: list[dict[str, Any]] = []
    stats: list[dict[str, Any]] = []
    statuses: list[str] = []
    for serial in devices:
        virtual_path = f"android:{serial}:{package}:files/history.json"
        result = run(
            [adb, "-s", serial, "shell", "run-as", package, "cat", "files/history.json"],
            check=False,
            capture=True,
        )
        if result.returncode != 0 or not result.stdout.strip():
            detail = (result.stderr or result.stdout or "").strip().splitlines()[:1]
            suffix = f":{detail[0]}" if detail else ""
            public_records, public_stats, public_status = load_android_public_exports(adb, serial)
            records.extend(public_records)
            stats.extend(public_stats)
            statuses.append(f"{serial}:private_not_imported{suffix};{public_status}")
            continue
        payload = result.stdout.strip()
        parsed_records, stat, status = parse_android_history_payload(payload, f"android:{serial}", virtual_path)
        records.extend(parsed_records)
        stats.append(stat)
        public_records, public_stats, public_status = load_android_public_exports(adb, serial)
        records.extend(public_records)
        stats.extend(public_stats)
        statuses.append(f"{serial}:private_{status};{public_status}")
    return records, stats, ";".join(statuses)


def load_vault_inbox_histories(vault_root: pathlib.Path) -> tuple[list[dict[str, Any]], list[dict[str, Any]], str]:
    inbox_root = vault_root / "inbox" / "android"
    if not inbox_root.exists():
        return [], [], "vault_inbox:none"

    records: list[dict[str, Any]] = []
    stats: list[dict[str, Any]] = []
    parsed_files = 0
    parsed_entries = 0
    for path in sorted(inbox_root.rglob("*.json")):
        try:
            payload = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            stat = android_history_stat(str(path))
            stat["bad_count"] = 1
            stats.append(stat)
            continue

        relative_source = path.relative_to(vault_root).as_posix()
        device_id = path.parent.name or "android"
        parsed_records, stat, status = parse_android_history_payload(
            payload,
            f"android-inbox:{device_id}",
            relative_source,
        )
        try:
            mtime = path.stat().st_mtime
            stat["mtime"] = dt.datetime.fromtimestamp(mtime).astimezone().isoformat(timespec="seconds")
        except OSError:
            pass
        records.extend(parsed_records)
        stats.append(stat)
        if status.startswith("imported:"):
            parsed_files += 1
            parsed_entries += stat["parsed_count"]

    return records, stats, f"vault_inbox:{parsed_files}:parsed:{parsed_entries}"


def media_candidates(entry: dict[str, Any]) -> list[dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    items = pick(entry, "items", "Items")
    if isinstance(items, list) and items:
        for index, raw_item in enumerate(items):
            if not isinstance(raw_item, dict):
                continue
            path_value = pick(raw_item, "image_path", "imagePath", "file_path", "filePath", "saved_path", "savedPath")
            thumbnail = pick(raw_item, "thumbnail_data_url", "thumbnailDataUrl")
            kind = normalize_kind(raw_item, normalize_kind(entry, "media"))
            file_name = pick(raw_item, "file_name", "fileName", "name") or pick(entry, "file_name", "fileName")
            mime_type = pick(raw_item, "mime_type", "mimeType")
            if path_value or thumbnail or file_name:
                candidates.append(
                    {
                        "item_index": index,
                        "kind": kind,
                        "file_name": file_name,
                        "mime_type": mime_type,
                        "original_path": path_value,
                        "saved_path": pick(raw_item, "saved_path", "savedPath"),
                        "thumbnail_data_url": thumbnail,
                        "status": pick(raw_item, "status"),
                    }
                )
        return candidates

    path_value = pick(entry, "image_path", "imagePath", "file_path", "filePath", "saved_path", "savedPath")
    thumbnail = pick(entry, "thumbnail_data_url", "thumbnailDataUrl")
    kind = normalize_kind(entry)
    file_name = pick(entry, "file_name", "fileName")
    mime_type = pick(entry, "mime_type", "mimeType")
    if path_value or thumbnail or kind in {"image", "file", "video", "media"}:
        candidates.append(
            {
                "item_index": 0,
                "kind": kind,
                "file_name": file_name,
                "mime_type": mime_type,
                "original_path": path_value,
                "saved_path": pick(entry, "saved_path", "savedPath"),
                "thumbnail_data_url": thumbnail,
                "status": pick(entry, "status"),
            }
        )
    return candidates


def copy_media_object(path: pathlib.Path, objects_root: pathlib.Path, mime_type: str | None) -> dict[str, Any]:
    digest, size = sha256_file(path)
    bucket = objects_root / digest[:2]
    bucket.mkdir(parents=True, exist_ok=True)
    existing = sorted(bucket.glob(f"{digest}.*"))
    if existing:
        dest = existing[0]
        ext = dest.suffix
        object_path = pathlib.Path("objects") / digest[:2] / dest.name
        created = False
    else:
        ext = safe_ext(path, mime_type)
        object_path = pathlib.Path("objects") / digest[:2] / f"{digest}{ext}"
        dest = objects_root.parent / object_path
        created = True
    dest.parent.mkdir(parents=True, exist_ok=True)
    if created:
        shutil.copy2(path, dest)
    return {
        "hash": digest,
        "size_bytes": size,
        "extension": ext,
        "object_path": object_path.as_posix(),
        "created": created,
    }


def upsert_history_entry(
    conn: sqlite3.Connection,
    snapshot_id: str,
    record: dict[str, Any],
    objects_root: pathlib.Path,
    now: str,
) -> dict[str, int]:
    entry = record["entry"]
    raw_line = record["raw_line"]
    entry_id = record_entry_id(record)
    text = pick(entry, "text", "message") or ""
    kind = normalize_kind(entry)
    thumbnail = pick(entry, "thumbnail_data_url", "thumbnailDataUrl")
    item_count = pick(entry, "item_count", "itemCount")
    if item_count is None and isinstance(pick(entry, "items", "Items"), list):
        item_count = len(pick(entry, "items", "Items"))
    device_roles = derive_device_roles(entry, record["source"])

    conn.execute(
        """
        INSERT INTO history_entries (
            entry_id, first_seen_at, last_seen_at, timestamp, source, source_file, line_no, text,
            client_ip, client_id, client_name, sender_id, sender_name, receiver_id, receiver_name,
            receiver_host, receiver_server_id, kind, file_name, direction, status, session_id,
            item_count, save_target, thumbnail_data_url, raw_json
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(entry_id) DO UPDATE SET
            last_seen_at=excluded.last_seen_at,
            timestamp=excluded.timestamp,
            source=excluded.source,
            source_file=excluded.source_file,
            line_no=excluded.line_no,
            text=excluded.text,
            client_ip=excluded.client_ip,
            client_id=excluded.client_id,
            client_name=excluded.client_name,
            sender_id=excluded.sender_id,
            sender_name=excluded.sender_name,
            receiver_id=excluded.receiver_id,
            receiver_name=excluded.receiver_name,
            receiver_host=excluded.receiver_host,
            receiver_server_id=excluded.receiver_server_id,
            kind=excluded.kind,
            file_name=excluded.file_name,
            direction=excluded.direction,
            status=excluded.status,
            session_id=excluded.session_id,
            item_count=excluded.item_count,
            save_target=excluded.save_target,
            thumbnail_data_url=excluded.thumbnail_data_url,
            raw_json=excluded.raw_json
        """,
        (
            entry_id,
            now,
            now,
            pick(entry, "timestamp", "time", "created_at", "createdAt"),
            record["source"],
            record["source_file"],
            record["line_no"],
            text,
            pick(entry, "client_ip", "clientIp"),
            pick(entry, "client_id", "clientId"),
            pick(entry, "client_name", "clientName"),
            device_roles["sender_id"],
            device_roles["sender_name"],
            device_roles["receiver_id"],
            device_roles["receiver_name"],
            device_roles["receiver_host"],
            device_roles["receiver_server_id"],
            kind,
            pick(entry, "file_name", "fileName"),
            pick(entry, "direction"),
            pick(entry, "status"),
            pick(entry, "session_id", "sessionId"),
            item_count,
            pick(entry, "save_target", "saveTarget"),
            thumbnail,
            json.dumps(entry, ensure_ascii=False, separators=(",", ":")),
        ),
    )
    conn.execute(
        "INSERT OR IGNORE INTO snapshot_entries (snapshot_id, entry_id) VALUES (?, ?)",
        (snapshot_id, entry_id),
    )
    conn.execute("DELETE FROM history_media WHERE entry_id = ?", (entry_id,))

    counts = {"media_reference_count": 0, "media_object_count": 0, "missing_media_count": 0}
    for item in media_candidates(entry):
        counts["media_reference_count"] += 1
        original_path = item.get("original_path")
        resolved_path, path_error = expand_local_path(original_path)
        object_hash = None
        object_path = None
        missing_reason = None
        if resolved_path is None:
            missing_reason = path_error
        elif not resolved_path.exists():
            missing_reason = "file-not-found"
        elif not resolved_path.is_file():
            missing_reason = "not-a-file"
        else:
            obj = copy_media_object(resolved_path, objects_root, item.get("mime_type"))
            object_hash = obj["hash"]
            object_path = obj["object_path"]
            counts["media_object_count"] += 1
            conn.execute(
                """
                INSERT OR IGNORE INTO media_objects (
                    object_hash, object_path, size_bytes, mime_type, extension, first_seen_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    obj["hash"],
                    obj["object_path"],
                    obj["size_bytes"],
                    item.get("mime_type") or mimetypes.guess_type(str(resolved_path))[0],
                    obj["extension"],
                    now,
                ),
            )
        if missing_reason:
            counts["missing_media_count"] += 1
        conn.execute(
            """
            INSERT INTO history_media (
                entry_id, item_index, kind, file_name, mime_type, original_path, saved_path,
                object_hash, object_path, thumbnail_data_url, status, missing_reason
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                entry_id,
                item["item_index"],
                item.get("kind"),
                item.get("file_name"),
                item.get("mime_type"),
                original_path,
                item.get("saved_path"),
                object_hash,
                object_path,
                item.get("thumbnail_data_url"),
                item.get("status"),
                missing_reason,
            ),
        )
    return counts


def generate_viewer(root: pathlib.Path, conn: sqlite3.Connection, report: dict[str, Any]) -> None:
    viewer_root = root / "viewer"
    data_root = viewer_root / "data"
    viewer_root.mkdir(parents=True, exist_ok=True)
    data_root.mkdir(parents=True, exist_ok=True)

    entries: dict[str, dict[str, Any]] = {}
    for row in conn.execute(
        """
        SELECT entry_id, timestamp, source, source_file, line_no, text, client_ip, client_id,
               client_name, sender_id, sender_name, receiver_id, receiver_name, receiver_host,
               receiver_server_id, kind, file_name, direction, status, save_target,
               thumbnail_data_url, raw_json
        FROM history_entries
        ORDER BY COALESCE(timestamp, '') DESC, entry_id DESC
        """
    ):
        (
            entry_id,
            timestamp,
            source,
            source_file,
            line_no,
            text,
            client_ip,
            client_id,
            client_name,
            sender_id,
            sender_name,
            receiver_id,
            receiver_name,
            receiver_host,
            receiver_server_id,
            kind,
            file_name,
            direction,
            status,
            save_target,
            thumbnail_data_url,
            raw_json,
        ) = row
        try:
            raw_entry = json.loads(raw_json)
        except (TypeError, json.JSONDecodeError):
            raw_entry = {}
        fallback_roles = derive_device_roles(raw_entry if isinstance(raw_entry, dict) else {}, source)
        entries[entry_id] = {
            "id": entry_id,
            "timestamp": timestamp,
            "source": source,
            "sourceFile": source_file,
            "lineNo": line_no,
            "text": text,
            "clientIp": client_ip,
            "clientId": client_id,
            "clientName": client_name,
            "senderId": sender_id or fallback_roles["sender_id"],
            "senderName": sender_name or fallback_roles["sender_name"],
            "receiverId": receiver_id or fallback_roles["receiver_id"],
            "receiverName": receiver_name or fallback_roles["receiver_name"],
            "receiverHost": receiver_host or fallback_roles["receiver_host"],
            "receiverServerId": receiver_server_id or fallback_roles["receiver_server_id"],
            "kind": kind or "text",
            "fileName": file_name,
            "direction": direction,
            "status": status,
            "saveTarget": save_target,
            "thumbnailDataUrl": thumbnail_data_url,
            "media": [],
        }

    for row in conn.execute(
        """
        SELECT hm.entry_id, hm.item_index, hm.kind, hm.file_name, hm.mime_type, hm.original_path,
               hm.saved_path, hm.object_hash, hm.object_path, hm.thumbnail_data_url, hm.status,
               hm.missing_reason, mo.size_bytes
        FROM history_media hm
        LEFT JOIN media_objects mo ON mo.object_hash = hm.object_hash
        ORDER BY hm.entry_id, hm.item_index
        """
    ):
        (
            entry_id,
            item_index,
            kind,
            file_name,
            mime_type,
            original_path,
            saved_path,
            object_hash,
            object_path,
            thumbnail_data_url,
            status,
            missing_reason,
            size_bytes,
        ) = row
        if entry_id not in entries:
            continue
        object_url = f"../{object_path}" if object_path else None
        entries[entry_id]["media"].append(
            {
                "itemIndex": item_index,
                "kind": kind,
                "fileName": file_name,
                "mimeType": mime_type,
                "originalPath": original_path,
                "savedPath": saved_path,
                "objectHash": object_hash,
                "objectPath": object_path,
                "objectUrl": object_url,
                "thumbnailDataUrl": thumbnail_data_url,
                "status": status,
                "missingReason": missing_reason,
                "sizeBytes": size_bytes,
            }
        )

    history_payload = {
        "generatedAt": iso_now(),
        "stats": report,
        "entries": list(entries.values()),
    }
    (data_root / "history.json").write_text(json.dumps(history_payload, ensure_ascii=False), encoding="utf-8")
    (data_root / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (viewer_root / "index.html").write_text(VIEWER_HTML, encoding="utf-8")
    (viewer_root / "style.css").write_text(VIEWER_CSS, encoding="utf-8")
    (viewer_root / "app.js").write_text(VIEWER_JS, encoding="utf-8")
    (root / "open-home-vault-viewer.webloc").write_text(build_webloc(LOCAL_VIEWER_URL), encoding="utf-8")
    (root / "open-home-vault-viewer.html").write_text(
        build_viewer_launcher_html(
            LOCAL_VIEWER_URL,
            [
                ("IP 备用入口", DEFAULT_VIEWER_IP_URL),
                ("局域网名称入口", DEFAULT_VIEWER_MDNS_URL),
                ("报告入口", report.get("viewerUrl") or DEFAULT_VIEWER_URL),
            ],
            "这个文件适合放在 Mac mini 的 Vault 根目录里，从 Finder 双击打开本机 viewer。",
            prefer_http_origin=True,
        ),
        encoding="utf-8",
    )


def prepare_remote(remote: str, remote_root: str) -> None:
    quoted = shlex.quote(remote_root)
    bucket_cmd = " ".join(shlex.quote(f"{remote_root}/objects/{bucket}") for bucket in OBJECT_BUCKETS)
    remote_shell(
        remote,
        f"mkdir -p {quoted}/db {quoted}/viewer/data {quoted}/manifests {quoted}/logs {quoted}/sources/macbook/mirror {bucket_cmd}",
    )


def fetch_remote_db(remote: str, remote_root: str, local_db: pathlib.Path) -> bool:
    remote_db = f"{remote_root}/db/vibedrop.sqlite"
    exists = remote_shell(remote, f"test -f {shlex.quote(remote_db)}", check=False).returncode == 0
    if not exists:
        return False
    local_db.parent.mkdir(parents=True, exist_ok=True)
    run(["scp", "-q", f"{remote}:{remote_db}", str(local_db)])
    return True


def sync_to_remote(staging_root: pathlib.Path, remote: str, remote_root: str, source_roots: list[pathlib.Path]) -> None:
    run(["rsync", "-az", f"{staging_root}/", f"{remote}:{remote_root}/"])
    mirror_root = f"{remote}:{remote_root}/sources/macbook/mirror"
    for source in source_roots:
        if not source.exists():
            continue
        name = source.name
        excludes = ["--exclude", "signing", "--exclude", "signing/**"]
        run(
            [
                "rsync",
                "-az",
                "--delete",
                *excludes,
                f"{source}/",
                f"{mirror_root}/{name}/",
            ]
        )


def start_remote_viewer(remote: str, remote_root: str, port: int) -> str:
    quoted_root = shlex.quote(remote_root)
    command = (
        f"mkdir -p {quoted_root}/logs; "
        f"if lsof -nP -iTCP:{port} -sTCP:LISTEN >/dev/null 2>&1; then "
        f"echo already-listening; "
        f"else cd {quoted_root} && nohup python3 -m http.server {port} --bind 0.0.0.0 "
        f"> logs/viewer-http.log 2>&1 & echo started; fi"
    )
    remote_shell(remote, command, check=False, capture=True)
    return f"http://192.168.3.2:{port}/viewer/"


def prune_remote_unreferenced_objects(remote: str, remote_root: str) -> int:
    quoted_root = shlex.quote(remote_root)
    command = f"""
set -e
cd {quoted_root}
refs=$(mktemp)
files=$(mktemp)
orphans=$(mktemp)
sqlite3 db/vibedrop.sqlite "select object_path from media_objects where object_path is not null" | LC_ALL=C sort -u > "$refs"
find objects -type f | LC_ALL=C sort > "$files"
comm -23 "$files" "$refs" > "$orphans"
count=$(wc -l < "$orphans" | tr -d ' ')
while IFS= read -r path; do
  [ -n "$path" ] && /bin/rm -f -- "$path"
done < "$orphans"
/bin/rm -f "$refs" "$files" "$orphans"
echo "$count"
"""
    result = remote_shell(remote, command, capture=True)
    try:
        return int(result.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        return 0


def write_source_stats(conn: sqlite3.Connection, snapshot_id: str, stats: list[dict[str, Any]]) -> None:
    for stat in stats:
        conn.execute(
            """
            INSERT OR REPLACE INTO source_files (
                snapshot_id, source_file, line_count, parsed_count, bad_count,
                sha256, size_bytes, mtime
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                snapshot_id,
                stat["source_file"],
                stat["line_count"],
                stat["parsed_count"],
                stat["bad_count"],
                stat.get("sha256"),
                stat.get("size_bytes"),
                stat.get("mtime"),
            ),
        )


def backfill_device_roles(conn: sqlite3.Connection) -> None:
    rows = conn.execute(
        """
        SELECT entry_id, source, raw_json
        FROM history_entries
        WHERE COALESCE(sender_name, '') = '' OR COALESCE(receiver_name, '') = ''
        """
    ).fetchall()
    for entry_id, source, raw_json in rows:
        try:
            entry = json.loads(raw_json)
        except (TypeError, json.JSONDecodeError):
            entry = {}
        if not isinstance(entry, dict):
            entry = {}
        roles = derive_device_roles(entry, source)
        conn.execute(
            """
            UPDATE history_entries
            SET sender_id = COALESCE(NULLIF(sender_id, ''), ?),
                sender_name = COALESCE(NULLIF(sender_name, ''), ?),
                receiver_id = COALESCE(NULLIF(receiver_id, ''), ?),
                receiver_name = COALESCE(NULLIF(receiver_name, ''), ?),
                receiver_host = COALESCE(NULLIF(receiver_host, ''), ?),
                receiver_server_id = COALESCE(NULLIF(receiver_server_id, ''), ?)
            WHERE entry_id = ?
            """,
            (
                roles["sender_id"],
                roles["sender_name"],
                roles["receiver_id"],
                roles["receiver_name"],
                roles["receiver_host"],
                roles["receiver_server_id"],
                entry_id,
            ),
        )


def canonicalize_receiver_names(conn: sqlite3.Connection) -> None:
    rows = conn.execute(
        """
        SELECT receiver_server_id,
               MAX(NULLIF(receiver_host, '')) AS preferred_host
        FROM history_entries
        WHERE COALESCE(receiver_server_id, '') != ''
        GROUP BY receiver_server_id
        """
    ).fetchall()
    for server_id, preferred_host in rows:
        if not preferred_host:
            continue
        conn.execute(
            """
            UPDATE history_entries
            SET receiver_name = ?
            WHERE receiver_server_id = ?
            """,
            (preferred_host, server_id),
        )


def build_report(conn: sqlite3.Connection, snapshot_id: str, args: argparse.Namespace, source_stats: list[dict[str, Any]], android_status: str, counts: dict[str, int]) -> dict[str, Any]:
    unique_entries = conn.execute("SELECT COUNT(*) FROM snapshot_entries WHERE snapshot_id = ?", (snapshot_id,)).fetchone()[0]
    total_entries = conn.execute("SELECT COUNT(*) FROM history_entries").fetchone()[0]
    total_objects = conn.execute("SELECT COUNT(*) FROM media_objects").fetchone()[0]
    source_line_count = sum(stat["line_count"] for stat in source_stats)
    parsed_count = sum(stat["parsed_count"] for stat in source_stats)
    bad_count = sum(stat["bad_count"] for stat in source_stats)
    remote = None if args.no_remote or args.local_vault_root else args.remote
    remote_root = None if args.no_remote or args.local_vault_root else args.remote_root
    viewer_url = args.viewer_url
    if not viewer_url and not args.no_remote and not args.local_vault_root and args.start_viewer:
        viewer_url = DEFAULT_VIEWER_URL
    return {
        "snapshotId": snapshot_id,
        "createdAt": iso_now(),
        "host": os.uname().nodename,
        "remote": remote,
        "remoteRoot": remote_root,
        "vaultRoot": str(args.local_vault_root) if args.local_vault_root else remote_root,
        "viewerUrl": viewer_url,
        "sourceLineCount": source_line_count,
        "parsedCount": parsed_count,
        "badLineCount": bad_count,
        "uniqueEntryCount": unique_entries,
        "totalEntryCountInDb": total_entries,
        "mediaReferenceCount": counts["media_reference_count"],
        "mediaObjectCountThisRun": counts["media_object_count"],
        "missingMediaCount": counts["missing_media_count"],
        "totalObjectCountInDb": total_objects,
        "androidStatus": android_status,
        "sourceFiles": source_stats,
        "sensitiveExclusions": ["~/.vibedrop/signing"],
        "notes": "MacBook desktop history was migrated. Android private history is read when adb run-as is allowed; otherwise public exported VibeDrop history JSON files are imported when found.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Sync VibeDrop history into a Mac mini Home Vault.")
    parser.add_argument("--remote", default=DEFAULT_REMOTE)
    parser.add_argument("--remote-root", default=DEFAULT_REMOTE_ROOT)
    parser.add_argument("--staging", default=str(pathlib.Path(tempfile.gettempdir()) / "vibedrop-home-vault-staging"))
    parser.add_argument("--local-vault-root", default="", help="Update an existing vault in place instead of staging and rsyncing.")
    parser.add_argument("--no-remote", action="store_true", help="Build the vault locally without rsyncing to the Mac mini.")
    parser.add_argument("--skip-local-history", action="store_true", help="Do not read ~/.vibedrop or ~/.voicedrop history.")
    parser.add_argument("--include-vault-inbox", action="store_true", help="Read VibeDropVault/inbox/android exported JSON files.")
    parser.add_argument("--start-viewer", action="store_true", help="Start a simple HTTP viewer on the Mac mini.")
    parser.add_argument("--viewer-port", type=int, default=8787)
    parser.add_argument("--viewer-url", default="", help="Viewer URL to write into report.json.")
    parser.add_argument("--android-package", default=DEFAULT_ANDROID_PACKAGE)
    parser.add_argument("--skip-android", action="store_true")
    parser.add_argument(
        "--prune-unreferenced-objects",
        action="store_true",
        help="Remove remote object files that are not referenced by db/vibedrop.sqlite.",
    )
    args = parser.parse_args()

    local_vault_root = pathlib.Path(args.local_vault_root).expanduser().resolve() if args.local_vault_root else None
    args.local_vault_root = str(local_vault_root) if local_vault_root else ""
    if local_vault_root:
        staging_root = local_vault_root
    else:
        staging_root = pathlib.Path(args.staging).expanduser().resolve()
        if staging_root.exists():
            if "vibedrop-home-vault-staging" not in staging_root.name:
                raise RuntimeError(f"refusing to remove unexpected staging path: {staging_root}")
            shutil.rmtree(staging_root)
    ensure_vault_dirs(staging_root)

    db_path = staging_root / "db" / "vibedrop.sqlite"
    if not args.no_remote and not local_vault_root:
        prepare_remote(args.remote, args.remote_root)
        fetch_remote_db(args.remote, args.remote_root, db_path)

    conn = sqlite3.connect(db_path)
    init_db(conn)
    snapshot_id = f"{dt.datetime.now().strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:8]}"
    now = iso_now()

    home = pathlib.Path.home()
    conn.execute(
        """
        INSERT OR IGNORE INTO snapshots (
            snapshot_id, created_at, host, source_root, history_line_count, parsed_count,
            unique_entry_count, media_reference_count, media_object_count, missing_media_count,
            android_status, notes
        ) VALUES (?, ?, ?, ?, 0, 0, 0, 0, 0, 0, ?, ?)
        """,
        (
            snapshot_id,
            now,
            os.uname().nodename,
            str(home),
            "pending",
            "snapshot placeholder created before child rows",
        ),
    )
    vibedrop_root = home / ".vibedrop"
    voicedrop_root = home / ".voicedrop"
    records: list[dict[str, Any]] = []
    source_stats: list[dict[str, Any]] = []
    if not args.skip_local_history:
        for path, source in [
            (vibedrop_root / "history.jsonl", "macbook:.vibedrop"),
            (voicedrop_root / "history.jsonl", "macbook:.voicedrop"),
        ]:
            loaded, stat = read_jsonl(path, source)
            records.extend(loaded)
            source_stats.append(stat)

    android_status = "skipped"
    if not args.skip_android:
        android_records, android_stats, android_status = load_android_histories(args.android_package)
        records.extend(android_records)
        source_stats.extend(android_stats)
    if args.include_vault_inbox:
        inbox_records, inbox_stats, inbox_status = load_vault_inbox_histories(staging_root)
        records.extend(inbox_records)
        source_stats.extend(inbox_stats)
        android_status = f"{android_status};{inbox_status}" if android_status else inbox_status

    write_source_stats(conn, snapshot_id, source_stats)

    seen_entry_ids: set[str] = set()
    counts = {"media_reference_count": 0, "media_object_count": 0, "missing_media_count": 0}
    for record in records:
        entry_id = record_entry_id(record)
        if entry_id in seen_entry_ids:
            continue
        seen_entry_ids.add(entry_id)
        current_counts = upsert_history_entry(conn, snapshot_id, record, staging_root / "objects", now)
        for key, value in current_counts.items():
            counts[key] += value
    backfill_device_roles(conn)
    canonicalize_receiver_names(conn)

    report = build_report(conn, snapshot_id, args, source_stats, android_status, counts)
    conn.execute(
        """
        UPDATE snapshots
        SET created_at = ?,
            host = ?,
            source_root = ?,
            history_line_count = ?,
            parsed_count = ?,
            unique_entry_count = ?,
            media_reference_count = ?,
            media_object_count = ?,
            missing_media_count = ?,
            android_status = ?,
            notes = ?
        WHERE snapshot_id = ?
        """,
        (
            report["createdAt"],
            report["host"],
            str(home),
            report["sourceLineCount"],
            report["parsedCount"],
            report["uniqueEntryCount"],
            report["mediaReferenceCount"],
            report["mediaObjectCountThisRun"],
            report["missingMediaCount"],
            report["androidStatus"],
            report["notes"],
            snapshot_id,
        ),
    )
    conn.commit()

    manifest_path = staging_root / "manifests" / f"{snapshot_id}.json"
    manifest_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    generate_viewer(staging_root, conn, report)
    conn.close()

    if not args.no_remote and not local_vault_root:
        sync_to_remote(staging_root, args.remote, args.remote_root, [vibedrop_root, voicedrop_root])
        if args.prune_unreferenced_objects:
            report["prunedRemoteObjects"] = prune_remote_unreferenced_objects(args.remote, args.remote_root)
        if args.start_viewer:
            report["viewerUrl"] = start_remote_viewer(args.remote, args.remote_root, args.viewer_port)
        if args.prune_unreferenced_objects or args.start_viewer:
            (staging_root / "viewer" / "data" / "report.json").write_text(
                json.dumps(report, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
            run(["rsync", "-az", f"{staging_root}/viewer/data/report.json", f"{args.remote}:{args.remote_root}/viewer/data/report.json"])

    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


VIEWER_HTML = """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>VibeDrop Home Vault</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <header class="topbar">
    <div>
      <p class="eyebrow">Home Vault</p>
      <h1>VibeDrop 历史查看器</h1>
    </div>
    <div class="sync-meta" id="syncMeta">读取中...</div>
  </header>
  <main>
    <section class="summary" id="summary"></section>
    <section class="toolbar">
      <input id="searchInput" type="search" placeholder="搜索文本、文件名、设备、路径">
      <select id="senderFilter" aria-label="发送端筛选">
        <option value="all">全部发送端</option>
      </select>
      <select id="receiverFilter" aria-label="接收端筛选">
        <option value="all">全部接收端</option>
      </select>
      <select id="kindFilter" aria-label="类型筛选">
        <option value="all">全部类型</option>
        <option value="text">文本</option>
        <option value="image">图片</option>
        <option value="video">视频</option>
        <option value="file">文件</option>
        <option value="missing">无预览媒体</option>
      </select>
      <button id="resetButton" type="button">重置</button>
    </section>
    <section class="status-line" id="statusLine"></section>
    <section class="timeline" id="timeline"></section>
  </main>
  <template id="entryTemplate">
    <article class="entry">
      <div class="entry-main">
        <time></time>
        <div class="entry-title"></div>
        <div class="entry-text"></div>
        <div class="entry-meta"></div>
      </div>
      <div class="media-strip"></div>
    </article>
  </template>
  <script src="app.js"></script>
</body>
</html>
"""


VIEWER_CSS = """* {
  box-sizing: border-box;
}

body {
  margin: 0;
  min-height: 100vh;
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", sans-serif;
  color: #101827;
  background: #f4f7fb;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 26px clamp(20px, 4vw, 52px);
  background: rgba(255, 255, 255, 0.92);
  border-bottom: 1px solid #e5e9f0;
  backdrop-filter: blur(18px);
}

.eyebrow {
  margin: 0 0 4px;
  color: #647086;
  font-weight: 700;
  letter-spacing: 0;
}

h1 {
  margin: 0;
  font-size: clamp(28px, 4vw, 44px);
  line-height: 1.05;
}

.sync-meta {
  max-width: 560px;
  color: #667085;
  font-size: 14px;
  line-height: 1.45;
  text-align: right;
  overflow-wrap: anywhere;
}

main {
  width: min(1180px, calc(100vw - 32px));
  margin: 28px auto 72px;
}

.summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 18px;
}

.metric {
  padding: 14px 16px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
}

.metric-label {
  margin: 0 0 6px;
  color: #768197;
  font-size: 13px;
  font-weight: 700;
}

.metric-value {
  margin: 0;
  font-size: 24px;
  font-weight: 800;
}

.toolbar {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) 180px 180px 180px 100px;
  gap: 10px;
  margin: 18px 0 10px;
}

input,
select,
button {
  min-height: 46px;
  border: 1px solid #d8dee8;
  border-radius: 8px;
  background: #fff;
  color: #101827;
  font: inherit;
}

input,
select {
  padding: 0 14px;
}

button {
  cursor: pointer;
  font-weight: 750;
}

.status-line {
  min-height: 28px;
  color: #667085;
  font-size: 14px;
}

.timeline {
  display: grid;
  gap: 12px;
}

.entry {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(180px, 280px);
  gap: 16px;
  padding: 16px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
}

.entry time {
  display: block;
  margin-bottom: 6px;
  color: #667085;
  font-size: 13px;
  font-weight: 700;
}

.entry-title {
  margin-bottom: 8px;
  font-size: 17px;
  font-weight: 800;
  overflow-wrap: anywhere;
}

.entry-text {
  color: #1f2937;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.entry-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.pill {
  display: inline-flex;
  align-items: center;
  min-height: 26px;
  padding: 0 9px;
  border-radius: 999px;
  background: #eef2f7;
  color: #536177;
  font-size: 12px;
  font-weight: 700;
}

.media-strip {
  display: grid;
  gap: 10px;
  align-content: start;
}

.media-item {
  overflow: hidden;
  border: 1px solid #e5e9f0;
  border-radius: 8px;
  background: #f8fafc;
}

.media-item img {
  display: block;
  width: 100%;
  max-height: 240px;
  object-fit: contain;
  background: #111827;
}

.media-caption {
  padding: 9px 10px;
  color: #536177;
  font-size: 12px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.missing {
  border-color: #fecaca;
  background: #fff7f7;
}

.empty {
  padding: 36px;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  color: #667085;
  text-align: center;
}

@media (max-width: 820px) {
  .topbar,
  .entry {
    display: block;
  }

  .sync-meta {
    margin-top: 10px;
    text-align: left;
  }

  .summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .toolbar {
    grid-template-columns: 1fr;
  }

  .media-strip {
    margin-top: 14px;
  }
}
"""


VIEWER_JS = """const state = {
  entries: [],
  filtered: [],
  report: null,
};

const fmt = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
});

function text(value) {
  return value == null || value === '' ? '无' : String(value);
}

function escapeHtml(value) {
  return text(value).replace(/[&<>"']/g, (char) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  }[char]));
}

function safeSrc(value) {
  if (!value) return '';
  const src = String(value);
  if (src.startsWith('../objects/') || src.startsWith('data:image/')) return src;
  return '';
}

function formatTime(value) {
  if (!value) return '无时间';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return fmt.format(date);
}

function metric(label, value) {
  return `<div class="metric"><p class="metric-label">${label}</p><p class="metric-value">${value}</p></div>`;
}

function parsedStatusCount(status, marker) {
  const match = String(status || '').match(new RegExp(`${marker}:(\\\\d+):parsed:(\\\\d+)`));
  if (!match) return null;
  return {
    files: Number(match[1]),
    entries: Number(match[2]),
  };
}

function formatAndroidStatus(report) {
  const status = String(report.androidStatus || '').trim();
  const inbox = parsedStatusCount(status, 'vault_inbox');
  if (inbox) {
    return inbox.files > 0
      ? `Android 上传：${inbox.files} 份 / ${inbox.entries} 条`
      : 'Android 上传：暂无';
  }

  const publicExport = parsedStatusCount(status, 'public_exports');
  if (publicExport) {
    return publicExport.files > 0
      ? `Android 导出：${publicExport.files} 份 / ${publicExport.entries} 条`
      : 'Android 导出：暂无';
  }

  const privateImport = status.match(/private_imported:(\\d+)/);
  if (privateImport) {
    return `Android 直读：${Number(privateImport[1])} 条`;
  }

  if (!status || status === 'skipped') {
    return 'Android：本次未直读手机';
  }
  if (status.includes('no_adb_device')) {
    return 'Android：未连接调试设备';
  }
  if (status.includes('adb_not_found')) {
    return 'Android：ADB 不可用';
  }
  if (status.includes('private_not_imported')) {
    return 'Android：私有历史未直读';
  }
  return `Android：${status}`;
}

function formatVaultLocation(report) {
  const vaultRoot = report.vaultRoot || report.remoteRoot || '';
  if (vaultRoot) return `Vault：${vaultRoot}`;
  if (report.viewerUrl) return `Viewer：${report.viewerUrl}`;
  return 'Vault：本地';
}

function formatSyncMeta(report) {
  return [
    `最近刷新：${formatTime(report.createdAt)}`,
    formatAndroidStatus(report),
    formatVaultLocation(report),
  ].filter(Boolean).join(' · ');
}

function deviceDisplayName(name, id) {
  return name || id || '未知设备';
}

function deviceFilterValue(name, id) {
  return deviceDisplayName(name, id);
}

function populateDeviceFilter(selectId, entries, role) {
  const select = document.getElementById(selectId);
  const values = new Set();
  entries.forEach((entry) => {
    const label = role === 'sender'
      ? deviceFilterValue(entry.senderName, entry.senderId)
      : deviceFilterValue(entry.receiverName, entry.receiverId);
    if (label && label !== '未知设备') {
      values.add(label);
    }
  });
  const selected = select.value || 'all';
  const options = ['all', ...Array.from(values).sort((a, b) => a.localeCompare(b, 'zh-CN'))];
  select.innerHTML = options.map((value) => {
    const label = value === 'all'
      ? role === 'sender' ? '全部发送端' : '全部接收端'
      : value;
    return `<option value="${escapeHtml(value)}">${escapeHtml(label)}</option>`;
  }).join('');
  select.value = options.includes(selected) ? selected : 'all';
}

function populateDeviceFilters() {
  populateDeviceFilter('senderFilter', state.entries, 'sender');
  populateDeviceFilter('receiverFilter', state.entries, 'receiver');
}

function renderSummary(report) {
  const totalEntries = report.totalEntryCountInDb ?? report.uniqueEntryCount ?? 0;
  const previewMissingCount = state.entries.reduce((count, entry) => {
    return count + entry.media.filter((item) => !item.objectUrl && !item.thumbnailDataUrl).length;
  }, 0);
  document.getElementById('summary').innerHTML = [
    metric('历史记录', totalEntries),
    metric('源文件行数', report.sourceLineCount ?? 0),
    metric('媒体引用', report.mediaReferenceCount ?? 0),
    metric('无预览媒体', previewMissingCount),
    metric('对象总数', report.totalObjectCountInDb ?? 0),
  ].join('');
  document.getElementById('syncMeta').textContent = formatSyncMeta(report);
}

function entryMatches(entry, query, kind, senderFilter, receiverFilter) {
  const haystack = [
    entry.timestamp,
    entry.text,
    entry.clientName,
    entry.clientIp,
    entry.senderName,
    entry.senderId,
    entry.receiverName,
    entry.receiverId,
    entry.receiverHost,
    entry.receiverServerId,
    entry.kind,
    entry.fileName,
    entry.source,
    entry.sourceFile,
    ...entry.media.flatMap((item) => [
      item.fileName,
      item.originalPath,
      item.savedPath,
      item.objectPath,
      item.missingReason,
    ]),
  ].filter(Boolean).join('\\n').toLowerCase();
  const queryOk = !query || haystack.includes(query);
  if (!queryOk) return false;
  const senderOk = senderFilter === 'all' || deviceFilterValue(entry.senderName, entry.senderId) === senderFilter;
  if (!senderOk) return false;
  const receiverOk = receiverFilter === 'all' || deviceFilterValue(entry.receiverName, entry.receiverId) === receiverFilter;
  if (!receiverOk) return false;
  if (kind === 'all') return true;
  if (kind === 'missing') return entry.media.some((item) => !item.objectUrl && !item.thumbnailDataUrl);
  if (kind === 'text') return (entry.kind || 'text') === 'text' && entry.media.length === 0;
  return (entry.kind || '').includes(kind) || entry.media.some((item) => (item.kind || '').includes(kind));
}

function renderMedia(item) {
  const src = safeSrc(item.objectUrl || item.thumbnailDataUrl);
  const hasObject = Boolean(item.objectUrl);
  const hasThumbnail = Boolean(item.thumbnailDataUrl);
  const isMissingPreview = !hasObject && !hasThumbnail;
  const missingClass = isMissingPreview ? ' missing' : '';
  const availability = (() => {
    if (!isMissingPreview || !item.missingReason) return '';
    if (item.missingReason === 'no-path') {
      return '无预览: 历史里没有文件路径';
    }
    if (item.missingReason === 'file-not-found') {
      return '无预览: 原路径找不到';
    }
    return `无预览: ${item.missingReason}`;
  })();
  const caption = [
    item.fileName || item.kind || '媒体',
    item.sizeBytes ? `${Math.round(item.sizeBytes / 1024)} KB` : '',
    availability,
    item.originalPath || '',
  ].filter(Boolean).join(' · ');
  const preview = src
    ? `<a href="${src}" target="_blank" rel="noreferrer"><img src="${src}" alt="${escapeHtml(item.fileName || item.kind)}"></a>`
    : '';
  return `<div class="media-item${missingClass}">${preview}<div class="media-caption">${escapeHtml(caption)}</div></div>`;
}

function renderEntries() {
  const timeline = document.getElementById('timeline');
  document.getElementById('statusLine').textContent =
    `显示 ${state.filtered.length} / ${state.entries.length} 条`;
  if (!state.filtered.length) {
    timeline.innerHTML = '<div class="empty">没有匹配的历史记录</div>';
    return;
  }
  timeline.innerHTML = state.filtered.map((entry) => {
    const title = entry.fileName || entry.media[0]?.fileName || entry.kind || '文本';
    const body = entry.text || '';
    const senderLabel = deviceDisplayName(entry.senderName, entry.senderId);
    const receiverLabel = deviceDisplayName(entry.receiverName, entry.receiverId);
    const meta = [
      entry.kind || 'text',
      senderLabel !== '未知设备' ? `发送端 ${senderLabel}` : '',
      receiverLabel !== '未知设备' ? `接收端 ${receiverLabel}` : '',
      entry.status,
      entry.source,
      entry.sourceFile ? `line ${entry.lineNo}` : '',
    ].filter(Boolean).map((item) => `<span class="pill">${escapeHtml(item)}</span>`).join('');
    const media = entry.media.map(renderMedia).join('');
    return `<article class="entry">
      <div class="entry-main">
        <time>${escapeHtml(formatTime(entry.timestamp))}</time>
        <div class="entry-title">${escapeHtml(title)}</div>
        <div class="entry-text">${escapeHtml(body)}</div>
        <div class="entry-meta">${meta}</div>
      </div>
      <div class="media-strip">${media}</div>
    </article>`;
  }).join('');
}

function applyFilters() {
  const query = document.getElementById('searchInput').value.trim().toLowerCase();
  const kind = document.getElementById('kindFilter').value;
  const sender = document.getElementById('senderFilter').value;
  const receiver = document.getElementById('receiverFilter').value;
  state.filtered = state.entries.filter((entry) => entryMatches(entry, query, kind, sender, receiver));
  renderEntries();
}

async function main() {
  const response = await fetch('data/history.json', { cache: 'no-store' });
  const payload = await response.json();
  state.report = payload.stats || {};
  state.entries = payload.entries || [];
  state.filtered = state.entries;
  populateDeviceFilters();
  renderSummary(state.report);
  renderEntries();
  document.getElementById('searchInput').addEventListener('input', applyFilters);
  document.getElementById('kindFilter').addEventListener('change', applyFilters);
  document.getElementById('senderFilter').addEventListener('change', applyFilters);
  document.getElementById('receiverFilter').addEventListener('change', applyFilters);
  document.getElementById('resetButton').addEventListener('click', () => {
    document.getElementById('searchInput').value = '';
    document.getElementById('kindFilter').value = 'all';
    document.getElementById('senderFilter').value = 'all';
    document.getElementById('receiverFilter').value = 'all';
    applyFilters();
  });
}

main().catch((error) => {
  document.getElementById('timeline').innerHTML = `<div class="empty">读取失败: ${error.message}</div>`;
});
"""


if __name__ == "__main__":
    raise SystemExit(main())
