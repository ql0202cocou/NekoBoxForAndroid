# AnyTLS 内部兼容实现 / Internal AnyTLS implementation

普通 AnyTLS 节点在 sing-box 上默认使用本实现，运行、测速和 trial 共用 `neko-anytls`。旧原生节点带自定义出站 JSON 时继续使用 `anytls`。
自动选核优先 sing-box，并按完整能力表匹配；手动选核只使用指定核心，有冲突就明确拒绝。
升级到数据库 v11 时，一次性把全部 AnyTLS 的 core 重置为自动，包括旧手动核心与兼容测试节点。
迁移先依据旧核心保存独立证书策略，再重置核心；节点材料、流量、链式引用与其他协议不变。
此后编辑、订阅更新和恢复备份保留证书策略；恢复旧备份按原核心解析策略，不再次重置手动选择。
AnyTLSBean v4 新增 certificatePolicy；旧 v3 的 compatibilityTest 仅用于识别历史测试节点的固定策略，不再提供测试入口。
旧私有类型 `neko-anytls-compat-experiment` 仍可读取；新生成的配置只使用正式名称。

Sing-box uses this implementation by default. Existing profiles with native outbound JSON overrides use the native
`anytls` outbound. For profiles using the extension, runtime, URL testing and trial share
`neko-anytls`. Automatic selection prefers sing-box and checks complete capabilities; manual selection never silently
falls back. Database v11 resets all existing AnyTLS core choices to automatic once, including former test profiles.
Before resetting cores, migration stores the original certificate policy independently. Parameters, traffic and references remain intact.
Edits, subscription updates and backups preserve the policy. Old backups derive it from the original core while retaining manual choices.
AnyTLSBean v4 adds certificatePolicy; the historical v3 test marker only identifies former test profiles' pinning policy during migration.
The old private type remains readable as an alias.

## 行为 / Behavior

- 新节点默认固定策略：显式整证书 SHA-256 优先，否则 PEM 的第一张证书换算为固定值。叶子命中不再检查域名或有效期；
  非叶子命中仍验证证书链、域名与时间；错误固定不能被 insecure 绕过，与 mihomo v1.19.33 对照。
- 旧原生 sing-box 节点保留信任策略：PEM 全部加入独立根池并替换默认根池，继续校验证书链、域名与有效期；
  服务端无需发送根证书。insecure 的原有含义不变；显式证书指纹仍优先。旧 mihomo 与测试节点保留固定策略。
- 未指定信任证书或固定时使用应用 CertificateStore（缺省为系统根），不内置 mihomo CA 集合；不声称默认信任集合完全相同。
- mihomo 无法表达带 PEM 的信任策略，能力匹配和生成器均明确拒绝；自动选核也不能以固定策略代替信任。
- uTLS 使用 MetaCubeX v1.8.8 的 mihomo 模板；保留现有四个被拒绝的名字，未知名字也拒绝。
  ECH 只开放标准 TLS、chrome、firefox、chrome120、firefox120，拒绝 random 等未经确定支持的组合。
- ECH 自动查询使用 sing-box DNSRouter 与节点的 domain_resolver（含分组 DNS）；没有进程级备用解析器。
  每个新会话重新查询，缓存归 DNSRouter；握手必须实际接受 ECH，失败关闭连接、不降级。
- TCP 和 UoT v2 共用受控 sing-box dialer；detour、网络接口保护和生命周期由既有框架负责。
  构造/trial 不启动会话或拨号；Scope 初始化时启动会话，关闭覆盖拨号、TLS、认证写入和复用连接。
- 固定策略支持普通链接，信任策略通过 `sn://` 保留；备份保存独立策略。节点自定义 JSON 与其他内部出站一样参与 trial，未知字段或无效配置会拒绝。
- 带原生 JSON 覆写的旧信任策略节点沿用 sing-box 原生的会话、TLS（含 uTLS 与 ECH）和导出行为。普通新节点没有固定材料、兼容指纹或 ECH 时也可用原生覆写。显式整证书固定、固定策略的 PEM、专用指纹或私有 TLS 字段仍由兼容实现处理。
- 使用私有出站的配置不能作为通用 sing-box 配置导出；固定策略可改选 mihomo 再导出。
  该限制会拒绝整个导出，包括 AnyTLS 出现在规则目标或其链中时；不会靠删除规则生成一份成功结果。
- 迁移不放宽指纹或证书校验；旧节点使用不支持的模板时明确拒绝，不能靠迁移删除安全字段。

New profiles default to pinning. Explicit whole-certificate SHA-256 takes priority over the first PEM certificate.
Leaf pins accept directly; non-leaf pins still validate the chain, name and time. Insecure never bypasses a pin mismatch.
Former native sing-box profiles retain trust policy: all PEM certificates replace the default root pool, preserving chain,
hostname and validity checks without requiring the server to send its root. Existing insecure behavior is preserved;
explicit fingerprints still take priority. Former mihomo and test profiles retain pinning. Mihomo cannot express custom
trust certificates and is rejected for that policy. Without custom roots or a pin, the app certificate store supplies trust;
equivalence with mihomo’s bundled CA set is not claimed.
ECH requires acceptance and uses the controlled DNS router, including group DNS. Only the five verified TLS
profile choices above are exposed with ECH. Dialing and cancellation follow the existing sing-box lifecycle.
Trust-policy links use `sn://` to preserve the policy; pinning profiles support standard links. Backups preserve both policies.
Profile JSON overrides are trial-validated. Migrated native overrides retain sing-box's session, TLS (including uTLS
and ECH), and export behavior. New profiles can also use native overrides when they have no pinning material,
uTLS or ECH. Whole-certificate pins and extension fields continue to use the compatibility implementation.
Native configurations can be exported. The private extension rejects portable export. Migration does not
remove security fields or relax fingerprint validation.
An unsupported export fails as a whole, including when AnyTLS appears in a rule target or its chain; rules are never dropped to bypass this restriction.

## 验证与边界 / Verification and limits

`box_anytls_compat_test.go` 覆盖正式注册后的 trial、启动、detour、UrlTest、固定拒绝及分组 DNS，
实际 mihomo 由 MIHOMO_BIN 注入。此包还有双向互通、UoT、填充、复用、证书差分、ClientHello 结构、
ECH 和取消/并发关闭测试；入口见 [对照脚本](../../experiments/anytls-compat/README.md)。
`boxadapter/certificate_policy_test.go` 还以旧 sing-box TLS 为对照，验证域名不匹配、过期叶子、未发送根证书、
多个自定义根和 insecure；覆盖标准 TLS、Chrome 与 Firefox uTLS。

The registered integration tests exercise trial, startup, detour, URL testing, pin rejection and group DNS.
Package tests additionally cover bidirectional interoperability, UoT, padding, session reuse, certificate policy,
ClientHello structure, ECH and cancellation. A real mihomo binary is required for the oracle cases.
Trust-policy tests compare against native sing-box TLS for hostname mismatch, expired leaves, roots omitted by the server,
multiple custom roots and insecure mode, using standard TLS and Chrome/Firefox uTLS.

客户端证书、ShadowTLS/Restls/JLS 包装和文件重载不在本次支持范围；trial 仅检查 ECH 容器边界，
密码学参数由握手完整验证。Android AAR 构建和模拟器验证不代表所有真机或供应商节点都已覆盖。

Client certificates, ShadowTLS/Restls/JLS wrappers and file reloads are outside this integration. Trial validates
ECH container boundaries; the handshake validates cryptographic parameters. Android builds and emulator checks
do not establish coverage of every device or provider. [来源与许可 / Licensing](NOTICE.md).
