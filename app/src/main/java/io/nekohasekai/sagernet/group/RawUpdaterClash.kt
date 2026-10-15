@file:Suppress("UNCHECKED_CAST")

package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.CLASH_KEY_NOT_SHOWN
import io.nekohasekai.sagernet.fmt.ClashFieldReason
import io.nekohasekai.sagernet.fmt.ClashFieldRecord
import io.nekohasekai.sagernet.fmt.ClashFieldResult
import io.nekohasekai.sagernet.fmt.ClashNodeFailure
import io.nekohasekai.sagernet.fmt.ClashNodeResult
import io.nekohasekai.sagernet.fmt.EndpointProblem
import io.nekohasekai.sagernet.fmt.clashShownPath
import io.nekohasekai.sagernet.fmt.clashShownValue
import io.nekohasekai.sagernet.fmt.clashShownValueAt
import io.nekohasekai.sagernet.fmt.endpointProblem
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.tlsFields
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.UnsupportedTransportException
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.clashNetworkTransport
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.v2ray.muxProtocolType
import io.nekohasekai.sagernet.fmt.v2ray.setTLS
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.isWireGuardLocalAddressList
import io.nekohasekai.sagernet.ktx.blankAsNull
import io.nekohasekai.sagernet.ktx.isIpAddress
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.isCertificateFingerprint
import org.yaml.snakeyaml.error.YAMLException

// clash 订阅的 YAML 根节点。SafeConstructor：远端订阅绝不能实例化任意类
// （CVE-2022-1471）；它不支持 loadAs(Map)，但 load() 得到的是同样的
// LinkedHashMap 结构。合法 YAML 但根节点不是 map 时同样抛 YAMLException
// （直接强转会抛 ClassCastException，漏过 parseRaw 的 YAMLException 捕获），
// 让它像其他非 clash 内容一样回退到 base64 / 分享链接解析。成环或别名展开过大的结构同样按无效 YAML
// 处理（checkClashYamlGraph）：之后 parseClash 与 dns 段的读取都能放心地对值 toString
fun loadClashYaml(text: String): Map<*, *> {
    val root = clashYaml().load<Any?>(text) as? Map<*, *> ?: throw YAMLException("Root node is not a map")
    checkClashYamlGraph(root, text.length)
    return root
}

// 整份订阅没有 proxies 列表（或它不是列表）。parseRaw 换成本地化的「文件里没有节点」
class ClashNoProxiesException : IllegalStateException("No proxies list in the Clash YAML")

// parseClash 的结果：导入的节点（已补默认值并做过 Clash 修正），以及 proxies 里每个条目的结果，
// 顺序与条目一致。全部条目都被跳过时 beans 为空，由调用方决定怎么报
class ClashParseResult(val beans: List<AbstractBean>, val nodes: List<ClashNodeResult>)

// 节点被整体拒绝，原因来自封闭集合。路径按段给出，构造时每段都按键名规则处理（不能显示的段写成占位串），
// 不带键的原文；value 是原值，只在路径属于可显示的枚举键时按规则显示
internal class ClashNodeRejected(
    val failure: ClashNodeFailure,
    segments: List<Any?>? = null,
    value: Any? = null,
    // 节点的 type，只用于判断按 type 才能显示的值（CLASH_ENUM_PATHS_BY_TYPE）
    type: String? = null,
) : Exception(failure.text) {
    // 只有一段的路径（固定的键名）
    constructor(failure: ClashNodeFailure, key: String, value: Any? = null) : this(failure, listOf(key), value)

    val path: String? = segments?.let { clashShownPath(it) }
    val shownValue: String? =
        segments?.let { clashShownValueAt(type, it.joinToString("."), value) }
}

// 读一个键时的意外异常（类型不对、数字格式错）换成封闭集合里的原因并指出键，节点照旧被跳过。
// parent 是嵌套对象的键名（如 smux）；键本身按段交给 ClashNodeRejected，不是字符串的键（YAML 复杂键）
// 只显示占位串
private inline fun <T> clashKey(key: Any?, value: Any?, parent: String? = null, read: () -> T): T = try {
    read()
} catch (e: ClashNodeRejected) {
    throw e
} catch (e: UnsupportedTransportException) {
    throw e
} catch (e: Exception) {
    throw when (if (parent == null) (key as? String)?.replace('_', '-') else null) {
        "server" -> if (value == null) ClashNodeRejected(ClashNodeFailure.MISSING_SERVER)
        else ClashNodeRejected(ClashNodeFailure.INVALID_VALUE, "server")

        "port" -> ClashNodeRejected(if (value == null) ClashNodeFailure.MISSING_PORT else ClashNodeFailure.INVALID_PORT)
        else -> ClashNodeRejected(ClashNodeFailure.INVALID_VALUE, listOfNotNull(parent) + listOf(key), value)
    }
}

// 纯函数：不读 DataStore、不写日志、不取界面文案（JVM 单测直接覆盖）。
// 单个坏节点只跳过它本身，不拖垮整次更新
fun parseClash(yaml: Map<*, *>): ClashParseResult {
    // mihomo 已移除顶层的 global-client-fingerprint（config/config.go parseGeneral 只打一条错误日志），不再读取；
    // 它原来补的是 REALITY 节点没写的 client-fingerprint，这样的节点在字段记录里报告
    val globalFingerprintIgnored = !yaml["global-client-fingerprint"]?.toString().isNullOrEmpty()

    // 用 List<*> 而不带 Map 类型参数：泛型 List<Map> 的强转会被擦除，编译器在判断之后才逐个元素
    // checkcast，一个「- ss://...」字符串条目就会抛 ClassCastException 拖垮整次更新
    val entries = yaml["proxies"] as? List<*> ?: throw ClashNoProxiesException()
    val beans = mutableListOf<AbstractBean>()
    val nodes = ArrayList<ClashNodeResult>(entries.size)
    entries.forEachIndexed { index, entry -> nodes += parseClashEntry(index, entry, beans, globalFingerprintIgnored) }

    // Fix ent
    beans.forEach {
        it.initializeDefaultValues()
        if (it is StandardV2RayBean) it.applyClashFixups()
    }
    return ClashParseResult(beans, nodes)
}

