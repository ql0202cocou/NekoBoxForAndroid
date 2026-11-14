#!/bin/sh
# run -test / convert pb -debug 组。按 test/manifest.tsv 逐条执行，并与期望对照。
# 用法（模拟器，mksh）：cd <本目录>; XRAY=<xray 绝对路径> sh run-test.sh > test-results.txt 2>&1
# 用法（本机预跑，只检查配置写得对不对，不作证据）：XRAY=<macOS 版 xray 的绝对路径> sh run-test.sh
# 判读：
#   OK            退出码 0 且输出有「Configuration OK.」
#   ERR:<原文>    退出码 23 且输出含 <原文>
#   PB:+<片段>    convert pb -debug 成功退出且输出含 <片段>；PB:-<片段> 成功退出且不含
#   多个条件用「 && 」连接，全部满足才算 PASS
. "$(dirname "$0")/runner-lib.sh"
require_bins XRAY
cd "$(dirname "$0")/test" || exit 1

open_manifest
while IFS='	' read -r id file cmd expect item src note <&3; do
  run_config_cmd "$cmd" "$file"
  eval_expect check_config "$expect" "$out" "$code"
  echo "### $id [$item] $verdict exit=$code cmd=$cmd file=$file"
  echo "    expect: $expect"
  # 原始输出：去掉版本横幅、Reading config 行与弃用警告（警告只记一个标记）；pb 输出只留匹配期望的行
  if [ "$cmd" = pb ]; then
    printf '%s\n' "$out" | grep -E 'type|authType|accessLogType|udpEnabled|xudpProxyUDP443|echConfigList|echForceQuery|pinnedPeerCertSha256|mldsa65Verify|serviceName|protocolName|"ed"|"path"|"host"|"address"|Host|example' | sed 's/^/    | /' | head -40
  else
    printf '%s\n' "$out" | grep -v -E '^Xray [0-9]|^A unified platform|Reading config|^$' \
      | sed -E 's/^[0-9\/]+ [0-9:.]+ \[Warning\] common\/errors: The feature ([^(]*[^ (]) *\(.*deprecated.*/[W: \1 deprecated]/; s/^/    | /' | head -20
  fi
done
