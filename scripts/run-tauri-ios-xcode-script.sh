#!/usr/bin/env bash
set -euo pipefail

export PATH="$HOME/.cargo/bin:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:$PATH"

if ! command -v cargo >/dev/null 2>&1; then
  echo "[run-tauri-ios-xcode-script] cargo not found in PATH: $PATH" >&2
  exit 127
fi

exec cargo tauri ios xcode-script "$@"