private fun parseClashEntry(
    index: Int, entry: Any?, beans: MutableList<AbstractBean>, globalFingerprintIgnored: Boolean,
): ClashNodeResult {
    val proxy = entry as? Map<String, Any?>
        ?: return ClashNodeResult.Failed(index, CLASH_KEY_NOT_SHOWN, null, ClashNodeFailure.ENTRY_NOT_MAP)
    val name = proxy["name"]?.toString()
    val type = proxy["type"] as? String
        ?: return ClashNodeResult.Failed(
            index, clashShownValue(proxy["type"]), name, ClashNodeFailure.MISSING_TYPE
        )
    val shownType = clashShownValue(type)
    return try {
        val bean = parseClashProxy(type, proxy)
            ?: return ClashNodeResult.UnknownType(index, shownType, name)
        // 端点在 bean 上统一校验；必须早于 parseClash 尾部的 initializeDefaultValues，
        // 否则缺省被填成 127.0.0.1:1080 就验不出缺失
        bean.endpointProblem()?.let { throw ClashNodeRejected(it.clashFailure()) }
        clashRejection(type, proxy, bean)?.let { throw it }
        // 字段记录只读输入与解析结果，不改 bean，也不会因记录本身出错让节点被跳过
        var fields = classifyClashFields(type, proxy, bean)
        // 没写 client-fingerprint 的 REALITY 节点：顶层的 global-client-fingerprint 不再补给它（用默认的 chrome）
        if (globalFingerprintIgnored && bean is StandardV2RayBean && !bean.realityPubKey.isNullOrBlank() &&
            bean.utlsFingerprint.isNullOrBlank()
        ) {
            fields = fields + ClashFieldRecord("global-client-fingerprint", ClashFieldResult.IGNORED, ClashFieldReason.REMOVED_IN_MIHOMO)
        }
        beans.add(bean)
        ClashNodeResult.Imported(index, shownType, name, fields)
    } catch (e: ClashNodeRejected) {
        ClashNodeResult.Failed(index, shownType, name, e.failure, e.path, e.shownValue)
    } catch (e: UnsupportedTransportException) {
        ClashNodeResult.Failed(
            index, shownType, name, ClashNodeFailure.UNSUPPORTED_TRANSPORT, "network", clashShownValue(e.transport)
        )
    } catch (e: Exception) {
        ClashNodeResult.Failed(index, shownType, name, ClashNodeFailure.OTHER, detail = e.javaClass.simpleName)
    }
}

private fun EndpointProblem.clashFailure() = when (this) {
    EndpointProblem.MISSING_SERVER -> ClashNodeFailure.MISSING_SERVER
    EndpointProblem.MISSING_PORT -> ClashNodeFailure.MISSING_PORT
    EndpointProblem.INVALID_PORT -> ClashNodeFailure.INVALID_PORT
}

