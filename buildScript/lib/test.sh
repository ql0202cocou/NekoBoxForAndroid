#!/bin/bash

# 跑 libcore 的 Go 单元测试。-checklinkname=0 是必需的：libcore/certs.go 用 linkname 引用
# crypto/x509.systemRootsMu，Go 1.23 起不加这个参数会在链接时被拒（与 libcore/build.sh 相同）。
# 构建标签与 AAR 相同（libcore/build_tags.sh）：逐出站试建的测试要构造 REALITY / ShadowTLS（with_utls）、
# WireGuard（with_wireguard、with_gvisor）、Hysteria / TUIC（with_quic）等出站，不带标签时这些类型在
# 构造时直接报「未编入本构建」，测的就不是 AAR 里的行为了。

set -eo pipefail

cd libcore
source ./build_tags.sh
go test -tags "$LIBCORE_BUILD_TAGS" -ldflags=-checklinkname=0 ./...
