# 各执行器共用（在 cd 之前用 . 引入）：本机 socks 凭据、路径检查、读清单、期望子句的拆分与公共判读。
U=uc90f4cf742b44cca
P=p7b7c6006f6cfa3368ea9f75160f0df4b

# 路径必须是绝对路径且可执行；进程是否成功启动由每个用例另行检查。
require_bins() { # 参数：变量名
  for name in "$@"; do
    eval "bin=\${$name:-}"
    case "$bin" in
      /*) [ -x "$bin" ] || { echo "$name 不可执行：$bin" >&2; exit 2; } ;;
      *) echo "$name 必须是绝对路径（当前：${bin:-未设置}）" >&2; exit 2 ;;
    esac
  done
}

# manifest 走单独的 fd 3（循环里用 read … <&3）：走标准输入的话，继承它的子进程可能读走剩下的行
open_manifest() {
  exec 3< manifest.tsv
  read -r header <&3
}

has() { # $1 文本 $2 片段
  case "$1" in *"$2"*) return 0 ;; esac
  return 1
}

# 清单行过滤：没给过滤串，或「id 文件名」里含它
selected() { # $1 过滤串 $2 「id 文件名」
  [ -z "$1" ] || has "$2" "$1"
}

tcp_ok() { # $1 探针输出
  printf '%s\n' "$1" | grep -qxF 'TCP-REPLY=TARGET-REPLY-OK'
}

# 所有后台进程都由执行器本身持有，不能在命令替换的子 shell 中启动。
init_children() {
  children=""
  trap 'trap "" INT TERM; cleanup_children' 0
  trap 'exit 130' INT
  trap 'exit 143' TERM
}

start_child() { # $1 日志文件，其余为命令；结果写到 child
  child_log=$1
  shift
  "$@" >> "$child_log" 2>&1 < /dev/null 3<&- &
  child=$!
  children="$children $child"
}

wait_child() { # 已完成的短命令不再参与清理
  wait "$1"
  child_code=$?
  remaining=""
  for owned in $children; do
    [ "$owned" = "$1" ] || remaining="$remaining $owned"
  done
  children=$remaining
  return "$child_code"
}

pause_children() { # 后台 wait 可及时响应只发给执行器的信号
  start_child /dev/null sleep "$1"
  wait_child "$child"
}

cleanup_children() {
  [ -n "$children" ] || return 0
  kill $children 2>/dev/null
  # 最多给进程两秒处理 TERM；拒绝退出的进程也必须回收。
  attempts=0
  while [ "$attempts" -lt 20 ]; do
    alive=""
    for owned in $children; do
      kill -0 "$owned" 2>/dev/null && alive="$alive $owned"
    done
    [ -n "$alive" ] || break
    sleep 0.1
    attempts=$((attempts + 1))
  done
  for owned in $children; do
    kill -0 "$owned" 2>/dev/null && kill -KILL "$owned" 2>/dev/null
    wait "$owned" 2>/dev/null
  done
  children=""
}

case_error() {
  run_error="${run_error}${run_error:+; }$*"
}

check_service() { # $1 PID $2 日志 $3 就绪标志（grep ERE）
  if ! kill -0 "$1" 2>/dev/null; then
    case_error "service exited: $2"
  elif ! grep -qE "$3" "$2"; then
    case_error "service not ready: $2"
  fi
}

run_probe() { # $1 日志，其余为探针参数；保留退出码，不能用缺少成功输出代表负例
  probe_log=$1
  shift
  : > "$probe_log"
  start_child "$probe_log" "$PROBE" "$@"
  wait_child "$child"
  probe_code=$?
  out=$(cat "$probe_log")
  [ "$probe_code" = 0 ] || case_error "probe exit=$probe_code"
  has "$out" 'ERR=' && case_error 'probe reported an error'
  case "$1" in
    tcp) has "$out" 'TCP-REPLY=' || has "$out" 'TCP-NOREPLY' || case_error 'missing TCP observation' ;;
    udp) has "$out" 'R1 REPLY=' || has "$out" 'R1 TIMEOUT' || case_error 'missing UDP observation' ;;
    hs) has "$out" 'HS=' || case_error 'missing handshake observation' ;;
    *) case_error "unknown probe: $1" ;;
  esac
}

traffic_verdict() { # $1 判读函数 $2 期望 $3 日志
  if [ -n "$run_error" ]; then
    verdict=ERROR
  else
    eval_expect "$1" "$2" "$out" "$3"
  fi
}

print_run_error() {
  [ -z "$run_error" ] || echo "    runner| $run_error"
}

# 拆开「 && 」连接的期望子句逐条判读，结果写到 verdict
eval_expect() { # $1 判读函数 $2 期望，其余参数原样传给判读函数
  checker=$1
  rest=$2
  shift 2
  verdict=PASS
  while [ -n "$rest" ]; do
    clause=${rest%% && *}
    if [ "$clause" = "$rest" ]; then rest=""; else rest=${rest#* && }; fi
    "$checker" "$clause" "$@" || verdict=MISMATCH
  done
}

# run -test / convert pb -debug 组：执行一条，输出写到 out、退出码写到 code
run_config_cmd() { # $1 cmd $2 配置文件
  case "$1" in
    test) out=$("$XRAY" run -test -c "$2" 2>&1); code=$? ;;
    pb) out=$("$XRAY" convert pb -debug "$2" 2>&1); code=$? ;;
    test+env:*) out=$(env "${1#test+env:}" "$XRAY" run -test -c "$2" 2>&1); code=$? ;;
    *) out="unknown cmd $1"; code=99 ;;
  esac
}

check_config() { # $1 子句 $2 输出 $3 退出码
  case "$1" in
    OK) [ "$3" = 0 ] && has "$2" "Configuration OK." ;;
    ERR:*) [ "$3" = 23 ] && has "$2" "${1#ERR:}" ;;
    PB:+*) [ "$3" = 0 ] && has "$2" "${1#PB:+}" ;;
    PB:-*) [ "$3" = 0 ] && ! has "$2" "${1#PB:-}" ;;
    *) return 1 ;;
  esac
}

# v26.11.9 proxy/proxy.go：CommandPaddingDirect=2；收到完整块后 VisionReader 才切换读取外层缓冲。
# CopyRawConn 也由 freedom 的普通回包路径调用，readv / splice 日志不是 Vision 切换的专属证据。
check_vision_log() { # $1 PADDED / DIRECT $2 日志文件
  sent='proxy: XtlsPadding [0-9]+ [0-9]+ '
  received='proxy: Xtls Unpadding new block, content [0-9]+ padding [0-9]+ command '
  case "$1" in
    PADDED)
      grep -qE "${sent}0$" "$2" && grep -qE "${received}0$" "$2" &&
        ! grep -qE "(${sent}|${received})2$" "$2"
      ;;
    DIRECT)
      grep -qF 'proxy: XtlsFilterTls found tls 1.3!' "$2" &&
        grep -qE "${sent}2$" "$2" && grep -qE "${received}2$" "$2"
      ;;
    *) return 1 ;;
  esac
}

# 回环组的公共子句
check_traffic() { # $1 子句 $2 探针输出 $3 日志文件
  case "$1" in
    PASS) tcp_ok "$2" ;;
    FAIL) { has "$2" 'TCP-NOREPLY' || has "$2" 'TCP-REPLY='; } && ! tcp_ok "$2" ;;
    UDP:REPLY) has "$2" "R1 REPLY=" ;;
    UDP:TIMEOUT) has "$2" "R1 TIMEOUT" ;;
    vision:*) check_vision_log "${1#vision:}" "$3" ;;
    log:+*) has "$(cat "$3")" "${1#log:+}" ;;
    log:-*) ! has "$(cat "$3")" "${1#log:-}" ;;
    *) return 1 ;;
  esac
}
