#!/bin/bash

# K0 实测（plan.md K0「实测」）：在设备 / 模拟器上用一个外核节点很多的选择器分组启动服务，
# 记录外核进程数随时间的变化、系统对 phantom process 的清理、应用的重启日志、内存与启动耗时。
# 构建并安装 oss debug 包 → 记录设备条件 → 经 content call 触发 debug 包里的实测入口写夹具 →
# 拉起主界面 → 发出启动 → 周期性取样 → 停止并清掉夹具、恢复设置 → 汇总成 result.json。
# 结果格式见 app/src/test/resources/golden/README.md「K0 实测」一节。需要 python3（只用标准库）。
#
# 用法（在仓库根目录）：
#   ANDROID_SERIAL=emulator-5554 ./run golden measure [选项]
# 选项：
#   --xray <N>        选择器里走 Xray 的 VLESS + REALITY 节点数（默认 38）
#   --mihomo <N>      走 mihomo 的 AnyTLS 节点数（默认 2）
#   --singbox <N>     sing-box 内核节点数（默认 2）
#   --duration <秒>   从发出启动起取样多久（默认 120）
#   --interval <秒>   取样间隔（默认 3）
#   --mem-at <秒>     第一次记内存的时刻（默认 30；结束前再记一次）
#   --mode <vpn|proxy> 服务模式（默认 vpn）
#   --log-level <N>   实测期间的日志等级（默认 1 即 warn：0 会关掉应用日志，看不到外核重启记录）
#   --settle <秒>     拉起主界面后等多久再发出启动（默认 5）
#   --out <目录>      结果目录（必须不存在或为空；默认新建临时目录）
#   --allow-wipe      数据库里有不是实测入口建的节点 / 分组 / 规则时，仍然清空后实测
#   --no-build        不构建，直接安装上一次构建（按 output-metadata.json 清单）产出的 APK
#   --no-install      不构建也不安装，直接用设备上已装的 debug 包
# 失败时返回非零并打印原因；无论成败都会停掉服务、清掉夹具、恢复设置。

set -eo pipefail

cd "$(dirname "$0")/../.."

usage() {
  sed -n '3,26p' "$0" | sed 's/^# \{0,1\}//'
}

XRAY=38
MIHOMO=2
SINGBOX=2
DURATION=120
INTERVAL=3
MEM_AT=30
MODE=vpn
LOG_LEVEL=1
SETTLE=5
OUT=""
ALLOW_WIPE=0
BUILD=1
INSTALL=1
need_value() { [ -n "$2" ] || { echo "$1 needs a value" >&2; exit 2; }; }
while [ $# -gt 0 ]; do
  case "$1" in
  --xray | --mihomo | --singbox | --duration | --interval | --mem-at | --log-level | --settle)
    need_value "$1" "$2"
    case "$2" in '' | *[!0-9]*) echo "$1 needs a non-negative integer" >&2; exit 2 ;; esac
    case "$1" in
    --xray) XRAY=$2 ;;
    --mihomo) MIHOMO=$2 ;;
    --singbox) SINGBOX=$2 ;;
    --duration) DURATION=$2 ;;
    --interval) INTERVAL=$2 ;;
    --mem-at) MEM_AT=$2 ;;
    --log-level) LOG_LEVEL=$2 ;;
    --settle) SETTLE=$2 ;;
    esac
    shift
    ;;
  --mode)
    need_value "$1" "$2"
    case "$2" in vpn | proxy) MODE=$2 ;; *) echo "--mode is vpn or proxy" >&2; exit 2 ;; esac
    shift
    ;;
  --out)
    need_value "$1" "$2"
    OUT="$2"
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
[ "$INTERVAL" -gt 0 ] || { echo "--interval must be positive" >&2; exit 2; }
command -v python3 > /dev/null || { echo "python3 is required to summarize the results" >&2; exit 1; }

source buildScript/golden/lib.sh
golden_setup_device

if [ -z "$OUT" ]; then
  OUT=$(mktemp -d "${TMPDIR:-/tmp}/golden-measure.XXXXXX")
elif [ -e "$OUT" ] && [ -n "$(ls -A "$OUT")" ]; then
  echo "output directory $OUT is not empty" >&2
  exit 1
fi
mkdir -p "$OUT/raw"
OUT=$(cd "$OUT" && pwd)
RAW="$OUT/raw"

if [ "$BUILD" = 1 ]; then
  echo ">> building oss debug APK"
  ./gradlew -q app:assembleOssDebug
fi
golden_select_apk
golden_install_apk "$INSTALL"

# ---- 与设备交互的小工具 ----

# 调实测入口；原样输出 content call 的结果（Bundle 的文本形式）
call_provider() {
  local method=$1
  shift
  "$ADB" shell content call --uri "content://$AUTHORITY" --method "$method" "$@" 2>&1 | tr -d '\r'
}
# 结果里的 b64=… 解码成 JSON（单行）
decode_result() { sed -n 's/.*b64=\([A-Za-z0-9+/=]*\).*/\1/p' | base64 --decode; }
is_ok() { printf '%s' "$1" | grep -q 'status=ok'; }
json_number() { sed -n "s/.*\"$1\":\([0-9-]*\).*/\1/p"; }

