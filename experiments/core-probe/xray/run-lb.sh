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
#   ANY                  不判定：跑 5 次，记 TCP 通的次数（random / randomized 指纹）
. "$(dirname "$0")/runner-lib.sh"
require_bins XRAY PROBE
cd "$(dirname "$0")/${LBDIR:-loopback}" || exit 1

once() { # $1 file $2 kind $3 args $4 log → 把 socksprobe 的输出写到标准输出
  "$PROBE" tcpserve -listen 127.0.0.1:40000 > /dev/null 2>&1 &
  t1=$!
  if [ "$2" = hello ]; then
    "$PROBE" tlshello -listen 127.0.0.1:32000 > "$4.hello" 2>&1 &
  else
    "$PROBE" echo -listen 127.0.0.1:40053 > "$4.echo" 2>&1 &
  fi
  t2=$!
  "$XRAY" run -c "$1" > "$4" 2>&1 &
  x=$!
  sleep 2
  case "$2" in
    tcp|hello) "$PROBE" tcp -addr 127.0.0.1:31000 -wait 5s 2>&1 ;;
    udp) "$PROBE" udp $3 -user "$U" -pass "$P" 2>&1 ;;
    hs) "$PROBE" hs $3 -user "$U" -pass "$P" 2>&1 ;;
  esac
  sleep "${POST_WAIT:-1}"   # 日志受 target 双向连接关闭时序影响，按实际需要设置 POST_WAIT；握手负例不依赖清理阶段日志
  kill $x $t1 $t2 2>/dev/null
  wait $x $t1 $t2 2>/dev/null
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
  if [ -n "$1" ]; then
    case "$id $file" in *"$1"*) ;; *) continue ;; esac
  fi
  log="${file%.json}.log"
  [ "$args" = - ] && args=""
  if [ "$expect" = ANY ]; then
    n=0
    for i in 1 2 3 4 5; do
      o=$(once "$file" "$kind" "$args" "$log")
      case "$o" in *"TCP-REPLY=TARGET-REPLY-OK"*) n=$((n + 1)) ;; esac
    done
    echo "### $id [$item] ANY passes=$n/5 file=$file"
    echo "    note: $note"
    continue
  fi
  out=$(once "$file" "$kind" "$args" "$log")
  eval_expect check "$expect" "$out" "$log"
  echo "### $id [$item] $verdict kind=$kind file=$file"
  echo "    expect: $expect"
  printf '%s\n' "$out" | sed 's/^/    probe| /'
  [ "$kind" = hello ] && sed 's/^/    hello| /' "$log.hello" | head -3
  # Xray 输出只留与判读有关的行
  grep -E 'Failed|failed|error|rejected|invalid|unrecognized|peer cert|ECH|x509|REALITY remoteAddr: .*(ClientVer|conn == conn|forwarded SNI)|processed invalid|mux|XUDP|received request|does not support|blocked|finalRules' "$log" \
    | grep -v 'deprecated' | sed -E 's/^[0-9\/]+ [0-9:.]+ //; s/^/    xray| /' | head -12
done
