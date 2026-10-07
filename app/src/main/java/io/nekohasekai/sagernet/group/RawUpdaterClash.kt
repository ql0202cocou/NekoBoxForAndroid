@file:Suppress("UNCHECKED_CAST")

package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.CLASH_KEY_NOT_SHOWN
import io.nekohasekai.sagernet.fmt.ClashNodeFailure
import io.nekohasekai.sagernet.fmt.ClashNodeResult
import io.nekohasekai.sagernet.fmt.clashShownPath
import io.nekohasekai.sagernet.fmt.clashShownValue
import io.nekohasekai.sagernet.fmt.clashShownValueAt
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteriaPorts
import io.nekohasekai.sagernet.fmt.requireValidEndpoint
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
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
// 让它像其他非 clash 内容一样回退到 base64 / 分享链接解析
fun loadClashYaml(text: String): Map<*, *> =
    clashYaml().load(text) as? Map<*, *> ?: throw YAMLException("Root node is not a map")

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
    val globalClientFingerprint = yaml["global-client-fingerprint"]?.toString() ?: ""

    // 用 List<*> 而不带 Map 类型参数：泛型 List<Map> 的强转会被擦除，编译器在判断之后才逐个元素
    // checkcast，一个「- ss://...」字符串条目就会抛 ClassCastException 拖垮整次更新
    val entries = yaml["proxies"] as? List<*> ?: throw ClashNoProxiesException()
    val beans = mutableListOf<AbstractBean>()
    val nodes = ArrayList<ClashNodeResult>(entries.size)
    entries.forEachIndexed { index, entry -> nodes += parseClashEntry(index, entry, beans) }

    // Fix ent
    beans.forEach {
        it.initializeDefaultValues()
        if (it is StandardV2RayBean) it.applyClashFixups(globalClientFingerprint)
    }
    return ClashParseResult(beans, nodes)
}