# 设备 uptime（毫秒，与 SystemClock.elapsedRealtime 同一时钟）和进程表放在同一条 adb shell 里取
PROC_FILTER='$3 ~ /(^|\/)(libxray|libmihomo)\.so$/ || $3 == pkg || $3 == pkg ":bg"'
snapshot_processes() {
  "$ADB" shell 'cat /proc/uptime; ps -A -o PID,PPID,NAME' | tr -d '\r'
}

# 一次内存快照：主进程、:bg、全部外核子进程的 smaps_rollup（经 run-as 读本应用自己的进程）
mem_snapshot() {
  local label=$1 file="$RAW/mem-$1.txt" ps pids
  ps=$(snapshot_processes)
  pids=$(printf '%s\n' "$ps" | awk -v pkg="$PACKAGE" "NR > 2 && ($PROC_FILTER) { print \$1 }" | tr '\n' ' ')
  {
    echo "## label=$label uptime=$(printf '%s\n' "$ps" | head -n 1 | awk '{print $1}')"
    printf '%s\n' "$ps" | awk -v pkg="$PACKAGE" "NR > 2 && ($PROC_FILTER) { print \"## proc \" \$1 \" \" \$2 \" \" \$3 }"
    "$ADB" shell "head -n 3 /proc/meminfo" | tr -d '\r' | sed 's/^/## meminfo /'
    "$ADB" shell "run-as $PACKAGE sh -c 'for p in $pids; do echo \"== \$p\"; cat /proc/\$p/smaps_rollup 2>/dev/null; done'" |
      tr -d '\r'
  } > "$file"
}

# ---- 收尾：无论成败都停服务、清夹具、恢复设置与 VPN 授权 ----

PREPARED=0
FINISHED=0
LOGCAT_PID=""
APPOP_RESTORE=""
finish_measurement() {
  local result
  result=$(call_provider measureFinish) || true
  printf '%s\n' "$result" > "$RAW/finish.txt"
  if is_ok "$result" && printf '%s' "$result" | decode_result | grep -q '"stopped":true'; then
    FINISHED=1
    return
  fi
  # 服务没在时限内停下（或入口报错）：强行停止应用后再调一次，那时服务已不在，只做清理
  echo ">> service did not stop cleanly, force-stopping $PACKAGE" >&2
  echo "forceStopped=1" >> "$RAW/host.txt"
  "$ADB" shell am force-stop "$PACKAGE" || true
  result=$(call_provider measureFinish) || true
  printf '%s\n' "$result" > "$RAW/finish-retry.txt"
  is_ok "$result" && FINISHED=1
  return 0
}
cleanup() {
  local code=$?
  set +e
  [ -n "$LOGCAT_PID" ] && kill "$LOGCAT_PID" 2> /dev/null
  if [ "$PREPARED" = 1 ] && [ "$FINISHED" = 0 ]; then
    finish_measurement
    [ "$FINISHED" = 1 ] || echo "!! cleanup failed; see $RAW/finish*.txt and rerun to restore" >&2
  fi
  if [ -n "$APPOP_RESTORE" ]; then
    "$ADB" shell appops set "$PACKAGE" ACTIVATE_VPN "$APPOP_RESTORE"
  fi
  exit $code
}
trap cleanup EXIT

# ---- 记录设备条件（只读，不改任何系统设置）----

