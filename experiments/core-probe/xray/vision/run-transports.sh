#!/bin/sh
# Vision 跑在各传输上的核实（gen-transports.py 生成的 transports/）。模拟器 mksh：
#   cd <本目录>; XRAY=<xray> SINGBOX=<sing-box linux/arm64> PROBE=<socksprobe>（均为绝对路径） sh run-transports.sh > transports-results.txt 2>&1
# 判读：PASS / FAIL = 经 :31000 发一行，收到 / 收不到 TARGET-REPLY-OK；UDP:REPLY = socksprobe 第一次发包有回显；- = 只记录
. "$(dirname "$0")/../runner-lib.sh"
require_bins XRAY SINGBOX PROBE
cd "$(dirname "$0")/transports" || exit 1
init_children

check_services() {
  check_service "$t1" "$id/tcp.log" 'TCP-LISTEN='
  check_service "$t2" "$id/echo.log" 'ECHO-LISTEN='
  check_service "$x" "$server_log" "$server_ready"
  [ -z "$s" ] || check_service "$s" "$id/sing-box.log" 'sing-box started'
}

open_manifest
while IFS='	' read -r id kind args expect note <&3; do
  run_error=""
  out=""
  start_targets "$id/tcp.log" "$id/echo.log"
  if [ -f "$id/sing-box-server.json" ]; then
    server_log="$id/sing-box-server.log"; server_ready='sing-box started'
    start_logged "$server_log" "$SINGBOX" run -c "$id/sing-box-server.json"
  else
    server_log="$id/xray.log"; server_ready='core: Xray .* started'
    start_logged "$server_log" "$XRAY" run -c "$id/xray.json"
  fi
  x=$child
  s=""
  if [ -f "$id/sing-box.json" ]; then
    start_logged "$id/sing-box.log" "$SINGBOX" run -c "$id/sing-box.json"
    s=$child
  fi
  pause_children 2
  check_services
  if [ -z "$run_error" ]; then
    case "$kind" in
      *-udp) probe_socks "$id/probe.log" udp "$args" ;;
      *-tcp) probe_tcp "$id/probe.log" ;;
      *) case_error "unknown kind: $kind" ;;
    esac
    pause_children 1
    check_services
  fi
  cleanup_children
  if [ -n "$run_error" ]; then got=ERROR
  elif tcp_ok "$out"; then got=PASS
  elif has "$out" "R1 REPLY="; then got=UDP:REPLY
  elif has "$out" "R1 TIMEOUT"; then got=UDP:TIMEOUT
  else got=FAIL
  fi
  if [ "$got" = ERROR ]; then verdict=ERROR
  elif [ "$expect" = - ]; then verdict=RECORD
  elif [ "$expect" = "$got" ]; then verdict=PASS
  else verdict=MISMATCH
  fi
  echo "### $id [$kind] $verdict got=$got expect=$expect"
  echo "    note: $note"
  print_run_error
  echo "$out" | sed 's/^/    probe| /'
  # 两个核心各自的关键报错（去掉时间戳与连接编号，同类合并计数）
  for f in "$id/xray.log" "$id/sing-box-server.log" "$id/sing-box.log"; do
    [ -f "$f" ] || continue
    grep -a -o -E 'XTLS[^>]{0,80}|vision[^>]{0,120}|initialize vision[^>]{0,120}|failed to use [^>]{0,80}|not a valid supported TLS connection[^>]{0,80}|rejected[^>]{0,60}|found tls 1\.3|CopyRawConn[a-z() ]*' "$f" \
      | sed -E 's/\[[0-9]+ [0-9a-z]+ms\] //' | sort | uniq -c | sed "s#^#    $(basename "$f" .log)| #"
  done
done
