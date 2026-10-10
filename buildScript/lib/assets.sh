#!/bin/bash

set -eo pipefail

source buildScript/init/verify_sha256.sh

# geo 数据库不固定版本，构建时取上游当前最新的发布（2026-10-10 维护者决定）：上游只保留
# 最近几个发布（sing-geosite 约 11 个、三周左右；sing-geoip 约 4 个月），固定的版本过期后
# 下载会 404，CI 的 Go 测试与发版随之失败。做法：先解析 releases/latest 跳转到的 tag，再从
# 这个 tag 下载 db 与上游附带的 "<file>.sha256sum" 并核对，同一次运行里版本号、文件与校验值
# 来自同一个发布，不会被发布期间的新版本错开。校验只能证明下载完整、与上游发布的一致，
# 防不住上游发布本身被换；要防那个只能回到固定 sha256 的做法。
# 后果：不同日期的构建内置的 geo 数据不同，旧 tag 重建时拿到的也是当时最新的数据库。

DIR=app/src/main/assets/sing-box

# 先下载到临时目录，全部成功后再替换 assets，下载失败不会留下空的 assets 目录
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cd "$TMP"

# 解析 <repo> 最新发布的 tag：GitHub 把 releases/latest 跳转到 releases/tag/<tag>，
# 不走 API，没有未登录的速率限制
latest_tag() {
  local url tag
  url=$(curl -fsSLI -o /dev/null -w '%{url_effective}' "https://github.com/$1/releases/latest")
  tag="${url##*/releases/tag/}"
  if [ -z "$tag" ] || [ "$tag" = "$url" ]; then
    echo "cannot resolve the latest release of $1: $url" >&2
    return 1
  fi
  echo "$tag"
}

# 下载 <repo> 发布 <tag> 里的 <file>，按同一发布里的 <file>.sha256sum 校验。不校验的话，
# 截断或被篡改的下载会被直接打进 APK，到运行时才以 sing-box 加载资源失败的形式暴露
download_verified() {
  local repo="$1" tag="$2" file="$3" expect
  local base="https://github.com/$repo/releases/download/$tag"
  curl -fLSs -o "$file" "$base/$file"
  curl -fLSs -o "$file.sha256sum" "$base/$file.sha256sum"
  expect=$(awk 'NR == 1 { print $1 }' "$file.sha256sum")
  if ! [[ "$expect" =~ ^[0-9a-f]{64}$ ]]; then
    echo "unexpected sha256sum file for $file: $(head -c 200 "$file.sha256sum")" >&2
    return 1
  fi
  verify_sha256 "$file" "$expect" || return 1
  rm -f "$file.sha256sum"
}

####
GEOIP_VERSION=$(latest_tag SagerNet/sing-geoip)
echo VERSION_GEOIP=$GEOIP_VERSION
echo -n "$GEOIP_VERSION" > geoip.version.txt
download_verified SagerNet/sing-geoip "$GEOIP_VERSION" geoip.db

####
GEOSITE_VERSION=$(latest_tag SagerNet/sing-geosite)
echo VERSION_GEOSITE=$GEOSITE_VERSION
echo -n "$GEOSITE_VERSION" > geosite.version.txt
download_verified SagerNet/sing-geosite "$GEOSITE_VERSION" geosite.db

####
cd "$OLDPWD"
bash buildScript/lib/replace-assets.sh "$TMP" "$DIR"
