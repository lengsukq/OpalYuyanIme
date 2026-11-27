#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

EXT_GRADLE_HOME="/Volumes/Su-SSD/dependencies/gradle"
USE_EXTERNAL_SSD=0
if [[ -d "$EXT_GRADLE_HOME" ]]; then
  USE_EXTERNAL_SSD=1
  export GRADLE_USER_HOME="$EXT_GRADLE_HOME"
fi

detect_jdk() {
  local found cand ver major best17=""
  for cand in \
    "${JAVA_HOME:-}" \
    "/Volumes/Su-SSD/dependencies/jdk-21" \
    "/Volumes/Su-SSD/dependencies/gradle/jdks"/*/jdk-21*/Contents/Home \
    "$HOME/.gradle/jdks"/*/jdk-21*/Contents/Home \
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
        elif [[ -n "$major" && "$major" -ge 17 && -z "$best17" ]]; then
          best17="$cand"
        fi
      fi
    done < <(find /Volumes/Su-SSD/dependencies -maxdepth 6 -type f -path '*/bin/java' 2>/dev/null || true)
  fi

  if [[ -n "$best17" ]]; then
    echo "$best17"
    return 0
  fi
  if command -v java >/dev/null 2>&1; then
    dirname "$(dirname "$(command -v java)")"
    return 0
  fi
  return 1
}

JDK_HOME="$(detect_jdk || true)"
if [[ -z "$JDK_HOME" ]]; then
  echo "ERROR: No JDK 17/21 found." >&2
  exit 1
fi

export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ -z "${ANDROID_HOME:-}" && -d "/Volumes/Su-SSD/dependencies/android-sdk" ]]; then
  export ANDROID_HOME="/Volumes/Su-SSD/dependencies/android-sdk"
  export ANDROID_SDK_ROOT="/Volumes/Su-SSD/dependencies/android-sdk"
fi
if [[ -z "${ANDROID_USER_HOME:-}" && -d "/Volumes/Su-SSD/dependencies/.android" ]]; then
  export ANDROID_USER_HOME="/Volumes/Su-SSD/dependencies/.android"
fi

if [[ "$USE_EXTERNAL_SSD" == "1" ]]; then
  echo "==> External GRADLE_USER_HOME=$GRADLE_USER_HOME (on /Volumes/Su-SSD)"
fi
echo "==> JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -n 2

cd "$ROOT_DIR"
TASKS=(":app:testDebugUnitTest" ":app:assembleDebug")
if [[ $# -gt 0 ]]; then
  TASKS=("$@")
fi

COMMON_ARGS=("--console=plain")
if [[ "$USE_EXTERNAL_SSD" == "1" ]]; then
  COMMON_ARGS+=("--gradle-user-home=$GRADLE_USER_HOME")
fi

chmod +x ./gradlew
if ./gradlew --offline "${COMMON_ARGS[@]}" "${TASKS[@]}"; then
  echo "==> Offline plugin verification & APK assembly succeeded!"
  exit 0
fi

./gradlew "${COMMON_ARGS[@]}" "${TASKS[@]}"
