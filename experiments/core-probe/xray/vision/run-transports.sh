#!/bin/sh
# Vision 跑在各传输上的核实（gen-transports.py 生成的 transports/）。模拟器 mksh：
#   cd <本目录>; XRAY=<xray> SINGBOX=<sing-box linux/arm64> PROBE=<socksprobe>（均为绝对路径） sh run-transports.sh > transports-results.txt 2>&1
# 判读：PASS / FAIL = 经 :31000 发一行，收到 / 收不到 TARGET-REPLY-OK；UDP:REPLY = socksprobe 第一次发包有回显；- = 只记录
. "$(dirname "$0")/../runner-lib.sh"
require_bins XRAY SINGBOX PROBE
cd "$(dirname "$0")/transports" || exit 1

open_manifest
while IFS='	' read -r id kind args expect note <&3; do
  "$PROBE" tcpserve -listen 127.0.0.1:40000 > /dev/null 2>&1 &
  t1=$!
  "$PROBE" echo -listen 127.0.0.1:40053 > "$id/echo.log" 2>&1 &
  t2=$!
  if [ -f "$id/sing-box-server.json" ]; then
    "$SINGBOX" run -c "$id/sing-box-server.json" > "$id/sing-box-server.log" 2>&1 &
  else
    "$XRAY" run -c "$id/xray.json" > "$id/xray.log" 2>&1 &
  fi
  x=$!
  s=""
  if [ -f "$id/sing-box.json" ]; then
    "$SINGBOX" run -c "$id/sing-box.json" > "$id/sing-box.log" 2>&1 &
    s=$!
  fi
  sleep 2
  case "$kind" in
    *-udp) out=$("$PROBE" udp $args -user "$U" -pass "$P" 2>&1) ;;
    *) out=$("$PROBE" tcp -addr 127.0.0.1:31000 -wait 5s 2>&1) ;;
  esac
  sleep 1
  kill $x $t1 $t2 $s 2>/dev/null
  wait $x $t1 $t2 $s 2>/dev/null
  if tcp_ok "$out"; then got=PASS
  elif has "$out" "R1 REPLY="; then got=UDP:REPLY
  elif has "$out" "R1 TIMEOUT"; then got=UDP:TIMEOUT
  else got=FAIL
  fi
  if [ "$expect" = - ]; then verdict=RECORD; elif [ "$expect" = "$got" ]; then verdict=PASS; else verdict=MISMATCH; fi
  echo "### $id [$kind] $verdict got=$got expect=$expect"
  echo "    note: $note"
  echo "$out" | sed 's/^/    probe| /'
  # 两个核心各自的关键报错（去掉时间戳与连接编号，同类合并计数）
  for f in "$id/xray.log" "$id/sing-box-server.log" "$id/sing-box.log"; do
    [ -f "$f" ] || continue
    grep -a -o -E 'XTLS[^>]{0,80}|vision[^>]{0,120}|initialize vision[^>]{0,120}|failed to use [^>]{0,80}|not a valid supported TLS connection[^>]{0,80}|rejected[^>]{0,60}|found tls 1\.3|CopyRawConn[a-z() ]*' "$f" \
      | sed -E 's/\[[0-9]+ [0-9a-z]+ms\] //' | sort | uniq -c | sed "s#^#    $(basename "$f" .log)| #"
  done
done
