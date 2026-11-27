#!/usr/bin/env bash
set -euo pipefail

# Build, test, verify, and optionally install the OpalYuyanIme companion plugin APK (:app).
# Works both locally (respecting /Volumes/Su-SSD/dependencies on Mac mini) and in GitHub Actions CI.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

SKIP_TESTS=0
ALLOW_UNSIGNED=0
NO_DAEMON=0
DO_INSTALL=0
INSTALL_TARGET=""
OUT_DIR="$ROOT_DIR/build/out"
ASSET_TAG=""

usage() {
  cat <<'EOF'
Usage: ./scripts/build-plugin.sh [options]

Options:
  --output-dir <dir>        Directory to stage OpalYuyanIme_v<ver>.apk (default: build/out)
  --tag <tag>               Override version tag in filename (e.g. v1.0.20 -> OpalYuyanIme_v1.0.20.apk)
  --skip-tests              Skip :app:testDebugUnitTest
  --allow-unsigned          Allow unsigned verification APK if no keystore is present
  --no-daemon               Pass --no-daemon to Gradle (recommended in CI)
  --install [serial|ip:pt]  Install built APK via adb (auto-selects connected device if omitted)
  -h, --help                Show this help message
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output-dir)
      OUT_DIR="$2"
      shift 2
      ;;
    --tag)
      ASSET_TAG="$2"
      shift 2
      ;;
    --skip-tests)
      SKIP_TESTS=1
      shift
      ;;
    --allow-unsigned)
      ALLOW_UNSIGNED=1
      shift
      ;;
    --no-daemon)
      NO_DAEMON=1
      shift
      ;;
    --install)
      DO_INSTALL=1
      if [[ $# -ge 2 && "$2" != -* ]]; then
        INSTALL_TARGET="$2"
        shift 2
      else
        shift
      fi
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

detect_jdk21() {
  local cand found ver major
  for cand in \
    "${JAVA_HOME:-}" \
    "/Volumes/Su-SSD/dependencies/jdk-21" \
    "/Volumes/Su-SSD/dependencies/gradle/jdks"/*/jdk-21*/Contents/Home \
    "$HOME/.gradle/jdks"/*/jdk-21*/Contents/Home \
    "$HOME/.gradle/jdks"/*/jdk-21* \
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    "$HOME/Library/Java/JavaVirtualMachines"/*21*/Contents/Home \
    /Library/Java/JavaVirtualMachines/*21*/Contents/Home; do
    if [[ -n "$cand" && -x "$cand/bin/java" ]]; then
      ver="$("$cand/bin/java" -version 2>&1 | head -n 1 || true)"
      major="$(echo "$ver" | sed -nE 's/.*version "([0-9]+).*/\1/p' || true)"
      if [[ -n "$major" && "$major" -ge 21 ]]; then
        echo "$cand"
        return 0
      fi
    fi
  done
  if [[ -d "/Volumes/Su-SSD/dependencies" ]]; then
    while IFS= read -r found; do
      [[ -z "$found" ]] && continue
      cand="$(dirname "$(dirname "$found")")"
      if [[ -x "$cand/bin/java" ]]; then
        ver="$("$cand/bin/java" -version 2>&1 | head -n 1 || true)"
        major="$(echo "$ver" | sed -nE 's/.*version "([0-9]+).*/\1/p' || true)"
        if [[ -n "$major" && "$major" -ge 21 ]]; then
          echo "$cand"
          return 0
        fi
      fi
    done < <(find /Volumes/Su-SSD/dependencies -maxdepth 6 -type f -path '*/bin/java' 2>/dev/null || true)
  fi
  if command -v java >/dev/null 2>&1; then
    ver="$(java -version 2>&1 | head -n 1 || true)"
    major="$(echo "$ver" | sed -nE 's/.*version "([0-9]+).*/\1/p' || true)"
    if [[ -n "$major" && "$major" -ge 21 ]]; then
      dirname "$(dirname "$(command -v java)")"
      return 0
    fi
  fi
  return 1
}

detect_android_sdk() {
  local prop_sdk=""
  if [[ -f "$ROOT_DIR/local.properties" ]]; then
    prop_sdk="$(sed -nE 's/^sdk\.dir=(.*)$/\1/p' "$ROOT_DIR/local.properties" | head -n 1 | tr -d '\r' || true)"
  fi
  local cand
  for cand in \
    "${ANDROID_HOME:-}" \
    "${ANDROID_SDK_ROOT:-}" \
    "$prop_sdk" \
    "/Volumes/Su-SSD/dependencies/android-sdk" \
    "$HOME/Library/Android/sdk" \
    "$HOME/Android/Sdk"; do
    if [[ -n "$cand" && -d "$cand" ]]; then
      echo "$cand"
      return 0
    fi
  done
  return 1
}

JDK_HOME="$(detect_jdk21 || true)"
if [[ -z "$JDK_HOME" ]]; then
  echo "ERROR: JDK 21+ is required to build OpalYuyanIme." >&2
  exit 1
fi
export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

SDK_DIR="$(detect_android_sdk || true)"
if [[ -z "$SDK_DIR" ]]; then
  echo "ERROR: Android SDK not found (check ANDROID_HOME or local.properties)." >&2
  exit 1
fi
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"

GRADLE_FLAGS=("--console=plain")
if [[ -z "${CI:-}" && -z "${GRADLE_USER_HOME:-}" && -d "/Volumes/Su-SSD/dependencies/gradle" ]]; then
  export GRADLE_USER_HOME="/Volumes/Su-SSD/dependencies/gradle"
  GRADLE_FLAGS+=("--gradle-user-home=$GRADLE_USER_HOME")
fi
if [[ -z "${ANDROID_USER_HOME:-}" && -d "/Volumes/Su-SSD/dependencies/.android" ]]; then
  export ANDROID_USER_HOME="/Volumes/Su-SSD/dependencies/.android"
fi

BUILD_TOOLS_DIR="$(find "$SDK_DIR/build-tools" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)"
AAPT2_BIN="${BUILD_TOOLS_DIR:+$BUILD_TOOLS_DIR/aapt2}"
APKSIGNER_BIN="${BUILD_TOOLS_DIR:+$BUILD_TOOLS_DIR/apksigner}"
ADB_BIN="$SDK_DIR/platform-tools/adb"

cd "$ROOT_DIR"

SIBLING_KS="$(cd "$ROOT_DIR/.." && pwd)/OpalDesk/opaldesk.p12"
if [[ -z "${OPALDESK_KEYSTORE:-}" && ! -f "$ROOT_DIR/opaldesk.p12" && ! -f "$SIBLING_KS" && "$ALLOW_UNSIGNED" != "1" ]]; then
  echo "ERROR: Signing keystore not found (opaldesk.p12, OPALDESK_KEYSTORE, or ../OpalDesk/opaldesk.p12)." >&2
  echo "Run ./scripts/sync-signing-secrets.sh first, or pass --allow-unsigned for unsigned CI verification." >&2
  exit 1
fi

GRADLE_TASKS=()
if [[ "$SKIP_TESTS" != "1" ]]; then
  GRADLE_TASKS+=(":app:testDebugUnitTest")
fi
GRADLE_TASKS+=(":app:assembleRelease")

if [[ "$NO_DAEMON" == "1" ]]; then
  GRADLE_FLAGS+=("--no-daemon")
fi
if [[ "$ALLOW_UNSIGNED" == "1" ]]; then
  GRADLE_FLAGS+=("-PallowUnsignedRelease=true")
fi

echo "==> Building OpalYuyanIme (:app) with JAVA_HOME=$JAVA_HOME"
chmod +x ./gradlew
./gradlew "${GRADLE_TASKS[@]}" "${GRADLE_FLAGS[@]}"

VER="$(sed -nE 's/.*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$ROOT_DIR/app/build.gradle.kts" | head -n 1)"
VER="${VER:-0.0.1}"
VER_CODE="$(sed -nE 's/.*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$ROOT_DIR/app/build.gradle.kts" | head -n 1)"
VER_CODE="${VER_CODE:-1}"
FILE_TAG="${ASSET_TAG:-v${VER}}"