COMMIT=$(git rev-parse HEAD)
DIRTY_COUNT=$(git status --porcelain=v1 --untracked-files=all | wc -l | tr -d ' ')
getprop() { "$ADB" shell getprop "$1" | tr -d '\r'; }
APPOP_BEFORE=$("$ADB" shell appops get "$PACKAGE" ACTIVATE_VPN | tr -d '\r' | sed -n 's/^ACTIVATE_VPN: \([a-z_]*\).*/\1/p')
{
  echo "release=$(getprop ro.build.version.release)"
  echo "sdk=$(getprop ro.build.version.sdk)"
  echo "buildType=$(getprop ro.build.type)"
  echo "fingerprint=$(getprop ro.build.fingerprint)"
  echo "model=$(getprop ro.product.model)"
  echo "abi=$ABI"
  echo "memTotalKb=$("$ADB" shell cat /proc/meminfo | tr -d '\r' | awk '/^MemTotal:/ {print $2}')"
  echo "cpus=$("$ADB" shell nproc | tr -d '\r')"
  # null：没有覆盖，系统默认上限 32（全系统合计）
  echo "maxPhantomProcesses=$("$ADB" shell device_config get activity_manager max_phantom_processes | tr -d '\r')"
  # null / true：系统监控并清理 phantom process；false 时不杀
  echo "monitorPhantomProcs=$("$ADB" shell settings get global settings_enable_monitor_phantom_procs | tr -d '\r')"
  echo "vpnAppopBefore=${APPOP_BEFORE:-default}"
} > "$RAW/device.txt"
{
  echo "serial=$ANDROID_SERIAL"
  echo "package=$PACKAGE"
  echo "apk=$APK"
  echo "versionCode=$APK_VERSION_CODE"
  echo "commit=$COMMIT"
  echo "dirtyCount=$DIRTY_COUNT"
  echo "xrayVersion=$(golden_pinned XRAY_VERSION)"
  echo "mihomoVersion=$(golden_pinned MIHOMO_VERSION)"
  echo "startedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$RAW/host.txt"

# VPN 模式要预先放行 ACTIVATE_VPN（等同用户在授权对话框点了确定），结束后恢复原值
if [ "$MODE" = vpn ] && [ "$APPOP_BEFORE" != allow ]; then
  "$ADB" shell appops set "$PACKAGE" ACTIVATE_VPN allow
  APPOP_RESTORE=${APPOP_BEFORE:-default}
fi

# ---- 准备：写夹具与实测设置 ----

echo ">> preparing fixture: xray=$XRAY mihomo=$MIHOMO singbox=$SINGBOX mode=$MODE"
# 先停掉应用：上次被打断的实测若还留着服务，这里一并结束
"$ADB" shell am force-stop "$PACKAGE"
EXTRAS=(
  --extra "xray:i:$XRAY" --extra "mihomo:i:$MIHOMO" --extra "singbox:i:$SINGBOX"
  --extra "mode:s:$MODE" --extra "logLevel:i:$LOG_LEVEL"
)
[ "$ALLOW_WIPE" = 1 ] && EXTRAS+=(--extra "allowWipe:b:true")
RESULT=$(call_provider measurePrepare "${EXTRAS[@]}") || true
printf '%s\n' "$RESULT" > "$RAW/prepare.txt"
# 拒绝运行时入口没写任何东西；其余失败（例如夹具分核不符）可能已写了夹具，交给收尾清理
if printf '%s' "$RESULT" | grep -Eq 'message=refused: (the database holds|the proxy service is running|an interrupted)'; then
  echo "measurement refused:" >&2
  printf '%s\n' "$RESULT" >&2
  exit 1
fi
PREPARED=1
if ! is_ok "$RESULT"; then
  echo "prepare failed:" >&2
  printf '%s\n' "$RESULT" >&2
  exit 1
fi
printf '%s' "$RESULT" | decode_result > "$RAW/prepare.json"

# 再停一次：主进程与 :bg 都按实测设置（日志等级在进程启动时读取）重新起来，服务从冷状态启动。
# 应用日志清空，取样结束后整份取回
"$ADB" shell am force-stop "$PACKAGE"
"$ADB" shell "run-as $PACKAGE sh -c ': > cache/neko.log'" 2> /dev/null || true

# 从后台启动前台服务受限（Android 12+）：先把主界面拉到前台，由界面所在的主进程发出启动，
# 与用户点启动按钮时的进程状态一致。VPN 模式另有 ACTIVATE_VPN 的豁免
"$ADB" shell input keyevent KEYCODE_WAKEUP || true
"$ADB" shell am start -W -n "$PACKAGE/io.nekohasekai.sagernet.ui.MainActivity" > "$RAW/am-start.txt"
sleep "$SETTLE"
"$ADB" shell dumpsys activity activities | tr -d '\r' | grep -m 1 'topResumedActivity\|mResumedActivity' > "$RAW/top-activity.txt" || true

# 启动前全系统的进程数（ps -A 去掉表头）
PS_BEFORE=$(snapshot_processes)
printf '%s\n' "$PS_BEFORE" > "$RAW/ps-before.txt"
echo "processCountBeforeStart=$(printf '%s\n' "$PS_BEFORE" | awk 'NR > 2' | wc -l | tr -d ' ')" >> "$RAW/device.txt"

# 取样期间的系统日志（main / system / events / crash），时间戳为 epoch 秒，便于对齐启动请求
"$ADB" logcat -v epoch -b main,system,events,crash -T 1 > "$RAW/logcat.txt" 2>&1 &
LOGCAT_PID=$!
sleep 1

# ---- 启动与取样 ----

echo ">> starting the service"
RESULT=$(call_provider measureStart) || true
printf '%s\n' "$RESULT" > "$RAW/start.txt"
if ! is_ok "$RESULT"; then
  echo "start failed:" >&2
  printf '%s\n' "$RESULT" >&2
  echo "startFailed=1" >> "$RAW/host.txt"
else
  printf '%s' "$RESULT" | decode_result > "$RAW/start.json"
  REQ=$(json_number requestedAtElapsedMs < "$RAW/start.json")
  echo ">> sampling every ${INTERVAL}s for ${DURATION}s"
  : > "$RAW/samples.txt"
  MEM_DONE=0
  K=0
  while :; do
    PS=$(snapshot_processes)
    UP_MS=$(printf '%s\n' "$PS" | head -n 1 | awk '{printf "%d", $1 * 1000}')
    T=$((UP_MS - REQ))
    TOTAL=$(printf '%s\n' "$PS" | awk 'NR > 2' | wc -l | tr -d ' ')
    STATUS=$(call_provider measureStatus) || true
    {
      echo "## sample t=$T total=$TOTAL"
      printf '%s\n' "$PS" | awk -v pkg="$PACKAGE" "NR > 2 && ($PROC_FILTER) { print \$1, \$2, \$3 }"
      if is_ok "$STATUS"; then
        echo "## status $(printf '%s' "$STATUS" | decode_result)"
      else
        echo "## status-error $(printf '%s' "$STATUS" | tr '\n' ' ')"
      fi
    } >> "$RAW/samples.txt"
    XN=$(printf '%s\n' "$PS" | awk 'NR > 2 && $3 ~ /(^|\/)libxray\.so$/' | wc -l | tr -d ' ')
    MN=$(printf '%s\n' "$PS" | awk 'NR > 2 && $3 ~ /(^|\/)libmihomo\.so$/' | wc -l | tr -d ' ')
    STATE=$(printf '%s' "$STATUS" | decode_result 2> /dev/null | sed -n 's/^{"state":"\([A-Za-z]*\)".*/\1/p')
    printf '   t=%6.1fs  xray=%-3s mihomo=%-3s state=%s\n' "$(echo "$T" | awk '{print $1 / 1000}')" "$XN" "$MN" "${STATE:-?}"
    if [ "$MEM_DONE" = 0 ] && [ "$T" -ge $((MEM_AT * 1000)) ]; then
      mem_snapshot "t$MEM_AT"
      MEM_DONE=1
    fi
    [ "$T" -ge $((DURATION * 1000)) ] && break
    K=$((K + 1))
    # 按固定节拍取样：下一个整数倍间隔减去当前时刻（以设备时钟为准）
    UP_MS=$("$ADB" shell cat /proc/uptime | tr -d '\r' | awk '{printf "%d", $1 * 1000}')
    WAIT=$((K * INTERVAL * 1000 - (UP_MS - REQ)))
    [ "$WAIT" -gt 0 ] && sleep "$(echo "$WAIT" | awk '{print $1 / 1000}')"
  done
  mem_snapshot end
  # 系统当前登记的 phantom process（dumpsys 的「All Active App Child Processes」一节）：系统只在
  # 内部事件触发扫描时才登记并清理，表为空说明取样期间还没扫描过。只在取样结束后读一次，
  # 避免取样中的 dumpsys 改变系统行为
  "$ADB" shell dumpsys activity processes | tr -d '\r' |
    sed -n '/All Active App Child Processes/,/^  m[A-Z]/p' > "$RAW/phantom-table.txt" || true
fi

# ---- 停止、清理、核对 ----

echo ">> stopping the service and restoring settings"
finish_measurement
# 停止后外核进程应已全部退出（BoxInstance.close 等守护协程收尾后才报 Stopped）
sleep 2
snapshot_processes > "$RAW/ps-after.txt"
sleep 1
kill "$LOGCAT_PID" 2> /dev/null || true
wait "$LOGCAT_PID" 2> /dev/null || true
LOGCAT_PID=""
"$ADB" exec-out run-as "$PACKAGE" cat cache/neko.log > "$RAW/neko.log" 2> /dev/null || true

# ---- 汇总 ----

python3 - "$OUT" "$PACKAGE" "$XRAY" "$MIHOMO" "$SINGBOX" "$DURATION" "$INTERVAL" "$MEM_AT" "$MODE" "$LOG_LEVEL" "$SETTLE" << 'PY'
import base64, calendar, json, os, re, sys

out, package = sys.argv[1], sys.argv[2]
xray, mihomo, singbox, duration, interval, mem_at = (int(v) for v in sys.argv[3:9])
mode, log_level, settle = sys.argv[9], int(sys.argv[10]), int(sys.argv[11])
raw = os.path.join(out, "raw")


def read(name):
    path = os.path.join(raw, name)
    return open(path, encoding="utf-8", errors="replace").read() if os.path.exists(path) else None


def kv(name):
    text = read(name) or ""
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line)


