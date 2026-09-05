#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

export ANDROID_HOME="${ANDROID_HOME:-/home/windroid/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/26.1.10909125}"

echo "=========================================================="
echo " Building Silent Hill: Downpour Android Port"
echo " ANDROID_HOME:     $ANDROID_HOME"
echo " ANDROID_NDK_HOME: $ANDROID_NDK_HOME"
echo "=========================================================="

BUILD_TYPE="${1:-assembleRelease}"
if [ "$1" = "assembleDebug" ] || [ "$1" = "assembleRelease" ]; then
    shift
fi

chmod +x gradlew
./gradlew "$BUILD_TYPE" "$@"

echo "=========================================================="
echo " Build finished successfully!"
if [ -f "app/build/outputs/apk/release/app-release.apk" ]; then
    echo " APK location: app/build/outputs/apk/release/app-release.apk"
elif [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    echo " APK location: app/build/outputs/apk/debug/app-debug.apk"
fi
echo "=========================================================="
