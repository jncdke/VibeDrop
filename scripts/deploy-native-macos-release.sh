#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
KEYCHAIN_PATH="${KEYCHAIN_PATH:-$HOME/.vibedrop/signing/vibedrop-codesign.keychain-db}"
KEYCHAIN_PASSWORD_FILE="${KEYCHAIN_PASSWORD_FILE:-$HOME/.vibedrop/signing/.keychain-password}"
CERT_NAME_PATTERN="${CERT_NAME_PATTERN:-VibeTech Local Code Signing}"
CODESIGN_IDENTITY="${CODESIGN_IDENTITY:-}"
ALLOW_ADHOC_FALLBACK="${ALLOW_ADHOC_FALLBACK:-0}"

INSTALL=0
NO_SIGN=0
USER_SIGN_IDENTITY=0
FORWARD_ARGS=()

usage() {
  cat <<'EOF'
Usage: ./scripts/deploy-native-macos-release.sh [options]

Builds the native macOS app as the formal VibeDrop bundle:
  app name:  VibeDrop.app
  bundle id: com.vibedrop.desktop
  install:   /Applications

By default this script only creates the release app bundle for inspection. Pass
--install to replace /Applications/VibeDrop.app, register the Share Extension,
and launch the app.

Options:
  --install                       Replace /Applications/VibeDrop.app
  --skip-install                  Build only, default behavior
  --no-sign                       Do not codesign app or Share Extension
  --sign-identity <identity>      Forward an explicit codesign identity
  -h, --help                      Show this help

All other options are forwarded to deploy-native-macos.sh.

Environment:
  CODESIGN_IDENTITY       Force a signing identity when --sign-identity is not passed
  KEYCHAIN_PATH           Signing keychain, default: ~/.vibedrop/signing/vibedrop-codesign.keychain-db
  KEYCHAIN_PASSWORD_FILE  Optional password file for the signing keychain
  CERT_NAME_PATTERN       Certificate match text, default: VibeTech Local Code Signing
  ALLOW_ADHOC_FALLBACK    Allow ad-hoc signing if no stable identity is found, default: 0
  APP_VERSION, APP_BUILD  Override release version metadata
EOF
}

log() {
  printf '[deploy-native-macos-release] %s\n' "$1"
}

fail() {
  printf '[deploy-native-macos-release] %s\n' "$1" >&2
  exit 1
}

unlock_signing_keychain() {
  [[ -f "$KEYCHAIN_PATH" ]] || return 0
  [[ -f "$KEYCHAIN_PASSWORD_FILE" ]] || return 0

  local password
  password="$(<"$KEYCHAIN_PASSWORD_FILE")"
  [[ -n "$password" ]] || fail "Signing keychain password file is empty: $KEYCHAIN_PASSWORD_FILE"

  log "Unlocking signing keychain $KEYCHAIN_PATH"
  security unlock-keychain -p "$password" "$KEYCHAIN_PATH"
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
      | head -n 1 || true
  )"

  if [[ -z "$identity_line" ]]; then
    printf '%s\n' "-"
    return
  fi

  printf '%s\n' "$identity_line" | sed -E 's/.*"(.+)".*/\1/'
}

stop_running_apps() {
  local stopped=0
  for process_name in VibeDrop "VibeDrop Native" VibeDropMacApp voicedrop; do
    if pgrep -x "$process_name" >/dev/null 2>&1; then
      log "Stopping running process: $process_name"
      pkill -x "$process_name" || true
      stopped=1
    fi
  done

  if [[ $stopped -eq 1 ]]; then
    sleep 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install)
      INSTALL=1
      shift
      ;;
    --skip-install)
      INSTALL=0
      shift
      ;;
    --no-sign)
      NO_SIGN=1
      FORWARD_ARGS+=("$1")
      shift
      ;;
    --sign-identity)
      [[ $# -ge 2 ]] || fail "--sign-identity requires an identity"
      USER_SIGN_IDENTITY=1
      FORWARD_ARGS+=("$1" "$2")
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      FORWARD_ARGS+=("$1")
      shift
      ;;
  esac
done

SIGNING_ARGS=()
if [[ $NO_SIGN -eq 0 && $USER_SIGN_IDENTITY -eq 0 ]]; then
  command -v security >/dev/null 2>&1 || fail "Missing required command: security"
  unlock_signing_keychain
  SIGNING_IDENTITY="$(resolve_codesign_identity)"
  if [[ "$SIGNING_IDENTITY" == "-" && "$ALLOW_ADHOC_FALLBACK" != "1" ]]; then
    fail "No stable codesign identity found under $KEYCHAIN_PATH matching \"$CERT_NAME_PATTERN\". Set CODESIGN_IDENTITY or ALLOW_ADHOC_FALLBACK=1."
  fi
  if [[ "$SIGNING_IDENTITY" == "-" ]]; then
    log "Falling back to ad-hoc signing. macOS Accessibility permission may need re-approval after updates."
  else
    log "Using stable codesign identity: $SIGNING_IDENTITY"
  fi
  SIGNING_ARGS=(--sign-identity "$SIGNING_IDENTITY")
fi

if [[ $INSTALL -eq 1 ]]; then
  stop_running_apps
else
  FORWARD_ARGS+=(--skip-install)
fi

APP_VERSION="${APP_VERSION:-0.2.0-native}" \
APP_BUILD="${APP_BUILD:-2}" \
"$SCRIPT_DIR/deploy-native-macos.sh" \
  --with-share-extension \
  --app-name "VibeDrop" \
  --bundle-id "com.vibedrop.desktop" \
  --install-dir "/Applications" \
  "${SIGNING_ARGS[@]}" \
  "${FORWARD_ARGS[@]}"
