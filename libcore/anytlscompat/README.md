# AnyTLS 兼容测试出站 / AnyTLS compatibility test outbound

仅“新建节点 → AnyTLS（兼容测试）”启用。AnyTLSBean v3 保存 compatibilityTest；v0–v2、普通新建、
链接和订阅均默认 false。运行、测速和 trial 共用私有类型 `neko-anytls-compat-experiment`；
普通 AnyTLS 的核心选择保持原规则，mihomo 继续保留。编辑已有节点时，Intent 不能改变测试标记。

Only “New profile → AnyTLS (compatibility test)” enables this path. AnyTLSBean v3 persists the explicit marker;
older records, ordinary creation, links and subscriptions default to false. Runtime, URL testing and trial share
the private `neko-anytls-compat-experiment` type. Ordinary core selection and mihomo remain available.

## 行为 / Behavior

- 显式整证书 SHA-256 优先，否则 PEM 的第一张证书换算为固定值。叶子命中不再检查域名或有效期；
  非叶子命中仍验证证书链、域名与时间；错误固定不能被 insecure 绕过，与 mihomo v1.19.33 对照。
- 不带固定时使用应用 CertificateStore（缺省为系统根），不内置 mihomo CA 集合；不声称默认信任集合完全相同。
- uTLS 使用 MetaCubeX v1.8.8 的 mihomo 模板；保留现有四个被拒绝的名字，未知名字也拒绝。
  ECH 只开放标准 TLS、chrome、firefox、chrome120、firefox120，拒绝 random 等未经确定支持的组合。
- ECH 自动查询使用 sing-box DNSRouter 与节点的 domain_resolver（含分组 DNS）；没有进程级备用解析器。
  每个新会话重新查询，缓存归 DNSRouter；握手必须实际接受 ECH，失败关闭连接、不降级。
- TCP 和 UoT v2 共用受控 sing-box dialer；detour、网络接口保护和生命周期由既有框架负责。
  构造/trial 不启动会话或拨号；Scope 初始化时启动会话，关闭覆盖拨号、TLS、认证写入和复用连接。
- 测试节点不支持分享、配置导出或节点自定义 JSON。备份保留其标记。没有自动迁移或普通节点切换开关。

Explicit whole-certificate SHA-256 takes priority over the first PEM certificate. Leaf pins accept directly;
non-leaf pins still validate the chain, name and time. Insecure never bypasses a pin mismatch. Without a pin,
the app certificate store supplies trust; equivalence with mihomo’s bundled CA set is not claimed.
ECH requires acceptance and uses the controlled DNS router, including group DNS. Only the five verified TLS
profile choices above are exposed with ECH. Dialing and cancellation follow the existing sing-box lifecycle.
Test profiles cannot be shared, exported or overridden with profile JSON; backups preserve the marker.

## 验证与边界 / Verification and limits

`box_anytls_compat_test.go` 覆盖正式注册后的 trial、启动、detour、UrlTest、固定拒绝及分组 DNS，
实际 mihomo 由 MIHOMO_BIN 注入。此包还有双向互通、UoT、填充、复用、证书差分、ClientHello 结构、
ECH 和取消/并发关闭测试；入口见 [对照脚本](../../experiments/anytls-compat/README.md)。

The registered integration tests exercise trial, startup, detour, URL testing, pin rejection and group DNS.
Package tests additionally cover bidirectional interoperability, UoT, padding, session reuse, certificate policy,
ClientHello structure, ECH and cancellation. A real mihomo binary is required for the oracle cases.

客户端证书、ShadowTLS/Restls/JLS 包装和文件重载不在本次支持范围；trial 仅检查 ECH 容器边界，
密码学参数由握手完整验证。Android AAR 构建和模拟器验证不代表所有真机或供应商节点都已覆盖。

Client certificates, ShadowTLS/Restls/JLS wrappers and file reloads are outside this integration. Trial validates
ECH container boundaries; the handshake validates cryptographic parameters. Android builds and emulator checks
do not establish coverage of every device or provider. [来源与许可 / Licensing](NOTICE.md).
