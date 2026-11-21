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
    REALITY_SERVER_MLKEM(
        listOf(ProfileField.CORE, ProfileField.REALITY_PUBLIC_KEY),
        mapOf(
            DialCore.SING_BOX to "REALITY 客户端的 ClientHello 只有 X25519 / P-256 / P-384，不提供 X25519MLKEM768（自报 1.8.1）：" +
                "Xray v26.9.8 以上的服务端一律拒绝，运营者没有开关（minClientVer 设 1.8.0 也拒），客户端报 " +
                "reality verification failed（目标证书不受信时是 x509 错误）；连更早的服务端照常",
            DialCore.XRAY to "用带 X25519MLKEM768 的指纹（chrome / firefox / safari 一类，空值即 chrome）新旧服务端都能连",
        ),
        "维护者 2026-10-06 定：只记差异并在测速失败时提示（withRealityHint、libcore/sing-box/NEKO.md），" +
            "不拒绝手动选 sing-box 的 REALITY 节点，因为对 v26.9.8 以前的服务端仍可用；自动选核本来就让 REALITY 优先走 Xray" +
            "（新旧服务端回环实测）",
        DifferenceDecision.DECIDED,
    ),
    REALITY_FINGERPRINT_MLKEM(
        listOf(ProfileField.UTLS_FINGERPRINT, ProfileField.REALITY_PUBLIC_KEY),
        mapOf(
            DialCore.XRAY to "不带 X25519MLKEM768 的指纹（ios、edge、qq、360、android、hellochrome_120、hellofirefox_120、" +
                "helloios_14、hello360_11_0 等）连 v26.9.8 以上的服务端必然失败，客户端报 received real certificate" +
                "（android、360 自报不支持 TLS 1.3）；random / randomized 看抽到的模板，时通时不通",
            DialCore.SING_BOX to "任何指纹都不带 X25519MLKEM768，同样连不上这类服务端（见 REALITY_SERVER_MLKEM）",
        ),
        "失败只取决于服务端版本与指纹，与客户端核心新旧无关：v26.3.27 客户端用 edge 连新服务端同样失败，新客户端用 edge " +
            "连旧服务端能通（指纹矩阵与新旧服务端回环实测）。维护者 2026-10-06 定：只记差异，不做冲突、不加编辑器提示",
        DifferenceDecision.DECIDED,
    ),
    UTLS_DEFAULT(
        listOf(ProfileField.UTLS_FINGERPRINT),
        mapOf(
            DialCore.XRAY to "没填指纹时用 uTLS chrome（GetFingerprint(\"\")）；v26.11.9 的 chrome / hellochrome_auto 为 Chrome 155，" +
                "显式 hellochrome_133 仍使用旧模板",
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
            "v26.3.27 与 v26.9.30 相同）",
        DifferenceDecision.DECIDED,
    ),
    VMESS_SECURITY_AUTO(
        listOf(ProfileField.ENCRYPTION),
        mapOf(
            DialCore.SING_BOX to "带 TLS 时生成器把 auto（或空）写成 aes-128-gcm（vendored sing-box 会把 auto 换成 zero）；" +
                "不带 TLS 时照写 auto",
            DialCore.XRAY to "auto 按平台选：有 AES 硬件加速时 AES-128-GCM，否则 ChaCha20-Poly1305",
        ),
        "维护者 2026-10-06 定：Xray 服务端自 v26.7.11 起删掉了 none / zero，sing-box 带 TLS 时换出的 " +
            "zero 连这类服务端静默失败（回环实测与 Xray VMess 源码确认），生成器改写 aes-128-gcm 后对新旧服务端都能通；" +
            "两边只在没有 AES 硬件加速的设备上选的加密不同，不影响连通",
        DifferenceDecision.DECIDED,
    ),
    VMESS_SECURITY_NONE_ZERO(
        listOf(ProfileField.ENCRYPTION),
        mapOf(
            DialCore.XRAY to "none / zero 被静默改成 AUTO（不报错、不警告），连新旧服务端都能通",
            DialCore.SING_BOX to "照写 none / zero，连 v26.7.11 以上的 Xray 服务端失败",
        ),
        "同一节点换核后一个能通一个不通，取决于服务端的 Xray 版本（分别实测 Xray 与 sing-box 客户端）。" +
            "维护者 2026-10-06 定：none / zero 是用户显式选的，生成器照写，不改写、不拦",
        DifferenceDecision.DECIDED,
    ),
    V2RAY_ID_MAPPING(
        listOf(ProfileField.UUID),
        mapOf(
            DialCore.XRAY to "id 按 common/uuid ParseString 解析：1–30 字节的非 UUID 串用 UUIDv5（命名空间全零）映射；" +
                "空串、31 字节以上的非 UUID 串、32–36 字节里的非十六进制字符报错；32–36 字节时逐组读十六进制、" +
                "每组前的连字符可有可无、读满 32 位后其余字符不看",
            DialCore.SING_BOX to "id 按 gofrs/uuid FromString 解析（固定的 sing-vmess 的 VMess 与 VLESS 客户端）：" +
                "只认 32 / 36 位与带花括号、urn:uuid: 前缀的写法，其余一律用同样的 UUIDv5 映射，从不报错",
        ),
        "Xray 拒绝的格式只允许 sing-box；两侧都能读却映射成不同身份时须手动选核。标准 UUID 与短别名照常自动选核。",
        DifferenceDecision.DECIDED,
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
    XRAY_MUX_CONCURRENCY(
        listOf(ProfileField.ENABLE_MUX, ProfileField.MUX_TYPE, ProfileField.MUX_CONCURRENCY),
        mapOf(
            DialCore.XRAY to "v26.11.9 的 Mux.Cool 把 concurrency 0–128 都提升为 128，显式 1–127 也不保留；负数仍关闭 TCP 复用。" +
                "xudpConcurrency 不抬高，TCP / XUDP 的单连接累计复用上限为 1024",
            DialCore.SING_BOX to "sing-mux 将 muxConcurrency 写成 max_streams，不做 Xray 的最低 128 调整；两者协议族不能互换",
        ),
        "连接复用策略差异，不改变安全校验与协议族。生成器保留配置原值，不为规避上游行为而自动换核或改写节点；" +
            "上游 #7375 的修复尚未进入此固定版本，后续升级须复核",
        DifferenceDecision.BY_PRINCIPLE,
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
            DialCore.XRAY to "追加到 Xray 内置的根证书包（v26.10.10 起；useSystemCa 可改用系统根证书，本应用不写该键，" +
                "此前追加到系统根证书），内置根证书仍然可信",
            DialCore.SING_BOX to "只信任这些证书（替换系统根证书）",
        ),
        "Xray 丢掉「只信任这些 CA」的限制、仍额外信任内置根证书（不含设备系统根证书库里额外装的 CA）；证书链与域名仍校验",
        DifferenceDecision.DECIDED,
    ),
    CUSTOM_OUTBOUND_JSON_EXTERNAL(
        listOf(ProfileField.CUSTOM_OUTBOUND_JSON),
        mapOf(
            DialCore.SING_BOX to "合并进协议出站本身",
            DialCore.XRAY to "合并进 sing-box 连外核的本机 socks 出站",
        ),
        "外核节点在 sing-box 里只有本机 socks 出站，这是现有设计",
        DifferenceDecision.DECIDED,
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
        "正 alterId 指定旧认证，Xray 忽略该字段。选核须保留所指定的认证方式。",
        DifferenceDecision.DECIDED,
    ),
    HTTP_HEADER_CAMOUFLAGE(
        listOf(ProfileField.TRANSPORT, ProfileField.HOST, ProfileField.PATH),
        mapOf(
            DialCore.SING_BOX to "http 传输（不带 TLS 时是 HTTP/1.1）：只发 Host 头，响应必须是 200",
            DialCore.XRAY to "tcp 的 http 头伪装：带一组默认请求头",
        ),
        "固定版本的 VMess 明文 HTTP 在默认和自定义 Host/path 下，两个方向与同核对照均收到目标回包。保留两种实现。",
        DifferenceDecision.DECIDED,
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
            DialCore.SING_BOX to "metacubex/utls v1.8.8；random 从当前常见指纹里选一个",
            DialCore.XRAY to "Xray 自带的 uTLS；random 启动时从 ModernFingerprints 里选一个",
        ),
        "同名指纹都是 uTLS 模板，具体版本与随机取样不同，不影响安全校验",
        DifferenceDecision.BY_PRINCIPLE,
    ),
    SING_BOX_UTLS_RANDOMIZED_SEED(
        listOf(ProfileField.UTLS_FINGERPRINT),
        mapOf(
            DialCore.SING_BOX to "randomized 按进程选种子，首包同时提供 X25519MLKEM768 组和密钥",
            DialCore.XRAY to "采用其自带的 uTLS 随机模板",
        ),
        "固定的 metacubex/utls v1.8.8 不能在 HRR 中补发混合组密钥。sing-box 补丁保证随机模板首次发送。",
        DifferenceDecision.DECIDED,
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
            DialCore.XRAY to "有 mux 块（开了 Mux.Cool 或 xudp）时生成器写 xudpProxyUDP443: allow，UDP/443 与其它 UDP 一样经 mux " +
                "转发；不写时 Xray 缺省 reject，且拒绝记录只在 info 级",
            DialCore.SING_BOX to "sing-mux 照常转发 UDP/443",
        ),
        "维护者 2026-10-06 定：生成器写 allow，与 sing-box 一致，要不要挡 QUIC 由路由规则决定" +
            "（回环实测 reject / 不写 / allow / skip，v26.3.27 与 v26.9.30 相同，实测 warning 级下没有拒绝记录）",
        DifferenceDecision.DECIDED,
    ),
}
