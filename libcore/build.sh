#!/bin/bash

set -e

source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
  $BUILD/java \
  $BUILD/javac-output \
  $BUILD/src

# go install honours GOBIN over GOPATH/bin, and init.sh installs gomobile-matsuri
# wherever that resolves to; look in the same place.
GOBIN_DIR=$(go env GOBIN)
if [ -z "$GOBIN_DIR" ]; then
  if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
  fi
  GOBIN_DIR="$GOPATH/bin"
fi

export PATH="$GOBIN_DIR:$PATH"
mkdir -p "$BUILD"

# NDK r25's linker defaults to max-page-size=4096, producing a libgojni.so that cannot be
# dlopen'ed on 16 KB-page devices (Android 15+). Pass it through explicitly until the NDK
# pin moves to r27+, which defaults to 16 KB.
# -checklinkname=0: certs.go linknames crypto/x509.systemRootsMu (not marked
# linkname-able upstream) to lock writes to systemRoots; drop this if the Go
# upgrade ever removes the escape hatch and rework certs.go instead.
export GOBIND=gobind-matsuri
"$GOBIN_DIR"/gomobile-matsuri bind -v -androidapi 21 -target android/arm64,android/amd64 -cache "$(realpath $BUILD)" -trimpath -ldflags='-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384' -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"
