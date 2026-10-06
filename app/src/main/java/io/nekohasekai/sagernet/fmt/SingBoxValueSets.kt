package io.nekohasekai.sagernet.fmt

// BUG-001 短期部分：sing-box 只在创建出站（box.New）时才校验的几项枚举类取值。导入链接、订阅会原样保留这些值，
// 一个不认识的值让整份配置起不来，连带同组没选中的节点。这里在构建内部核心出站时（planHop → buildInternalOutbound
// → 各协议的 sing-box 构建函数）先按名单检查，取值不在名单里就抛 UnsupportedSingBoxValueException：选择器成员与
// 路由规则目标由 planOrSkip 跳过并记 ProfileSkipped，选中的节点整次构建失败。外核跳不经这里（各核心有自己的名单）。
//
// 每个名单逐项抄自 vendored sing-box（SING_BOX_VALUE_SETS_VERSION）与它依赖的上游模块源码，模块版本取自
// libcore/sing-box/go.mod（SING_BOX_VALUE_SET_MODULES）；CoreCapabilitiesTest 把两者与仓库里的版本绑定，升级
// sing-box 或这些模块时测试先红，要回来逐项复核。名单都区分大小写（上游按字符串原样比较）。
// 检查的是 bean 里的值：自定义出站 JSON（customOutboundJson）到序列化时才合并，它覆盖同名键后的值不算。这类节点
// （bean 的值不在名单里、自定义 JSON 改成了合法值）此前能跑，现在会被拒或跳过。
// 这些取值本身不是凭据，但畸形链接可能把别的字段错放进来（v2rayN 形式的 ss:// 漏了 method 时，密码会落进
// method），所以报错只回显形如 ^[A-Za-z0-9._+-]{1,64}$ 的取值，其余写 (value not shown)，不暴露长度。

// 名单核实时 vendored sing-box 的上游版本（libcore/sing-box/constant/version.go 去掉 -neko-N）
const val SING_BOX_VALUE_SETS_VERSION = "1.14.2"

// 名单依赖的上游模块与核实时的版本（libcore/sing-box/go.mod 的 require）
val SING_BOX_VALUE_SET_MODULES = mapOf(
    "github.com/sagernet/sing-shadowsocks2" to "v0.2.1",
    "github.com/sagernet/sing-vmess" to "v0.2.8",
    "github.com/sagernet/sing-quic" to "v0.7.1-0.20260924092235-6a3a24d65b99",
)

// 取值不在 sing-box 的名单里。field 是报错里的字段说明，value 是原值（只给代码判断用，不进消息）；
// 消息里的取值按 shownSingBoxValue 决定是否回显
class UnsupportedSingBoxValueException(val field: String, val value: String?) :
    IllegalArgumentException("sing-box does not support $field ${shownSingBoxValue(value)}")

// 可以回显的取值形态：枚举名常见的字符，最长 64
private val SHOWN_SINGBOX_VALUE = Regex("^[A-Za-z0-9._+-]{1,64}$")

// 报错里的取值：形态合格的加引号原样写出，否则（含 null 与空串）写 (value not shown)
internal fun shownSingBoxValue(value: String?): String =
    if (value != null && SHOWN_SINGBOX_VALUE.matches(value)) "\"$value\"" else "(value not shown)"

// Shadowsocks 加密方式。sing-shadowsocks2 v0.2.1：shadowaead/method.go（AEAD）、shadowaead_2022/method.go（2022）、
// shadowstream/method.go（流密码）、cipher/method_none.go（none），经 shadowsocks.go 的空白导入注册；名单外
// cipher/method_registry.go 的 CreateMethod 报 unknown method（vendored protocol/shadowsocks/outbound.go 调用）
val SING_BOX_SHADOWSOCKS_METHODS: Set<String> = setOf(
    // AEAD
    "aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
    // 2022
    "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
    // 流密码
    "aes-128-ctr", "aes-192-ctr", "aes-256-ctr", "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
    "rc4-md5", "chacha20-ietf", "xchacha20",
    "none",
)

// Shadowsocks 的 SIP003 插件名。vendored transport/sip003/obfs.go、v2ray.go 注册，名单外 plugin.go 的 CreatePlugin
// 报 plugin not found。不写插件（空串）不经过 CreatePlugin；构建函数把插件名为空（插件串为空，或以「;」开头只有
// 参数，如 ss:// 链接原样保留的 ";obfs=http"）与 none 都当作不写，不查名单
val SING_BOX_SHADOWSOCKS_PLUGINS: Set<String> = setOf("obfs-local", "v2ray-plugin")

// VMess 加密方式（出站的 security）。sing-vmess v0.2.8 client.go 的 NewClient，名单外报 unsupported security type；
// vendored protocol/vmess/outbound.go 先把空串当 auto（构建函数也把空值写成 auto）
val SING_BOX_VMESS_SECURITIES: Set<String> = setOf("auto", "none", "zero", "aes-128-cfb", "aes-128-gcm", "chacha20-poly1305")

// TUIC 拥塞控制。sing-quic v0.7.1-0.20260924092235-6a3a24d65b99 tuic/client.go 的 NewClient：空串按 cubic，名单外报
// unknown congestion control algorithm
val SING_BOX_TUIC_CONGESTION_CONTROLS: Set<String> = setOf("", "cubic", "new_reno", "bbr")

// 取值在名单里时原样返回，否则（含 null）抛 UnsupportedSingBoxValueException
fun requireSingBoxValue(field: String, value: String?, accepted: Set<String>): String {
    if (value == null || value !in accepted) throw UnsupportedSingBoxValueException(field, value)
    return value
}