// 安全校验不能悄悄丢；导入后永远连不上的节点要大声丢弃（与 TUIC v4、不支持的 SS 插件同一原则）。
// 下面几类在本应用里没有对应实现或解析器读不到，照常导入只会少一道校验或永远连不上，整个节点拒绝。
// 涉及安全的键按 mihomo 的读法找（mihomoEntry）：mihomo 认、解析器不认的写法同样是丢了。路径用节点里的原始键名
private fun clashRejection(type: String, proxy: Map<String, Any?>, bean: AbstractBean): ClashNodeRejected? {
    fun rejected(failure: ClashNodeFailure, vararg path: Any?) = ClashNodeRejected(failure, path.toList())
    // mihomo 读法下打开 TLS 的那个键（键名大小写不同、写成整数 1 都算）；没打开时为 null
    val tls = proxy.mihomoEntry("tls")?.takeIf { it.value.mihomoTrue() }
    // socks5：本应用的 SOCKS 出站没有 TLS，导入成明文永远连不上
    if (bean is SOCKSBean) return tls?.let { rejected(ClashNodeFailure.TLS_NOT_SUPPORTED, it.key) }
    // vmess / vless / http：mihomo 打开了 TLS，导入的节点却是明文
    if (bean is StandardV2RayBean && bean !is TrojanBean && tls != null && !bean.isTLS()) {
        return rejected(ClashNodeFailure.SECURITY_SETTING_NOT_READ, tls.key)
    }

    // 证书固定（TLS 打开时；vmess / vless / http 的 TLS 关着时它不起作用）：值不是 SHA-256 摘要（解析器会丢掉），
    // 或是摘要却没被解析器读到（键名写法不同）
    val pin = tlsFields(bean)?.let { it.certificateFingerprint.orEmpty() }
    if (pin != null) proxy.mihomoEntry("fingerprint")?.let { fingerprint ->
        val text = fingerprint.value?.toString().orEmpty()
        if (text.isNotEmpty() && !isCertificateFingerprint(text)) {
            return rejected(ClashNodeFailure.CERT_FINGERPRINT_INVALID, fingerprint.key)
        }
        if (text.isNotEmpty() && text != pin) return rejected(ClashNodeFailure.SECURITY_SETTING_NOT_READ, fingerprint.key)
    }

    if (bean is StandardV2RayBean && bean !is HttpBean) {
        // 带 REALITY 公钥，TLS 却被写在后面的 tls: false 关掉：导入成明文节点（mihomo 对这种配置报 REALITY requires TLS）。
        // tls: false 写在 reality-opts 前面时 REALITY 照常打开 TLS，不在此列
        if (!bean.realityPubKey.isNullOrBlank() && !bean.isTLS()) return rejected(ClashNodeFailure.REALITY_WITHOUT_TLS, "tls")
        // reality-opts：mihomo 只在 public-key 非空时启用 REALITY（RealityOptions.Parse），public-key 显式写成空串的
        // 两边都是普通 TLS，照常导入。只有 short-id、缺 public-key 键的，mihomo 因 has unset fields 整个节点解码失败
        // （PublicKey 的 tag 没有 omitempty），这里仍照常导入成普通 TLS，字段记录报告这处不同（SEMANTICS_DIFFER）。
        // 只拒三种：不是 map（mihomo 解码失败）；mihomo 读到了公钥（含 Public-Key、reality_opts 之类的写法），解析器
        // 没拿到；vmess / vless 没有可用公钥的 reality-opts 让解析器打开了 TLS，mihomo 却是明文
        proxy.mihomoEntry("reality-opts")?.let { reality ->
            val opts = reality.value
            if (opts !is Map<*, *>) {
                if (!opts.isFalseOrEmpty()) return rejected(ClashNodeFailure.REALITY_PUBLIC_KEY_MISSING, reality.key)
            } else opts.mihomoEntry("public-key")?.let { publicKey ->
                val text = publicKey.value?.toString().orEmpty()
                if (text.isNotEmpty() && text != bean.realityPubKey) {
                    return rejected(ClashNodeFailure.REALITY_PUBLIC_KEY_MISSING, reality.key, publicKey.key)
                }
            }
        }
        if (bean !is TrojanBean && bean.isTLS() && bean.realityPubKey.isNullOrBlank() && tls == null &&
            !proxy["tls"].clashBoolean()
        ) return rejected(ClashNodeFailure.REALITY_PUBLIC_KEY_MISSING, "reality-opts")

        // mihomo 的 VLESS 加密层（encryption 不是空串或 none）
        if (bean is VMessBean && bean.isVLESS) proxy.mihomoEntry("encryption")?.let { encryption ->
            val text = encryption.value?.toString()
            if (text != null && text != "" && text != "none") return rejected(ClashNodeFailure.VLESS_ENCRYPTION, encryption.key)
        }
        // mihomo 的 NewVless：flow 至少 16 个字节而前 16 个字节不是 xtls-rprx-vision，报 unsupported xtls flow type
        if (bean is VMessBean && bean.isVLESS) proxy.mihomoEntry("flow")?.let { flow ->
            val text = flow.value as? String ?: return@let
            if (text.toByteArray(Charsets.UTF_8).size >= 16 && !text.startsWith(StandardV2RayBean.FLOW_VISION)) {
                return ClashNodeRejected(ClashNodeFailure.INVALID_VALUE, listOf(flow.key), flow.value, type)
            }
        }
    }

    // TLS 层变体（shadow-tls / restls / jls，vmess 另有 tlsmirror），是否生效按 mihomo 判断；anytls 同样支持前三种
    val variants = when {
        bean is VMessBean && !bean.isVLESS -> listOf("shadow-tls-opts", "restls-opts", "jls-opts", "tlsmirror-opts")
        bean is VMessBean || bean is TrojanBean || bean is AnyTLSBean -> listOf("shadow-tls-opts", "restls-opts", "jls-opts")
        else -> emptyList()
    }
    for (key in variants) {
        val entry = proxy.mihomoEntry(key) ?: continue
        if (clashTlsVariantActive(key, entry.value)) return rejected(ClashNodeFailure.TLS_VARIANT, entry.key)
    }
    // trojan 的 ss-opts 加密层
    if (bean is TrojanBean) {
        val ssOpts = proxy.mihomoEntry("ss-opts")
        if (ssOpts != null && clashTlsVariantActive("ss-opts", ssOpts.value)) {
            return rejected(ClashNodeFailure.EXTRA_ENCRYPTION, ssOpts.key)
        }
    }

    // ss 的 udp-over-tcp-version：mihomo 不接受 1、2 以外的值（0 与不写同义），无论 UoT 开没开
    if (bean is ShadowsocksBean) proxy.mihomoEntry("udp-over-tcp-version")?.let { version ->
        if (version.value != null && clashUdpOverTcpVersion(version.value) == null) {
            return rejected(ClashNodeFailure.INVALID_VALUE, version.key)
        }
    }

    // ss 的 v2ray-plugin：插件打开 TLS 时的证书固定，本应用的插件做不到（键的读法同解析器，见 clashSsPlugin）
    if (bean is ShadowsocksBean && proxy.clashSsPlugin()?.value?.toString() == "v2ray-plugin") {
        val pluginPin = proxy.clashSsPluginOpt("fingerprint")
        if (pluginPin != null && proxy.clashSsPluginOpt("tls")?.value.mihomoTrue() &&
            !pluginPin.value?.toString().isNullOrEmpty()
        ) return rejected(ClashNodeFailure.CERT_PIN_NOT_SUPPORTED, proxy.clashSsPluginOpts()!!.key, pluginPin.key)
    }

    // hysteria2 的混淆：构建只会写 salamander，gecko 等其它类型本应用没有。只有已知的混淆类型名才显示
    // （CLASH_ENUM_PATHS_BY_TYPE：误写进来的口令不显示；hysteria v1 的 obfs 是口令，从不显示）
    if (bean is HysteriaBean && bean.protocolVersion == 2) proxy.mihomoEntry("obfs")?.let { obfs ->
        val text = obfs.value?.toString().orEmpty()
        if (text.isNotEmpty() && text != "salamander") {
            return ClashNodeRejected(ClashNodeFailure.OBFS_NOT_SUPPORTED, listOf(obfs.key), obfs.value, type)
        }
        if (text == "salamander") {
            // mihomo 的 NewHysteria2：obfs 非空时口令不能为空，否则报 missing obfs password，节点起不来
            val password = proxy.mihomoEntry("obfs-password")?.value?.toString().orEmpty()
            if (password.isEmpty()) {
                return ClashNodeRejected(ClashNodeFailure.MISSING_OBFS_PASSWORD, listOf(obfs.key), obfs.value, type)
            }
            // 口令的键写法与解析器不同（解析器没读到）：导入成不混淆的节点永远连不上
            if (password != bean.obfuscation) return rejected(ClashNodeFailure.SECURITY_SETTING_NOT_READ, obfs.key)
        }
    }

    // hysteria 1 的 auth：mihomo 的 NewHysteria 按标准 base64 解码，解不开就报错，节点起不来
    if (bean is HysteriaBean && bean.protocolVersion == 1) proxy.mihomoEntry("auth")?.let { auth ->
        val text = auth.value?.toString().orEmpty()
        if (text.isNotEmpty() && !isGoStdBase64(text)) return rejected(ClashNodeFailure.INVALID_VALUE, auth.key)
    }
    return null
}

