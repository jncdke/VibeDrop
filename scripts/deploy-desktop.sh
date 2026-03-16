#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
DESKTOP_DIR="$ROOT_DIR/desktop"
ICON_GENERATOR="$ROOT_DIR/scripts/generate-app-icons.py"
FINDER_WORKFLOW_INSTALLER="$ROOT_DIR/scripts/install-finder-send-workflow.py"
SHARE_EXTENSION_GENERATOR="$ROOT_DIR/scripts/generate-share-extension-project.rb"
SHARE_EXTENSION_PROJECT="$ROOT_DIR/desktop/share-extension/VibeDropShare.xcodeproj"
SHARE_EXTENSION_TARGET="${SHARE_EXTENSION_TARGET:-VibeDropShare}"
SHARE_EXTENSION_BUILD_DIR="$ROOT_DIR/desktop/share-extension/build/Release"
SHARE_EXTENSION_PRODUCT_PATH="$SHARE_EXTENSION_BUILD_DIR/VibeDropShare.appex"
SHARE_EXTENSION_ENTITLEMENTS="$ROOT_DIR/desktop/share-extension/VibeDropShare/VibeDropShare.entitlements"
SHARE_EXTENSION_INSTALL_PATH_SUFFIX="Contents/PlugIns/VibeDropShare.appex"

APP_NAME="${APP_NAME:-VibeDrop.app}"
APP_BUNDLE_NAME="${APP_BUNDLE_NAME:-VibeDrop}"
INSTALL_DIR="${INSTALL_DIR:-/Applications}"
APP_IDENTIFIER="${APP_IDENTIFIER:-com.vibedrop.desktop}"
LEGACY_APP_IDENTIFIER="${LEGACY_APP_IDENTIFIER:-com.voicedrop.desktop}"
KEYCHAIN_PATH="${KEYCHAIN_PATH:-$HOME/.vibedrop/signing/vibedrop-codesign.keychain-db}"
KEYCHAIN_PASSWORD_FILE="${KEYCHAIN_PASSWORD_FILE:-$HOME/.vibedrop/signing/.keychain-password}"
CERT_NAME_PATTERN="${CERT_NAME_PATTERN:-VibeTech Local Code Signing}"
CODESIGN_IDENTITY="${CODESIGN_IDENTITY:-}"
ALLOW_ADHOC_FALLBACK="${ALLOW_ADHOC_FALLBACK:-0}"
DEST_APP_PATH="$INSTALL_DIR/$APP_NAME"
BUILT_APP_PATH="$DESKTOP_DIR/src-tauri/target/release/bundle/macos/$APP_NAME"
PLIST_BUDDY="/usr/libexec/PlistBuddy"
LSREGISTER="/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
PLUGINKIT="/usr/bin/pluginkit"

SKIP_BUILD=0
NO_OPEN=0
SKIP_ICONS=0

usage() {
  cat <<'EOF'
Usage: ./scripts/deploy-desktop.sh [options]

Regenerates app icon assets, builds the macOS app bundle, installs it to /Applications, and relaunches it.

Options:
  --skip-icons   Reuse existing generated icon assets
  --skip-build   Reuse the existing app bundle and only reinstall/relaunch
  --no-open      Do not relaunch the app after installation
  -h, --help     Show this help

Environment:
  APP_NAME         App bundle name, default: VibeDrop.app
  APP_BUNDLE_NAME  Process/app name without .app, default: VibeDrop
  INSTALL_DIR      Install destination, default: /Applications
  APP_IDENTIFIER   Expected bundle identifier, default: com.vibedrop.desktop
  KEYCHAIN_PATH    Keychain to search for signing identities
  KEYCHAIN_PASSWORD_FILE Optional file containing the signing keychain password
  CERT_NAME_PATTERN Preferred certificate name pattern, default: VibeTech Local Code Signing
  CODESIGN_IDENTITY Force a specific codesign identity SHA-1 hash or name
  ALLOW_ADHOC_FALLBACK Allow ad-hoc signing when no stable identity is found, default: 0
EOF
}

log() {
  printf '[deploy-desktop] %s\n' "$1"
}

