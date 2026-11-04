# AnyTLS 兼容对照 / AnyTLS compatibility oracle

实现与回归测试现位于 `libcore/anytlscompat/`，由正式 libcore 注册表共用于运行、测速和 trial。
本目录只保留真实 mihomo 对照的运行入口，不再维护第二份独立实现或 Go 模块。

Implementation and regression tests now live in `libcore/anytlscompat/`, registered by libcore for runtime,
URL testing and trial. This directory retains the real mihomo oracle runner, with no duplicate implementation or module.

需要 Go 1.26.10 和经过官方资产 SHA-256 校验的本机 mihomo **v1.19.33**。服务只监听回环地址，
临时证书、私钥与 mihomo 配置在测试期间生成并清理。拒绝场景包含重试等待，全套约需数分钟。

Requires Go 1.26.10 and a native mihomo **v1.19.33** binary verified against its official asset SHA-256.
Fixtures bind to loopback and generate temporary PKI and configurations. Rejection cases include retry delays.

```sh
MIHOMO_BIN=/absolute/path/to/mihomo sh experiments/anytls-compat/run.sh
MIHOMO_BIN=/absolute/path/to/mihomo sh experiments/anytls-compat/run.sh -race \
  -run 'Test(MihomoServerInterop|ConcurrentClose|CancelDuringAuthenticationWrite|ControlledDialDNSAndScope|ECHRefreshAndRecovery|HandshakeCancellation)$'
# 正式注册、UrlTest、trial 与分组 DNS；在 libcore 中执行。
cd libcore
MIHOMO_BIN=/absolute/path/to/mihomo GOTOOLCHAIN=go1.26.10 go test -race \
  -ldflags=-checklinkname=0 -run '^TestCompatibility' .
```

`run.sh` 缺少真实 mihomo 时失败；直接 `go test` 的相关用例会明确跳过，不代表互通验证。
接口、安全语义和支持边界见 [实现说明](../../libcore/anytlscompat/README.md)，
来源见 [许可说明](../../libcore/anytlscompat/NOTICE.md)。

`run.sh` requires the real oracle. Direct `go test` skips oracle cases when the binary is absent.
See the [implementation notes](../../libcore/anytlscompat/README.md) and [licensing](../../libcore/anytlscompat/NOTICE.md).
