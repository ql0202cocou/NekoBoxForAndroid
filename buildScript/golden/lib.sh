# collect.sh 与 measure.sh 共用的设备准备与 APK 选择，由它们 source（本文件不可执行，
# ./run 不会把它当成命令）。调用前当前目录必须是仓库根目录。

# 设备必须显式指定：debug 包可能装在开发者自己的手机上，不能落到「碰巧唯一连着的那台」。
# 设好 ADB、PACKAGE（debug 包名）、AUTHORITY（采集 / 实测入口的 provider）、ABI
golden_setup_device() {
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
}

# 内置核心的版本与上游 sha256 以 plugins.sh 为准（只读取，不执行它）
golden_pinned() { sed -n "s/^$1=\"\{0,1\}\([^\"]*\)\"\{0,1\}\$/\1/p" buildScript/lib/plugins.sh; }

# 选「本次构建产出的 APK」：只认 Gradle 写的清单，不按文件名猜（目录里常留着旧版本的 APK）。
# 依赖 output-metadata.json 的格式（AGP 生成）：根对象的 elements 数组里每项是一个对象，含
# filters（数组，每项 {filterType, value}；universal 包为空数组）、versionCode（数字）、
# versionName、outputFile（文件名，相对清单所在目录）。不假定字段顺序与缩进：先把
# { } [ ] , 都拆成独立的行逐个记号扫描，在 element 对象（花括号深度 2）闭合时输出一行
# 「ABI<TAB>versionCode<TAB>versionName<TAB>outputFile」（无过滤的 ABI 列为空，
# 有过滤但不是 ABI 的记为 "?"）。字符串值里不能有 , { } [ ]，文件名与版本号满足这一点。
# 设好 APK、APK_VERSION_CODE
golden_select_apk() {
  local apk_dir=app/build/outputs/apk/oss/debug
  local metadata="$apk_dir/output-metadata.json"
  [ -f "$metadata" ] || { echo "$metadata not found: build first (drop --no-build / --no-install)" >&2; exit 1; }
  local entries entry apk_file
  entries=$(sed 's/[][{},]/\
&\
/g' "$metadata" | awk '
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
  ')
  # 取设备 ABI 对应的那项，没有就取 universal
  entry=$(printf '%s\n' "$entries" | awk -F '\t' -v abi="$ABI" '$1 == abi { print; exit }')
  [ -n "$entry" ] || entry=$(printf '%s\n' "$entries" | awk -F '\t' '$1 == "" && $4 != "" { print; exit }')
  [ -n "$entry" ] || { echo "$metadata has no entry for ABI $ABI and no universal entry" >&2; exit 1; }
  APK_VERSION_CODE=$(printf '%s\n' "$entry" | awk -F '\t' '{print $2}')
  apk_file=$(printf '%s\n' "$entry" | awk -F '\t' '{print $4}')
  case "$APK_VERSION_CODE" in '' | *[!0-9]*) echo "$metadata: bad versionCode '$APK_VERSION_CODE' for $apk_file" >&2; exit 1 ;; esac
  [ -n "$apk_file" ] || { echo "$metadata: entry without outputFile" >&2; exit 1; }
  APK="$apk_dir/$apk_file"
  [ -f "$APK" ] || { echo "$APK listed in $metadata does not exist: rebuild" >&2; exit 1; }
  echo ">> APK from build manifest: $APK (versionCode $APK_VERSION_CODE)"
}

# 参数为 1 时安装 golden_select_apk 选出的 APK；无论装没装，都核对设备上实际装的版本号与
# 清单一致，免得带着别的包的代码去采集 / 实测
golden_install_apk() {
  if [ "$1" = 1 ]; then
    echo ">> installing $APK"
    # -r 保留数据：签名不符时 adb 直接失败，不自动卸载（卸载会删掉设备上的数据）
    "$ADB" install -r -t "$APK" > /dev/null
  fi
  local installed
  installed=$("$ADB" shell pm list packages --show-versioncode "$PACKAGE" | tr -d '\r' |
    sed -n "s/^package:$PACKAGE versionCode:\([0-9]*\).*/\1/p" | head -n 1)
  if [ "$installed" != "$APK_VERSION_CODE" ]; then
    echo "installed $PACKAGE versionCode is '${installed:-none}', build manifest says $APK_VERSION_CODE: refusing to continue" >&2
    exit 1
  fi
}