private fun parseClashEntry(index: Int, entry: Any?, beans: MutableList<AbstractBean>): ClashNodeResult {
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
        clashEndpointFailure(bean)?.let { throw ClashNodeRejected(it) }
        bean.requireValidEndpoint()
        clashRejection(type, proxy, bean)?.let { throw it }
        // 字段记录只读输入与解析结果，不改 bean，也不会因记录本身出错让节点被跳过
        val fields = classifyClashFields(type, proxy, bean)
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

// requireValidEndpoint 的同一套判断，只为给出封闭集合里的原因；它本身仍在后面照常调用
private fun clashEndpointFailure(bean: AbstractBean): ClashNodeFailure? {
    if (bean.serverAddress.isNullOrBlank()) return ClashNodeFailure.MISSING_SERVER
    if (bean is HysteriaBean) {
        val ports = bean.serverPorts ?: return ClashNodeFailure.MISSING_PORT
        return if (runCatching { parseHysteriaPorts(ports) }.isSuccess) null else ClashNodeFailure.INVALID_PORT
    }
    val port = bean.serverPort ?: return ClashNodeFailure.MISSING_PORT
    return if (port in 1..65535) null else ClashNodeFailure.INVALID_PORT
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
    val pin = when (bean) {
        is StandardV2RayBean -> if (bean.isTLS()) bean.certificateFingerprint.orEmpty() else null
        is AnyTLSBean -> bean.certificateFingerprint.orEmpty()
        is HysteriaBean -> bean.certificateFingerprint.orEmpty()
        is TuicBean -> bean.certificateFingerprint.orEmpty()
        else -> null
    }
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
        // reality-opts：mihomo 只在 public-key 非空时启用 REALITY（RealityOptions.Parse），空公钥或只有 short-id 的
        // 两边都是普通 TLS，照常导入。只拒三种：不是 map（mihomo 解码失败）；mihomo 读到了公钥（含 Public-Key、
        // reality_opts 之类的写法），解析器没拿到；vmess / vless 没有可用公钥的 reality-opts 让解析器打开了 TLS，
        // mihomo 却是明文
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

    // ss 的 v2ray-plugin：插件打开 TLS 时的证书固定，本应用的插件做不到（mihomo 的插件选项解码器不把 _ 当作 -）
    if (bean is ShadowsocksBean && proxy.mihomoEntry("plugin")?.value?.toString() == "v2ray-plugin") {
        proxy.mihomoEntry("plugin-opts")?.let { pluginOpts ->
            val opts = pluginOpts.value as? Map<*, *> ?: return@let
            val pluginPin = opts.mihomoEntry("fingerprint", underscoreAsDash = false) ?: return@let
            if (opts.mihomoEntry("tls", underscoreAsDash = false)?.value.mihomoTrue() &&
                !pluginPin.value?.toString().isNullOrEmpty()
            ) return rejected(ClashNodeFailure.CERT_PIN_NOT_SUPPORTED, pluginOpts.key, pluginPin.key)
        }
    }

    // hysteria2 的混淆：构建只会写 salamander，gecko 等其它类型本应用没有。只有已知的混淆类型名才显示
    // （CLASH_ENUM_PATHS_BY_TYPE：误写进来的口令不显示；hysteria v1 的 obfs 是口令，从不显示）
    if (bean is HysteriaBean && bean.protocolVersion == 2) proxy.mihomoEntry("obfs")?.let { obfs ->
        val text = obfs.value?.toString().orEmpty()
        if (text.isNotEmpty() && text != "salamander") {
            return ClashNodeRejected(ClashNodeFailure.OBFS_NOT_SUPPORTED, listOf(obfs.key), obfs.value, type)
        }
    }
    return null
}

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

private fun parseClashShadowsocks(proxy: Map<String, Any?>): ShadowsocksBean {
    val ssPlugin = mutableListOf<String>()
    val ssPluginName = proxy["plugin"]?.toString().blankAsNull()
    if (ssPluginName != null) {
        // plugin-opts is optional in clash; without it the
        // plugin still applies with its default options
        val opts = proxy["plugin-opts"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        when (ssPluginName) {
            "obfs" -> {
                ssPlugin.apply {
                    add("obfs-local")
                    add("obfs=" + (opts["mode"]?.toString() ?: ""))
                    add("obfs-host=" + (opts["host"]?.toString() ?: ""))
                }
            }

            "v2ray-plugin" -> {
                ssPlugin.apply {
                    add("v2ray-plugin")
                    add("mode=" + (opts["mode"]?.toString() ?: ""))
                    if (opts["tls"].clashBoolean()) add("tls")
                    add("host=" + (opts["host"]?.toString() ?: ""))
                    add("path=" + (opts["path"]?.toString() ?: ""))
                    if (opts["mux"].clashBoolean()) add("mux=8")
                }
            }

            // shadow-tls/restls are configured through their own
            // *-opts keys and have no sip003 plugin here; importing
            // the node without them yields something that looks fine
            // and can never connect, so drop it loudly instead
            else -> throw ClashNodeRejected(ClashNodeFailure.UNSUPPORTED_SS_PLUGIN, "plugin", ssPluginName)
        }
    }
    return ShadowsocksBean().apply {
    serverAddress = clashKey("server", proxy["server"]) { proxy["server"] as String }
    serverPort = clashKey("port", proxy["port"]) { proxy["port"].toString().toInt() }
    password = proxy["password"]?.toString()
    method = clashKey("cipher", proxy["cipher"]) { clashCipher(proxy["cipher"] as String) }
    plugin = ssPlugin.joinToString(";")
    sUoT = proxy["udp-over-tcp"].clashBoolean()
    name = proxy["name"]?.toString()
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

    // ws-opts 的 v2ray-http-upgrade：循环结束、network 定下来之后再套用，与键的先后无关
    var httpUpgrade = false
    for (opt in proxy) clashKey(opt.key, opt.value) {
        when (opt.key) {
            "name" -> bean.name = opt.value?.toString()
            "password" -> if (bean is TrojanBean) bean.password =
                opt.value?.toString()

            "uuid" -> if (bean is VMessBean) bean.uuid =
                opt.value?.toString()

            "alterId" -> if (bean is VMessBean && !bean.isVLESS) bean.alterId =
                opt.value?.toString()?.toIntOrNull()

            "cipher" -> if (bean is VMessBean && !bean.isVLESS) bean.encryption =
                (opt.value as? String)

            "flow" -> if (bean is VMessBean && bean.isVLESS) {
                (opt.value as? String)?.let {
                    if (it.contains(StandardV2RayBean.FLOW_VISION)) {
                        bean.encryption = StandardV2RayBean.FLOW_VISION
                    }
                }
            }

            // 不走 packetEncodingType：mihomo 不认 "packet"，未知值也要显式归 0
            // （覆盖上面 vless 默认的 xudp）
            "packet-encoding" -> if (bean is VMessBean) {
                bean.packetEncoding = when ((opt.value as? String)) {
                    "packetaddr" -> 1
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

            // 不认识的 network 抛异常，按已知类型的坏节点记日志并跳过；
            // 忽略它的话 type 停在默认的 tcp，之后再也看不出来
            "network" -> bean.type = clashNetworkTransport(opt.value?.toString())

            "ws-opts", "h2-opts", "http-opts", "grpc-opts" ->
                if (bean.applyClashTransportOpts(opt.key, opt.value)) httpUpgrade = true

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
    // mihomo 只在 network 为 ws 时读 ws-opts（含升级标记），其它 network 下它不起作用
    if (httpUpgrade && bean.type == "ws") bean.type = "httpupgrade"
    return bean
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
                    val enable = it["enable"]
                    bean.enableECH = enable.clashBoolean()
                    // 没写 enable 时有 config 就保存（本应用随后开 ECH）：沿用旧的宽松读法。mihomo v1.19.32 的
                    // ECHOptions.Parse 没有 enable 就不开 ECH，字段记录报告这处不同，是否跟进由维护者定
                    if (enable == null || bean.enableECH) {
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

                    "auth-str" -> {
                        bean.authPayloadType = HysteriaBean.TYPE_STRING
                        bean.authPayload = opt.value.toString()
                    }

                    "auth" -> {
                        bean.authPayloadType = HysteriaBean.TYPE_BASE64
                        bean.authPayload = opt.value.toString()
                    }

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
                    "obfs-password" -> bean.obfuscation = opt.value.toString()

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
private fun StandardV2RayBean.applyClashFixups(globalClientFingerprint: String) {
    // 1. SNI; h2-opts/http-opts hosts arrive newline-joined and the
    // SNI can only carry one of them
    val firstHost = host.substringBefore("\n")
    if (isTLS() && sni.isNullOrBlank() && firstHost.isNotBlank() && !firstHost.isIpAddress()) {
        sni = firstHost
    }
    // 2. globalClientFingerprint
    if (!realityPubKey.isNullOrBlank() && utlsFingerprint.isNullOrBlank()) {
        utlsFingerprint = globalClientFingerprint
        if (utlsFingerprint.isNullOrBlank()) utlsFingerprint = "chrome"
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
