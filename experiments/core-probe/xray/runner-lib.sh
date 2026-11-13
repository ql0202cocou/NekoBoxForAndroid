# 各执行器共用（在 cd 之前用 . 引入）：本机 socks 凭据、路径检查、读清单、期望子句的拆分与公共判读。
U=uc90f4cf742b44cca
P=p7b7c6006f6cfa3368ea9f75160f0df4b

# 路径必须是绝对路径且可执行：脚本会切换目录；核心没起来时 FAIL / log:- 子句照样成立，会报假 PASS
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
  has "$1" "TCP-REPLY=TARGET-REPLY-OK"
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
    PB:+*) has "$2" "${1#PB:+}" ;;
    PB:-*) ! has "$2" "${1#PB:-}" ;;
    *) return 1 ;;
  esac
}

# 回环组的公共子句
check_traffic() { # $1 子句 $2 探针输出 $3 日志文件
  case "$1" in
    PASS) tcp_ok "$2" ;;
    FAIL) ! tcp_ok "$2" ;;
    UDP:REPLY) has "$2" "R1 REPLY=" ;;
    UDP:TIMEOUT) has "$2" "R1 TIMEOUT" ;;
    log:+*) has "$(cat "$3")" "${1#log:+}" ;;
    log:-*) ! has "$(cat "$3")" "${1#log:-}" ;;
    *) return 1 ;;
  esac
}
