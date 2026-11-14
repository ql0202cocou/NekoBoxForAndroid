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
init_children

check_services() {
  check_service "$t1" "$log.tcp" 'TCP-LISTEN='
  check_service "$t2" "$log.echo" 'ECHO-LISTEN='
  check_service "$x" "$log" 'core: Xray .* started'
  [ -z "$t3" ] || check_service "$t3" "$log.dump" 'HTTPDUMP-LISTEN='
  [ -z "$t4" ] || check_service "$t4" "$log.dump" 'ECHO-LISTEN='
}

once() { # $1 file $2 kind $3 args $4 log → 结果写到 out、run_error
  run_error=""
  out=""
  start_targets "$4.tcp" "$4.echo"
  t3=""
  t4=""
  case "$2" in
    dump|dumpudp)
      # args 为空时 httpdump 用自己的缺省监听地址 127.0.0.1:32000
      # shellcheck disable=SC2086
      start_logged "$4.dump" "$HTTPDUMP" $3
      t3=$child
      if [ "$2" = dumpudp ]; then
        # 与 httpdump 共用一份日志，接着写
        start_child "$4.dump" "$PROBE" echo -listen 127.0.0.1:32000
        t4=$child
      fi
      ;;
  esac
  start_logged "$4" "$XRAY" run -c "$1"
  x=$child
  pause_children 2
  check_services
  if [ -z "$run_error" ]; then
    case "$2" in
      tcp|dump|dumpudp) probe_tcp "$4.probe" ;;
      udp) probe_socks "$4.probe" udp "$3" ;;
      *) case_error "unknown kind: $2" ;;
    esac
    pause_children "${POST_WAIT:-2}"
    check_services
    if [ -n "$t3" ] && grep -q '^HTTPDUMP-ERROR=' "$4.dump"; then
      case_error 'incomplete HTTP observation'
    fi
  fi
  cleanup_children
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
  selected "$1" "$id $file" || continue
  log="${file%.json}.log"
  [ "$args" = - ] && args=""
  once "$file" "$kind" "$args" "$log"
  traffic_verdict check "$expect" "$log"
  echo "### $id [$item] $verdict kind=$kind file=$file"
  echo "    expect: $expect"
  echo "    note: $note"
  print_run_error
  printf '%s\n' "$out" | sed 's/^/    probe| /'
  case "$kind" in dump|dumpudp) grep -E 'REQLINE|HOST=|ECHO-GOT|^RAW\| (GET|POST|PRI|\\x16)|X-K2|Referer' "$log.dump" | sed 's/^/    dump| /' | head -16 ;; esac
  grep -E 'XHTTP is dialing|XTLS|padding|Failed|failed|error|rejected|invalid|unrecognized|peer cert|x509|mux|XUDP|does not support|blocked|splithttp|mismatch|unauthorized|forbidden|not allowed|host' "$log" \
    | grep -v 'deprecated' | sed -E 's/^[0-9\/]+ [0-9:.]+ //; s/^/    xray| /' | cut -c1-260 | head -10
done
