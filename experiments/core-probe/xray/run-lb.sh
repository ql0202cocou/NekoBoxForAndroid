#!/bin/sh
# 回环组。按 <LBDIR>/manifest.tsv 逐条：起 TCP 目标（:40000）与 UDP 回显（:40053）→ 起 Xray → 用 socksprobe 发流量 → 判读。
# LBDIR 缺省 loopback；LBDIR=vision/loopback 跑 Vision 那一组。
# 用法（模拟器，mksh）：cd <本目录>; XRAY=<xray 绝对路径> PROBE=<socksprobe 绝对路径> sh run-lb.sh [只跑 id 或文件名里含此串的用例] > lb-results.txt 2>&1
# 用法（本机预跑，只检查配置写得对不对，不作证据）：同上，XRAY / PROBE 换成 macOS 版
# 期望子句（用「 && 」连接，全部满足才算 PASS）：
#   PASS / FAIL          经 :31000 发一行，收到 / 收不到 TARGET-REPLY-OK
#   UDP:REPLY / UDP:TIMEOUT    socksprobe 第一次发包（R1）有 / 没有回显；R2:… / R3:… 同理（-second）
#   HS=<十六进制>         握手时服务端回的字节
#   log:+<片段> / log:-<片段>   Xray 进程输出（含 REALITY show 打印）里应有 / 不应有
#   hello:+<片段> / hello:-<片段>   socksprobe tlshello 打印的 ClientHello（SNI / ALPN / 曲线）里应有 / 不应有
#   vision:PADDED / vision:DIRECT   核对 Vision 的 padding / 直拷命令，不能用通用 CopyRawConn 日志替代
#   ANY                  不判定：跑 5 次，记 TCP 通的次数（random / randomized 指纹）
. "$(dirname "$0")/runner-lib.sh"
require_bins XRAY PROBE
cd "$(dirname "$0")/${LBDIR:-loopback}" || exit 1
init_children

check_services() {
  check_service "$t1" "$log.tcp" 'TCP-LISTEN='
  check_service "$t2" "$helper_log" "$helper_ready"
  check_service "$x" "$log" 'core: Xray .* started'
}

once() { # $1 file $2 kind $3 args $4 log → 结果写到 out、run_error
  run_error=""
  out=""
  if [ "$2" = hello ]; then
    helper_log="$4.hello"; helper_ready='TLS-LISTEN='
    start_logged "$4.tcp" "$PROBE" tcpserve -listen 127.0.0.1:40000
    t1=$child
    start_logged "$helper_log" "$PROBE" tlshello -listen 127.0.0.1:32000
    t2=$child
  else
    helper_log="$4.echo"; helper_ready='ECHO-LISTEN='
    start_targets "$4.tcp" "$helper_log"
  fi
  start_logged "$4" "$XRAY" run -c "$1"
  x=$child
  pause_children 2
  check_services
  if [ -z "$run_error" ]; then
    case "$2" in
      tcp|hello) probe_tcp "$4.probe" ;;
      udp|hs) probe_socks "$4.probe" "$2" "$3" ;;
      *) case_error "unknown kind: $2" ;;
    esac
    pause_children "${POST_WAIT:-1}" # 保留日志等待窗口；握手负例不依赖清理阶段日志
    check_services
    if [ "$2" = hello ] && ! grep -q '^HELLO ' "$helper_log"; then
      case_error 'missing ClientHello observation'
    fi
  fi
  cleanup_children
}

check() { # $1 子句 $2 探针输出 $3 日志文件
  case "$1" in
    R2:*|R3:*) has "$2" "${1%%:*} ${1#*:}" ;;
    HS=*) has "$2" "$1" ;;
    hello:+*) has "$(cat "$3.hello")" "${1#hello:+}" ;;
    hello:-*) ! has "$(cat "$3.hello")" "${1#hello:-}" ;;
    *) check_traffic "$@" ;;
  esac
}

open_manifest
while IFS='	' read -r id file kind args expect item src note <&3; do
  selected "$1" "$id $file" || continue
  log="${file%.json}.log"
  [ "$args" = - ] && args=""
  if [ "$expect" = ANY ]; then
    n=0
    for i in 1 2 3 4 5; do
      once "$file" "$kind" "$args" "$log"
      [ -n "$run_error" ] && break
      tcp_ok "$out" && n=$((n + 1))
    done
    verdict=ANY
    [ -n "$run_error" ] && verdict=ERROR
    echo "### $id [$item] $verdict passes=$n/5 file=$file"
    echo "    note: $note"
    print_run_error
    continue
  fi
  once "$file" "$kind" "$args" "$log"
  traffic_verdict check "$expect" "$log"
  echo "### $id [$item] $verdict kind=$kind file=$file"
  echo "    expect: $expect"
  print_run_error
  printf '%s\n' "$out" | sed 's/^/    probe| /'
  [ "$kind" = hello ] && sed 's/^/    hello| /' "$log.hello" | head -3
  case "$expect" in
    *vision:*) grep -E 'proxy: (Xtls|CopyRawConn)' "$log" | sed -E 's/^[0-9\/]+ [0-9:.]+ //; s/^/    vision| /' | head -20 ;;
  esac
  # Xray 输出只留与判读有关的行
  grep -E 'Failed|failed|error|rejected|invalid|unrecognized|peer cert|ECH|x509|REALITY remoteAddr: .*(ClientVer|conn == conn|forwarded SNI)|processed invalid|mux|XUDP|received request|does not support|blocked|finalRules' "$log" \
    | grep -v 'deprecated' | sed -E 's/^[0-9\/]+ [0-9:.]+ //; s/^/    xray| /' | head -12
done
