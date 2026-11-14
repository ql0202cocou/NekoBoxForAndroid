# 核心探测工具 / Core probes

用于核心升级时复核 Xray 配置检查、SOCKS、TLS / REALITY、XHTTP 和 Vision 的真实连接行为。当前能力约束与升级清单见 [`CoreCapabilities.kt`](../../app/src/main/java/io/nekohasekai/sagernet/fmt/CoreCapabilities.kt)；这些手动工具不属于 Gradle 或 CI 的自动测试。

Manual probes for core upgrades: Xray configuration checks and real SOCKS, TLS / REALITY, XHTTP and Vision connections. They are separate from the Gradle and CI test suites; application contracts live in `CoreCapabilities.kt`.

## 内容 / Contents

| 路径 / Path | 用途 / Purpose |
| --- | --- |
| `xray/gen.py`、`run-test.sh`、`run-lb.sh` | 配置检查与回环 / Configuration checks and loopback |
| `xray/probe_common.py`、`runner-lib.sh` | 生成器与执行器的共用部分 / Shared generator and runner code |
| `xray/xhttp/` | XHTTP 检查、回环及 Kotlin 输出样本 / XHTTP checks, loopback and Kotlin output samples |
| `xray/vision/` | Vision 数据通路和传输矩阵 / Vision data paths and transport matrix |
| `xray/tools/` | 仅用 Go 标准库的 SOCKS、TCP / HTTP 探针 / Go standard-library probes |
| `fixtures/` | 虚构的测试证书及 REALITY 材料 / Synthetic certificates and REALITY inputs |
| `tests/`、`xray/tools/*/*_test.go` | 执行器与探针的回归测试 / Runner and probe regression tests |

只保留工具和必要输入。生成配置、二进制和运行日志已忽略；结果按需保存到 `results/`，不要把旧结果当成新版本验证。用例 ID 保持稳定；`gen.py` 的 `src` 是最初核实版本的上游源码路径，升级时需重新定位。

Only tools and required inputs are retained. Generated configurations, binaries and logs are ignored. Save new results under `results/`; historical outcomes do not verify a new version. Case IDs remain stable; source paths describe the originally inspected upstream version.

## 生成与运行 / Generate and run

在仓库根目录运行以下生成命令。需要 Python 3；四个生成器会覆盖各自的输出目录，输入材料不变。`gen.py` 的 `XRAY_VER` 必须与待测 Xray 对应，不能只改版本号后默认所有预期仍成立。

Run these generators from the repository root with Python 3. They replace their generated outputs without changing fixtures. Match `XRAY_VER` to the tested Xray and review expectations when upgrading.

```sh
python3 experiments/core-probe/xray/gen.py
python3 experiments/core-probe/xray/xhttp/gen-xhttp.py
python3 experiments/core-probe/xray/vision/gen-vision.py
python3 experiments/core-probe/xray/vision/gen-transports.py
```

探针使用项目要求的 Go 1.26.x；在各 `xray/tools/<name>/` 目录用 `CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -o <绝对输出路径> .` 编译（输出可放在已忽略的 `experiments/core-probe/bin/`）。其它架构按目标调整。Vision 矩阵还需要与应用相同版本及构建标签的 sing-box CLI，命令见 `vision/gen-transports.py` 文件头。

Build each probe from `xray/tools/<name>/` with the project's Go 1.26.x, using the command above and an absolute output path (the ignored `experiments/core-probe/bin/` works). Adjust the target architecture as needed. The Vision matrix also needs a sing-box CLI matching the app's version and build tags; see `vision/gen-transports.py`.

执行器按 Android 模拟器的 shell 编写。将整个 `xray/` 目录（含生成配置；执行器共用 `runner-lib.sh`）和目标架构二进制放到模拟器；`XRAY`、`PROBE`、`HTTPDUMP`、`SINGBOX` 均传**目标设备上的绝对路径**，因为脚本会切换目录；不是绝对路径或不可执行时脚本直接退出。在各自目录运行：

Runners target the Android emulator shell. Copy the whole `xray/` directory with generated configurations (runners share `runner-lib.sh`) and matching binaries to the device. Set `XRAY`, `PROBE`, `HTTPDUMP` and `SINGBOX` to **absolute device paths**, since runners change directories; runners exit if a path is relative or not executable. Run from each suite directory:

```sh
# xray/
sh run-test.sh
sh run-lb.sh
LBDIR=vision/loopback sh run-lb.sh
# xray/xhttp/
sh run-test.sh
sh run-lb.sh
LBDIR=loopback-gen sh run-lb.sh
# xray/vision/
sh run-transports.sh
```

每个脚本文件头列出所需变量和筛选参数。

Runner headers list required variables and filters.

## 工具回归测试 / Tool regression tests

在仓库根目录执行以下命令，Go 使用项目要求的 1.26.x（完整补丁版本取 `.github/workflows/libcore.yml`）。测试用临时目录、可控子进程与内存连接，不需要外核、模拟器或固定端口；它们验证判读、TCP 分段读取、HTTP 头边界及中断清理，不替代上面的真实核心矩阵。可通过 `PROBE_TEST_SHELL` 指定本机的其它 shell。

