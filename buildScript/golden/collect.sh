#!/bin/bash

# 在设备 / 模拟器上采集旧配置输出的基线（plan.md R1a 第 1 步）：构建并安装 oss debug 包 →
# 经 content call 触发 debug 包里的采集入口 → 等它跑完 → 用 run-as 取回产物。
# 产物格式见 app/src/test/resources/golden/README.md。
#
# 用法（在仓库根目录）：
#   ANDROID_SERIAL=emulator-5554 ./run golden collect [选项]
# 选项：
#   --golden        写到 app/src/test/resources/golden/（保留已有的 README.md，其余全部替换）
#   --out <目录>    写到指定目录（必须不存在或为空）
#   --allow-wipe    设备上的数据库里有不是采集入口建的节点 / 分组 / 规则时，仍然清空后采集
#   --no-build      不构建，直接安装上一次构建（按 output-metadata.json 清单）产出的 APK
#   --no-install    不构建也不安装，直接用设备上已装的 debug 包
# 不带 --golden / --out 时写到一个新建的临时目录。失败时返回非零并打印原因。

set -eo pipefail

cd "$(dirname "$0")/../.."

usage() {
  sed -n '3,16p' "$0" | sed 's/^# \{0,1\}//'
}

DEST=""
GOLDEN=0
ALLOW_WIPE=0
BUILD=1
INSTALL=1
while [ $# -gt 0 ]; do
  case "$1" in
  --golden) GOLDEN=1 ;;
  --out)
    [ -n "$2" ] || { echo "--out needs a directory" >&2; exit 2; }
    DEST="$2"
    shift
    ;;
  --allow-wipe) ALLOW_WIPE=1 ;;
  --no-build) BUILD=0 ;;
  --no-install) BUILD=0; INSTALL=0 ;;
  -h | --help) usage; exit 0 ;;
  *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done
if [ "$GOLDEN" = 1 ] && [ -n "$DEST" ]; then
  echo "--golden and --out are mutually exclusive" >&2
  exit 2
fi

# 设备必须显式指定：debug 包可能装在开发者自己的手机上，不能落到「碰巧唯一连着的那台」
if [ -z "$ANDROID_SERIAL" ]; then
  echo "set ANDROID_SERIAL to the target device (adb devices lists them)" >&2
  exit 2
fi
export ANDROID_SERIAL

if [ -z "$ANDROID_HOME" ]; then
  for dir in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "$HOME/.local/lib/android/sdk"; do
    [ -d "$dir" ] && export ANDROID_HOME="$dir" && break
  done
fi
ADB="$ANDROID_HOME/platform-tools/adb"
[ -x "$ADB" ] || ADB=$(command -v adb || true)
[ -n "$ADB" ] || { echo "adb not found: set ANDROID_HOME" >&2; exit 1; }

"$ADB" get-state > /dev/null 2>&1 || { echo "device $ANDROID_SERIAL is not reachable" >&2; exit 1; }

PACKAGE="$(sed -n 's/^PACKAGE_NAME=//p' nb4a.properties).debug"
AUTHORITY="$PACKAGE.golden"
ABI=$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')

