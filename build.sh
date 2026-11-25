#!/usr/bin/env bash
# Build build/aa-tweak.dex from src/AaTweak.java.
# Needs a JDK (11 or newer) and the Android SDK with build-tools and at least one platform installed.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "Set ANDROID_HOME to your Android SDK folder." >&2
  exit 1
fi

# Newest platform android.jar and newest build-tools.
PLATFORM_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)
BUILD_TOOLS=$(ls -d "$SDK"/build-tools/[0-9]*/ 2>/dev/null | sort -V | tail -1 || true)
if [ -z "$PLATFORM_JAR" ]; then
  echo "No Android platform in $SDK/platforms. Install one, e.g.: sdkmanager \"platforms;android-36\"" >&2
  exit 1
fi
if [ -z "$BUILD_TOOLS" ]; then
  echo "No build-tools in $SDK/build-tools. Install them, e.g.: sdkmanager \"build-tools;36.0.0\"" >&2
  exit 1
fi
D8="${BUILD_TOOLS%/}/d8"
[ -f "$D8" ] || D8="${BUILD_TOOLS%/}/d8.bat"

rm -rf build
mkdir -p build/classes
javac --release 11 -Xlint:-options -cp "$PLATFORM_JAR" -d build/classes src/AaTweak.java
"$D8" --release --min-api 29 --lib "$PLATFORM_JAR" --output build build/classes/*.class
mv build/classes.dex build/aa-tweak.dex
echo "Built build/aa-tweak.dex"