def provider_result(name):
    # content call 的输出：status=ok 时取 b64 里的 JSON，否则取 message
    text = read(name)
    if text is None:
        return None
    m = re.search(r"b64=([A-Za-z0-9+/=]+)", text)
    if "status=ok" in text and m:
        return {"status": "ok", "result": json.loads(base64.b64decode(m.group(1)))}
    m = re.search(r"message=(.*)\}\]", text, re.S)
    return {"status": "error", "message": (m.group(1) if m else text).strip()}


# fork 之后、exec 之前的子进程仍叫「包名:bg」：父进程也叫这个名字的是正在启动的外核，不是 :bg 本身
def split_bg(candidates):
    roots = [p for p, pp in candidates.items() if pp not in candidates]
    return (roots[0] if roots else None), len(candidates) - len(roots)


def core_of(name):
    base = name.rsplit("/", 1)[-1]
    return {"libxray.so": "xray", "libmihomo.so": "mihomo"}.get(base)


device, host = kv("device.txt"), kv("host.txt")
prepare, start, finish = provider_result("prepare.txt"), provider_result("start.txt"), provider_result("finish.txt")
finish_retry = provider_result("finish-retry.txt")
start_info = start["result"] if start and start["status"] == "ok" else {}
req_wall = start_info.get("requestedAtWallMs")


