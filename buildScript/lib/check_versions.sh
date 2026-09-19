#!/bin/bash

# Fails when the toolchain/dependency versions pinned in several places drift
# apart. Checked:
#   a. modules required by both libcore/go.mod and libcore/sing-box/go.mod must
#      be at the same version (one `go mod tidy` in the wrong module silently
#      bumps only one side); modules replaced with a local path are skipped
#   b. the NDK version in buildScript/init/env_ndk.sh, libcore.yml and
#      build-apk.yml must be identical
#   c. the go-version in libcore.yml must match the major.minor of the go
#      directive in libcore/go.mod
#   d. compileSdk / buildToolsVersion in buildSrc Helpers.kt must match the SDK
#      platform / build-tools installed in build-apk.yml
#   e. libcore/sing-box/ 必须等于上游 tag + libcore/patches/ 里的补丁：
#      克隆上游 tag、应用补丁后与 vendored 树 diff 对比
# Bumping any of these means editing every place it appears; this script is the
# guard that nothing was missed. Run from the repo root: ./run lib check_versions

set -eo pipefail

cd "$(dirname "$0")/../.."

FAILED=0
fail() {
  echo "MISMATCH: $1"
  FAILED=1
}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

#### a. go.mod module versions

GO_MOD=libcore/go.mod
BOX_MOD=libcore/sing-box/go.mod

# modules replaced with a local path (./sing-box, ./gomobile, ./libneko) carry
# no meaningful upstream version — exclude them from the comparison; covers
# both `replace A => ./dir` and lines inside a replace ( ... ) block
local_replaces() {
  awk '/=>/ && $NF ~ /^\.\.?\// { if ($1 == "replace") print $2; else print $1 }' "$1" | sort -u
}

# "module version" pairs from require directives, stripping // indirect
# comments; single-line `require x v1` is normalized to the block shape first.
# \{1,\} instead of \+: the dev machines ship BSD sed, which silently misparse
# the GNU \+ extension on bracket expressions
requires() {
  sed 's|^require \([^ (]\)|\1|' "$1" \
    | sed -n 's|^[[:space:]]*\([^[:space:]]\{1,\}\)[[:space:]]\{1,\}\(v[^[:space:]]\{1,\}\).*|\1 \2|p' \
    | sort -u
}

for side in core box; do
  mod="$GO_MOD"; [ "$side" = box ] && mod="$BOX_MOD"
  requires "$mod" > "$TMP/$side.all"
  local_replaces "$mod" > "$TMP/$side.skip"
  # FILENAME == ARGV[1] instead of NR==FNR: with an empty skip file NR==FNR
  # stays true for every record of the .all file (NR and FNR increment in
  # lockstep), so the whole file would be swallowed into `skip` and the
  # comparison silently reduced to nothing — sing-box/go.mod has no local
  # replaces, which is exactly the empty-skip-file case
  awk 'FILENAME == ARGV[1] { skip[$1]=1; next } !($1 in skip)' "$TMP/$side.skip" "$TMP/$side.all" > "$TMP/$side"
done

comm -12 <(cut -d' ' -f1 "$TMP/core") <(cut -d' ' -f1 "$TMP/box") > "$TMP/common"
while read -r mod; do
  v_core=$(awk -v m="$mod" '$1 == m { print $2 }' "$TMP/core")
  v_box=$(awk -v m="$mod" '$1 == m { print $2 }' "$TMP/box")
  [ "$v_core" = "$v_box" ] || fail "$mod: $GO_MOD has $v_core but $BOX_MOD has $v_box"
done < "$TMP/common"

#### b. NDK version

# NDK revisions are the only x.y.zzzzzzz (7-digit patch) numbers in these files,
# so build-tools "36.0.0" and friends are not picked up
ndk_of() { grep -oE '[0-9]+\.[0-9]+\.[0-9]{7}' "$1" | sort -u; }
NDK_ENV=$(ndk_of buildScript/init/env_ndk.sh)
NDK_LIBCORE=$(ndk_of .github/workflows/libcore.yml)
NDK_APK=$(ndk_of .github/workflows/build-apk.yml)
if [ -z "$NDK_ENV" ] || [ "$NDK_ENV" != "$NDK_LIBCORE" ] || [ "$NDK_ENV" != "$NDK_APK" ]; then
  fail "NDK version: env_ndk.sh has ${NDK_ENV:-none}, libcore.yml has ${NDK_LIBCORE:-none}, build-apk.yml has ${NDK_APK:-none}"
