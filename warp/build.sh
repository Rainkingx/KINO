#!/bin/sh
# Builds the WARP helper into the APK's native lib folders as a normal Android program (position independent, linked to
# Bionic) with the NDK's compiler. A plain static Linux build runs too, but antivirus engines (Ikarus, Google) call the
# static 32-bit one a "flooder". Needs Go and an Android NDK (ANDROID_NDK_HOME, else the newest in the SDK).
set -e
cd "$(dirname "$0")"
go mod tidy
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
NDK=${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)
build() { # $1 = ABI folder, $2 = GOARCH, $3 = clang target
  CGO_ENABLED=1 GOOS=android GOARCH=$2 GOARM=7 CC="$BIN/$3-clang" go build -trimpath -buildmode=pie -ldflags '-s -w' -o ../jni/$1/libwarp.so .
}
build arm64-v8a arm64 aarch64-linux-android26
build armeabi-v7a arm armv7a-linux-androideabi26