# 内置核心的版本与上游 sha256 以 plugins.sh 为准（只读取，不执行它），并核对 app/executableSo
# 下打进包的源文件确是这一版
source buildScript/init/verify_sha256.sh
pinned() { sed -n "s/^$1=\"\{0,1\}\([^\"]*\)\"\{0,1\}\$/\1/p" buildScript/lib/plugins.sh; }
ABI_VAR=${ABI//-/_}
XRAY_VERSION=$(pinned XRAY_VERSION)
MIHOMO_VERSION=$(pinned MIHOMO_VERSION)
XRAY_PINNED=$(pinned "XRAY_SHA256_$ABI_VAR")
MIHOMO_PINNED=$(pinned "MIHOMO_SHA256_$ABI_VAR")
for v in XRAY_VERSION MIHOMO_VERSION XRAY_PINNED MIHOMO_PINNED; do
  [ -n "${!v}" ] || { echo "cannot read $v for ABI $ABI from buildScript/lib/plugins.sh" >&2; exit 1; }
done
verify_sha256 "app/executableSo/$ABI/libxray.so" "$XRAY_PINNED" || { echo "run ./run lib plugins first" >&2; exit 1; }
verify_sha256 "app/executableSo/$ABI/libmihomo.so" "$MIHOMO_PINNED" || { echo "run ./run lib plugins first" >&2; exit 1; }

# 采集所基于的提交；工作区不干净时记下有改动的路径（含未跟踪文件）。路径可能多达数千条，
# 而 content call 的命令行有长度上限，所以只传总数与排序后的前 DIRTY_TOP 条（换行分隔后
# Base64，"-" 表示没有）。「代码改动」指不在 app/src/debug/、app/src/test/、buildScript/golden/、
# doc/ 之下的路径：它们才可能影响配置输出
DIRTY_TOP=50
CODE_FILTER='^(app/src/debug/|app/src/test/|buildScript/golden/|doc/)'
COMMIT=$(git rev-parse HEAD)
DIRTY_LIST=$(git status --porcelain=v1 --untracked-files=all | cut -c4- | sed 's/^.* -> //' | LC_ALL=C sort)
CODE_LIST=""
[ -z "$DIRTY_LIST" ] || CODE_LIST=$(printf '%s\n' "$DIRTY_LIST" | grep -v -E "$CODE_FILTER" || true)
count_lines() { [ -z "$1" ] && echo 0 || printf '%s\n' "$1" | wc -l | tr -d ' '; }
top_b64() { [ -z "$1" ] && echo - || printf '%s\n' "$1" | sed -n "1,${DIRTY_TOP}p" | base64 | tr -d '\n'; }
DIRTY_COUNT=$(count_lines "$DIRTY_LIST")
CODE_COUNT=$(count_lines "$CODE_LIST")
DIRTY_TOP_B64=$(top_b64 "$DIRTY_LIST")
CODE_TOP_B64=$(top_b64 "$CODE_LIST")
if [ "$DIRTY_COUNT" != 0 ]; then
  echo ">> working tree is dirty ($DIRTY_COUNT paths, $CODE_COUNT outside the golden tooling), recorded in manifest.json"
fi

if [ "$BUILD" = 1 ]; then
  echo ">> building oss debug APK"
  ./gradlew -q app:assembleOssDebug
fi
# 选「本次构建产出的 APK」：只认 Gradle 写的清单，不按文件名猜（目录里常留着旧版本的 APK）。
# 依赖 output-metadata.json 的格式（AGP 生成）：根对象的 elements 数组里每项是一个对象，含
# filters（数组，每项 {filterType, value}；universal 包为空数组）、versionCode（数字）、
# versionName、outputFile（文件名，相对清单所在目录）。不假定字段顺序与缩进：先把
# { } [ ] , 都拆成独立的行逐个记号扫描，在 element 对象（花括号深度 2）闭合时输出一行
# 「ABI<TAB>versionCode<TAB>versionName<TAB>outputFile」（无过滤的 ABI 列为空，
# 有过滤但不是 ABI 的记为 "?"）。字符串值里不能有 , { } [ ]，文件名与版本号满足这一点。
APK_DIR=app/build/outputs/apk/oss/debug
METADATA="$APK_DIR/output-metadata.json"
[ -f "$METADATA" ] || { echo "$METADATA not found: build first (drop --no-build / --no-install)" >&2; exit 1; }
parse_metadata() {
  sed 's/[][{},]/\
&\
/g' "$METADATA" | awk '
    function val(line,   v) {
      v = line
      sub(/^[^:]*:[ \t]*/, "", v)
      gsub(/^"|"[ \t]*$/, "", v)
      return v
    }
    { sub(/^[ \t]+/, ""); sub(/[ \t\r]+$/, "") }
    $0 == "" { next }
    $0 == "{" {
      depth++
      if (depth == 2) { abi = ""; code = ""; name = ""; file = ""; ftype = ""; fval = "" }
      next
    }
    $0 == "}" {
      if (depth == 3) { if (ftype == "ABI") { abi = fval } else if (abi == "") { abi = "?" } }
      if (depth == 2) { print abi "\t" code "\t" name "\t" file }
      depth--
      next
    }
    depth == 3 && /^"filterType"/ { ftype = val($0); next }
    depth == 3 && /^"value"/ { fval = val($0); next }
    depth == 2 && /^"versionCode"/ { code = val($0); next }
    depth == 2 && /^"versionName"/ { name = val($0); next }
    depth == 2 && /^"outputFile"/ { file = val($0); next }
  '
}
ENTRIES=$(parse_metadata)
# 取设备 ABI 对应的那项，没有就取 universal
ENTRY=$(printf '%s\n' "$ENTRIES" | awk -F '\t' -v abi="$ABI" '$1 == abi { print; exit }')
[ -n "$ENTRY" ] || ENTRY=$(printf '%s\n' "$ENTRIES" | awk -F '\t' '$1 == "" && $4 != "" { print; exit }')
[ -n "$ENTRY" ] || { echo "$METADATA has no entry for ABI $ABI and no universal entry" >&2; exit 1; }
APK_VERSION_CODE=$(printf '%s\n' "$ENTRY" | awk -F '\t' '{print $2}')
APK_FILE=$(printf '%s\n' "$ENTRY" | awk -F '\t' '{print $4}')
case "$APK_VERSION_CODE" in '' | *[!0-9]*) echo "$METADATA: bad versionCode '$APK_VERSION_CODE' for $APK_FILE" >&2; exit 1 ;; esac
[ -n "$APK_FILE" ] || { echo "$METADATA: entry without outputFile" >&2; exit 1; }
APK="$APK_DIR/$APK_FILE"
[ -f "$APK" ] || { echo "$APK listed in $METADATA does not exist: rebuild" >&2; exit 1; }
echo ">> APK from build manifest: $APK (versionCode $APK_VERSION_CODE)"
if [ "$INSTALL" = 1 ]; then
  echo ">> installing $APK"
  # -r 保留数据：签名不符时 adb 直接失败，不自动卸载（卸载会删掉设备上的数据）
  "$ADB" install -r -t "$APK" > /dev/null