def as_int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


# ---- 取样 ----
samples, cur = [], None
for line in (read("samples.txt") or "").splitlines():
    if line.startswith("## sample "):
        fields = dict(f.split("=", 1) for f in line[len("## sample "):].split())
        cur = {"tMs": int(fields["t"]), "systemProcesses": int(fields["total"]),
               "xray": [], "mihomo": [], "mainPid": None, "bgPid": None, "forkingChildren": 0, "orphanCores": 0,
               "state": None, "statusError": None, "_ppids": {}, "_bg": {}}
        samples.append(cur)
    elif line.startswith("## status-error "):
        cur["statusError"] = line[len("## status-error "):]
    elif line.startswith("## status "):
        cur["_status"] = json.loads(line[len("## status "):])
        cur["state"] = cur["_status"]["state"]
    elif cur is not None and line.strip():
        pid, ppid, name = line.split(None, 2)
        core = core_of(name)
        if core:
            cur[core].append(int(pid))
            cur["_ppids"][int(pid)] = int(ppid)
        elif name == package:
            cur["mainPid"] = int(pid)
        elif name == package + ":bg":
            cur["_bg"][int(pid)] = int(ppid)
for s in samples:
    s["bgPid"], s["forkingChildren"] = split_bg(s.pop("_bg"))
    s["orphanCores"] = sum(1 for p, pp in s.pop("_ppids").items() if pp != s["bgPid"])
    s["xray"].sort()
    s["mihomo"].sort()

last_status = next((s["_status"] for s in reversed(samples) if "_status" in s), None)
for s in samples:
    s.pop("_status", None)


# 按运行计划应起的外核进程数（measurePrepare 的 externalProcesses，键是插件 id）；没有时按节点数
planned = (prepare["result"].get("externalProcesses") or {}) if prepare and prepare["status"] == "ok" else {}


def core_stats(core, nodes):
    expected = planned.get(core + "-plugin", nodes)
    counts = [(s["tMs"], len(s[core])) for s in samples]
    if not counts:
        return None
    peak = max(c for _, c in counts)
    first_seen, last_seen = {}, {}
    for s in samples:
        for p in s[core]:
            first_seen.setdefault(p, s["tMs"])
            last_seen[p] = s["tMs"]
    late = [s for s in samples if s["tMs"] >= duration * 1000 / 2]
    # 已连接时首轮外核进程都已起好（BoxInstance.launch 在报 Connected 之前跑完）：以连接后的第一个
    # 样本为基准，之后才出现的 pid 都是重启出来的
    base = next((s["tMs"] for s in samples if connected and s["tMs"] >= connected["tMs"]), None)
    return {
        "expected": expected,
        "nodes": nodes,
        "max": peak,
        "maxAtMs": next(t for t, c in counts if c == peak),
        "final": counts[-1][1],
        "secondHalfMin": min((len(s[core]) for s in late), default=None),
        "secondHalfMax": max((len(s[core]) for s in late), default=None),
        "distinctPids": len(first_seen),
        # 多于应有的进程数即有进程被杀后由应用重启（取样间隔内生灭的进程看不到，只是下限）
        "pidsBeyondExpected": max(0, len(first_seen) - expected),
        "pidsNewAfterConnected": None if base is None else sum(1 for t in first_seen.values() if t > base),
        "pidsGoneBeforeEnd": sum(1 for t in last_seen.values() if t < samples[-1]["tMs"]),
    }


# ---- 状态与启动耗时 ----
transitions = (last_status or {}).get("transitions") or []
connected = next((t for t in transitions if t["state"] == "Connected"), None)
stopped = [t for t in transitions if t["state"] == "Stopped"]
start_block = {
    "requested": start is not None and start["status"] == "ok",
    "error": None if start is None or start["status"] == "ok" else start["message"],
    "serviceMode": start_info.get("serviceMode"),
    "service": start_info.get("service"),
    "appProcessesAtRequest": start_info.get("processes"),
    "connectedMs": connected["tMs"] if connected else None,
    "transitions": transitions,
}
if not connected and start_block["requested"]:
    start_block["failure"] = (
        {"state": "Stopped", "msg": stopped[0]["msg"], "tMs": stopped[0]["tMs"]} if stopped
        else {"state": (last_status or {}).get("state"), "msg": "not connected within the sampling window"}
    )
final_state = (last_status or {}).get("state")
stopped_during = [t for t in transitions if t["state"] in ("Stopping", "Stopped") and (not connected or t["tMs"] > connected["tMs"])]
final_block = {
    "stateAtEnd": final_state,
    "serviceRunningAtEnd": final_state in ("Connecting", "Connected"),
    "stoppedDuringSampling": bool(stopped_during),
    "stopMessage": next((t["msg"] for t in stopped_during if t.get("msg")), None),
}

# ---- 系统日志：phantom process ----
def rel_ms(epoch):
    return None if req_wall is None else int(round(float(epoch) * 1000 - req_wall))