// Go 的 base64.StdEncoding.DecodeString 能解的串：标准字母表、带填充，换行符被忽略
private val GO_STD_BASE64 = Regex("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")

private fun isGoStdBase64(text: String) = GO_STD_BASE64.matches(text.replace("\r", "").replace("\n", ""))

// clash 的 type -> bean。不认识的 type 返回 null，按类型不支持报告；已知类型的坏节点抛异常
private fun parseClashProxy(type: String, proxy: Map<String, Any?>): AbstractBean? = when (type) {
    "socks5" -> parseClashSocks(proxy)
    "http" -> parseClashHttp(proxy)
    "ss" -> parseClashShadowsocks(proxy)
    "vmess", "vless", "trojan" -> parseClashV2Ray(proxy)
    "anytls" -> parseClashAnyTLS(proxy)
    "hysteria" -> parseClashHysteria(proxy, 1)
    "hysteria2", "hy2" -> parseClashHysteria(proxy, 2)
    "tuic" -> parseClashTuic(proxy)
    "wireguard" -> parseClashWireGuard(proxy)
    else -> null
}

private fun parseClashSocks(proxy: Map<String, Any?>) = SOCKSBean().apply {
    serverAddress = clashKey("server", proxy["server"]) { proxy["server"] as String }
    serverPort = clashKey("port", proxy["port"]) { proxy["port"].toString().toInt() }
    username = proxy["username"]?.toString()
    password = proxy["password"]?.toString()
    name = proxy["name"]?.toString()
}

private fun parseClashHttp(proxy: Map<String, Any?>) = HttpBean().apply {
    serverAddress = clashKey("server", proxy["server"]) { proxy["server"] as String }
    serverPort = clashKey("port", proxy["port"]) { proxy["port"].toString().toInt() }
    username = proxy["username"]?.toString()
    password = proxy["password"]?.toString()
    setTLS(proxy["tls"].clashBoolean())
    sni = proxy["sni"]?.toString()
    name = proxy["name"]?.toString()
    allowInsecure = proxy["skip-cert-verify"].clashBoolean()
    // 证书固定（mihomo 只在 TLS 打开时用）：合法摘要读进来，本应用的 HTTP 出站做不到，构建时由选核明确拒绝
    // （PROTOCOL_CERTIFICATE_PIN），不再悄悄丢掉；不合法的值整个节点被拒（clashRejection）
    if (isTLS()) proxy["fingerprint"]?.let { certificateFingerprint = parseCertificateFingerprint(it) }
}

// ss 的插件与 UoT 键按 mihomo 的读法找，解析器、clashRejection 与字段记录共用这几个函数（NewShadowSocks）：
// - 顶层的 plugin、plugin-opts、udp-over-tcp、udp-over-tcp-version 由主解码器读，先找原样的键，没有再不区分大小写、
//   把 _ 当作 -（common/structure 的 decodeStructFromMap，adapter/parser.go 传入 DefaultKeyReplacer）；同一个键写了几种
//   拼法时原样的优先
// - plugin-opts 里的子键由插件自己的解码器读（NewShadowSocks 里 TagName 为 obfs、没有 KeyReplacer）：不区分大小写，
//   但不把 _ 当作 -
// - 插件名 option.Plugin 的比较区分大小写；空白的 plugin 视为没有插件
internal fun Map<*, *>.clashSsPlugin(): Map.Entry<*, *>? =
    mihomoEntry("plugin")?.takeIf { !it.value?.toString().isNullOrBlank() }

internal fun Map<*, *>.clashSsPluginOpts(): Map.Entry<*, *>? = mihomoEntry("plugin-opts")

internal fun Map<*, *>.clashSsPluginOpt(name: String): Map.Entry<*, *>? =
    (clashSsPluginOpts()?.value as? Map<*, *>)?.mihomoEntry(name, underscoreAsDash = false)

private fun parseClashShadowsocks(proxy: Map<String, Any?>): ShadowsocksBean {
    val ssPlugin = mutableListOf<String>()
    val pluginEntry = proxy.clashSsPlugin()
    val ssPluginName = pluginEntry?.value?.toString()
    if (ssPluginName != null) {
        // plugin-opts 在 Clash 里可以不写：没有它插件照样用默认选项
        fun opt(name: String) = proxy.clashSsPluginOpt(name)?.value
        when (ssPluginName) {
            "obfs" -> {
                ssPlugin.apply {
                    add("obfs-local")
                    add("obfs=" + (opt("mode")?.toString() ?: ""))
                    add("obfs-host=" + (opt("host")?.toString() ?: ""))
                }
            }

            "v2ray-plugin" -> {
                ssPlugin.apply {
                    add("v2ray-plugin")
                    add("mode=" + (opt("mode")?.toString() ?: ""))
                    if (opt("tls").mihomoTrue()) add("tls")
                    add("host=" + (opt("host")?.toString() ?: ""))
                    add("path=" + (opt("path")?.toString() ?: ""))
                    if (opt("mux").mihomoTrue()) add("mux=8")
                }
            }

            // shadow-tls / restls 等通过各自的 *-opts 键配置，这里没有对应的 sip003 插件；
            // 导入成没有它们的节点看起来正常却永远连不上，所以明确拒绝
            else -> throw ClashNodeRejected(
                ClashNodeFailure.UNSUPPORTED_SS_PLUGIN, pluginEntry.key.toString(), ssPluginName
            )
        }
    }
    return ShadowsocksBean().apply {
        serverAddress = clashKey("server", proxy["server"]) { proxy["server"] as String }
        serverPort = clashKey("port", proxy["port"]) { proxy["port"].toString().toInt() }
        password = proxy["password"]?.toString()
        method = clashKey("cipher", proxy["cipher"]) { clashCipher(proxy["cipher"] as String) }
        plugin = ssPlugin.joinToString(";")
        // 布尔值按 mihomo 的弱类型规则：true 或非 0 的整数
        sUoT = proxy.mihomoEntry("udp-over-tcp")?.value.mihomoTrue()
        // 版本不属于 sing-box 默认值（2）时才记下；UoT 没开时版本不起作用，留默认值
        if (sUoT == true) sUoTVersion = clashUdpOverTcpVersion(proxy.mihomoEntry("udp-over-tcp-version")?.value) ?: 1
        name = proxy["name"]?.toString()
    }
}

