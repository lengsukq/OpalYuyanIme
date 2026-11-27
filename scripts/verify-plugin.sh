#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

EXT_GRADLE_HOME="/Volumes/Su-SSD/dependencies/gradle"
if [[ ! -d "$EXT_GRADLE_HOME" ]]; then
  echo "ERROR: External Gradle home not found at $EXT_GRADLE_HOME (refusing internal SSD)." >&2
  exit 1
fi

JDK_HOME="/Volumes/Su-SSD/dependencies/jdk-17"
if [[ ! -x "$JDK_HOME/bin/java" ]]; then
  found="$(find /Volumes/Su-SSD/dependencies -maxdepth 6 -type f -path '*/bin/java' 2>/dev/null | head -n 1 || true)"
  if [[ -n "$found" ]]; then
    JDK_HOME="$(dirname "$(dirname "$found")")"
  else
    echo "ERROR: No JDK found under /Volumes/Su-SSD/dependencies." >&2
    exit 1
  fi
fi

export GRADLE_USER_HOME="$EXT_GRADLE_HOME"
export JAVA_HOME="$JDK_HOME"
export ANDROID_SDK_ROOT="/Volumes/Su-SSD/dependencies/android-sdk"
export ANDROID_HOME="/Volumes/Su-SSD/dependencies/android-sdk"
export PATH="$JAVA_HOME/bin:$PATH"

echo "==> External GRADLE_USER_HOME=$GRADLE_USER_HOME (100% on /Volumes/Su-SSD)"
echo "==> JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -n 2

cd "$ROOT_DIR"
TASKS=(":app:testDebugUnitTest" ":app:assembleDebug")
if [[ $# -gt 0 ]]; then
  TASKS=("$@")
fi

COMMON_ARGS=("--gradle-user-home=$GRADLE_USER_HOME" "--console=plain")
if ./gradlew --offline "${COMMON_ARGS[@]}" "${TASKS[@]}"; then
  echo "==> Offline plugin verification & APK assembly succeeded!"
  exit 0
fi

./gradlew "${COMMON_ARGS[@]}" "${TASKS[@]}"
