#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
MOBILE_TAURI_DIR="$ROOT_DIR/mobile/src-tauri"
IOS_PROJECT_PATH="$MOBILE_TAURI_DIR/gen/apple/vibedrop-mobile.xcodeproj"

TEAM_ID="${APPLE_DEVELOPMENT_TEAM:-}"
DEVICE_NAME="${IOS_DEVICE_NAME:-}"
DEVICE_ID="${IOS_DEVICE_ID:-}"
OPEN_XCODE=0
SKIP_INIT=0
LIST_ONLY=0
RELEASE=0
PRODUCT_NAME="VibeDrop"
BUNDLE_ID="com.vibedrop.mobile"

usage() {
  cat <<'EOF'
Usage: ./scripts/deploy-ios.sh [options]

Initializes the iOS project if needed, validates Personal Team signing inputs,
and runs the VibeDrop iOS app on a connected iPhone.

Options:
  --team <TEAM_ID>      Apple Personal Team / Development Team ID
  --device <name>       iPhone device name shown by Xcode / devicectl
  --device-id <id>      iPhone UDID / CoreDevice identifier for exact install
  --open-xcode          Only open the generated Xcode project
  --skip-init           Skip `cargo tauri ios init --ci`
  --list-devices        Only print connected iOS devices and exit
  --release             Run the app in release mode
  -h, --help            Show this help

Environment:
  APPLE_DEVELOPMENT_TEAM   Default Team ID if --team is omitted
  IOS_DEVICE_NAME          Default device name if --device is omitted
  IOS_DEVICE_ID            Default exact device identifier if --device-id is omitted
EOF
}

log() {
  printf '[deploy-ios] %s\n' "$1"
}

fail() {
  printf '[deploy-ios] %s\n' "$1" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

ensure_ios_project() {
  [[ -d "$IOS_PROJECT_PATH" ]] && return
  log "iOS project missing, running cargo tauri ios init --ci"
  (
    cd "$MOBILE_TAURI_DIR"
    cargo tauri ios init --ci
  )
}

print_connected_devices() {
  xcrun devicectl list devices
}

find_latest_built_app() {
  local config="$1"
  local min_mtime="$2"
  local search_root="$HOME/Library/Developer/Xcode/DerivedData"

  [[ -d "$search_root" ]] || return 1

  find "$search_root" -type d -path "*/Build/Products/${config}-iphoneos/${PRODUCT_NAME}.app" -print 2>/dev/null |
    while IFS= read -r app_path; do
      local app_mtime
      app_mtime="$(stat -f '%m' "$app_path")" || continue
      [[ "$app_mtime" -ge "$min_mtime" ]] || continue
      printf '%s\t%s\n' "$app_mtime" "$app_path"
    done |
    sort -rn |
    head -n 1 |
    cut -f 2-
}

install_latest_built_app() {
  local config="$1"
  local min_mtime="$2"
  local device_selector="${DEVICE_ID:-$DEVICE_NAME}"

  [[ -n "$device_selector" ]] || fail "Cannot direct-install without --device or --device-id."

  local app_path
  app_path="$(find_latest_built_app "$config" "$min_mtime")"
  [[ -n "$app_path" ]] || return 1

  log "Standard Tauri run failed after build; installing freshly built app directly: $app_path"
  xcrun devicectl device install app --device "$device_selector" "$app_path" --timeout 120
  if ! xcrun devicectl device process launch --device "$device_selector" --terminate-existing "$BUNDLE_ID" --timeout 60; then
    log "App installed, but automatic launch failed. Unlock the iPhone and open VibeDrop manually if needed."
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --team)
      [[ $# -ge 2 ]] || fail "--team requires a Team ID"
      TEAM_ID="$2"
      shift 2
      ;;
    --device)
      [[ $# -ge 2 ]] || fail "--device requires a device name"
      DEVICE_NAME="$2"
      shift 2
      ;;
    --device-id)
      [[ $# -ge 2 ]] || fail "--device-id requires a device identifier"
      DEVICE_ID="$2"
      shift 2
      ;;
    --open-xcode)
      OPEN_XCODE=1
      shift
      ;;
    --skip-init)
      SKIP_INIT=1
      shift
      ;;
    --list-devices)
      LIST_ONLY=1
      shift
      ;;
    --release)
      RELEASE=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "Unknown option: $1"
      ;;
  esac
done

require_cmd cargo
require_cmd xcodebuild
require_cmd xcrun
require_cmd open

if [[ $SKIP_INIT -eq 0 ]]; then
  ensure_ios_project
fi

if [[ $LIST_ONLY -eq 1 ]]; then
  print_connected_devices
  exit 0
fi

[[ -d "$IOS_PROJECT_PATH" ]] || fail "iOS project not found: $IOS_PROJECT_PATH"

if [[ $OPEN_XCODE -eq 1 ]]; then
  log "Opening Xcode project"
  open "$IOS_PROJECT_PATH"
  exit 0
fi

[[ -n "$TEAM_ID" ]] || fail "Missing Team ID. First sign in to Xcode -> Settings -> Accounts, then re-run with --team <TEAM_ID> or APPLE_DEVELOPMENT_TEAM=<TEAM_ID>."

log "Connected iOS devices:"
print_connected_devices || true

IOS_ARGS=(ios run)
BUILD_CONFIG="debug"
if [[ $RELEASE -eq 1 ]]; then
  IOS_ARGS+=(--release)
  BUILD_CONFIG="release"
fi
[[ -n "${DEVICE_NAME:-$DEVICE_ID}" ]] && IOS_ARGS+=("${DEVICE_NAME:-$DEVICE_ID}")

log "Running VibeDrop iOS with Team ID $TEAM_ID"
RUN_STARTED_AT="$(date +%s)"
if ! (
  cd "$MOBILE_TAURI_DIR"
  APPLE_DEVELOPMENT_TEAM="$TEAM_ID" cargo tauri "${IOS_ARGS[@]}"
); then
  install_latest_built_app "$BUILD_CONFIG" "$RUN_STARTED_AT" || fail "Tauri iOS run failed and no fresh built app was available for direct install."
fi
