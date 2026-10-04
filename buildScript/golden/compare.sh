#!/bin/bash

# 把新采集的配置产物与基线做结构比较并打印报告；有差异、或没能真正比较时返回非零。
# 用法：./run golden compare <新采集目录> [基线目录]
# 基线默认 app/src/test/resources/golden/。比较逻辑在 JVM 单测 GoldenCompareBaselineTest 里，
# 这里负责传参、强制重跑，并取回测试写出的报告。

set -eo pipefail

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
  echo "用法：./run golden compare <新采集目录> [基线目录]" >&2
  exit 2
fi

# 在调用方的当前目录下先解析成绝对路径：测试进程的工作目录是 app/
abs_dir() {
  if [ ! -d "$1" ]; then
    echo "目录不存在：$1" >&2
    return 1
  fi
  (cd "$1" && pwd)
}

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ACTUAL=$(abs_dir "$1")
EXPECTED=$(abs_dir "${2:-$ROOT/app/src/test/resources/golden}")

cd "$ROOT"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
REPORT="$TMP/report.txt"

# 只跑这一个测试类；--rerun 让输入没变时也真的执行，环境变量不算任务输入
set +e
NEKO_GOLDEN_ACTUAL="$ACTUAL" NEKO_GOLDEN_EXPECTED="$EXPECTED" NEKO_GOLDEN_REPORT="$REPORT" \
  ./gradlew app:testOssDebugUnitTest \
  --tests 'io.nekohasekai.sagernet.golden.GoldenCompareBaselineTest' \
  --rerun --console=plain
STATUS=$?
set -e

# 报告由测试写出：没有报告说明测试没执行到比较（编译失败、被跳过，或环境变量没传到测试进程）
if [ ! -s "$REPORT" ]; then
  echo "没有拿到比较报告：测试没有执行到比较（见上面的 Gradle 输出）" >&2
  exit 1
fi

echo
cat "$REPORT"

# 报告里的目录必须就是这次传入的，确认环境变量确实到了测试进程
if ! grep -qxF "预期（基线）：$EXPECTED" "$REPORT" || ! grep -qxF "实际（新采集）：$ACTUAL" "$REPORT"; then
  echo "报告里的目录与传入的不一致" >&2
  exit 1
fi

if [ "$STATUS" -ne 0 ] || ! grep -qxF "结论：一致" "$REPORT"; then
  exit 1
fi
