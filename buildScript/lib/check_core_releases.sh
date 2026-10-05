#!/bin/bash

# 检查内置核心（Xray、mihomo）与 sing-box 上游是否有新版本，只读、不下载任何二进制。
# 固定版本读自 buildScript/lib/plugins.sh（用 sed 读，不 source：它的主体是下载逻辑）。
#   - 不用 /releases/latest：Xray 自 v26.4.13 起所有发布都标成 pre-release，
#     latest 仍停在固定的那一版；mihomo 列表第一项是常驻的 Prerelease-Alpha。
#     所以拉 releases 列表，在本地按版本号过滤、比较。
#   - Xray 另列出固定版本到最新 tag 之间与 REALITY / XHTTP / 版本门槛 / mux /
#     Trojan / VLESS / VMess 有关的提交（发布正文没有更新日志，只能看提交标题）。
#   - sing-box 只提示最新稳定版，不影响退出码。
# 有 GH_TOKEN 或 GITHUB_TOKEN 时带认证头（经环境变量传给 curl），否则走未登录接口
# （每小时 60 次，够用）。
# 退出码：0 没有新版本，1 有新版本，2 查询失败。
# 在仓库根运行：./run lib check_core_releases

set -eo pipefail

cd "$(dirname "$0")/../.."

command -v curl >/dev/null || { echo "ERROR: 需要 curl" >&2; exit 2; }
command -v jq >/dev/null || { echo "ERROR: 需要 jq" >&2; exit 2; }

pinned() { sed -n "s/^$1=\"\{0,1\}\([^\"]*\)\"\{0,1\}\$/\1/p" buildScript/lib/plugins.sh; }

XRAY_PINNED=$(pinned XRAY_VERSION)
MIHOMO_PINNED=$(pinned MIHOMO_VERSION)
SINGBOX_PINNED=$(sed -n 's/^var Version = "\([^"]*\)".*/\1/p' libcore/sing-box/constant/version.go | sed 's/-neko-[0-9]*$//')
if [ -z "$XRAY_PINNED" ] || [ -z "$MIHOMO_PINNED" ] || [ -z "$SINGBOX_PINNED" ]; then
  echo "ERROR: 读不到固定版本 (xray='$XRAY_PINNED' mihomo='$MIHOMO_PINNED' sing-box='$SINGBOX_PINNED')" >&2
  exit 2
fi

# 令牌经环境变量交给 curl 的配置，不出现在命令行与回显里
TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
export CORE_RELEASES_TOKEN="$TOKEN"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# gh_get <路径与查询> <输出文件>：成功返回 0；限速或其它失败返回非 0 并打印原因
gh_get() {
  local path="$1" out="$2" code
  local -a args=(-sS -L -o "$out" -w '%{http_code}' -H 'Accept: application/vnd.github+json'
    -H 'X-GitHub-Api-Version: 2022-11-28')
  if [ -n "$CORE_RELEASES_TOKEN" ]; then
    # -K - 从标准输入读配置，令牌不进进程参数
    code=$(printf 'header = "Authorization: Bearer %s"\n' "$CORE_RELEASES_TOKEN" |
      curl "${args[@]}" -K - "https://api.github.com/$path") || {
      echo "ERROR: 请求失败: $path" >&2
      return 1
    }
  else
    code=$(curl "${args[@]}" "https://api.github.com/$path") || {
      echo "ERROR: 请求失败: $path" >&2
      return 1
    }
  fi
  if [ "$code" = 403 ] || [ "$code" = 429 ]; then
    echo "ERROR: GitHub API 被限速 (HTTP $code): $path；请设置 GH_TOKEN 或 GITHUB_TOKEN 后重试" >&2
    return 1
  fi
  if [ "$code" != 200 ]; then
    echo "ERROR: GitHub API 返回 HTTP $code: $path" >&2
    return 1
  fi
}

# 版本号 → 可按数值排序的键：v1.19.32 -> 000001.000019.000032
vkey() {
  local v="${1#v}" a b c
  IFS=. read -r a b c <<<"$v"
  printf '%06d.%06d.%06d' "${a:-0}" "${b:-0}" "${c:-0}"
}

