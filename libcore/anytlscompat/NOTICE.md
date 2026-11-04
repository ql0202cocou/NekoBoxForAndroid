# 来源与许可

本兼容模块沿用仓库的 GPL-3.0 许可；完整条款见根目录 `LICENSE`。

以下逻辑依据 MetaCubeX/mihomo **v1.19.33**（GPL-3.0）适配，保留上游作者的权利。没有整体嵌入 mihomo 的 outbound、全局配置、日志、CA 或 resolver：

| 本地文件 | 上游来源 | 改动边界 |
| --- | --- | --- |
| `security.go` | `component/ca/fingerprint.go`、`component/ca/name_cert_verify.go`、`component/ca/config.go`、`transport/vmess/tls.go` | 保留固定和验证域名优先级；注入根证书与时钟，去掉全局信任池和文件加载；TLS/uTLS 分别构建配置，未复制反射转换 |
| `fingerprint.go` | `component/tls/utls.go` | 保留已允许模板、6:3:2:1 权重及每进程一次的 random 选择；保留应用已有的拒绝表；未知名称不静默降级 |
| `dns.go` | `dns/resolver.go`、`adapter/outbound/ech.go`、`component/ech/ech.go` | 显式 Exchange 注入，拒绝无配置答案；缓存归属 DNSRouter |
| `fixture_test.go` 的 ECH 生成器 | `component/ech/key.go` | 只生成测试用 X25519/AES-128-GCM 配置和一次性私钥 |

固定来源：[mihomo v1.19.33](https://github.com/MetaCubeX/mihomo/tree/v1.19.33)。

会话库使用 [SagerNet/sing-anytls](https://github.com/SagerNet/sing-anytls/tree/580984e4d8cb10f1c5e88d7ec14cfe4d955d66e6)，提交 `580984e4d8cb10f1c5e88d7ec14cfe4d955d66e6`（GPL-3.0-or-later），通过 Go 模块依赖引用，未复制或修改库源码。

TLS 与 uTLS 分别依赖 `github.com/metacubex/tls v0.1.9`、`github.com/metacubex/utls v1.8.8`，其许可证随对应模块提供。所有传递依赖与校验和固定于`libcore/go.mod` / `libcore/go.sum`。vendored sing-box 通过本地 `replace` 引用；共同 uTLS 依赖同步升级到 v1.8.8，记录在 `sing-box/NEKO.md`。
