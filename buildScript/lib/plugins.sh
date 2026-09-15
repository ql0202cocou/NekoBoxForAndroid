#!/bin/bash

# Downloads the built-in plugin cores (Xray / mihomo) from upstream releases
# into app/executableSo/<abi>/lib*.so (gitignored, packaged via jniLibs).
# Pinned versions; bump here when upgrading.

set -eo pipefail

XRAY_VERSION="v26.3.27"
MIHOMO_VERSION="v1.19.31"

# sha256 of the extracted binaries (exactly what ships in the APK), frozen when
# the versions above were bumped — recompute and update them together. Guards
# against a release asset being swapped under the same tag: mihomo publishes no
# checksums at all, and Xray's .dgst sits next to the asset it describes.
XRAY_SHA256_arm64_v8a=19101a8191d6d606da975f719c8cdb80b8710b87ab17edc00ef74b9e39588714
XRAY_SHA256_x86_64=26b2ac1e596242847a247df9e932ac480bf6072c5c686517f5a3017320895814
MIHOMO_SHA256_arm64_v8a=dbd8af275219a097d66362d543b32f65ba0d4de9d96a49bf5e9abdcdad3af6f1
MIHOMO_SHA256_x86_64=b3a9f4daba6351d7cefe512eeea735b380c8a6c441966ef84ab1e5ff0b5c4dac

ABIS="arm64-v8a x86_64"
DIR=app/executableSo
STAMP="$DIR/.versions"
WANT="xray=$XRAY_VERSION mihomo=$MIHOMO_VERSION"

is_elf() {
  [ "$(head -c 4 "$1" 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

check_pinned() {
  # $1: file, $2: core (XRAY|MIHOMO), $3: abi
  local var="$2_SHA256_${3//-/_}"
  local expect="${!var}"
  [ -n "$expect" ] || { echo "no pinned sha256 for $2 $3"; return 1; }
  local actual=$(shasum -a 256 "$1" | awk '{print $1}')
  [ "$actual" = "$expect" ] || { echo "pinned sha256 mismatch for $1: $actual != $expect"; return 1; }
}

# skip when the installed cores already match the pinned versions
# Verify cached bytes too: the version stamp and ELF header do not detect
# corruption or replacement of a cached binary.
if [ "$(cat "$STAMP" 2>/dev/null)" = "$WANT" ] \
  && is_elf "$DIR/arm64-v8a/libxray.so" && is_elf "$DIR/x86_64/libxray.so" \
  && is_elf "$DIR/arm64-v8a/libmihomo.so" && is_elf "$DIR/x86_64/libmihomo.so" \
  && check_pinned "$DIR/arm64-v8a/libxray.so" XRAY arm64-v8a \
  && check_pinned "$DIR/x86_64/libxray.so" XRAY x86_64 \
  && check_pinned "$DIR/arm64-v8a/libmihomo.so" MIHOMO arm64-v8a \
  && check_pinned "$DIR/x86_64/libmihomo.so" MIHOMO x86_64; then
  echo ">> plugin cores up to date ($WANT)"
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
  local actual=$(shasum -a 256 "$1" | awk '{print $1}')
  [ "$actual" = "$expect" ] || { echo "sha256 mismatch for $1: $actual != $expect"; exit 1; }
}

check_elf() {
  is_elf "$1" || { echo "$1 is not an ELF binary"; exit 1; }
}


upstream_arch() {
  # $1: abi, $2: the upstream's name for arm64-v8a (both use amd64 for x86_64)
  if [ "$1" = x86_64 ]; then echo amd64; else echo "$2"; fi
}

fetch_xray() {
  local abi zip dgst
  for abi in $ABIS; do
    zip="Xray-android-$(upstream_arch $abi arm64-v8a).zip"
    curl -fLSsO "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/$zip"
    dgst=$(curl -fLSs "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/$zip.dgst")
    check_sha256 "$zip" "$dgst"
    unzip -o -q "$zip" xray
    check_elf xray
    check_pinned xray XRAY "$abi"
    cp xray "$OLDPWD/$DIR/$abi/libxray.so"
    rm -f xray "$zip"
  done
}

# upstream publishes no checksums; gzip integrity + ELF magic + the pinned sha256
fetch_mihomo() {
  local abi gz bin
  for abi in $ABIS; do
    gz="mihomo-android-$(upstream_arch $abi arm64-v8)-$MIHOMO_VERSION.gz"
    curl -fLSsO "https://github.com/MetaCubeX/mihomo/releases/download/$MIHOMO_VERSION/$gz"
    gzip -t "$gz"
    gunzip -f "$gz"
    bin="${gz%.gz}"
    check_elf "$bin"
    check_pinned "$bin" MIHOMO "$abi"
    cp "$bin" "$OLDPWD/$DIR/$abi/libmihomo.so"
    rm -f "$bin"
  done
}

# the two upstreams are independent; download them concurrently
fetch_xray & xray_pid=$!
fetch_mihomo & mihomo_pid=$!
status=0
wait $xray_pid || status=1
wait $mihomo_pid || status=1
[ $status -eq 0 ] || exit 1

echo "$WANT" > "$OLDPWD/$STAMP"
echo ">> plugin cores installed to $DIR (xray $XRAY_VERSION, mihomo $MIHOMO_VERSION)"
