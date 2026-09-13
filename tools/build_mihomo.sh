#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
test "$(git -C .upstream rev-parse HEAD)" = ac017cdd246ce8bd547653d927e7bf77d7ee73d5
cp -r native/bridge .upstream/bichenbridge
NDK="$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd .upstream
go test -tags with_gvisor,cmfa -v ./bichenbridge
for target in 'arm64-v8a arm64 aarch64' 'x86_64 amd64 x86_64'; do
 read -r abi arch compiler <<< "$target"
 mkdir -p "$ROOT/android-app/app/src/main/jniLibs/$abi"
 CGO_ENABLED=1 GOOS=android GOARCH="$arch" CC="$NDK/$compiler-linux-android26-clang" go build -tags with_gvisor,cmfa -trimpath -buildmode=c-shared -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384' -o "$ROOT/android-app/app/src/main/jniLibs/$abi/libbichen_core.so" ./bichenbridge
done
mkdir -p "$ROOT/android-app/app/src/main/assets"
cp LICENSE "$ROOT/android-app/app/src/main/assets/MIHOMO-LICENSE"
git rev-parse HEAD > "$ROOT/android-app/app/src/main/assets/mihomo-revision.txt"
go version > "$ROOT/android-app/app/src/main/assets/mihomo-build.txt"
printf '%s\n' 'tags=with_gvisor,cmfa; ndk=28.2.13676358; androidApi=26; pageSize=16384' >> "$ROOT/android-app/app/src/main/assets/mihomo-build.txt"