// mihomo 的 NewShadowSocks：udp-over-tcp-version 是 1 或 2，不写（或写 0）时用 1，其它值报错（报错与 UoT 开没开无关，
// 在 clashRejection 里拒绝）。返回 null 表示这个值 mihomo 不接受。sing-box 的 udp_over_tcp 只写 true 时用 2，
// 所以不写版本的 Clash 节点要显式带上版本 1
internal fun clashUdpOverTcpVersion(value: Any?): Int? {
    if (value == null) return 1
    return when (clashInt(value.toString())) {
        0L, 1L -> 1
        2L -> 2
        else -> null
    }
}

private fun parseClashV2Ray(proxy: Map<String, Any?>): StandardV2RayBean {
    val bean = when (proxy["type"] as String) {
        "vmess" -> VMessBean()
        "vless" -> VMessBean().apply {
            alterId = -1 // make it VLESS
            packetEncoding = 2 // clash meta default XUDP
        }

        "trojan" -> TrojanBean().apply {
            security = "tls"
        }

        else -> error("impossible")
    }

    // 抛异常而不是 continue：调用方只跳过这一个节点
    bean.serverAddress = proxy["server"]?.toString()
        ?: throw ClashNodeRejected(ClashNodeFailure.MISSING_SERVER)
    val port = proxy["port"]?.toString() ?: throw ClashNodeRejected(ClashNodeFailure.MISSING_PORT)
    bean.serverPort = port.toIntOrNull() ?: throw ClashNodeRejected(ClashNodeFailure.INVALID_PORT)

    // 传输方式的 *-opts 与 network 的先后无关：循环里只暂存，network 定下来之后只套用当前 network 的那个
    // （含 ws-opts 的 v2ray-http-upgrade），其它 network 的 opts 不会改写 host / path / SNI
    var network: String? = null
    val transportOpts = HashMap<String, Any?>()
    for (opt in proxy) clashKey(opt.key, opt.value) {
        when (opt.key) {
            "name" -> bean.name = opt.value?.toString()
            "password" -> if (bean is TrojanBean) bean.password =
                opt.value?.toString()

            "uuid" -> if (bean is VMessBean) bean.uuid =
                opt.value?.toString()

            "alterId" -> if (bean is VMessBean && !bean.isVLESS) bean.alterId =
                opt.value?.toString()?.toIntOrNull()

            // mihomo 的 NewVmess 把 cipher 转成小写再用（strings.ToLower）
            "cipher" -> if (bean is VMessBean && !bean.isVLESS) bean.encryption =
                (opt.value as? String)?.lowercase()

            // mihomo 的 NewVless：flow 至少 16 个字节时取前 16 个字节，必须恰好是 xtls-rprx-vision，所以以它开头
            // （如 xtls-rprx-vision-udp443）才是 vision；不足 16 个字节的值被静默忽略。至少 16 个字节却不以它开头的
            // 值 mihomo 报 unsupported xtls flow type，已在 clashRejection 里拒绝
            "flow" -> if (bean is VMessBean && bean.isVLESS) {
                (opt.value as? String)?.let {
                    if (it.startsWith(StandardV2RayBean.FLOW_VISION)) {
                        bean.encryption = StandardV2RayBean.FLOW_VISION
                    }
                }
            }

            // 不走 packetEncodingType：未知值要显式归 0（覆盖上面 vless 默认的 xudp）。
            // mihomo 把 packet 当作 packetaddr（NewVmess / NewVless 里的 case "packetaddr", "packet"）
            "packet-encoding" -> if (bean is VMessBean) {
                bean.packetEncoding = when ((opt.value as? String)) {
                    "packetaddr", "packet" -> 1
                    "xudp" -> 2
                    else -> 0
                }
            }

            "tls" -> if (bean is VMessBean) {
                bean.security =
                    if (opt.value.clashBoolean()) "tls" else ""
            }

            "servername", "sni" -> bean.sni = opt.value?.toString()

            "alpn" -> bean.alpn =
                (opt.value as? List<Any>)?.joinToString("\n")

            "skip-cert-verify" -> bean.allowInsecure =
                opt.value.clashBoolean()

            "fingerprint" -> bean.certificateFingerprint =
                parseCertificateFingerprint(opt.value)

            "client-fingerprint" -> bean.utlsFingerprint =
                opt.value as String

            "reality-opts" -> (opt.value as? Map<String, Any?>)?.also {
                for (realityOpt in it) {
                    bean.security = "tls"

                    when (realityOpt.key) {
                        "public-key" -> bean.realityPubKey =
                            realityOpt.value?.toString()

                        "short-id" -> bean.realityShortId =
                            realityOpt.value?.toString()
                    }
                }
            }

            "network" -> network = opt.value?.toString()

            "ws-opts", "h2-opts", "http-opts", "grpc-opts" -> transportOpts[opt.key] = opt.value

            "smux" -> (opt.value as? Map<String, Any?>)?.also {
                for (smuxOpt in it) clashKey(smuxOpt.key, smuxOpt.value, "smux") {
                    when (smuxOpt.key) {
                        "enabled" -> bean.enableMux =
                            smuxOpt.value.clashBoolean()

                        "max-streams" -> bean.muxConcurrency =
                            smuxOpt.value.toString().toInt()

                        "padding" -> bean.muxPadding =
                            smuxOpt.value.clashBoolean()

                        "protocol" -> bean.muxType =
                            muxProtocolType(smuxOpt.value?.toString())
                    }
                }
            }

            "ech-opts" -> (opt.value as? Map<String, Any?>)?.also {
                for (echOpt in it) {
                    when (echOpt.key) {
                        "enable" -> bean.enableECH =
                            echOpt.value.clashBoolean()

                        "config" -> bean.echConfig =
                            echOpt.value?.toString() ?: ""
                    }
                }
            }
        }
    }
    bean.type = clashV2RayTransport(bean is TrojanBean, network)
    checkClashTransportTls(bean.type, network, bean.isTLS())
    val optsKey = optsKeyOf(if (bean is TrojanBean) "trojan" else "", network)
    // mihomo 只读当前 network 的 *-opts（含 ws-opts 的升级标记，它只在 network 为 ws 时出现）
    // 仍放在 clashKey 里：读 opts 时的意外异常按带键路径的 INVALID_VALUE 报，不变成 OTHER
    if (optsKey != null && clashKey(optsKey, transportOpts[optsKey]) {
            bean.applyClashTransportOpts(optsKey, transportOpts[optsKey])
        }
    ) bean.type = "httpupgrade"
    return bean
}

