#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
NATIVE_MACOS_DIR="$ROOT_DIR/native/macos"
BUILD_ROOT="$NATIVE_MACOS_DIR/.build/vibedrop-native-app"

CONFIGURATION="release"
APP_NAME="${APP_NAME:-VibeDrop Native}"
BUNDLE_ID="${BUNDLE_ID:-com.vibedrop.nativepreview}"
INSTALL_DIR="${INSTALL_DIR:-$HOME/Applications}"
SIGN_IDENTITY="${SIGN_IDENTITY:--}"
SKIP_BUILD=0
SKIP_INSTALL=0
NO_OPEN=0
NO_SIGN=0

usage() {
  cat <<'EOF'
Usage: ./scripts/deploy-native-macos.sh [options]

Builds the native Swift/AppKit macOS app, wraps it in a real .app bundle,
optionally signs it, installs it, and opens it. The default bundle is a native
preview app so it can coexist with the current Tauri release while parity is
still being verified.

Options:
  --configuration <debug|release>  SwiftPM build configuration, default: release
  --install-dir <path>             Install directory, default: ~/Applications
  --app-name <name>                App bundle display name, default: VibeDrop Native
  --bundle-id <id>                 Bundle identifier, default: com.vibedrop.nativepreview
  --sign-identity <identity>       codesign identity, default: ad-hoc "-"
  --no-sign                        Do not codesign the generated app
  --skip-build                     Reuse the existing SwiftPM executable
  --skip-install                   Stop after creating the .app bundle
  --no-open                        Install but do not open the app
  -h, --help                       Show this help

Environment:
  APP_NAME, BUNDLE_ID, INSTALL_DIR, SIGN_IDENTITY override the defaults above.
EOF
}

log() {
  printf '[deploy-native-macos] %s\n' "$1"
}