LOG_LINE = re.compile(r"^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFS])\s+([^:]*?)\s*:\s?(.*)$")
PHANTOM_KILL = re.compile(r"Killing PhantomProcessRecord \{\S+ (\d+):(\d+):([^/}]+)/(\S+)\}: (.*)")
phantom_lines, phantom_kills, am_kill_events = [], [], []
for line in (read("logcat.txt") or "").splitlines():
    m = LOG_LINE.match(line)
    if not m:
        continue
    epoch, _, _, _, tag, msg = m.groups()
    t = rel_ms(epoch)
    if t is None or t < -5000:
        continue
    # adbd 会把本脚本读设置的命令行原样记下来，里面也有 phantom 字样
    if "phantom" not in line.lower() or tag.strip() == "adbd":
        continue
    phantom_lines.append({"tMs": t, "line": line.strip()})
    k = PHANTOM_KILL.search(msg)
    if k and tag.strip() == "ActivityManager":
        phantom_kills.append({"tMs": t, "epoch": float(epoch), "pid": int(k.group(1)), "ppid": int(k.group(2)),
                              "name": k.group(3), "uid": k.group(4), "reason": k.group(5)})
    elif tag.strip() == "am_kill":
        am_kill_events.append({"tMs": t, "line": msg})


# 同一次扫描里的清理在几毫秒内完成：间隔 2 秒以内的算一批。epoch 是设备时间（秒），用来跨次比较
def bursts(kills, gap=2000):
    groups = []
    for k in sorted(kills, key=lambda k: k["tMs"]):
        if groups and k["tMs"] - groups[-1][-1]["tMs"] <= gap:
            groups[-1].append(k)
        else:
            groups.append([k])
    return [{"startMs": g[0]["tMs"], "endMs": g[-1]["tMs"], "startEpoch": g[0]["epoch"], "kills": len(g),
             "pids": [k["pid"] for k in g]} for g in groups]


kill_bursts = bursts(phantom_kills)
table = read("phantom-table.txt") or ""
TABLE_ROW = re.compile(r"PhantomProcessRecord \{\S+ (\d+):(\d+):([^/}]+)/")
known = [m.groups() for m in TABLE_ROW.finditer(table)]
ours = [k for k in phantom_kills if core_of(k["name"])]
phantom_block = {
    "killCount": len(phantom_kills),
    "killCountOurCores": len(ours),
    "killsByName": {n: sum(1 for k in phantom_kills if k["name"] == n) for n in sorted({k["name"] for k in phantom_kills})},
    "killsByReason": {r: sum(1 for k in phantom_kills if k["reason"] == r) for r in sorted({k["reason"] for k in phantom_kills})},
    "firstKillMs": phantom_kills[0]["tMs"] if phantom_kills else None,
    "lastKillMs": phantom_kills[-1]["tMs"] if phantom_kills else None,
    "bursts": kill_bursts,
    "burstIntervalsMs": [b["startMs"] - a["startMs"] for a, b in zip(kill_bursts, kill_bursts[1:])],
    "amKillEvents": len(am_kill_events),
    # 取样结束时系统登记的 phantom process：总数、其中本应用外核的数量、登记时间（knownSince 原文）
    "knownAtEnd": len(known),
    "knownAtEndOurCores": sum(1 for _, _, n in known if core_of(n)),
    "knownSinceAtEnd": sorted(set(re.findall(r"knownSince=(\S+)", table)))[:5],
    "phantomLineCount": len(phantom_lines),
    "sampleLines": [p["line"] for p in phantom_lines[:12]],
}

# ---- 应用日志（neko.log）：外核进程的启动 / 退出 / 重启 ----
APP_PATTERNS = {
    "startProcess": "] start process: ",
    "restartProcess": "restart process: ",
    "killed": " was killed",
    "unexpectedExit": " unexpectedly exits with code ",
    "exitsTooFast": " exits too fast ",
    "stopGuard": "error occurred. stop guard: ",
}
app_lines = (read("neko.log") or "").splitlines()
app_block = {"lines": len(app_lines), "counts": {}, "samples": {}, "events": []}
# Go 的 log 前缀是 UTC 的「年/月/日 时:分:秒」，只精确到秒
APP_TIME = re.compile(r"^(\d{4})/(\d\d)/(\d\d) (\d\d):(\d\d):(\d\d) ")


def app_rel_ms(line):
    m = APP_TIME.match(line)
    if not m or req_wall is None:
        return None
    epoch = calendar.timegm(tuple(int(v) for v in m.groups()) + (0, 0, 0))
    return epoch * 1000 - req_wall


for key, needle in APP_PATTERNS.items():
    hits = [l for l in app_lines if needle in l]
    for l in hits:
        app_block["events"].append({"tMs": app_rel_ms(l), "kind": key,
                                    "core": "xray" if "libxray" in l else "mihomo" if "libmihomo" in l else None})
    app_block["counts"][key] = {
        "all": len(hits),
        "xray": sum(1 for l in hits if "libxray" in l),
        "mihomo": sum(1 for l in hits if "libmihomo" in l),
    }
    app_block["samples"][key] = [l[:300] for l in hits[:5]]
