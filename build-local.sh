#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

die() {
  echo "build-local: $*" >&2
  exit 1
}

pick_dir() {
  local candidate
  for candidate in "$@"; do
    if [[ -n "$candidate" && -d "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

pick_file() {
  local candidate
  for candidate in "$@"; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

JAVA_HOME_VALUE="${JAVA_HOME:-}"
if [[ -z "$JAVA_HOME_VALUE" || ! -x "$JAVA_HOME_VALUE/bin/java" ]]; then
  JAVA_HOME_VALUE="$(pick_dir /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/temurin-17-jdk-amd64)" ||
    die "JDK 17 not found; set JAVA_HOME"
fi

ANDROID_SDK_VALUE="$(pick_dir "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" /workspace/android-sdk "$PROJECT_DIR/.android-sdk")" ||
  die "Android SDK not found; set ANDROID_SDK_ROOT (platform 35 and build-tools 35.0.0 are required)"

[[ -f "$ANDROID_SDK_VALUE/platforms/android-35/android.jar" ]] ||
  die "Android platform 35 is missing under $ANDROID_SDK_VALUE"
[[ -x "$ANDROID_SDK_VALUE/build-tools/35.0.0/aapt2" ]] ||
  die "Android build-tools 35.0.0 are missing under $ANDROID_SDK_VALUE"

GRADLE_HOME_VALUE="${GRADLE_USER_HOME:-}"
if [[ -z "$GRADLE_HOME_VALUE" ||
      ! -d "$GRADLE_HOME_VALUE/caches/modules-2/files-2.1/com.android.tools.build/gradle/8.7.3" ]]; then
  GRADLE_HOME_VALUE=""
  for candidate in /workspace/dms-gradle-home/.gradle /workspace/gradle-cache5 /workspace/gradle-cache4 "$PROJECT_DIR/.gradle-local"; do
    if [[ -d "$candidate/caches/modules-2/files-2.1/com.android.tools.build/gradle/8.7.3" ]]; then
      GRADLE_HOME_VALUE="$candidate"
      break
    fi
  done
fi
[[ -n "$GRADLE_HOME_VALUE" ]] ||
  die "Gradle dependency cache is missing; restore the documented cache snapshot or set GRADLE_USER_HOME"

GRADLE_BIN_VALUE="${GRADLE_BIN:-}"
if [[ -z "$GRADLE_BIN_VALUE" || ! -x "$GRADLE_BIN_VALUE" ]]; then
  GRADLE_BIN_VALUE="$(pick_file /workspace/gradle-dist/gradle-8.9/bin/gradle "$GRADLE_HOME_VALUE/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle")" ||
    die "Gradle 8.9 is missing; set GRADLE_BIN to an executable Gradle 8.9 binary"
fi

ANDROID_USER_HOME_VALUE="${ANDROID_USER_HOME:-/workspace/dms-android-home}"
mkdir -p "$ANDROID_USER_HOME_VALUE"

export JAVA_HOME="$JAVA_HOME_VALUE"
export ANDROID_HOME="$ANDROID_SDK_VALUE"
export ANDROID_SDK_ROOT="$ANDROID_SDK_VALUE"
export ANDROID_USER_HOME="$ANDROID_USER_HOME_VALUE"
export GRADLE_USER_HOME="$GRADLE_HOME_VALUE"

tasks=("$@")
if [[ ${#tasks[@]} -eq 0 ]]; then
  tasks=(testDebugUnitTest assembleDebug assembleRelease)
fi

gradle_args=(--no-daemon --stacktrace)
if [[ "${DMS_ALLOW_NETWORK:-0}" != "1" ]]; then
  gradle_args+=(--offline)
fi

if [[ ! -d "$GRADLE_USER_HOME/caches/modules-2/files-2.1/com.android.tools.lint/lint-gradle/31.7.3" ]]; then
  gradle_args+=(-x lintVitalRelease)
  echo "build-local: lint-gradle 31.7.3 is not cached; lintVitalRelease is excluded" >&2
fi

echo "build-local: java=$JAVA_HOME"
echo "build-local: sdk=$ANDROID_SDK_ROOT"
echo "build-local: gradle=$GRADLE_BIN_VALUE"
echo "build-local: cache=$GRADLE_USER_HOME"

exec "$GRADLE_BIN_VALUE" "${gradle_args[@]}" "${tasks[@]}"
