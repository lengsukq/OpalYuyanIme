#!/usr/bin/env bash
set -euo pipefail

# Sync the shared OpalDesk signing keystore (opaldesk.p12) into this repository,
# verify its alias/password/SHA-256 fingerprint, and optionally configure GitHub Actions Secrets.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOCAL_KS="$ROOT_DIR/opaldesk.p12"
LOCAL_PROPS="$ROOT_DIR/local.properties"
SOURCE_DIR="$(cd "$ROOT_DIR/.." && pwd)/OpalDesk"
ALIAS="opaldesk"
PUSH_GITHUB=0
GH_REPO=""
FORCE_COPY=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --source-dir <dir>   Path to sibling OpalDesk repo (default: $SOURCE_DIR)
  --force-copy         Overwrite local opaldesk.p12 from --source-dir even if it already exists
  --push-github        Upload OPALDESK_KEYSTORE_BASE64, OPALDESK_KEYSTORE_PASS, and
                       OPALDESK_EXPECTED_CERT_SHA256 to GitHub Actions Secrets via gh CLI
  --repo <owner/name>  Target GitHub repo for --push-github (default: current origin repo)
  -h, --help           Show this help message
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-dir)
      SOURCE_DIR="$2"
      shift 2
      ;;
    --force-copy)
      FORCE_COPY=1
      shift
      ;;
    --push-github)
      PUSH_GITHUB=1
      shift
      ;;
    --repo)
      GH_REPO="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

find_keytool() {
  local cand found
  for cand in \
    "${JAVA_HOME:-}" \
    "/Volumes/Su-SSD/dependencies/jdk-21" \
    "/Volumes/Su-SSD/dependencies/jdk-17" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    if [[ -n "$cand" && -x "$cand/bin/keytool" ]]; then
      echo "$cand/bin/keytool"
      return 0
    fi
  done
  if [[ -d "/Volumes/Su-SSD/dependencies" ]]; then
    found="$(find /Volumes/Su-SSD/dependencies -maxdepth 6 -type f -path '*/bin/keytool' 2>/dev/null | head -n 1 || true)"
    if [[ -n "$found" && -x "$found" ]]; then
      echo "$found"
      return 0
    fi
  fi
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    cand="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
    if [[ -n "$cand" && -x "$cand/bin/keytool" ]]; then
      echo "$cand/bin/keytool"
      return 0
    fi
  fi
  if command -v keytool >/dev/null 2>&1; then
    command -v keytool
    return 0
  fi
  return 1
}

sha256_stdin() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 | awk '{print tolower($1)}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{print tolower($1)}'
  else
    openssl dgst -sha256 -r | awk '{print tolower($1)}'
  fi
}

read_prop() {
  local file="$1"
  local key="$2"
  if [[ -f "$file" ]]; then
    sed -nE "s/^${key}=(.*)$/\\1/p" "$file" | head -n 1 | tr -d '\r' || true
  fi
}

SOURCE_KS="$SOURCE_DIR/opaldesk.p12"
SOURCE_PROPS="$SOURCE_DIR/local.properties"

if [[ ! -f "$LOCAL_KS" || "$FORCE_COPY" == "1" ]]; then
  if [[ ! -f "$SOURCE_KS" ]]; then
    echo "ERROR: Source keystore not found at $SOURCE_KS" >&2
    exit 1
  fi
  cp -f "$SOURCE_KS" "$LOCAL_KS"
  chmod 600 "$LOCAL_KS"
  echo "==> Copied $SOURCE_KS -> $LOCAL_KS (chmod 600)"
else
  chmod 600 "$LOCAL_KS"
  echo "==> Using existing local keystore: $LOCAL_KS"
fi

if git -C "$ROOT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  if ! git -C "$ROOT_DIR" check-ignore -q "$LOCAL_KS"; then
    echo "ERROR: $LOCAL_KS is NOT ignored by .gitignore! Refusing to proceed." >&2
    exit 1
  fi
  if [[ -f "$LOCAL_PROPS" ]] && ! git -C "$ROOT_DIR" check-ignore -q "$LOCAL_PROPS"; then
    echo "ERROR: $LOCAL_PROPS is NOT ignored by .gitignore! Refusing to proceed." >&2
    exit 1
  fi
  echo "==> Verified opaldesk.p12 and local.properties are ignored by .gitignore"
fi

PASS="${OPALDESK_KEYSTORE_PASS:-}"
if [[ -z "$PASS" ]]; then
  PASS="$(read_prop "$LOCAL_PROPS" "opaldesk\\.keystore\\.password")"
fi
if [[ -z "$PASS" ]]; then
  PASS="$(read_prop "$SOURCE_PROPS" "opaldesk\\.keystore\\.password")"
  if [[ -n "$PASS" ]]; then
    touch "$LOCAL_PROPS"
    chmod 600 "$LOCAL_PROPS"
    printf '\nopaldesk.keystore.password=%s\n' "$PASS" >> "$LOCAL_PROPS"
    echo "==> Synced opaldesk.keystore.password into ignored local.properties"
  fi
fi
if [[ -z "$PASS" ]]; then
  echo "ERROR: Keystore password not found in OPALDESK_KEYSTORE_PASS, local.properties, or $SOURCE_PROPS" >&2
  exit 1
fi

KEYTOOL="$(find_keytool || true)"
if [[ -z "$KEYTOOL" ]]; then
  echo "ERROR: keytool not found." >&2
  exit 1
fi

# Pass keystore password via scoped environment variable so it never appears in process argv.
CERT_SHA256="$(
  OPALDESK_KEYTOOL_PASS="$PASS" "$KEYTOOL" -exportcert \
    -keystore "$LOCAL_KS" \
    -storetype PKCS12 \
    -alias "$ALIAS" \
    -storepass:env OPALDESK_KEYTOOL_PASS \
    | sha256_stdin
)"
if [[ -z "$CERT_SHA256" || ${#CERT_SHA256} -ne 64 ]]; then
  echo "ERROR: Failed to unlock $LOCAL_KS (alias '$ALIAS') or compute SHA-256 fingerprint." >&2
  exit 1
fi

echo "==> Verified keystore unlock (alias: $ALIAS)"
echo "    Certificate SHA-256 : $CERT_SHA256"

if [[ "$PUSH_GITHUB" == "1" ]]; then
  if ! command -v gh >/dev/null 2>&1; then
    echo "ERROR: GitHub CLI (gh) not found." >&2
    exit 1
  fi
  GH_REPO_ARGS=()
  if [[ -n "$GH_REPO" ]]; then
    GH_REPO_ARGS=(-R "$GH_REPO")
  fi
  echo "==> Uploading GitHub Actions Secrets ..."
  base64 < "$LOCAL_KS" | tr -d '\r\n' | gh secret set OPALDESK_KEYSTORE_BASE64 "${GH_REPO_ARGS[@]}"
  printf '%s' "$PASS" | gh secret set OPALDESK_KEYSTORE_PASS "${GH_REPO_ARGS[@]}"
  printf '%s' "$CERT_SHA256" | gh secret set OPALDESK_EXPECTED_CERT_SHA256 "${GH_REPO_ARGS[@]}"
  echo "==> Configured GitHub Actions Secrets:"
  gh secret list "${GH_REPO_ARGS[@]}"
fi
