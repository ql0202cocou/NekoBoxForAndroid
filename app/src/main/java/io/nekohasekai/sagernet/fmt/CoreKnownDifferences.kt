package io.nekohasekai.sagernet.fmt

// 已知差异：两个核心处理不同、但不当作冲突的字段。归类原则：丢失安全校验、传输不可用或被悄悄替换、协议族不匹配、
// 加载期必然报错的取值算冲突（进能力表）；不影响安全与连通的实现差异算已知差异（这里）。
// 清单本身由 CoreCapabilitiesTest 钉住，增删都要改测试

enum class DifferenceDecision {
    // 维护者已定
    DECIDED,

    // 按归类原则归入
    BY_PRINCIPLE,

    // 拿不准，暂按已知差异处理，等维护者定
    OPEN,
}

enum class KnownDifference(
    val fields: List<ProfileField>,
    // 各核心的行为（中文）
    val behaviors: Map<DialCore, String>,
    // 为什么不拦（中文）
    val rationale: String,
    val decision: DifferenceDecision,
) {
    REALITY_ALPN(
        listOf(ProfileField.ALPN),
        mapOf(
            DialCore.SING_BOX to "REALITY 下照样把 alpn 写进 ClientHello",
            DialCore.XRAY to "REALITY 分支不写 alpn，ALPN 由 uTLS 指纹模板决定",
        ),
        "REALITY 握手的外观由指纹模板决定，alpn 不影响安全校验与连通",
        DifferenceDecision.DECIDED,
    ),
    REALITY_ALLOW_INSECURE_CERTIFICATES(
        listOf(ProfileField.ALLOW_INSECURE, ProfileField.GLOBAL_ALLOW_INSECURE, ProfileField.CERTIFICATES),
        mapOf(
            DialCore.SING_BOX to "写进 tls 块但不起作用：REALITY 握手换成自己的校验器，非 REALITY 证书按系统根校验",
            DialCore.XRAY to "REALITY 分支不写 allowInsecure 与 certificates",
        ),
        "两个核心上都不起作用，结果一致；所以 REALITY 节点的 allowInsecure 不算 Xray 的冲突",
        DifferenceDecision.DECIDED,
    ),
    XRAY_REALITY_IGNORES_PIN_ECH(
        listOf(ProfileField.CERTIFICATE_FINGERPRINT, ProfileField.ENABLE_ECH, ProfileField.ECH_CONFIG),
        mapOf(
            DialCore.XRAY to "REALITY 分支不写证书指纹与 ECH（对 REALITY 不适用）",
            DialCore.SING_BOX to "仍是冲突：不能整证书固定；REALITY 与 ECH 同用加载即报错",
        ),
        "REALITY 不按证书链验证服务端，ECH 与 REALITY 互斥，Xray 忽略它们不丢安全校验",
        DifferenceDecision.DECIDED,
    ),
    UTLS_DEFAULT(
        listOf(ProfileField.UTLS_FINGERPRINT),
        mapOf(
            DialCore.XRAY to "没填指纹时用 uTLS chrome（GetFingerprint(\"\")）",
            DialCore.SING_BOX to "没填指纹时用 Go 标准 TLS（REALITY 除外：生成器补 chrome）",
        ),
        "不影响安全校验与连通；维护者 2026-10-06 定：只有迁移时换到 sing-box 的存量节点由升级标注规则 d 补写 firefox，" +
            "新建节点不特殊处理",
        DifferenceDecision.DECIDED,
    ),
    PACKET_ENCODING_NONE(
        listOf(ProfileField.PACKET_ENCODING),
        mapOf(
            DialCore.SING_BOX to "packet_encoding 写空串，UDP 用协议原生方式",
            DialCore.XRAY to "不写编码；VMess / VLESS 的 UDP（53、443 端口除外）默认走 XUDP（cone）",
        ),
        "UDP 封装不同但都能用，不涉及安全校验（Xray 一侧已在回环实测：不开 mux 时 5353 端口走 XUDP、53 端口走原生，" +
            "v26.3.27 与 v26.9.30 相同，K1b X2 L2-52…54）",
        DifferenceDecision.DECIDED,
    ),
    VMESS_SECURITY_AUTO(
        listOf(ProfileField.ENCRYPTION),
        mapOf(
            DialCore.SING_BOX to "带 TLS 时把 auto 换成 zero（libcore/sing-box/protocol/vmess/outbound.go）",
            DialCore.XRAY to "auto 按平台选 AES-128-GCM 或 ChaCha20-Poly1305",
        ),
        "Xray 服务端自 v26.7.11 起删掉了 none / zero：sing-box 带 TLS 时写的 zero 连这类服务端失败，服务端日志 " +
            "unknown security type，客户端只见连接立即结束、没有报错；连 v26.3.27 服务端两种都能通（K1b X2 L2-V07/V08 " +
            "实测，X1 S2-X22 源码）。是否改生成器或选核待维护者定（K1b 待决定项 A）",
        DifferenceDecision.OPEN,
    ),
    VMESS_SECURITY_NONE_ZERO(
        listOf(ProfileField.ENCRYPTION),
        mapOf(
            DialCore.XRAY to "none / zero 被静默改成 AUTO（不报错、不警告），连新旧服务端都能通",
            DialCore.SING_BOX to "照写 none / zero，连 v26.7.11 以上的 Xray 服务端失败",
        ),
        "同一节点换核后一个能通一个不通，取决于服务端的 Xray 版本（K1b X2 T2-28/30、L2-42/43 实测 Xray 客户端，" +
            "L2-V01/V03/V11 实测 sing-box 客户端），待维护者定",
        DifferenceDecision.OPEN,
    ),
    V2RAY_ID_MAPPING(
        listOf(ProfileField.UUID),
        mapOf(
            DialCore.XRAY to "id 按 common/uuid ParseString 解析：1–30 字节的非 UUID 串用 UUIDv5（命名空间全零）映射；" +
                "空串、31 字节以上的非 UUID 串、32–36 字节里的非十六进制字符报错；32–36 字节时逐组读十六进制、" +
                "每组前的连字符可有可无、读满 32 位后其余字符不看",
            DialCore.SING_BOX to "id 按 gofrs/uuid FromString 解析（sing-vmess v0.2.8 的 VMess 与 VLESS 客户端）：" +
                "只认 32 / 36 位与带花括号、urn:uuid: 前缀的写法，其余一律用同样的 UUIDv5 映射，从不报错",
        ),
        "1–30 字节的串两边映射结果相同；其余写法不同：Xray 报错的（空串、过长、带花括号或 urn 前缀）sing-box 照样能连，" +
            "Xray 能读出 UUID 的 33–35 字节写法或 36 字节的非标准写法 sing-box 映射成另一个 UUID，换核后静默连不上" +
            "（K1b 本地用两边的解析函数逐例核对；源码：X2 S2-X42，Xray common/uuid/uuid.go:67-83；sing-vmess v0.2.8 " +
            "client.go:37、vless/client.go:28）。待维护者定",
        DifferenceDecision.OPEN,
    ),
    MUX_COOL_PADDING(
        listOf(ProfileField.MUX_PADDING),
        mapOf(
            DialCore.XRAY to "Mux.Cool 没有 padding，不写",
            DialCore.SING_BOX to "sing-mux 的 padding（Mux.Cool 只能走 Xray，这里不会出现）",
        ),
        "Mux.Cool 下没有对应项；编辑器在选 Mux.Cool 时隐藏它",
        DifferenceDecision.DECIDED,
    ),
    VISION_MUX(
        listOf(ProfileField.ENABLE_MUX, ProfileField.ENCRYPTION),
        mapOf(
            DialCore.SING_BOX to "vision 流控时不写 multiplex（singMux 返回 null）",
            DialCore.XRAY to "vision 流控时不写 mux",
        ),
        "两个核心上都不生效，行为一致",
        DifferenceDecision.DECIDED,
    ),
    CERTIFICATES_TRUST(
        listOf(ProfileField.CERTIFICATES),
        mapOf(
            DialCore.XRAY to "追加到系统根证书（系统根证书仍然可信）",
            DialCore.SING_BOX to "只信任这些证书（替换系统根证书）",
        ),
        "Xray 丢掉「只信任这些 CA」的限制、仍额外信任系统根证书；证书链与域名仍校验",
        DifferenceDecision.DECIDED,
    ),
    CUSTOM_OUTBOUND_JSON_EXTERNAL(
        listOf(ProfileField.CUSTOM_OUTBOUND_JSON),
        mapOf(
            DialCore.SING_BOX to "合并进协议出站本身",
            DialCore.XRAY to "合并进 sing-box 连外核的本机 socks 出站",
            DialCore.MIHOMO to "合并进 sing-box 连外核的本机 socks 出站",
        ),
        "外核节点在 sing-box 里只有本机 socks 出站，这是现有设计",
        DifferenceDecision.DECIDED,
    ),
    ANYTLS_CERTIFICATES(
        listOf(ProfileField.CERTIFICATES, ProfileField.CERTIFICATE_FINGERPRINT, ProfileField.ALLOW_INSECURE),
        mapOf(
            DialCore.SING_BOX to "自定义 CA：只信任这些证书，校验域名与有效期；同时生效的 allowInsecure 压过它（不校验）",
            DialCore.MIHOMO to "取第一张证书的 SHA-256 作证书固定（命中叶子时不查域名与有效期；CA 证书只有服务端发出才命中）；" +
                "填了证书指纹时忽略 certificates；有固定时 allowInsecure 不生效",
        ),
        "两种都不是放弃校验；自动选核不替用户换，维护者 2026-10-06 定：留在 mihomo；手动选哪个都允许",
        DifferenceDecision.DECIDED,
    ),
    ECH_AUTO_QUERY_RESOLVER(
        listOf(ProfileField.ENABLE_ECH),
        mapOf(
            DialCore.SING_BOX to "经 sing-box 自己的 DNS 路由查 HTTPS 记录",
            DialCore.MIHOMO to "mihomo 进程自己发 DNS 查询：本应用不写 dns 段，发布版没有 cmfa 构建标签，读 " +
                "/etc/resolv.conf；Android 上没有这个文件（模拟器实测），退到内置的 114.114.114.114 / 8.8.8.8（UDP 53）",
        ),
        "两边都能自动查询；mihomo 一侧只有源码依据（K1b M1 S2-M3、S2-M12），查询的实际走向没有实测（plan.md K1 的 " +
            "ECH DNS 例外），待维护者定",
        DifferenceDecision.OPEN,
    ),
    WS_EARLY_DATA_SIZE(
        listOf(ProfileField.WS_MAX_EARLY_DATA),
        mapOf(
            DialCore.SING_BOX to "首包超过上限时截取前 N 字节作 early data，其余随后发送",
            DialCore.XRAY to "首包超过上限时整包都不作 early data",
        ),
        "只影响首包是否走 early data，不影响连通",
        DifferenceDecision.BY_PRINCIPLE,
    ),
    VMESS_ALTER_ID(
        listOf(ProfileField.ALTER_ID),
        mapOf(
            DialCore.SING_BOX to "alterId > 0 时用旧版（非 AEAD）认证",
            DialCore.XRAY to "没有 alterId，一律 AEAD",
        ),
        "多数服务端两种都接受，存量带证书指纹的 VMess 在 Xray 上一直这样跑；只在服务端强制旧版认证时不通，待维护者定",
        DifferenceDecision.OPEN,
    ),
    HTTP_HEADER_CAMOUFLAGE(
        listOf(ProfileField.TRANSPORT, ProfileField.HOST, ProfileField.PATH),
        mapOf(
            DialCore.SING_BOX to "http 传输（不带 TLS 时是 HTTP/1.1）：只发 Host 头，响应必须是 200",
            DialCore.XRAY to "tcp 的 http 头伪装：带一组默认请求头",
        ),
        "应用一直把 sing-box 的 http 传输当作 tcp 伪 HTTP 头使用；与 Xray 服务端的互通没有实测，待维护者定",
        DifferenceDecision.OPEN,
    ),
    XUDP_UNDER_VISION(
        listOf(ProfileField.PACKET_ENCODING, ProfileField.ENCRYPTION),
        mapOf(
            DialCore.SING_BOX to "写 packet_encoding: xudp",
            DialCore.XRAY to "vision 时不写 mux 与 xudp；Xray 的 vision 本身把 UDP 改走 XUDP",
        ),
        "效果相同（Xray 一侧据源码推断）",
        DifferenceDecision.BY_PRINCIPLE,
    ),
    UTLS_IMPLEMENTATION(
        listOf(ProfileField.UTLS_FINGERPRINT),
        mapOf(
            DialCore.SING_BOX to "metacubex/utls v1.8.7；random 从当前常见指纹里选一个",
            DialCore.XRAY to "Xray 自带的 uTLS；random 启动时从 ModernFingerprints 里选一个",
            DialCore.MIHOMO to "metacubex/utls；random 按 chrome / safari / ios / firefox 加权选",
        ),
        "同名指纹都是 uTLS 模板，具体版本与随机取样不同，不影响安全校验",
        DifferenceDecision.BY_PRINCIPLE,
    ),
    CHAIN_MUX(
        listOf(ProfileField.ENABLE_MUX),
        mapOf(
            DialCore.SING_BOX to "一条链里只有第一个开了 mux 的 sing-box 跳带 multiplex",
            DialCore.XRAY to "每个 Xray 跳各自生效",
        ),
        "链级行为，不是单个节点的能力",
        DifferenceDecision.BY_PRINCIPLE,
    ),
    XRAY_MUX_UDP443(
        listOf(ProfileField.ENABLE_MUX, ProfileField.PACKET_ENCODING),
        mapOf(
            DialCore.XRAY to "只开 mux、没开 xudp 时生成器不写 xudpProxyUDP443，缺省 reject，UDP/443 被 Xray 拒绝；" +
                "拒绝记录只在 Xray 的 info 级，应用缺省（warning）下看不到",
            DialCore.SING_BOX to "sing-mux 照常转发 UDP/443",
        ),
        "生成器缺口（I1 §5.1；K1b X2 L2-48…51 回环实测，v26.3.27 与 v26.9.30 相同，L2-S08 实测 warning 级下没有记录）：" +
            "QUIC 被拒后应用一般回落 TCP，不丢安全校验；应在生成器里补，待维护者定",
        DifferenceDecision.OPEN,
    ),
}