APK_DIR="$ROOT_DIR/app/build/outputs/apk/release"
SIGNED_APK="$(find "$APK_DIR" -maxdepth 1 -name '*-release.apk' | head -n 1 || true)"
UNSIGNED_APK="$(find "$APK_DIR" -maxdepth 1 -name '*-unsigned.apk' | head -n 1 || true)"

IS_SIGNED="false"
SRC_APK=""
APK_NAME=""
if [[ -n "$SIGNED_APK" && -f "$SIGNED_APK" ]]; then
  SRC_APK="$SIGNED_APK"
  APK_NAME="OpalYuyanIme_${FILE_TAG}.apk"
  IS_SIGNED="true"
elif [[ -n "$UNSIGNED_APK" && -f "$UNSIGNED_APK" ]]; then
  if [[ "$ALLOW_UNSIGNED" != "1" ]]; then
    echo "ERROR: Expected signed release APK, but only unsigned APK was generated." >&2
    exit 1
  fi
  SRC_APK="$UNSIGNED_APK"
  APK_NAME="OpalYuyanIme_${FILE_TAG}-unsigned.apk"
else
  echo "ERROR: No release APK found under $APK_DIR" >&2
  exit 1
fi

if [[ -n "$AAPT2_BIN" && -x "$AAPT2_BIN" ]]; then
  BADGING="$("$AAPT2_BIN" dump badging "$SRC_APK")"
  echo "$BADGING" | grep -E "^(package:|sdkVersion:|minSdkVersion:|targetSdkVersion:|application-label:)" || true
  if ! echo "$BADGING" | grep -q "name='io.github.lengsukq.opaldesk.ime'"; then
    echo "ERROR: Unexpected package name in $SRC_APK" >&2
    exit 1
  fi
  if ! echo "$BADGING" | grep -qE "(minSdkVersion|sdkVersion):'30'"; then
    echo "ERROR: Expected minSdkVersion 30 in $SRC_APK" >&2
    exit 1
  fi
