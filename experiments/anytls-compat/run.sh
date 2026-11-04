#!/bin/sh
set -eu

# 真实对照不可省略，避免只跑单元测试却误报完整互通通过。
: "${MIHOMO_BIN:?请提供 mihomo v1.19.33 二进制的绝对路径}"
case "$MIHOMO_BIN" in
  /*) ;;
  *) echo 'MIHOMO_BIN 必须是绝对路径' >&2; exit 2 ;;
esac
cd "$(dirname "$0")/../../libcore"
export REQUIRE_MIHOMO=1
export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.26.10}"
exec go test -mod=readonly -ldflags=-checklinkname=0 -timeout=300s "$@" ./anytlscompat/...
