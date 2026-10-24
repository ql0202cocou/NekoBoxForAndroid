package io.nekohasekai.sagernet.fmt

// 能力表用到的取值名单（从 CoreCapabilities.kt 拆出的纯数据，行为不变）。升级核心时的复核清单仍在 CoreCapabilities.kt 顶部。

// ---- 取值名单

// sing-box 1.14.4 的 uTLS 指纹（utls_client.go 自 1.14.2 未变），区分大小写，名单外在加载配置时报 unknown uTLS fingerprint
// （libcore/sing-box/common/tls/utls_client.go 的 uTLSClientHelloID；空串在那里等于 chrome，但生成器不写空值）。
// randomized 虽在名单里，按源码在部分进程上同样握手失败（未实测，已知差异 SING_BOX_UTLS_RANDOMIZED_SEED）
val SING_BOX_UTLS_FINGERPRINTS = CoreNames(
    setOf(
        "chrome", "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle", "chrome_pq", "chrome_pq_psk",
        "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized",
    ),
    ignoreCase = false,
)

// Xray v26.9.30 的 fingerprint：infra/conf/transport_internet.go 先转小写，再查 transport/internet/tls/tls.go 的
// PresetFingerprints / ModernFingerprints / OtherFingerprints，名单外 run -test 报 unknown "fingerprint"。
// unsafe 表示 Go 标准 TLS；REALITY 另外拒绝 unsafe 与 hellogolang（见组合规则）。
// v26.9.30 比 v26.3.27 多了 hellochrome_133、hellofirefox_148、hellosafari_26_3（X2 T2-94/95/76 实测，旧版拒绝），
// 没有删除；Modern / Other 两组按 v26.6.1（455f6bc2）的新表分组（X1 S2-X8）
val XRAY_UTLS_FINGERPRINTS = CoreNames(
    setOf(
        // PresetFingerprints（预设名单）
        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq",
        "random", "randomized", "randomizednoalpn", "unsafe",
        // ModernFingerprints（random 在启动时从这一组里抽一个）
        "hellofirefox_120", "hellofirefox_148", "hellochrome_120", "hellochrome_131", "hellochrome_133",
        "helloios_13", "helloios_14", "helloedge_106", "hellosafari_26_3", "hello360_11_0", "helloqq_11_1",
        // OtherFingerprints：Go 标准、随机、auto 与过旧的模板
        "hellogolang", "hellorandomized", "hellorandomizedalpn", "hellorandomizednoalpn",
        "hellofirefox_auto", "hellofirefox_55", "hellofirefox_56", "hellofirefox_63", "hellofirefox_65",
        "hellofirefox_99", "hellofirefox_102", "hellofirefox_105",
        "hellochrome_auto", "hellochrome_58", "hellochrome_62", "hellochrome_70", "hellochrome_72",
        "hellochrome_83", "hellochrome_87", "hellochrome_96", "hellochrome_100", "hellochrome_102",
        "hellochrome_106_shuffle",
        "helloios_auto", "helloios_11_1", "helloios_12_1", "helloandroid_11_okhttp",
        "helloedge_85", "helloedge_auto", "hellosafari_16_0", "hellosafari_auto",
        "hello360_auto", "hello360_7_5", "helloqq_auto",
        // OtherFingerprints 里的 Chrome 测试版
        "hellochrome_100_psk", "hellochrome_112_psk_shuf", "hellochrome_114_padding_psk_shuf",
        "hellochrome_115_pq", "hellochrome_115_pq_psk", "hellochrome_120_pq",
    ),
    ignoreCase = true,
)

// mihomo v1.19.32 的 client-fingerprint（component/tls/utls.go 的 GetFingerprint 与 fingerprints，v1.19.33 未变），区分大小写。
// none 表示 Go 标准 TLS；名单外 -t 不报错，运行时只打 warning 并改用 Go 标准 TLS。
// mihomo 认识的名字共 19 个，这里只收能完成握手的 15 个，其余 4 个见 MIHOMO_UTLS_FINGERPRINTS_BROKEN
val MIHOMO_UTLS_FINGERPRINTS = CoreNames(
    setOf(
        "none", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random",
        "chrome120", "firefox120", "safari16",
        "chrome_psk_shuffle", "chrome_pq",
    ),
    ignoreCase = false,
)

// mihomo 认识、-t 也不报错，但握手跑不通的名字（v1.19.31、v1.19.32 与 v1.19.33 相同，K1b M1 L2-MF-* 与 N12 回环实测）。维护者 2026-10-06
// 定（K1b 待决定项 D）：从名单剔除，作为冲突 MIHOMO_UTLS_HANDSHAKE_FAILS。
// - chrome_psk、chrome_pq_psk、chrome_padding_psk_shuffle：每次拨号都报 tls: empty psk detected。uTLS 在没有可恢复的
//   会话时对带 PSK 扩展的模板直接报这个错，mihomo 没设 OmitEmptyPsk（M1 S2-U2）；
// - randomized：同一进程里要么每次都失败（tls: CurvePreferences includes unsupported curve），要么每次都通，取决于进程
//   启动时的随机种子，6 个进程里 4 个失败（M1 S2-U3，原因是推断）。
// sing-box 认这 4 个名字（psk 三个在 sing-box 里等同 chrome），不受影响
val MIHOMO_UTLS_FINGERPRINTS_BROKEN = CoreNames(
    setOf("chrome_psk", "chrome_pq_psk", "chrome_padding_psk_shuffle", "randomized"),
    ignoreCase = false,
)

// Xray REALITY 拒绝的 fingerprint（infra/conf/transport_internet.go，REALITY 分支）
val XRAY_REALITY_FORBIDDEN_FINGERPRINTS = CoreNames(setOf("unsafe", "hellogolang"), ignoreCase = true)

// VLESS flow：sing-vmess v0.2.8 vless/client.go 的 NewClient；Xray v26.9.30 infra/conf/vless.go 的出站 users 校验
val SING_BOX_VLESS_FLOWS = CoreNames(setOf("xtls-rprx-vision"), ignoreCase = false)
val XRAY_VLESS_FLOWS = CoreNames(setOf("xtls-rprx-vision", "xtls-rprx-vision-udp443"), ignoreCase = false)

// Xray 的 ws 客户端只用这个头携带 early data（transport/internet/websocket/dialer.go）；HTTP 头名不区分大小写
val XRAY_WS_EARLY_DATA_HEADERS = CoreNames(setOf(WS_EARLY_DATA_PROTOCOL_HEADER), ignoreCase = true)