fi

if [[ "$IS_SIGNED" == "true" && -n "$APKSIGNER_BIN" && -x "$APKSIGNER_BIN" ]]; then
  echo "==> Verifying APK signature (minSdk 30 -> V3 scheme) ..."
  SIGN_OUT="$("$APKSIGNER_BIN" verify --verbose --print-certs "$SRC_APK")"
  echo "$SIGN_OUT" | grep -E "Verified using v[123] scheme|certificate DN|certificate SHA-256" || true
  if ! echo "$SIGN_OUT" | grep -q "Verified using v3 scheme.*: true"; then
    echo "ERROR: APK failed v3 scheme verification!" >&2
    exit 1
  fi
  if [[ -n "${OPALDESK_EXPECTED_CERT_SHA256:-}" ]]; then
    EXP_LOWER="$(echo "$OPALDESK_EXPECTED_CERT_SHA256" | tr '[:upper:]' '[:lower:]')"
    if ! echo "$SIGN_OUT" | grep -iq "$EXP_LOWER"; then
      echo "ERROR: APK certificate SHA-256 does not match OPALDESK_EXPECTED_CERT_SHA256!" >&2
      exit 1
    fi
    echo "==> Certificate SHA-256 matched OPALDESK_EXPECTED_CERT_SHA256"
  fi
fi

mkdir -p "$OUT_DIR"
DEST_APK="$OUT_DIR/$APK_NAME"
cp -f "$SRC_APK" "$DEST_APK"

if command -v shasum >/dev/null 2>&1; then
  APK_SHA256="$(shasum -a 256 "$DEST_APK" | awk '{print $1}')"
else
  APK_SHA256="$(sha256sum "$DEST_APK" | awk '{print $1}')"
fi
printf '%s  %s\n' "$APK_SHA256" "$APK_NAME" > "${DEST_APK}.sha256"

echo "==> Staged plugin release artifact:"
ls -lh "$DEST_APK" "${DEST_APK}.sha256"
echo "    Version     : ${VER} (code ${VER_CODE})"
echo "    Signed      : ${IS_SIGNED}"
echo "    APK SHA-256 : ${APK_SHA256}"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "plugin_version=$VER"
    echo "plugin_version_code=$VER_CODE"
    echo "plugin_apk_name=$APK_NAME"
    echo "plugin_apk_path=$DEST_APK"
    echo "plugin_is_signed=$IS_SIGNED"
    echo "plugin_apk_sha256=$APK_SHA256"
  } >> "$GITHUB_OUTPUT"
fi

if [[ "$DO_INSTALL" == "1" ]]; then
  if [[ ! -x "$ADB_BIN" ]]; then
    ADB_BIN="$(command -v adb || true)"
  fi
  if [[ -z "$ADB_BIN" ]]; then
    echo "ERROR: adb not found for --install" >&2
    exit 1
  fi
  ADB_ARGS=()
  if [[ -n "$INSTALL_TARGET" ]]; then
    if [[ "$INSTALL_TARGET" == *:* ]]; then
      "$ADB_BIN" connect "$INSTALL_TARGET" || true
    fi
    ADB_ARGS=(-s "$INSTALL_TARGET")
  fi
  echo "==> Installing $APK_NAME onto device ${INSTALL_TARGET:-default} ..."
  "$ADB_BIN" "${ADB_ARGS[@]}" install -r "$DEST_APK"
fi