app_block["events"].sort(key=lambda e: (e["tMs"] is None, e["tMs"] or 0))

# ---- 内存 ----
def mem(label):
    text = read("mem-%s.txt" % label)
    if text is None:
        return None
    procs, ppids, rollup, info, uptime, cur_pid = {}, {}, {}, {}, None, None
    for line in text.splitlines():
        if line.startswith("## label="):
            uptime = float(line.split("uptime=")[1])
        elif line.startswith("## proc "):
            pid, ppid, name = line[len("## proc "):].split(None, 2)
            procs[int(pid)] = name
            ppids[int(pid)] = int(ppid)
        elif line.startswith("## meminfo "):
            k, v = line[len("## meminfo "):].split(":", 1)
            info[k.strip()] = int(v.split()[0])
        elif line.startswith("== "):
            cur_pid = int(line[3:])
            rollup[cur_pid] = {}
        elif cur_pid is not None and ":" in line and line.split(":")[0] in ("Rss", "Pss", "Pss_Anon", "Pss_File", "Pss_Shmem", "Swap", "SwapPss"):
            k, v = line.split(":", 1)
            rollup[cur_pid][k] = int(v.split()[0])
    req = start_info.get("requestedAtElapsedMs")

    def group(pids):
        rows = [rollup.get(p, {}) for p in pids]
        return {"count": len(pids), "pids": sorted(pids), "measured": sum(1 for r in rows if "Pss" in r),
                "pssKb": sum(r.get("Pss", 0) for r in rows), "rssKb": sum(r.get("Rss", 0) for r in rows),
                "swapPssKb": sum(r.get("SwapPss", 0) for r in rows)}

    by = {"main": [], "bg": [], "xray": [], "mihomo": [], "forking": []}
    bg_candidates = {p: ppids[p] for p, n in procs.items() if n == package + ":bg"}
    bg_pid, _ = split_bg(bg_candidates)
    for pid, name in procs.items():
        core = core_of(name)
        if core:
            by[core].append(pid)
        elif name == package:
            by["main"].append(pid)
        elif pid in bg_candidates:
            by["bg" if pid == bg_pid else "forking"].append(pid)
    result = {"label": label, "tMs": None if uptime is None or req is None else int(uptime * 1000 - req)}
    for k, pids in by.items():
        result[k] = group(pids)
    allp = [p for pids in by.values() for p in pids]
    result["externalCores"] = group(by["xray"] + by["mihomo"] + by["forking"])
    result["total"] = group(allp)
    result["memAvailableKb"] = info.get("MemAvailable")
    return result


memory = [m for m in (mem("t%d" % mem_at), mem("end")) if m]

# ---- 停止与残留 ----
leftover = {"xray": 0, "mihomo": 0}
ps_after = (read("ps-after.txt") or "").splitlines()
for line in ps_after[2:]:
    parts = line.split(None, 2)
    if len(parts) == 3 and core_of(parts[2]):
        leftover[core_of(parts[2])] += 1
fin = finish_retry or finish
fin_result = fin["result"] if fin and fin["status"] == "ok" else {}
stop_block = {
    "ok": bool(fin_result.get("stopped")),
    "stateBefore": (finish or {}).get("result", {}).get("stateBefore") if finish and finish["status"] == "ok" else None,
    "stopMs": (finish or {}).get("result", {}).get("stopMs") if finish and finish["status"] == "ok" else None,
    "forceStopped": host.get("forceStopped") == "1",
    "error": None if fin and fin["status"] == "ok" else (fin or {}).get("message"),
    "restoredSettings": fin_result.get("restoredSettings"),
    "tablesEmpty": fin_result.get("tablesEmpty"),
    "leftoverProcesses": leftover,
}

max_phantom = device.get("maxPhantomProcesses")
monitor = device.get("monitorPhantomProcs")
result = {
    "formatVersion": 1,
    "startedAt": host.get("startedAt"),
    "args": {"xray": xray, "mihomo": mihomo, "singbox": singbox, "durationSec": duration,
             "intervalSec": interval, "memAtSec": mem_at, "mode": mode, "logLevel": log_level, "settleSec": settle},
    "app": {"package": package, "apk": host.get("apk"), "versionCode": as_int(host.get("versionCode")),
            "commit": host.get("commit"), "dirtyCount": as_int(host.get("dirtyCount")),
            "xrayVersion": host.get("xrayVersion"), "mihomoVersion": host.get("mihomoVersion")},
    "device": {
        "serial": host.get("serial"), "release": device.get("release"), "sdk": as_int(device.get("sdk")),
        "buildType": device.get("buildType"), "fingerprint": device.get("fingerprint"), "model": device.get("model"),
        "abi": device.get("abi"), "memTotalKb": as_int(device.get("memTotalKb")), "cpus": as_int(device.get("cpus")),
        "maxPhantomProcesses": {"raw": max_phantom, "effective": 32 if max_phantom in (None, "", "null") else as_int(max_phantom)},
        "monitorPhantomProcs": {"raw": monitor, "effective": monitor != "false"},
        "processCountBeforeStart": as_int(device.get("processCountBeforeStart")),
        "vpnAppopBefore": device.get("vpnAppopBefore"),
        "topActivityAtStart": (read("top-activity.txt") or "").strip() or None,
    },
    "fixture": prepare["result"] if prepare and prepare["status"] == "ok" else {"error": (prepare or {}).get("message")},
    "start": start_block,
    "processes": {"xray": core_stats("xray", xray), "mihomo": core_stats("mihomo", mihomo),
                  "bgPids": sorted({s["bgPid"] for s in samples if s["bgPid"]}),
                  "mainPids": sorted({s["mainPid"] for s in samples if s["mainPid"]})},
    "phantom": phantom_block,
    "appLogs": app_block,
    "memory": memory,
    "final": final_block,
    "stop": stop_block,
    "samples": samples,
}
with open(os.path.join(out, "result.json"), "w", encoding="utf-8") as f:
    json.dump(result, f, ensure_ascii=False, indent=2)
    f.write("\n")

