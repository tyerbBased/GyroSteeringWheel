#!/usr/bin/env bash
set -e
TOOLS_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(dirname "$TOOLS_DIR")"

if ! ls "$TOOLS_DIR"/jdk-17* >/dev/null 2>&1 || [ ! -d "$TOOLS_DIR/gradle-8.7" ] || [ ! -d "$TOOLS_DIR/android-sdk" ]; then
    echo "Toolchain not found — running setup first (downloads JDK 17, Android SDK, Gradle)..."
    "$TOOLS_DIR/setup_toolchain.sh"
fi

export JAVA_HOME=$(echo "$TOOLS_DIR"/jdk-17*)
cd "$REPO_DIR/android-app"
if [ ! -f local.properties ]; then
    echo "sdk.dir=$TOOLS_DIR/android-sdk" > local.properties
fi

"$TOOLS_DIR"/gradle-8.7/bin/gradle assembleDebug --no-daemon

APK="$REPO_DIR/android-app/app/build/outputs/apk/debug/app-debug.apk"
echo "APK built: $APK"
