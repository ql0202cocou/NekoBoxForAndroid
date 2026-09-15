#!/bin/bash

# Runs the libcore Go unit tests. -checklinkname=0 is required because
# libcore/certs.go linknames crypto/x509.systemRootsMu, which Go 1.23+
# rejects at link time otherwise (same flag as libcore/build.sh).

set -eo pipefail

cd libcore
go test -ldflags=-checklinkname=0 ./...
