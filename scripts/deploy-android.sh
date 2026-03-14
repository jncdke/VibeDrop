#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
MOBILE_DIR="$ROOT_DIR/mobile"
DESKTOP_STATIC_DIR="$ROOT_DIR/desktop/static"

PACKAGE_NAME="${PACKAGE_NAME:-com.vibedrop.mobile}"
MAIN_ACTIVITY="${MAIN_ACTIVITY:-.MainActivity}"
TARGET="${TARGET:-aarch64}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
KEYSTORE_PATH="${VIBEDROP_KEYSTORE_PATH:-$HOME/.android/vibedrop.keystore}"
KEYSTORE_PASS="${VIBEDROP_KEYSTORE_PASS:-pass:vibedrop123}"

UNSIGNED_APK="$MOBILE_DIR/src-tauri/gen/android/app/build/outputs/apk/universal/release/app-universal-release-unsigned.apk"
SIGNED_APK="$MOBILE_DIR/src-tauri/gen/android/app/build/outputs/apk/universal/release/VibeDrop-signed.apk"

DEVICE_SERIAL="${ADB_SERIAL:-}"
SYNC_STATIC=0
SKIP_BUILD=0
SKIP_INSTALL=0

usage() {
  cat <<'EOF'
Usage: ./scripts/deploy-android.sh [options]

Builds, signs, installs, and restarts the Android app on a connected device.

Options:
  --device <serial>   Use the specified ADB device serial
  --sync-static       Sync mobile/src shared frontend files into desktop/static first
  --skip-build        Reuse the existing unsigned APK and only sign/install
  --skip-install      Stop after signing the APK
  -h, --help          Show this help

Environment:
  ANDROID_HOME                Android SDK root
  ADB_SERIAL                  Default device serial if --device is omitted
  VIBEDROP_KEYSTORE_PATH      Keystore path
  VIBEDROP_KEYSTORE_PASS      Keystore password, default: pass:vibedrop123
  PACKAGE_NAME                Android package name, default: com.vibedrop.mobile
  MAIN_ACTIVITY               Launch activity, default: .MainActivity
  TARGET                      cargo tauri android build target, default: aarch64
EOF
}

log() {
  printf '[deploy-android] %s\n' "$1"
}

fail() {
  printf '[deploy-android] %s\n' "$1" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

sync_static_assets() {
  local files
  files=(
    app.js
    index.html
    style.css
    manifest.json
    icon.png
    icon-192.png
    icon-512.png
  )

  for file in "${files[@]}"; do
    cp "$MOBILE_DIR/src/$file" "$DESKTOP_STATIC_DIR/$file"
  done

  log "Synced shared mobile frontend into desktop/static"
}

find_apksigner() {
  local build_tools_root apksigner
  build_tools_root="$ANDROID_HOME/build-tools"
  [[ -d "$build_tools_root" ]] || fail "ANDROID_HOME/build-tools not found: $build_tools_root"

  apksigner="$(
    find "$build_tools_root" -maxdepth 2 -type f -name apksigner 2>/dev/null \
      | sort \
      | tail -1
  )"

  [[ -n "$apksigner" ]] || fail "Unable to locate apksigner under $build_tools_root"
  printf '%s\n' "$apksigner"
}

resolve_device_serial() {
  local devices line
  devices=()

  while IFS= read -r line; do
    [[ -n "$line" ]] && devices+=("$line")
  done < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')

  if [[ -n "$DEVICE_SERIAL" ]]; then
    printf '%s\n' "$DEVICE_SERIAL"
    return
  fi

  if [[ ${#devices[@]} -eq 0 ]]; then
    fail "No connected Android device found via adb"
  fi

  if [[ ${#devices[@]} -gt 1 ]]; then
    fail "Multiple devices detected. Re-run with --device <serial> or set ADB_SERIAL"
  fi

  printf '%s\n' "${devices[0]}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      [[ $# -ge 2 ]] || fail "--device requires a serial"
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    --sync-static)
      SYNC_STATIC=1
      shift
      ;;
    --skip-build)
      SKIP_BUILD=1
      shift
      ;;
    --skip-install)
      SKIP_INSTALL=1
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
require_cmd adb

[[ -f "$KEYSTORE_PATH" ]] || fail "Keystore not found: $KEYSTORE_PATH"

if [[ $SYNC_STATIC -eq 1 ]]; then
  sync_static_assets
fi

if [[ $SKIP_BUILD -eq 0 ]]; then
  log "Building Android APK for target $TARGET"
  (
    cd "$MOBILE_DIR"
    cargo tauri android build --target "$TARGET"
  )
fi

[[ -f "$UNSIGNED_APK" ]] || fail "Unsigned APK not found: $UNSIGNED_APK"

APKSIGNER="$(find_apksigner)"
log "Signing APK with $(basename "$(dirname "$APKSIGNER")")"
"$APKSIGNER" sign \
  --ks "$KEYSTORE_PATH" \
  --ks-pass "$KEYSTORE_PASS" \
  --out "$SIGNED_APK" \
  "$UNSIGNED_APK"

[[ $SKIP_INSTALL -eq 0 ]] || {
  log "Signed APK ready at $SIGNED_APK"
  exit 0
}

DEVICE_SERIAL="$(resolve_device_serial)"
ADB_ARGS=(adb -s "$DEVICE_SERIAL")

log "Installing APK to device $DEVICE_SERIAL"
"${ADB_ARGS[@]}" install -r "$SIGNED_APK"

log "Restarting $PACKAGE_NAME/$MAIN_ACTIVITY"
"${ADB_ARGS[@]}" shell am start -S -n "$PACKAGE_NAME/$MAIN_ACTIVITY"

log "Done"