fail() {
  printf '[deploy-native-macos] %s\n' "$1" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

normalize_configuration() {
  case "$1" in
    debug|release)
      printf '%s\n' "$1"
      ;;
    *)
      fail "Unsupported configuration: $1"
      ;;
  esac
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --configuration)
      [[ $# -ge 2 ]] || fail "--configuration requires debug or release"
      CONFIGURATION="$(normalize_configuration "$2")"
      shift 2
      ;;
    --install-dir)
      [[ $# -ge 2 ]] || fail "--install-dir requires a path"
      INSTALL_DIR="$2"
      shift 2
      ;;
    --app-name)
      [[ $# -ge 2 ]] || fail "--app-name requires a name"
      APP_NAME="$2"
      shift 2
      ;;
    --bundle-id)
      [[ $# -ge 2 ]] || fail "--bundle-id requires an identifier"
      BUNDLE_ID="$2"
      shift 2
      ;;
    --sign-identity)
      [[ $# -ge 2 ]] || fail "--sign-identity requires an identity"
      SIGN_IDENTITY="$2"
      shift 2
      ;;
    --no-sign)
      NO_SIGN=1
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
    --no-open)
      NO_OPEN=1
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

require_cmd swift
require_cmd ditto

if [[ $NO_SIGN -eq 0 ]]; then
  require_cmd codesign
fi

if [[ $SKIP_BUILD -eq 0 ]]; then
  log "Building VibeDropMacApp ($CONFIGURATION)"
  swift build --package-path "$NATIVE_MACOS_DIR" -c "$CONFIGURATION" --product VibeDropMacApp
fi

BIN_DIR="$(swift build --package-path "$NATIVE_MACOS_DIR" -c "$CONFIGURATION" --show-bin-path)"
EXECUTABLE_PATH="$BIN_DIR/VibeDropMacApp"
[[ -x "$EXECUTABLE_PATH" ]] || fail "Executable not found: $EXECUTABLE_PATH"

APP_PATH="$BUILD_ROOT/$APP_NAME.app"
CONTENTS_DIR="$APP_PATH/Contents"
MACOS_DIR="$CONTENTS_DIR/MacOS"
RESOURCES_DIR="$CONTENTS_DIR/Resources"

rm -rf "$APP_PATH"
mkdir -p "$MACOS_DIR" "$RESOURCES_DIR"

log "Creating app bundle at $APP_PATH"
ditto "$EXECUTABLE_PATH" "$MACOS_DIR/VibeDropMacApp"

MARK_SOURCE="$NATIVE_MACOS_DIR/Sources/VibeDropMacApp/Resources/VibeDropMark.png"
if [[ -f "$MARK_SOURCE" ]]; then
  ditto "$MARK_SOURCE" "$RESOURCES_DIR/VibeDropMark.png"
fi
TRAY_ICON_SOURCE="$NATIVE_MACOS_DIR/Sources/VibeDropMacApp/Resources/VibeDropTrayIcon.png"
if [[ -f "$TRAY_ICON_SOURCE" ]]; then
  ditto "$TRAY_ICON_SOURCE" "$RESOURCES_DIR/VibeDropTrayIcon.png"
fi

ICON_SOURCE="${APP_ICON_SOURCE:-$MARK_SOURCE}"
if [[ -f "$ICON_SOURCE" ]] && command -v sips >/dev/null 2>&1 && command -v iconutil >/dev/null 2>&1; then
  ICONSET_DIR="$BUILD_ROOT/AppIcon.iconset"
  rm -rf "$ICONSET_DIR"
  mkdir -p "$ICONSET_DIR"
  sips -z 16 16 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_16x16.png" >/dev/null
  sips -z 32 32 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_16x16@2x.png" >/dev/null
  sips -z 32 32 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_32x32.png" >/dev/null
  sips -z 64 64 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_32x32@2x.png" >/dev/null
  sips -z 128 128 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_128x128.png" >/dev/null
  sips -z 256 256 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_128x128@2x.png" >/dev/null
  sips -z 256 256 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_256x256.png" >/dev/null
  sips -z 512 512 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_256x256@2x.png" >/dev/null
  sips -z 512 512 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_512x512.png" >/dev/null
  sips -z 1024 1024 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_512x512@2x.png" >/dev/null
  iconutil -c icns "$ICONSET_DIR" -o "$RESOURCES_DIR/AppIcon.icns"
  rm -rf "$ICONSET_DIR"
fi

cat > "$CONTENTS_DIR/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDevelopmentRegion</key>
  <string>zh_CN</string>
  <key>CFBundleDisplayName</key>
  <string>$APP_NAME</string>
  <key>CFBundleExecutable</key>
  <string>VibeDropMacApp</string>
  <key>CFBundleIconFile</key>
  <string>AppIcon</string>
  <key>CFBundleIdentifier</key>
  <string>$BUNDLE_ID</string>
  <key>CFBundleInfoDictionaryVersion</key>
  <string>6.0</string>
  <key>CFBundleName</key>
  <string>$APP_NAME</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>0.1.0-native-preview</string>
  <key>CFBundleVersion</key>
  <string>1</string>
  <key>LSMinimumSystemVersion</key>
  <string>13.0</string>
  <key>NSHighResolutionCapable</key>
  <true/>
  <key>NSLocalNetworkUsageDescription</key>
  <string>VibeDrop 需要访问局域网，以便 Android 手机发现并连接这台 Mac。</string>
</dict>
</plist>
EOF

printf 'APPL????' > "$CONTENTS_DIR/PkgInfo"

if [[ $NO_SIGN -eq 0 ]]; then
  log "Signing app bundle with identity: $SIGN_IDENTITY"
  codesign --force --deep --sign "$SIGN_IDENTITY" "$APP_PATH"
fi

if [[ $SKIP_INSTALL -eq 1 ]]; then
  log "App bundle ready at $APP_PATH"
  exit 0
fi

mkdir -p "$INSTALL_DIR"
INSTALLED_APP="$INSTALL_DIR/$APP_NAME.app"
rm -rf "$INSTALLED_APP"
log "Installing to $INSTALLED_APP"
ditto "$APP_PATH" "$INSTALLED_APP"

if [[ $NO_OPEN -eq 0 ]]; then
  log "Opening $INSTALLED_APP"
  open "$INSTALLED_APP"
fi

log "Done"
