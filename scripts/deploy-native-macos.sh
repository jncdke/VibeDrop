#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
NATIVE_MACOS_DIR="$ROOT_DIR/native/macos"
BUILD_ROOT="$NATIVE_MACOS_DIR/.build/vibedrop-native-app"
FINDER_WORKFLOW_INSTALLER="$ROOT_DIR/scripts/install-finder-send-workflow.py"
SHARE_EXTENSION_GENERATOR="$ROOT_DIR/scripts/generate-share-extension-project.rb"
SHARE_EXTENSION_PROJECT="$ROOT_DIR/desktop/share-extension/VibeDropShare.xcodeproj"
SHARE_EXTENSION_TARGET="${SHARE_EXTENSION_TARGET:-VibeDropShare}"
SHARE_EXTENSION_BUILD_DIR="$ROOT_DIR/desktop/share-extension/build/Release"
SHARE_EXTENSION_PRODUCT_PATH="$SHARE_EXTENSION_BUILD_DIR/VibeDropShare.appex"
SHARE_EXTENSION_ENTITLEMENTS="$ROOT_DIR/desktop/share-extension/VibeDropShare/VibeDropShare.entitlements"
SHARE_EXTENSION_INSTALL_PATH_SUFFIX="Contents/PlugIns/VibeDropShare.appex"
PLUGINKIT="/usr/bin/pluginkit"
LSREGISTER="/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"

CONFIGURATION="release"
APP_NAME="${APP_NAME:-VibeDrop Native}"
BUNDLE_ID="${BUNDLE_ID:-com.vibedrop.nativepreview}"
APP_VERSION="${APP_VERSION:-0.1.0-native-preview}"
APP_BUILD="${APP_BUILD:-1}"
INSTALL_DIR="${INSTALL_DIR:-$HOME/Applications}"
SIGN_IDENTITY="${SIGN_IDENTITY:--}"
KEYCHAIN_PATH="${KEYCHAIN_PATH:-$HOME/.vibedrop/signing/vibedrop-codesign.keychain-db}"
SKIP_BUILD=0
SKIP_INSTALL=0
NO_OPEN=0
NO_SIGN=0
WITH_SHARE_EXTENSION="${WITH_SHARE_EXTENSION:-0}"

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
  --with-share-extension           Build, embed, sign, and register VibeDropShare.appex
  --no-sign                        Do not codesign the generated app
  --skip-build                     Reuse the existing SwiftPM executable
  --skip-install                   Stop after creating the .app bundle
  --no-open                        Install but do not open the app
  -h, --help                       Show this help

Environment:
  APP_NAME, BUNDLE_ID, APP_VERSION, APP_BUILD, INSTALL_DIR, SIGN_IDENTITY, KEYCHAIN_PATH override the defaults above.
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

ensure_share_extension_project() {
  [[ -f "$SHARE_EXTENSION_GENERATOR" ]] || fail "Share extension generator not found: $SHARE_EXTENSION_GENERATOR"
  if [[ ! -d "$SHARE_EXTENSION_PROJECT" ]]; then
    log "Generating Share Extension Xcode project"
    ruby "$SHARE_EXTENSION_GENERATOR"
  fi
}

build_share_extension() {
  ensure_share_extension_project
  [[ -f "$SHARE_EXTENSION_ENTITLEMENTS" ]] || fail "Share extension entitlements not found: $SHARE_EXTENSION_ENTITLEMENTS"
  log "Building macOS Share Extension"
  rm -rf "$SHARE_EXTENSION_BUILD_DIR"
  xcodebuild \
    -project "$SHARE_EXTENSION_PROJECT" \
    -scheme "$SHARE_EXTENSION_TARGET" \
    -configuration Release \
    CODE_SIGNING_ALLOWED=NO \
    CONFIGURATION_BUILD_DIR="$SHARE_EXTENSION_BUILD_DIR" \
    build
  [[ -d "$SHARE_EXTENSION_PRODUCT_PATH" ]] || fail "Built Share Extension not found: $SHARE_EXTENSION_PRODUCT_PATH"
}

embed_share_extension() {
  local app_path="$1"
  local plugins_dir="$app_path/Contents/PlugIns"
  local installed_appex="$app_path/$SHARE_EXTENSION_INSTALL_PATH_SUFFIX"

  [[ -d "$app_path" ]] || fail "App bundle not found for Share Extension embed: $app_path"
  [[ -d "$SHARE_EXTENSION_PRODUCT_PATH" ]] || fail "Built Share Extension not found: $SHARE_EXTENSION_PRODUCT_PATH"

  log "Embedding Share Extension into $app_path"
  mkdir -p "$plugins_dir"
  rm -rf "$installed_appex"
  ditto "$SHARE_EXTENSION_PRODUCT_PATH" "$installed_appex"
}