# newer_releases <仓库> <固定版本>：打印比固定版本新的正式形式 tag（v数字.数字[.数字]），
# 每行「tag<TAB>是否 pre-release<TAB>发布日期」，由新到旧。不按 prerelease 过滤，
# 只作为一列输出。结果写入 stdout；失败返回非 0。
newer_releases() {
  local repo="$1" pin="$2" file="$TMP/rel-${1//\//_}.json" pinkey tag pre date
  gh_get "repos/$repo/releases?per_page=50" "$file" || return 1
  jq -e 'type == "array"' "$file" >/dev/null || {
    echo "ERROR: $repo 的 releases 响应不是列表" >&2
    return 1
  }
  pinkey=$(vkey "$pin")
  jq -r '.[] | select(.draft == false)
    | select(.tag_name | test("^v[0-9]+\\.[0-9]+(\\.[0-9]+)?$"))
    | [.tag_name, (.prerelease | tostring), (.published_at // "" | .[0:10])] | @tsv' "$file" |
    while IFS=$'\t' read -r tag pre date; do
      if [[ "$(vkey "$tag")" > "$pinkey" ]]; then
        printf '%s\t%s\t%s\t%s\n' "$(vkey "$tag")" "$tag" "$pre" "$date"
      fi
    done | sort -r | cut -f2-
}

NEWER=0
FAILED=0
LATEST=""

report_core() {
  local name="$1" repo="$2" pin="$3" list
  LATEST=""
  echo "== $name: 固定 $pin"
  if ! list=$(newer_releases "$repo" "$pin"); then
    FAILED=1
    return
  fi
  if [ -z "$list" ]; then
    echo "   没有比 $pin 更新的发布"
    return
  fi
  NEWER=1
  local latest
  latest=$(printf '%s\n' "$list" | head -n 1 | cut -f1)
  echo "   更新的发布（tag / pre-release / 发布日期）："
  printf '%s\n' "$list" | while IFS=$'\t' read -r tag pre date; do
    echo "     $tag  prerelease=$pre  $date"
  done
  echo "   对比：https://github.com/$repo/compare/$pin...$latest"
  LATEST="$latest"
}

# Xray：固定版本到最新 tag 之间、标题含关键字的提交（compare 一页最多 250 条，要翻页）
xray_commits() {
  local base="$1" head="$2" page=1 total file count
  echo "   固定版本之后与 REALITY / XHTTP / 版本门槛 / mux / 协议有关的提交（标题，不分大小写）："
  while :; do
    file="$TMP/cmp-$page.json"
    gh_get "repos/XTLS/Xray-core/compare/$base...$head?per_page=250&page=$page" "$file" || return 1
    jq -r '.commits[]? | [.sha[0:8], (.commit.committer.date[0:10]),
      (.commit.message | split("\n")[0])] | @tsv' "$file" |
      grep -iE 'reality|xhttp|splithttp|minClientVer|mux|trojan|vless|vmess' |
      sed 's/^/     /' || true
    count=$(jq '.commits | length' "$file")
    total=$(jq '.total_commits // 0' "$file")
    # 少于一页即到头；total_commits 兜底，防止空页死循环
    if [ "$count" -lt 250 ] || [ $((page * 250)) -ge "$total" ]; then
      break
    fi
    page=$((page + 1))
  done
}

report_core "Xray" XTLS/Xray-core "$XRAY_PINNED"
if [ -n "$LATEST" ]; then
  xray_commits "$XRAY_PINNED" "$LATEST" || FAILED=1
fi
report_core "mihomo" MetaCubeX/mihomo "$MIHOMO_PINNED"

# sing-box：只提示最新稳定版，不影响退出码
echo "== sing-box: 固定 v$SINGBOX_PINNED（仅提示，不影响退出码）"
if sb=$(newer_releases SagerNet/sing-box "v$SINGBOX_PINNED"); then
  if [ -z "$sb" ]; then
    echo "   没有比 v$SINGBOX_PINNED 更新的稳定发布"
  else
    echo "   更新的稳定发布（升级要重做补丁，见 libcore/sing-box/NEKO.md）："
    printf '%s\n' "$sb" | while IFS=$'\t' read -r tag pre date; do
      echo "     $tag  prerelease=$pre  $date"
    done
  fi
else
  echo "   查询失败，已忽略"
fi

echo
if [ "$FAILED" = 1 ]; then
  echo "RESULT: 查询失败（退出码 2）；不要把它当作没有新版本"
  exit 2
elif [ "$NEWER" = 1 ]; then
  echo "RESULT: 有新版本（退出码 1）"
  exit 1
fi
echo "RESULT: 内置核心已是最新（退出码 0）"