Run these commands from the repository root using Go 1.26.x (the full patch version is pinned in `.github/workflows/libcore.yml`). Tests use temporary directories, controlled processes and in-memory connections, without external cores, an emulator or fixed ports. They cover verdicts, fragmented TCP reads, HTTP header boundaries and interruption cleanup, and do not replace the real-core matrix. Set `PROBE_TEST_SHELL` to test another locally executable shell.

```sh
python3 -m unittest discover -s experiments/core-probe/tests -v
(cd experiments/core-probe/xray/tools/socksprobe && go test -race ./...)
(cd experiments/core-probe/xray/tools/httpdump && go test -race ./...)
```

## 更新输入 / Refresh inputs

`xray/xhttp/genout/` 的 JSON 是从 Kotlin 生成器导出的测试输入，`gen-xhttp.py` 的规范 `extra` 也直接取自这里；样本与用例必须一一对应，缺失或多余时生成器直接报错。修改应用生成器或测试材料后，需要重新导出并审查。`TmpXhttpDump.kt.txt` 保留可重用的 JVM 导出器，输出目录需显式提供：

The JSON files in `xray/xhttp/genout/` are test inputs exported from the Kotlin generator; `gen-xhttp.py` also takes the canonical `extra` from them and fails if a sample is missing or unused. Re-export and review them after changing the app generator or fixtures. To use the reusable JVM exporter:

1. 临时复制 `xray/xhttp/TmpXhttpDump.kt.txt` 为 `app/src/test/java/io/nekohasekai/sagernet/fmt/v2ray/TmpXhttpDump.kt`，确认该目标不存在。 / Copy the exporter into the test source directory temporarily, ensuring the destination does not exist.
2. 在仓库根目录执行下列命令，输出到新目录。 / Run from the repository root, exporting to a new directory.
3. 审查全部输出，再替换 `genout/` 并重新生成探测配置；结束时删除临时测试源文件。 / Review all outputs before replacing `genout/` and regenerating probes; remove the temporary test source afterward.

```sh
CORE_PROBE_DIR="$PWD/experiments/core-probe" \
CORE_PROBE_GENOUT="/absolute/path/to/new-output" \
./gradlew app:testOssDebugUnitTest --tests '*TmpXhttpDump' --rerun
```

`fixtures/pki/gen-pki.sh` 可重建测试证书，仅保留六个必需输入。更换证书或 REALITY 材料后，必须重新导出 Kotlin 样本并运行全部生成器；导出器从当前材料读取公钥与证书指纹。测试材料只用于本机实验。

`fixtures/pki/gen-pki.sh` regenerates certificates and keeps only six required inputs. After changing certificates or REALITY material, re-export Kotlin samples and rerun all generators. The exporter derives the public key and certificate fingerprint from current fixtures. These inputs are for local testing only.

## 判读 / Interpretation

- 配置生成、`run -test`、真实连接是不同的验证层；前两项不能证明连接成功。 / Generation and configuration checks do not prove connectivity.
- `MISMATCH` 只表示与预期不同，需查看对应连接日志；`ANY` / `RECORD` 不代表稳定可用。不要自动重写预期。 / Inspect connection logs for mismatches; `ANY` and `RECORD` are not reliability guarantees. Never automatically rebaseline.
- `PB:+` / `PB:-` 要求转换成功退出。回环的 `ERROR` 表示核心或辅助服务未就绪、进程异常退出、探针失败或观测不完整；不能作为协议负例通过。`hello:-` 要求确实捕获 ClientHello；`dump:-` 允许无连接，但收集器必须就绪且不能读到残缺 HTTP 头。执行器在正常结束及 INT / TERM 中断时回收本次启动的进程。 / PB assertions require successful conversion. Loopback `ERROR` means setup, process, probe or observation failure, never an expected protocol rejection. Negative hello assertions require a captured ClientHello; negative dump assertions allow no connection but require a ready collector without incomplete HTTP headers. Runners reap their children on normal completion and INT / TERM.
- Vision 明文用例要求观察到 `command 0` 的发送与接收，且没有 `command 2`；TLS 直拷用例要求 TLS 1.3 识别及 `command 2` 的发送与接收。依据固定 Xray v26.11.9 的 `CommandPaddingDirect`，通用的 `CopyRawConn` / readv / splice 日志不能替代 Vision 协议状态证据；这也不证明内核零拷贝。 / Plaintext Vision cases require sent and received command 0 with no command 2; direct-copy cases require TLS 1.3 recognition plus sent and received command 2, matching `CommandPaddingDirect` in pinned Xray v26.11.9. Generic CopyRawConn / readv / splice logs do not prove a Vision transition or kernel zero-copy.
- REALITY 日志有时序差异；`POST_WAIT` 只改变等待时间。回环服务端必须允许 `127.0.0.0/8` 与 `::1/128`，否则目标可能被核心拦截。 / Account for REALITY log timing and allow loopback targets on the test server.
- 这里不能证明真实系统根证书的信任集合、服务商端到端表现或所有 DNS 行为。源码推断仍须与实测区分。 / These probes do not establish real system trust stores, provider connectivity or all DNS behavior. Keep source inference distinct from runtime evidence.