strip_extended_attributes() {
  local target="$1"
  [[ -e "$target" ]] || return 0
  if command -v xattr >/dev/null 2>&1; then
    log "Removing extended attributes from $target"
    xattr -cr "$target" || true
  fi
}

codesign_with_optional_keychain() {
  local identity="$1"
  shift

  if [[ "$identity" != "-" && -f "$KEYCHAIN_PATH" ]]; then
    codesign --force --keychain "$KEYCHAIN_PATH" --sign "$identity" "$@"
    return
  fi

  codesign --force --sign "$identity" "$@"
}

sign_share_extension() {
  local appex_path="$1"
  local identity="$2"
  [[ -d "$appex_path" ]] || fail "Share Extension bundle not found for signing: $appex_path"

  log "Signing Share Extension with identity: $identity"
  codesign_with_optional_keychain "$identity" --entitlements "$SHARE_EXTENSION_ENTITLEMENTS" "$appex_path"
}

sign_app() {
  local app_path="$1"
  local identity="$2"
  [[ -d "$app_path" ]] || fail "App bundle not found for signing: $app_path"

  log "Signing app bundle with identity: $identity"
  codesign_with_optional_keychain "$identity" "$app_path"
}

verify_app() {
  local app_path="$1"
  log "Verifying code signature for $app_path"
  codesign --verify --deep --strict --verbose=2 "$app_path"
}

register_share_extension() {
  local appex_path="$1"
  [[ -x "$PLUGINKIT" ]] || return 0
  [[ -d "$appex_path" ]] || return 0
  log "Registering Share Extension $appex_path"
  "$PLUGINKIT" -a "$appex_path" >/dev/null || true
}

install_finder_workflow() {
  [[ -f "$FINDER_WORKFLOW_INSTALLER" ]] || fail "Finder workflow installer not found: $FINDER_WORKFLOW_INSTALLER"
  command -v python3 >/dev/null 2>&1 || fail "Missing required command: python3"
  log "Installing Finder service workflow"
  python3 "$FINDER_WORKFLOW_INSTALLER"
}

refresh_launch_services() {
  local app_path="$1"
  [[ -x "$LSREGISTER" ]] || return 0
  [[ -d "$app_path" ]] || return 0
  log "Refreshing Launch Services registration for $app_path"
  "$LSREGISTER" -f "$app_path" >/dev/null || true
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
    --with-share-extension)
      WITH_SHARE_EXTENSION=1
      shift
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

if [[ $WITH_SHARE_EXTENSION -eq 1 ]]; then
  require_cmd ruby
  require_cmd xcodebuild
fi

if [[ $NO_SIGN -eq 0 ]]; then
  require_cmd codesign
fi

if [[ $SKIP_BUILD -eq 0 ]]; then
  log "Building VibeDropMacApp ($CONFIGURATION)"
  swift build --package-path "$NATIVE_MACOS_DIR" -c "$CONFIGURATION" --product VibeDropMacApp
  if [[ $WITH_SHARE_EXTENSION -eq 1 ]]; then
    build_share_extension
  fi
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
  <string>$APP_VERSION</string>
  <key>CFBundleVersion</key>
  <string>$APP_BUILD</string>
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

if [[ $WITH_SHARE_EXTENSION -eq 1 ]]; then
  embed_share_extension "$APP_PATH"
  strip_extended_attributes "$APP_PATH/$SHARE_EXTENSION_INSTALL_PATH_SUFFIX"
fi
strip_extended_attributes "$APP_PATH"

if [[ $NO_SIGN -eq 0 ]]; then
  if [[ $WITH_SHARE_EXTENSION -eq 1 ]]; then
    sign_share_extension "$APP_PATH/$SHARE_EXTENSION_INSTALL_PATH_SUFFIX" "$SIGN_IDENTITY"
  fi
  sign_app "$APP_PATH" "$SIGN_IDENTITY"
  verify_app "$APP_PATH"
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
refresh_launch_services "$INSTALLED_APP"

if [[ $WITH_SHARE_EXTENSION -eq 1 ]]; then
  register_share_extension "$INSTALLED_APP/$SHARE_EXTENSION_INSTALL_PATH_SUFFIX"
  install_finder_workflow
fi

if [[ $NO_OPEN -eq 0 ]]; then
  log "Opening $INSTALLED_APP"
  open "$INSTALLED_APP"
fi

log "Done"