// network -> bean.type。Trojan（mihomo 的 trojan.go）只认 ws 与 grpc，其它值（包括 h2、http、xhttp、kcp）都当作 TCP 跑；
// VMess / VLESS 不认识的 network 抛异常，按已知类型的坏节点记日志并跳过（忽略它的话 type 停在默认的 tcp，之后再也看不出来）
private fun clashV2RayTransport(trojan: Boolean, network: String?): String =
    if (trojan) when (network) {
        "ws", "grpc" -> network
        else -> "tcp"
    } else clashNetworkTransport(network)

// mihomo 的 h2 是 HTTP/2 传输（带 TLS 时在 TLS 上，不带 TLS 是明文 h2c），http 是 TCP 上的 HTTP/1.1 伪装头（带 TLS 时在
// TLS 里）。本应用的 http 传输带 TLS 时是 h2、不带 TLS 时是 HTTP/1.1 伪装头，所以只有 h2 + TLS 与 http + 明文
// 对得上；另外两种组合没有对应的表示，导入成别的传输只会永远连不上，整个节点被拒
private fun checkClashTransportTls(type: String, network: String?, tls: Boolean) {
    if (type != "http") return
    if ((network == "h2") != tls) throw ClashNodeRejected(ClashNodeFailure.TRANSPORT_TLS_MISMATCH, "network", network)
}

// clash *-opts -> StandardV2RayBean 的 host / path 与 ws early data 两个字段，
// 即 buildSingBoxOutboundStreamSettings 的解析一侧。返回 ws-opts 是否要求 HTTP upgrade：
// 它改的是传输方式，要等 network 定下来再由调用方套用
private fun StandardV2RayBean.applyClashTransportOpts(key: String, value: Any?): Boolean {
    var httpUpgrade = false
    when (key) {
        "ws-opts" -> (value as? Map<String, Any?>)?.also {
            for (wsOpt in it) {
                when (wsOpt.key) {
                    "headers" -> (wsOpt.value as? Map<Any, Any?>)?.forEach { (name, headerValue) ->
                        when (name.toString().lowercase()) {
                            "host" -> {
                                host = headerValue?.toString()
                            }
                        }
                    }

                    "path" -> {
                        path = wsOpt.value?.toString()
                    }

                    "max-early-data" -> {
                        wsMaxEarlyData =
                            wsOpt.value?.toString()?.toIntOrNull()
                    }

                    "early-data-header-name" -> {
                        earlyDataHeaderName =
                            wsOpt.value?.toString()
                    }

                    "v2ray-http-upgrade" -> {
                        if (wsOpt.value.clashBoolean()) httpUpgrade = true
                    }
                }
            }
        }

        "h2-opts" -> (value as? Map<String, Any?>)?.also {
            for (h2Opt in it) {
                when (h2Opt.key) {
                    "host" -> host =
                        (h2Opt.value as? List<Any>)?.joinToString("\n")

                    "path" -> path = h2Opt.value?.toString()
                }
            }
        }

        "http-opts" -> (value as? Map<String, Any?>)?.also {
            for (httpOpt in it) {
                when (httpOpt.key) {
                    // a list mihomo picks from at random;
                    // the builders carry a single path
                    "path" -> path =
                        (httpOpt.value as? List<*>)?.firstOrNull()
                            ?.toString()

                    "headers" -> {
                        // Map<*, *>: a typed value cast is erased and the
                        // destructuring checkcasts it, so a scalar
                        // "Host: example.com" would drop the whole node
                        (httpOpt.value as? Map<*, *>)?.forEach { (name, headerValue) ->
                            when (name.toString().lowercase()) {
                                "host" -> host = when (headerValue) {
                                    is List<*> -> headerValue.mapNotNull { it?.toString() }
                                        .joinToString("\n")

                                    else -> headerValue?.toString()
                                }
                            }
                        }
                    }
                }
            }
        }

        "grpc-opts" -> (value as? Map<String, Any?>)?.also {
            for (grpcOpt in it) {
                when (grpcOpt.key) {
                    "grpc-service-name" -> path =
                        grpcOpt.value?.toString()
                }
            }
        }
    }
    return httpUpgrade
}

