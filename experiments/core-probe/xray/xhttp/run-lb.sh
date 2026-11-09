#!/bin/sh
# XHTTP 回环组。基于上一级 run-lb.sh，新增两种 kind：
#   dump     客户端出站拨向 httpdump（TCP，默认 127.0.0.1:32000，可用 args 的 -listen 改）：看客户端真正发出的请求
#   dumpudp  同 dump，另在 UDP 127.0.0.1:32000 起 socksprobe echo：看 alpn=[h3] 时客户端往哪里拨
# 期望子句新增 dump:+片段 / dump:-片段（httpdump 与 echo 的输出）。其余同上一级 run-lb.sh。
# LBDIR=loopback-gen 跑「生成器真实输出」那一组。
# 用法（模拟器，mksh）：cd <本目录>; XRAY=… PROBE=… HTTPDUMP=…（均为绝对路径） sh run-lb.sh [只跑 id 或文件名里含此串的用例] > lb-results.txt 2>&1
. "$(dirname "$0")/../runner-lib.sh"
require_bins XRAY PROBE HTTPDUMP
cd "$(dirname "$0")/${LBDIR:-loopback}" || exit 1

once() { # $1 file $2 kind $3 args $4 log → 把探针的输出写到标准输出
  "$PROBE" tcpserve -listen 127.0.0.1:40000 > /dev/null 2>&1 &
  t1=$!
  "$PROBE" echo -listen 127.0.0.1:40053 > "$4.echo" 2>&1 &
  t2=$!
  t3=""
  case "$2" in
    dump|dumpudp)
      : > "$4.dump"
      # args 为空时 httpdump 用自己的缺省监听地址 127.0.0.1:32000
      # shellcheck disable=SC2086
      "$HTTPDUMP" $3 >> "$4.dump" 2>&1 &
      t3=$!
      if [ "$2" = dumpudp ]; then
        "$PROBE" echo -listen 127.0.0.1:32000 >> "$4.dump" 2>&1 &
        t3="$t3 $!"
      fi
      ;;
  esac
  "$XRAY" run -c "$1" > "$4" 2>&1 &
  x=$!
  sleep 2
  case "$2" in
    tcp|dump|dumpudp) "$PROBE" tcp -addr 127.0.0.1:31000 -wait 5s 2>&1 ;;
    udp) "$PROBE" udp $3 -user "$U" -pass "$P" 2>&1 ;;
  esac
  sleep "${POST_WAIT:-2}"
  # shellcheck disable=SC2086
  kill $x $t1 $t2 $t3 2>/dev/null
  # shellcheck disable=SC2086
  wait $x $t1 $t2 $t3 2>/dev/null
}

check() { # $1 子句 $2 探针输出 $3 日志文件
  case "$1" in
    dump:+*) has "$(cat "$3.dump")" "${1#dump:+}" ;;
    dump:-*) ! has "$(cat "$3.dump")" "${1#dump:-}" ;;
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
  out=$(once "$file" "$kind" "$args" "$log")
  eval_expect check "$expect" "$out" "$log"
  echo "### $id [$item] $verdict kind=$kind file=$file"
  echo "    expect: $expect"
  echo "    note: $note"
  printf '%s\n' "$out" | sed 's/^/    probe| /'
  case "$kind" in dump|dumpudp) grep -E 'REQLINE|HOST=|ECHO-GOT|^RAW\| (GET|POST|PRI|\\x16)|X-K2|Referer' "$log.dump" | sed 's/^/    dump| /' | head -16 ;; esac
  grep -E 'XHTTP is dialing|XTLS|padding|Failed|failed|error|rejected|invalid|unrecognized|peer cert|x509|mux|XUDP|does not support|blocked|splithttp|mismatch|unauthorized|forbidden|not allowed|host' "$log" \
    | grep -v 'deprecated' | sed -E 's/^[0-9\/]+ [0-9:.]+ //; s/^/    xray| /' | cut -c1-260 | head -10
done