fail() {
  printf '[deploy-desktop] %s\n' "$1" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

ensure_share_extension_project() {
  [[ -f "$SHARE_EXTENSION_GENERATOR" ]] || fail "Share extension generator not found: $SHARE_EXTENSION_GENERATOR"
  if [[ ! -d "$SHARE_EXTENSION_PROJECT" ]]; then
    log "Generating Share Extension Xcode project"
    ruby "$SHARE_EXTENSION_GENERATOR"
  fi
}

regenerate_icons() {
  [[ -f "$ICON_GENERATOR" ]] || fail "Icon generator not found: $ICON_GENERATOR"
  log "Regenerating icon assets from 图标.jpg"
  python3 "$ICON_GENERATOR"
}

install_finder_workflow() {
  [[ -f "$FINDER_WORKFLOW_INSTALLER" ]] || fail "Finder workflow installer not found: $FINDER_WORKFLOW_INSTALLER"
  log "Installing Finder service workflow"
  python3 "$FINDER_WORKFLOW_INSTALLER"
}

bundle_identifier() {
  local app_path="$1"
  "$PLIST_BUDDY" -c "Print :CFBundleIdentifier" "$app_path/Contents/Info.plist"
}

validate_app_identifier() {
  local app_path="$1"
  local actual_identifier

  [[ -d "$app_path" ]] || fail "App bundle not found for identifier check: $app_path"
  actual_identifier="$(bundle_identifier "$app_path")"

  if [[ "$actual_identifier" == "$LEGACY_APP_IDENTIFIER" ]]; then
    fail "App bundle at $app_path still uses legacy identifier $LEGACY_APP_IDENTIFIER"
  fi

  if [[ "$actual_identifier" != "$APP_IDENTIFIER" ]]; then
    fail "App bundle at $app_path has unexpected identifier $actual_identifier, expected $APP_IDENTIFIER"
  fi

  log "Bundle identifier OK: $actual_identifier"
}

build_app() {
  log "Building desktop app bundle"
  (
    cd "$DESKTOP_DIR"
    cargo tauri build --bundles app
  )
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

sign_app() {
  local app_path="$1"
  local identity="$2"
  [[ -d "$app_path" ]] || fail "App bundle not found for signing: $app_path"
  if [[ "$identity" == "-" ]]; then
    log "Applying bundle signature to $app_path"
    codesign --force --sign - "$app_path"
    return
  fi

  log "Signing $app_path with identity $identity"
  if [[ -f "$KEYCHAIN_PATH" ]]; then
    codesign --force --keychain "$KEYCHAIN_PATH" --sign "$identity" "$app_path"
    return
  fi

  codesign --force --sign "$identity" "$app_path"
}

sign_share_extension() {
  local appex_path="$1"
  local identity="$2"
  [[ -d "$appex_path" ]] || fail "Share Extension bundle not found for signing: $appex_path"

  if [[ "$identity" == "-" ]]; then
    log "Signing Share Extension with ad-hoc identity"
    codesign --force --sign - --entitlements "$SHARE_EXTENSION_ENTITLEMENTS" "$appex_path"
    return
  fi

  log "Signing Share Extension with identity $identity"
  if [[ -f "$KEYCHAIN_PATH" ]]; then
    codesign --force --keychain "$KEYCHAIN_PATH" --sign "$identity" --entitlements "$SHARE_EXTENSION_ENTITLEMENTS" "$appex_path"
    return
  fi

  codesign --force --sign "$identity" --entitlements "$SHARE_EXTENSION_ENTITLEMENTS" "$appex_path"
}

verify_app() {
  local app_path="$1"
  log "Verifying code signature for $app_path"
  codesign --verify --deep --strict --verbose=2 "$app_path"
}

resolve_codesign_identity() {
  if [[ -n "$CODESIGN_IDENTITY" ]]; then
    printf '%s\n' "$CODESIGN_IDENTITY"
    return
  fi

  if [[ ! -f "$KEYCHAIN_PATH" ]]; then
    printf '%s\n' "-"
    return
  fi

  local identity_line
  identity_line="$(
    security find-identity -v -p codesigning "$KEYCHAIN_PATH" 2>/dev/null \
      | grep -i "$CERT_NAME_PATTERN" \
      | head -n 1
  )"

  if [[ -z "$identity_line" ]]; then
    printf '%s\n' "-"
    return
  fi

  printf '%s\n' "$identity_line" | sed -E 's/.*"(.+)".*/\1/'
}

unlock_signing_keychain() {
  [[ -f "$KEYCHAIN_PATH" ]] || return
  [[ -n "$KEYCHAIN_PASSWORD_FILE" ]] || return
  [[ -f "$KEYCHAIN_PASSWORD_FILE" ]] || fail "Signing keychain password file not found: $KEYCHAIN_PASSWORD_FILE"

  local password
  password="$(<"$KEYCHAIN_PASSWORD_FILE")"
  [[ -n "$password" ]] || fail "Signing keychain password file is empty: $KEYCHAIN_PASSWORD_FILE"

  log "Unlocking signing keychain $KEYCHAIN_PATH"
  security unlock-keychain -p "$password" "$KEYCHAIN_PATH"
}

refresh_launch_services() {
  local app_path="$1"
  log "Refreshing Launch Services registration for $app_path"
  "$LSREGISTER" -f "$app_path" >/dev/null
}

register_share_extension() {
  local appex_path="$1"
  [[ -x "$PLUGINKIT" ]] || return 0
  [[ -d "$appex_path" ]] || return 0
  log "Registering Share Extension $appex_path"
  "$PLUGINKIT" -a "$appex_path" >/dev/null || true
}

install_app() {
  [[ -d "$BUILT_APP_PATH" ]] || fail "Built app bundle not found: $BUILT_APP_PATH"
  log "Installing $APP_NAME to $DEST_APP_PATH"
  ditto "$BUILT_APP_PATH" "$DEST_APP_PATH"
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

stop_running_app() {
  if pgrep -x "voicedrop" >/dev/null 2>&1; then
    log "Stopping running voicedrop process"
    pkill -x "voicedrop" || true
    sleep 1
  fi
}

open_app() {
  log "Launching $DEST_APP_PATH"
  open -n "$DEST_APP_PATH"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-icons)
      SKIP_ICONS=1
      shift
      ;;
    --skip-build)
      SKIP_BUILD=1
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

require_cmd cargo
require_cmd ditto
require_cmd pgrep
require_cmd pkill
require_cmd open
require_cmd codesign
require_cmd security
require_cmd python3
require_cmd ruby
require_cmd xcodebuild
[[ -x "$PLIST_BUDDY" ]] || fail "Missing PlistBuddy: $PLIST_BUDDY"
[[ -x "$LSREGISTER" ]] || fail "Missing lsregister: $LSREGISTER"

unlock_signing_keychain
SIGNING_IDENTITY="$(resolve_codesign_identity)"
if [[ "$SIGNING_IDENTITY" == "-" ]]; then
  if [[ "$ALLOW_ADHOC_FALLBACK" != "1" ]]; then
    fail "No stable codesign identity found under $KEYCHAIN_PATH matching \"$CERT_NAME_PATTERN\""
  fi
  log "No stable codesign identity found under $KEYCHAIN_PATH matching \"$CERT_NAME_PATTERN\""
  log "Falling back to ad-hoc signing. macOS Accessibility permission may need re-approval after updates."
else
  log "Using stable codesign identity: $SIGNING_IDENTITY"
fi

if [[ $SKIP_BUILD -eq 0 ]]; then
  if [[ $SKIP_ICONS -eq 0 ]]; then
    regenerate_icons
  fi
  build_app
  build_share_extension
fi

validate_app_identifier "$BUILT_APP_PATH"
embed_share_extension "$BUILT_APP_PATH"
sign_share_extension "$BUILT_APP_PATH/$SHARE_EXTENSION_INSTALL_PATH_SUFFIX" "$SIGNING_IDENTITY"
sign_app "$BUILT_APP_PATH" "$SIGNING_IDENTITY"
verify_app "$BUILT_APP_PATH"

stop_running_app
install_app
validate_app_identifier "$DEST_APP_PATH"
embed_share_extension "$DEST_APP_PATH"
sign_share_extension "$DEST_APP_PATH/$SHARE_EXTENSION_INSTALL_PATH_SUFFIX" "$SIGNING_IDENTITY"
sign_app "$DEST_APP_PATH" "$SIGNING_IDENTITY"
verify_app "$DEST_APP_PATH"
refresh_launch_services "$DEST_APP_PATH"
register_share_extension "$DEST_APP_PATH/$SHARE_EXTENSION_INSTALL_PATH_SUFFIX"
install_finder_workflow

if [[ $NO_OPEN -eq 0 ]]; then
  open_app
fi

log "Done"