private fun parseClashAnyTLS(proxy: Map<String, Any?>): AnyTLSBean {
    val bean = AnyTLSBean()
    for (opt in proxy) {
        if (opt.value == null) continue
        clashKey(opt.key, opt.value) {
            when (opt.key.replace("_", "-")) {
                "name" -> bean.name = opt.value.toString()
                "server" -> bean.serverAddress = opt.value as String
                "port" -> bean.serverPort = opt.value.toString().toInt()
                "password" -> bean.password = opt.value.toString()
                "client-fingerprint" -> bean.utlsFingerprint =
                    opt.value as String

                "sni" -> bean.sni = opt.value.toString()
                "skip-cert-verify" -> bean.allowInsecure =
                    opt.value.clashBoolean()

                // mihomo 的 certificate / private-key 是 mTLS 的客户端证书（ca.GetTLSConfig ->
                // GetClientCertificate），不是自定义 CA：映射到 bean.certificates 会变成永远对不上的
                // 服务端证书固定。不导入，由字段记录报告

                "fingerprint" -> bean.certificateFingerprint =
                    parseCertificateFingerprint(opt.value)

                "alpn" -> {
                    val alpn = (opt.value as? (List<String>))
                    bean.alpn = alpn?.joinToString("\n")
                }

                "ech-opts" -> (opt.value as? Map<String, Any?>)?.also {
                    bean.enableECH = it["enable"].clashBoolean()
                    // mihomo 的 ECHOptions.Parse：没有 enable 就不开 ECH，config 也不用。AnyTLSBean 里填了 config
                    // 就等于启用 ECH，所以 enable 没打开时不能保存 config
                    if (bean.enableECH) {
                        bean.echConfig =
                            it["config"]?.toString() ?: ""
                    }
                }
            }
        }
    }
    return bean
}

// hysteria and hysteria2 share most keys; the version-specific ones sit in
// the nested when. hysteria 1 defaults a missing bandwidth to 100 Mbps where
// hysteria 2 leaves it at 0.
private fun parseClashHysteria(proxy: Map<String, Any?>, version: Int): HysteriaBean {
    val bean = HysteriaBean()
    bean.protocolVersion = version
    val defaultMbps = if (version == 1) 100 else 0
    var hopPorts = ""
    // 认证与混淆都要等所有键读完再定：mihomo 不看键的先后（NewHysteria / NewHysteria2）
    var auth: String? = null
    var authStr: String? = null
    var obfsType: String? = null
    var obfsPassword: String? = null
    for (opt in proxy) {
        if (opt.value == null) continue
        clashKey(opt.key, opt.value) {
            when (val key = opt.key.replace("_", "-")) {
                "name" -> bean.name = opt.value.toString()
                "server" -> bean.serverAddress = opt.value as String
                "port" -> bean.serverPorts = opt.value.toString()
                "ports" -> hopPorts = opt.value.toString()

                "sni" -> bean.sni = opt.value.toString()

                "fingerprint" -> bean.certificateFingerprint =
                    parseCertificateFingerprint(opt.value)

                "skip-cert-verify" -> bean.allowInsecure =
                    opt.value.clashBoolean()

                "up" -> bean.uploadMbps =
                    opt.value.toString().substringBefore(" ").toIntOrNull() ?: defaultMbps

                "down" -> bean.downloadMbps =
                    opt.value.toString().substringBefore(" ").toIntOrNull() ?: defaultMbps

                // clash "ca" is a local file path, only
                // "ca-str" carries an inline PEM
                "ca-str" -> bean.caText = opt.value.toString()

                "hop-interval" -> bean.hopInterval =
                    opt.value.toString().toIntOrNull() ?: bean.hopInterval

                else -> if (version == 1) when (key) {
                    "obfs" -> bean.obfuscation = opt.value.toString()

                    "auth-str" -> authStr = opt.value.toString()
                    "auth" -> auth = opt.value.toString()

                    "protocol" -> {
                        when (opt.value.toString()) {
                            "faketcp" -> bean.protocol =
                                HysteriaBean.PROTOCOL_FAKETCP

                            "wechat-video" -> bean.protocol =
                                HysteriaBean.PROTOCOL_WECHAT_VIDEO
                        }
                    }

                    "recv-window-conn" -> bean.streamReceiveWindow =
                        opt.value.toString().toIntOrNull() ?: 0

                    "recv-window" -> bean.connectionReceiveWindow =
                        opt.value.toString().toIntOrNull() ?: 0

                    "disable-mtu-discovery" -> bean.disableMtuDiscovery =
                        opt.value.clashBoolean() || opt.value.toString() == "1"

                    "alpn" -> {
                        val alpn = (opt.value as? (List<String>))
                        bean.alpn = alpn?.joinToString("\n") ?: "h3"
                    }
                } else when (key) {
                    "obfs" -> obfsType = opt.value.toString()
                    "obfs-password" -> obfsPassword = opt.value.toString()

                    "password" -> bean.authPayload = opt.value.toString()

                    // no "alpn" here: hysteria2 mandates h3 and the
                    // sing-box builder hardcodes it
                }
            }
        }
    }
    if (hopPorts.isNotBlank()) {
        bean.serverPorts = hopPorts
    }
    // hysteria 1：auth 非空时总是用它（按 base64 解码），auth-str 只在 auth 为空时用（mihomo NewHysteria）
    if (!auth.isNullOrEmpty()) {
        bean.authPayloadType = HysteriaBean.TYPE_BASE64
        bean.authPayload = auth
    } else if (authStr != null) {
        bean.authPayloadType = HysteriaBean.TYPE_STRING
        bean.authPayload = authStr
    }
    // hysteria 2：只有 obfs 写了 salamander 才混淆，口令是 obfs-password；只写 obfs-password 不混淆
    // （mihomo NewHysteria2）。obfs 写了别的类型、或写了 salamander 却没有口令的节点已在 clashRejection 里被拒
    if (version == 2 && obfsType == "salamander") bean.obfuscation = obfsPassword.orEmpty()
    return bean
}

