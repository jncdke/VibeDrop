#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
NATIVE_ANDROID_DIR="$ROOT_DIR/native/android"
GRADLEW="$ROOT_DIR/mobile/src-tauri/gen/android/gradlew"
DEFAULT_JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
KEYSTORE_PATH="${VIBEDROP_KEYSTORE_PATH:-$HOME/.android/vibedrop.keystore}"
KEYSTORE_PASS="${VIBEDROP_KEYSTORE_PASS:-pass:vibedrop123}"
PACKAGE_NAME="${PACKAGE_NAME:-com.vibedrop.mobile}"
MAIN_ACTIVITY="${MAIN_ACTIVITY:-com.vibedrop.mobile.nativeapp.MainActivity}"
OLD_TAURI_VERSION_CODE="${OLD_TAURI_VERSION_CODE:-1004}"
DEVICE_SERIAL="${ADB_SERIAL:-}"

UNSIGNED_APK="$NATIVE_ANDROID_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
SIGNED_APK="$NATIVE_ANDROID_DIR/app/build/outputs/apk/release/VibeDrop-native-release-signed.apk"
METADATA_JSON="$NATIVE_ANDROID_DIR/app/build/outputs/apk/release/output-metadata.json"

SKIP_BUILD=0
INSTALL=0

usage() {
  cat <<'EOF'
Usage: ./scripts/deploy-native-android-release.sh [options]

Builds and signs the native Kotlin/Compose Android release package for the
future Tauri replacement path. By default it does not install, because this
release package uses com.vibedrop.mobile and will replace the current formal app.

Options:
  --device <serial>   Use the specified ADB device serial when --install is set
  --skip-build        Reuse the existing release unsigned APK and metadata
  --skip-install      Explicit no-op install flag; this is the default
  --install           Install with adb install -r and restart the release app
  -h, --help          Show this help

Environment:
  ANDROID_HOME                Android SDK root
  ADB_SERIAL                  Default device serial if --device is omitted
  JAVA_HOME                   Java runtime; defaults to Android Studio JBR when present
  VIBEDROP_KEYSTORE_PATH      Keystore path, default: ~/.android/vibedrop.keystore
  VIBEDROP_KEYSTORE_PASS      Keystore password, default: pass:vibedrop123
  PACKAGE_NAME                Release package, default: com.vibedrop.mobile
  MAIN_ACTIVITY               Activity class, default: com.vibedrop.mobile.nativeapp.MainActivity
  OLD_TAURI_VERSION_CODE      Minimum previous Tauri versionCode, default: 1004
EOF
}

log() {
  printf '[deploy-native-android-release] %s\n' "$1"
}

fail() {
  printf '[deploy-native-android-release] %s\n' "$1" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
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

validate_release_metadata() {
  [[ -f "$METADATA_JSON" ]] || fail "Release metadata not found: $METADATA_JSON"
  python3 - "$METADATA_JSON" "$PACKAGE_NAME" "$OLD_TAURI_VERSION_CODE" <<'PY'
import json
import sys

metadata_path, expected_package, old_code_raw = sys.argv[1:4]
old_code = int(old_code_raw)
with open(metadata_path, "r", encoding="utf-8") as handle:
    metadata = json.load(handle)

application_id = metadata.get("applicationId")
elements = metadata.get("elements") or []
version_code = int((elements[0] if elements else {}).get("versionCode", 0))
version_name = (elements[0] if elements else {}).get("versionName", "")

if application_id != expected_package:
    raise SystemExit(f"release applicationId {application_id!r} != expected {expected_package!r}")
if version_code <= old_code:
    raise SystemExit(f"release versionCode {version_code} must be greater than old Tauri {old_code}")

print(f"{application_id} versionCode={version_code} versionName={version_name}")
PY
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      [[ $# -ge 2 ]] || fail "--device requires a serial"
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=1
      shift
      ;;
    --skip-install)
      INSTALL=0
      shift
      ;;
    --install)
      INSTALL=1
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

if [[ -z "${JAVA_HOME:-}" && -d "$DEFAULT_JAVA_HOME" ]]; then
  export JAVA_HOME="$DEFAULT_JAVA_HOME"
fi

require_cmd python3
[[ -x "$GRADLEW" ]] || fail "Gradle wrapper not found or not executable: $GRADLEW"
[[ -f "$KEYSTORE_PATH" ]] || fail "Keystore not found: $KEYSTORE_PATH"

if [[ $INSTALL -eq 1 ]]; then
  require_cmd adb
fi

if [[ $SKIP_BUILD -eq 0 ]]; then
  log "Building native Android release APK"
  "$GRADLEW" -p "$NATIVE_ANDROID_DIR" :app:assembleRelease
fi

[[ -f "$UNSIGNED_APK" ]] || fail "Unsigned release APK not found: $UNSIGNED_APK"

log "Validating release metadata"
validate_release_metadata

APKSIGNER="$(find_apksigner)"
log "Signing release APK with $(basename "$(dirname "$APKSIGNER")")"
"$APKSIGNER" sign \
  --ks "$KEYSTORE_PATH" \
  --ks-pass "$KEYSTORE_PASS" \
  --out "$SIGNED_APK" \
  "$UNSIGNED_APK"

log "Signed release APK ready at $SIGNED_APK"

if [[ $INSTALL -eq 0 ]]; then
  log "Install skipped. Re-run with --install to replace the current $PACKAGE_NAME app."
  exit 0
fi

DEVICE_SERIAL="$(resolve_device_serial)"
ADB_ARGS=(adb -s "$DEVICE_SERIAL")

log "Installing native release APK to device $DEVICE_SERIAL"
"${ADB_ARGS[@]}" install -r "$SIGNED_APK"

log "Restarting $PACKAGE_NAME/$MAIN_ACTIVITY"
"${ADB_ARGS[@]}" shell am start -S -n "$PACKAGE_NAME/$MAIN_ACTIVITY"

log "Done"
