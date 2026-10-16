#!/bin/bash

set -e

source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
  $BUILD/java \
  $BUILD/javac-output \
  $BUILD/src

source ../buildScript/init/env_gobin.sh
# 构建标签与 go test 共用一份（buildScript/lib/test.sh 也 source 它）
source ./build_tags.sh
export PATH="$GOBIN_DIR:$PATH"
mkdir -p "$BUILD"

# NDK r25's linker defaults to max-page-size=4096, producing a libgojni.so that cannot be
# dlopen'ed on 16 KB-page devices (Android 15+). Pass it through explicitly until the NDK
# pin moves to r27+, which defaults to 16 KB.
# -checklinkname=0: certs.go linknames crypto/x509.systemRootsMu (not marked
# linkname-able upstream) to lock writes to systemRoots; drop this if the Go
# upgrade ever removes the escape hatch and rework certs.go instead.
export GOBIND=gobind-matsuri
"$GOBIN_DIR"/gomobile-matsuri bind -v -androidapi 21 -target android/arm64,android/amd64 -cache "$(realpath $BUILD)" -trimpath -ldflags='-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384' -tags="$LIBCORE_BUILD_TAGS" . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"
