#!/bin/bash

# 将上游发布的内置 Xray 下载到 app/executableSo/<abi>/libxray.so（gitignored，经 jniLibs 打包）。
# 版本固定；升级时同时更新版本与哈希。

set -eo pipefail

source buildScript/init/verify_sha256.sh

XRAY_VERSION="v26.11.9"

# 解包后原始二进制的 sha256（APK 打包时会剥离符号）。与版本一起更新，防止同一 tag 下的发布资产被替换。
XRAY_SHA256_arm64_v8a=dbfe83c136ebb58635f5ad4e21d1df077473aa6fc4bfa1b7ee193516d0af8255
XRAY_SHA256_x86_64=63ba44d391e1bfd4061fad9584ed171671b5928b91128001bf4921e8cb9b46aa

ABIS="arm64-v8a x86_64"
DIR=app/executableSo
STAMP="$DIR/.versions"
WANT="xray=$XRAY_VERSION"

is_elf() {
  [ "$(head -c 4 "$1" 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

check_pinned() {
  # $1: file, $2: abi
  local var="XRAY_SHA256_${2//-/_}"
  local expect="${!var}"
  [ -n "$expect" ] || { echo "no pinned sha256 for Xray $2"; return 1; }
  verify_sha256 "$1" "$expect" "pinned "
}

# 老工作区里已下载的 mihomo 仍会被 jniLibs 打包，必须清掉。
for abi in $ABIS; do rm -f "$DIR/$abi/libmihomo.so"; done

# 已安装核心匹配时跳过；版本戳与 ELF 头识别不了缓存损坏或替换，因此仍核对固定哈希。
if [ "$(cat "$STAMP" 2>/dev/null)" = "$WANT" ] \
  && is_elf "$DIR/arm64-v8a/libxray.so" && is_elf "$DIR/x86_64/libxray.so" \
  && check_pinned "$DIR/arm64-v8a/libxray.so" arm64-v8a \
  && check_pinned "$DIR/x86_64/libxray.so" x86_64; then
  echo ">> plugin core up to date ($WANT)"
  exit 0
fi

mkdir -p $DIR/arm64-v8a $DIR/x86_64
rm -f "$STAMP"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cd $TMP

check_sha256() {
  # $1: file, $2: dgst content with "SHA2-256= <hash>"
  local expect=$(echo "$2" | grep 'SHA2-256=' | awk '{print $2}')
  [ -n "$expect" ] || { echo "no SHA2-256 in dgst"; exit 1; }
  verify_sha256 "$1" "$expect" || exit 1
}

upstream_arch() {
  if [ "$1" = x86_64 ]; then echo amd64; else echo arm64-v8a; fi
}

for abi in $ABIS; do
  zip="Xray-android-$(upstream_arch $abi).zip"
  curl -fLSsO "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/$zip"
  dgst=$(curl -fLSs "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/$zip.dgst")
  check_sha256 "$zip" "$dgst"
  unzip -o -q "$zip" xray
  is_elf xray || { echo "xray is not an ELF binary"; exit 1; }
  check_pinned xray "$abi"
  cp xray "$OLDPWD/$DIR/$abi/libxray.so"
  rm -f xray "$zip"
done

echo "$WANT" > "$OLDPWD/$STAMP"
echo ">> plugin core installed to $DIR (xray $XRAY_VERSION)"
