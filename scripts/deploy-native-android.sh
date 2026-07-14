#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
NATIVE_ANDROID_DIR="$ROOT_DIR/native/android"
GRADLEW="$ROOT_DIR/mobile/src-tauri/gen/android/gradlew"
DEFAULT_JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

DEVICE_SERIAL="${ADB_SERIAL:-}"
SKIP_BUILD=0
SKIP_INSTALL=0
PACKAGE_NAME="${PACKAGE_NAME:-com.vibedrop.mobile.nativepreview}"
MAIN_ACTIVITY="${MAIN_ACTIVITY:-com.vibedrop.mobile.nativeapp.MainActivity}"

usage() {
  cat <<'EOF'
Usage: ./scripts/deploy-native-android.sh [options]

Builds, installs, and restarts the native Kotlin/Compose Android preview app.
This script intentionally targets the debug preview package so it can coexist
with the current Tauri release app while native parity is still being verified.

Options:
  --device <serial>   Use the specified ADB device serial
  --skip-build        Reuse the existing debug APK and only install/restart
  --skip-install      Stop after building the debug APK
  -h, --help          Show this help

Environment:
  ADB_SERIAL          Default device serial if --device is omitted
  JAVA_HOME           Java runtime; defaults to Android Studio's bundled JBR when present
  PACKAGE_NAME        Package to restart, default: com.vibedrop.mobile.nativepreview
  MAIN_ACTIVITY       Activity class to start, default: com.vibedrop.mobile.nativeapp.MainActivity
EOF
}

log() {
  printf '[deploy-native-android] %s\n' "$1"
}

fail() {
  printf '[deploy-native-android] %s\n' "$1" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
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

if [[ -z "${JAVA_HOME:-}" && -d "$DEFAULT_JAVA_HOME" ]]; then
  export JAVA_HOME="$DEFAULT_JAVA_HOME"
fi

require_cmd adb
[[ -x "$GRADLEW" ]] || fail "Gradle wrapper not found or not executable: $GRADLEW"

APK_PATH="$NATIVE_ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"

if [[ $SKIP_BUILD -eq 0 ]]; then
  log "Building native Android debug APK"
  "$GRADLEW" -p "$NATIVE_ANDROID_DIR" :app:assembleDebug
fi

[[ -f "$APK_PATH" ]] || fail "Debug APK not found: $APK_PATH"

if [[ $SKIP_INSTALL -eq 1 ]]; then
  log "Debug APK ready at $APK_PATH"
  exit 0
fi

DEVICE_SERIAL="$(resolve_device_serial)"
ADB_ARGS=(adb -s "$DEVICE_SERIAL")

log "Installing native preview APK to device $DEVICE_SERIAL"
"${ADB_ARGS[@]}" install -r "$APK_PATH"

log "Restarting $PACKAGE_NAME/$MAIN_ACTIVITY"
"${ADB_ARGS[@]}" shell am start -S -n "$PACKAGE_NAME/$MAIN_ACTIVITY"

log "Done"