fi
# 核对设备上实际装的版本号与清单一致，免得带着别的包的代码去采集（--no-install 时同样核对）
INSTALLED_CODE=$("$ADB" shell pm list packages --show-versioncode "$PACKAGE" | tr -d '\r' |
  sed -n "s/^package:$PACKAGE versionCode:\([0-9]*\).*/\1/p" | head -n 1)
if [ "$INSTALLED_CODE" != "$APK_VERSION_CODE" ]; then
  echo "installed $PACKAGE versionCode is '${INSTALLED_CODE:-none}', build manifest says $APK_VERSION_CODE: refusing to collect" >&2
  exit 1
fi
# 打包时 AGP 会 strip 原生库，装进设备的文件与上游二进制不再逐字节相同：取 APK 里那份的
# sha256 交给采集入口，与设备上实际安装的文件核对
packaged() { unzip -p "$APK" "lib/$ABI/$1" | shasum -a 256 | awk '{print $1}'; }
XRAY_PACKAGED=$(packaged libxray.so)
MIHOMO_PACKAGED=$(packaged libmihomo.so)

if [ "$GOLDEN" = 1 ]; then
  DEST=app/src/test/resources/golden
elif [ -z "$DEST" ]; then
  DEST=$(mktemp -d "${TMPDIR:-/tmp}/golden.XXXXXX")
elif [ -e "$DEST" ] && [ -n "$(ls -A "$DEST")" ]; then
  echo "output directory $DEST is not empty" >&2
  exit 1
fi

EXTRAS=(
  --extra "commit:s:$COMMIT"
  --extra "dirtyCount:i:$DIRTY_COUNT"
  --extra "dirtyTop:s:$DIRTY_TOP_B64"
  --extra "codeDirtyCount:i:$CODE_COUNT"
  --extra "codeDirtyTop:s:$CODE_TOP_B64"
  --extra "xrayVersion:s:$XRAY_VERSION"
  --extra "xrayPinnedSha256:s:$XRAY_PINNED"
  --extra "xrayPackagedSha256:s:$XRAY_PACKAGED"
  --extra "mihomoVersion:s:$MIHOMO_VERSION"
  --extra "mihomoPinnedSha256:s:$MIHOMO_PINNED"
  --extra "mihomoPackagedSha256:s:$MIHOMO_PACKAGED"
)
[ "$ALLOW_WIPE" = 1 ] && EXTRAS+=(--extra "allowWipe:b:true")

echo ">> collecting on $ANDROID_SERIAL ($ABI)"
START=$(date +%s)
# call 同步返回：采集跑完才有结果；Bundle 里 status=ok 才算成功
RESULT=$("$ADB" shell content call --uri "content://$AUTHORITY" --method collect "${EXTRAS[@]}" 2>&1 | tr -d '\r') || true
ELAPSED=$(($(date +%s) - START))
if ! printf '%s' "$RESULT" | grep -q 'status=ok'; then
  echo "collection failed after ${ELAPSED}s:" >&2
  printf '%s\n' "$RESULT" >&2
  echo "(adb logcat may hold more detail)" >&2
  exit 1
fi
echo "$RESULT"

# 产物在应用私有目录，经 run-as 打包取回
STAGE=$(mktemp -d "${TMPDIR:-/tmp}/golden-stage.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT
"$ADB" exec-out run-as "$PACKAGE" tar -cf - -C files golden-out > "$STAGE/out.tar"
tar -xf "$STAGE/out.tar" -C "$STAGE"
[ -f "$STAGE/golden-out/manifest.json" ] || { echo "pulled output has no manifest.json" >&2; exit 1; }
"$ADB" shell run-as "$PACKAGE" rm -rf files/golden-out

mkdir -p "$DEST"
if [ "$GOLDEN" = 1 ]; then
  # 基线目录整体替换，只保留手写的 README.md
  find "$DEST" -mindepth 1 -maxdepth 1 ! -name README.md -exec rm -rf {} +
fi
cp -R "$STAGE/golden-out/." "$DEST/"

echo ">> collected in ${ELAPSED}s into $DEST"
