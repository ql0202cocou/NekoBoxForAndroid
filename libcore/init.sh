#!/bin/bash

set -e

chmod -R u+w .build 2>/dev/null || true
rm -rf .build 2>/dev/null || true

# go install honours GOBIN over GOPATH/bin; without this the mv below fails with
# a bare "No such file or directory" on any machine that sets GOBIN.
GOBIN_DIR=$(go env GOBIN)
if [ -z "$GOBIN_DIR" ]; then
    if [ -z "$GOPATH" ]; then
        GOPATH=$(go env GOPATH)
    fi
    GOBIN_DIR="$GOPATH/bin"
fi

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
