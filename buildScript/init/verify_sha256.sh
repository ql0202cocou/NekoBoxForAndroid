#!/bin/bash

# verify_sha256 <文件> <期望的 sha256> [报错前缀]：不一致时打印两者并返回 1。
# 用 shasum 而非 sha256sum：开发机是 macOS
verify_sha256() {
  local actual=$(shasum -a 256 "$1" | awk '{print $1}')
  [ "$actual" = "$2" ] || { echo "${3}sha256 mismatch for $1: $actual != $2"; return 1; }
}
