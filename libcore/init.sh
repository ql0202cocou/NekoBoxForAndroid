#!/bin/bash

set -e

chmod -R u+w .build 2>/dev/null || true
rm -rf .build 2>/dev/null || true

source ../buildScript/init/env_gobin.sh

# Install gomobile (vendored in ./gomobile, MatsuriDayo/gomobile @ master2).
# Always reinstall: go install is cached and cheap, and this picks up vendored
# source updates that the old [ ! -f ] guard would have kept stale forever.
pushd gomobile
pushd cmd
pushd gomobile
go install -v
popd
pushd gobind
go install -v
popd
popd
popd
mv -f "$GOBIN_DIR/gomobile" "$GOBIN_DIR/gomobile-matsuri"
mv -f "$GOBIN_DIR/gobind" "$GOBIN_DIR/gobind-matsuri"

GOBIND=gobind-matsuri "$GOBIN_DIR/gomobile-matsuri" init
