#!/usr/bin/env bash
set -e
TOOLS_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$TOOLS_DIR"

if ! command -v curl >/dev/null || ! command -v unzip >/dev/null || ! command -v tar >/dev/null; then
    echo "Need curl, unzip and tar installed (e.g. sudo apt install curl unzip tar)."
    exit 1
fi

if ls jdk-17* >/dev/null 2>&1; then
    echo "JDK 17 already present."
else
    echo "Downloading JDK 17 (Adoptium, ~185 MB)..."
    curl -L -o jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
    tar xzf jdk17.tar.gz && rm jdk17.tar.gz
fi
JDK_DIR=$(echo "$TOOLS_DIR"/jdk-17*)

if [ -d android-sdk/cmdline-tools/latest ]; then
    echo "Android cmdline-tools already present."
else
    echo "Downloading Android cmdline-tools (~150 MB)..."
    curl -L -o cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
    mkdir -p android-sdk/cmdline-tools
    unzip -q cmdtools.zip -d android-sdk/cmdline-tools
    mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest
    rm cmdtools.zip
fi

export JAVA_HOME="$JDK_DIR"

echo "Accepting licenses and installing SDK packages (platform-34, build-tools 34.0.0)..."
yes | android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$TOOLS_DIR/android-sdk" --licenses >/dev/null 2>&1 || true
android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$TOOLS_DIR/android-sdk" \
    "platforms;android-34" "build-tools;34.0.0" "platform-tools"

if [ -d gradle-8.7 ]; then
    echo "Gradle 8.7 already present."
else
    echo "Downloading Gradle 8.7 (~130 MB)..."
    curl -L -o gradle.zip "https://services.gradle.org/distributions/gradle-8.7-bin.zip"
    unzip -q gradle.zip && rm gradle.zip
fi

echo "sdk.dir=$TOOLS_DIR/android-sdk" > "$TOOLS_DIR/../android-app/local.properties"
echo "Toolchain ready. Build with: tools/build_apk.sh"
