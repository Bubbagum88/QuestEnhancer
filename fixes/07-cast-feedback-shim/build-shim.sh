#!/bin/sh
# Builds the shim's .so files into module/zygisk, with xHook's licence beside them.
# Used by CI (both the fixes job and the APK build, which bundles this module).
#   sh build-shim.sh /path/to/ndk     (NDK r26b is what CI uses)
set -eu
NDK=$1
HERE=$(cd "$(dirname "$0")" && pwd)
# xHook (iqiyi/xHook v1.2.0, MIT), vendored at build time.
X=$(mktemp -d)
git clone -q --depth 1 --branch v1.2.0 https://github.com/iqiyi/xHook "$X"
mkdir -p "$HERE/jni/xhook"
cp "$X"/libxhook/jni/*.c "$X"/libxhook/jni/*.h "$HERE/jni/xhook/"
cp "$X/LICENSE" "$HERE/jni/xhook/LICENSE"
(cd "$HERE" && "$NDK/ndk-build" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=jni/Android.mk NDK_APPLICATION_MK=jni/Application.mk)
rm -f "$HERE/module/zygisk/.gitkeep"
mkdir -p "$HERE/module/zygisk"
cp "$HERE/libs/arm64-v8a/libqecastshim.so"   "$HERE/module/zygisk/arm64-v8a.so"
cp "$HERE/libs/armeabi-v7a/libqecastshim.so" "$HERE/module/zygisk/armeabi-v7a.so"
cp "$X/LICENSE" "$HERE/module/LICENSE-xHook"
rm -rf "$X"
ls -l "$HERE/module/zygisk"