# ---- 人读摘要 ----
def fmt_ms(v):
    return "-" if v is None else "%.1fs" % (v / 1000)


print()
print("== K0 实测摘要：%s" % out)
d = result["device"]
print("设备：Android %s（API %s，%s），内存 %s MB，%s 核；phantom 上限 %s（原值 %s），监控 %s（原值 %s）；启动前全系统进程 %s 个"
      % (d["release"], d["sdk"], d["abi"], (d["memTotalKb"] or 0) // 1024, d["cpus"],
         d["maxPhantomProcesses"]["effective"], d["maxPhantomProcesses"]["raw"],
         "开" if d["monitorPhantomProcs"]["effective"] else "关", d["monitorPhantomProcs"]["raw"],
         d["processCountBeforeStart"]))
print("夹具：Xray %d、mihomo %d、sing-box %d，模式 %s，日志等级 %d" % (xray, mihomo, singbox, mode, log_level))
if start_block["requested"]:
    if start_block["connectedMs"] is not None:
        print("启动：%s 后已连接" % fmt_ms(start_block["connectedMs"]))
    else:
        print("启动：未连接（%s）" % json.dumps(start_block.get("failure"), ensure_ascii=False))
else:
    print("启动：请求失败（%s）" % start_block["error"])
for core in ("xray", "mihomo"):
    st = result["processes"][core]
    if st:
        print("%s：最多 %d 个（%s），结束时 %d 个，后半段 %s–%s 个，出现过 %d 个不同 pid（节点 %d 个，应有进程 %d 个）"
              % (core, st["max"], fmt_ms(st["maxAtMs"]), st["final"], st["secondHalfMin"], st["secondHalfMax"],
                 st["distinctPids"], st["nodes"], st["expected"]))
pb = phantom_block
print("系统清理：phantom 进程被杀 %d 次（本应用外核 %d 次），%d 批，首次 %s，末次 %s，批间隔 %s"
      % (pb["killCount"], pb["killCountOurCores"], len(pb["bursts"]), fmt_ms(pb["firstKillMs"]),
         fmt_ms(pb["lastKillMs"]), [round(i / 1000, 1) for i in pb["burstIntervalsMs"]]))
c = app_block["counts"]
print("系统登记的 phantom 进程（取样结束时）：%d 个，其中本应用外核 %d 个" % (pb["knownAtEnd"], pb["knownAtEndOurCores"]))
print("应用日志：启动 %d、被杀 %d、异常退出 %d、退出太快 %d、重启 %d、守护放弃 %d"
      % (c["startProcess"]["all"], c["killed"]["all"], c["unexpectedExit"]["all"], c["exitsTooFast"]["all"],
         c["restartProcess"]["all"], c["stopGuard"]["all"]))
for m in memory:
    print("内存 %s（%s）：主进程 PSS %d MB，:bg PSS %d MB，外核 %d 个 PSS %d MB / RSS %d MB，合计 PSS %d MB / RSS %d MB，系统可用 %s MB"
          % (m["label"], fmt_ms(m["tMs"]), m["main"]["pssKb"] // 1024, m["bg"]["pssKb"] // 1024,
             m["externalCores"]["count"], m["externalCores"]["pssKb"] // 1024, m["externalCores"]["rssKb"] // 1024,
             m["total"]["pssKb"] // 1024, m["total"]["rssKb"] // 1024,
             (m["memAvailableKb"] or 0) // 1024))
print("结束时服务：%s%s" % (final_state, "（取样期间停过：%s）" % final_block["stopMessage"] if final_block["stoppedDuringSampling"] else ""))
print("停止：%s，用时 %s%s，残留外核进程 %s，夹具已清 %s"
      % ("成功" if stop_block["ok"] else "失败", fmt_ms(stop_block["stopMs"]),
         "（强行停止应用）" if stop_block["forceStopped"] else "", leftover, stop_block["tablesEmpty"]))
PY

[ "$FINISHED" = 1 ] || { echo "cleanup did not complete; see $RAW/finish*.txt" >&2; exit 1; }
echo ">> results in $OUT"
