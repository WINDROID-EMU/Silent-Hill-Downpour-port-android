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

chmod +x gradlew
./gradlew assembleDebug "$@"

echo "=========================================================="
echo " Build finished successfully!"
echo " APK location: app/build/outputs/apk/debug/app-debug.apk"
echo "=========================================================="
