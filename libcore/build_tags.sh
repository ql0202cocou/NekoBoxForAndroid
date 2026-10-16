#!/bin/bash

# libcore 的 Go 构建标签：build.sh（gomobile 出 AAR）与 buildScript/lib/test.sh（go test）共用这一份，
# 测试与 AAR 编进同样的功能，两处不各抄一份。由调用方 source（工作目录为 libcore/）
LIBCORE_BUILD_TAGS='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api'