private fun parseClashTuic(proxy: Map<String, Any?>): TuicBean {
    val bean = TuicBean()
    var ip = ""
    for (opt in proxy) {
        if (opt.value == null) continue
        clashKey(opt.key, opt.value) {
            when (opt.key.replace("_", "-")) {
                "name" -> bean.name = opt.value.toString()
                "server" -> bean.serverAddress = opt.value.toString()
                "ip" -> ip = opt.value.toString()
                "port" -> bean.serverPort = opt.value.toString().toInt()

                // mihomo treats a "token" node as TUIC v4, which the
                // core dropped; importing it would only fail at connect
                // time, so skip it like an unsupported ss plugin
                "token" -> throw ClashNodeRejected(ClashNodeFailure.TUIC_V4, "token")

                "uuid" -> bean.uuid = opt.value.toString()

                "password" -> bean.token = opt.value.toString()

                "skip-cert-verify" -> bean.allowInsecure =
                    opt.value.clashBoolean()

                "disable-sni" -> bean.disableSNI =
                    opt.value.clashBoolean()

                "reduce-rtt" -> bean.reduceRTT =
                    opt.value.clashBoolean()

                "sni" -> bean.sni = opt.value.toString()

                "ca-str" -> bean.caText = opt.value.toString()

                "fingerprint" -> bean.certificateFingerprint =
                    parseCertificateFingerprint(opt.value)

                "fast-open" -> bean.fastConnect =
                    opt.value.clashBoolean()

                "alpn" -> {
                    val alpn = (opt.value as? (List<String>))
                    bean.alpn = alpn?.joinToString("\n")
                }

                "congestion-controller" -> bean.congestionController =
                    opt.value.toString()

                "udp-relay-mode" -> bean.udpRelayMode = opt.value.toString()

                // mihomo milliseconds, sing-box seconds (rounded)
                "heartbeat-interval" -> bean.heartbeatInterval =
                    opt.value.toString().toLongOrNull()
                        ?.let { ((it + 500) / 1000).toInt().coerceAtLeast(0) }
                        ?: bean.heartbeatInterval

            }
        }
    }
    if (ip.isNotBlank()) {
        val domain = bean.serverAddress
        bean.serverAddress = ip
        if (bean.sni.isNullOrBlank() && !domain.isNullOrBlank() && !domain.isIpAddress()) {
            bean.sni = domain
        }
    }
    return bean
}

private fun parseClashWireGuard(proxy: Map<String, Any?>): WireGuardBean {
    // 不做 applyDefaultValues：serverPort 填了默认值后，缺 port 的节点会
    // 瞒过 requireValidEndpoint；归一化统一由调用处尾部完成
    val bean = WireGuardBean()
    val localAddresses = mutableListOf<String>()
    for (opt in proxy) {
        if (opt.value == null) continue
        clashKey(opt.key, opt.value) {
            when (opt.key.replace("_", "-")) {
                "name" -> bean.name = opt.value.toString()
                "server" -> bean.serverAddress = opt.value.toString()
                "port" -> bean.serverPort = opt.value.toString().toInt()
                "ip", "ipv6" -> {
                    val address = opt.value.toString()
                    if (address.isNotBlank()) localAddresses.add(address)
                }

                "private-key" -> bean.privateKey = opt.value.toString()
                "public-key" -> bean.peerPublicKey = opt.value.toString()
                "pre-shared-key" -> bean.peerPreSharedKey =
                    opt.value.toString()

                "mtu" -> bean.mtu =
                    opt.value.toString().toIntOrNull() ?: bean.mtu

                // mihomo writes the three reserved bytes as a
                // list; the bean keeps genReservedList's comma form
                "reserved" -> bean.reserved =
                    (opt.value as? List<*>)?.joinToString(",") {
                        it?.toString() ?: ""
                    } ?: opt.value.toString()

                "persistent-keepalive" -> bean.peerKeepalive =
                    opt.value.toString().toIntOrNull() ?: 0

                // 构建时生效（留空才用默认路由），见 buildSingBoxEndpointWireGuardBean。
                // 非法值在导入时清空，同 sanitizeImportedAllowedIps（它会写日志，这里由字段记录报告）
                "allowed-ips" -> {
                    val raw = (opt.value as? List<*>)?.mapNotNull { it?.toString() }
                        ?.joinToString(",") ?: opt.value.toString()
                    bean.peerAllowedIps = if (isWireGuardLocalAddressList(raw)) raw else ""
                }

                // remote-dns-resolve、amnezia-wg-option 不支持，由字段记录报告
            }
        }
    }
    bean.localAddress = localAddresses.joinToString("\n")
    return bean
}

// SNI and uTLS fixups after every node was parsed
private fun StandardV2RayBean.applyClashFixups() {
    // 1. SNI; h2-opts/http-opts hosts arrive newline-joined and the
    // SNI can only carry one of them
    val firstHost = host.substringBefore("\n")
    if (isTLS() && sni.isNullOrBlank() && firstHost.isNotBlank() && !firstHost.isIpAddress()) {
        sni = firstHost
    }
    // 2. REALITY 必须有 uTLS 指纹，没写时用 chrome（mihomo 对这样的节点报 REALITY is based on uTLS，本应用给默认值让它可用）
    if (!realityPubKey.isNullOrBlank() && utlsFingerprint.isNullOrBlank()) {
        utlsFingerprint = "chrome"
    }
}

private fun clashCipher(cipher: String): String {
    return when (cipher) {
        "dummy" -> "none"
        else -> cipher
    }
}

// mihomo 的 fingerprint 是服务端证书的 SHA-256 摘要；别的形状的值进了核心也只会失败，这里丢掉
// （字段记录报告它）
private fun parseCertificateFingerprint(value: Any?): String {
    val text = value?.toString() ?: ""
    if (text.isNotEmpty() && !isCertificateFingerprint(text)) return ""
    return text
}
