#!/bin/sh
# XHTTP run -test / convert pb -debug 组，同上一级 run-test.sh（目录换成本目录的 test/）。XRAY 为绝对路径。
. "$(dirname "$0")/../runner-lib.sh"
require_bins XRAY
cd "$(dirname "$0")/test" || exit 1

open_manifest
while IFS='	' read -r id file cmd expect item src note <&3; do
  case "$cmd" in
    test) out=$("$XRAY" run -test -c "$file" 2>&1); code=$? ;;
    pb) out=$("$XRAY" convert pb -debug "$file" 2>&1); code=$? ;;
    *) out="unknown cmd $cmd"; code=99 ;;
  esac
  eval_expect check_config "$expect" "$out" "$code"
  echo "### $id [$item] $verdict exit=$code cmd=$cmd file=$file"
  echo "    expect: $expect"
  echo "    note: $note"
  if [ "$cmd" = pb ]; then
    printf '%s\n' "$out" | grep -E '"(path|host|mode|from|to|X-K2|address)"|dl.example|extra|Stream' | sed 's/^/    out| /' | head -14
  else
    printf '%s\n' "$out" | grep -v -E '^Xray |^A unified|Reading config|deprecated' | sed 's/^[0-9\/]* [0-9:.]* //; s/^/    out| /' | cut -c1-300 | head -6
  fi
done
