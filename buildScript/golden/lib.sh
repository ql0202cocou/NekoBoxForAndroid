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

  PACKAGE="$(sed -n 's/^PACKAGE_NAME=//p' gradle/nb4a.properties).debug"
  AUTHORITY="$PACKAGE.golden"
  ABI=$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')
}

# 内置核心的版本与上游 sha256 以 plugins.sh 为准（只读取，不执行它）
golden_pinned() { sed -n "s/^$1=\"\{0,1\}\([^\"]*\)\"\{0,1\}\$/\1/p" buildScript/lib/plugins.sh; }

# 选「本次构建产出的 APK」：只认 Gradle 写的清单，不按文件名猜（目录里常留着旧版本的 APK）。
# 先选单一 ABI 的包，再回退到无过滤的通用包。
golden_select_apk() {
  local apk_dir=app/build/outputs/apk/oss/debug
  local entry
  entry=$(python3 buildScript/golden/select_apk.py "$apk_dir/output-metadata.json" "$ABI") || return 1
  APK_VERSION_CODE=${entry%%$'\n'*}
  APK=${entry#*$'\n'}
  echo ">> APK from build manifest: $APK (versionCode $APK_VERSION_CODE)"
}

# 重新安装也会停止已有进程，先检查另一工具的持久标记；provider 在改库前还会重新核对。
golden_check_other_session() {
  if "$ADB" shell run-as "$PACKAGE" test -e "files/$1" > /dev/null 2>&1; then
    echo "another golden session owns the database ($1); finish its recovery first" >&2
    return 1
  fi
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