fi

#### c. Go version (workflow go-version vs go.mod go directive, major.minor)

GO_CI=$(grep -oE "go-version: '?[0-9]+\.[0-9]+" .github/workflows/libcore.yml | grep -oE '[0-9]+\.[0-9]+' | sort -u)
GO_MOD_MM=$(awk '$1 == "go" { print $2 }' "$GO_MOD" | cut -d. -f1-2)
if [ -z "$GO_CI" ] || [ "$GO_CI" != "$GO_MOD_MM" ]; then
  fail "Go version: libcore.yml go-version is ${GO_CI:-none} but $GO_MOD says go $GO_MOD_MM"
fi

#### d. compileSdk / buildToolsVersion (Helpers.kt vs build-apk.yml)

HELPERS=buildSrc/src/main/kotlin/Helpers.kt
APK_YML=.github/workflows/build-apk.yml
BTV_HELPERS=$(sed -n 's|.*buildToolsVersion = "\([^"]*\)".*|\1|p' "$HELPERS")
BTV_CI=$(grep -oE 'build-tools[;/][0-9.]+' "$APK_YML" | sed 's|.*[;/]||' | sort -u)
CSDK_HELPERS=$(sed -n 's|.*compileSdk = \([0-9]*\).*|\1|p' "$HELPERS")
CSDK_CI=$(grep -oE 'android-[0-9]+' "$APK_YML" | sed 's|android-||' | sort -u)
if [ -z "$BTV_HELPERS" ] || [ "$BTV_HELPERS" != "$BTV_CI" ]; then
  fail "buildToolsVersion: Helpers.kt has ${BTV_HELPERS:-none} but $APK_YML has ${BTV_CI:-none}"
fi
if [ -z "$CSDK_HELPERS" ] || [ "$CSDK_HELPERS" != "$CSDK_CI" ]; then
  fail "compileSdk: Helpers.kt has ${CSDK_HELPERS:-none} but $APK_YML has android-${CSDK_CI:-none}"
fi

#### e. sing-box vendored tree == upstream tag + patch artifact

# 校验 vendored sing-box 与可重放补丁一致：克隆上游 tag、应用补丁后 diff 对比。
# vendored 树每次改动都必须重新生成补丁（命令见 libcore/sing-box/NEKO.md）。
# clients/ 是上游的 git submodule 占位，vendored 树不携带；NEKO.md 是补丁集
# 之外的管理文档——二者都不参与生成与对比。
BOX_BASE_TAG=v1.14.1
BOX_PATCH=libcore/patches/sing-box-v1.14.1-neko-1.diff
if [ ! -f "$BOX_PATCH" ]; then
  fail "$BOX_PATCH missing; regenerate it as documented in libcore/sing-box/NEKO.md"
else
  git clone -q -c advice.detachedHead=false --depth 1 --branch "$BOX_BASE_TAG" https://github.com/SagerNet/sing-box "$TMP/box-base"
  if ! patch -d "$TMP/box-base" -p1 -s -t < "$BOX_PATCH"; then
    fail "$BOX_PATCH does not apply cleanly on sing-box $BOX_BASE_TAG"
  elif ! diff -r -q --exclude=.git --exclude=clients --exclude=NEKO.md "$TMP/box-base" libcore/sing-box > "$TMP/box.diff"; then
    fail "sing-box $BOX_BASE_TAG + $BOX_PATCH diverges from libcore/sing-box (regenerate the patch):
$(cat "$TMP/box.diff")"
  fi
fi

####

if [ $FAILED -ne 0 ]; then
  echo ">> version consistency check FAILED" >&2
  exit 1
fi
echo ">> version consistency check OK"
