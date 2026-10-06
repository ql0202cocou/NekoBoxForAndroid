package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.v2ray.XRAY_BLOCK_TAG
import org.yaml.snakeyaml.Yaml

// 外核「哑管道」不变量（plan.md K1）：Xray 与 mihomo 在本应用里只是 sing-box 的出站插件，只承担节点拨号与本机端口
// 绑定，不启用 tun、sniffing、DNS 或用户分流规则。这里把它写成两张白名单加取值约束，同一套检查用在生成器的单测
// （ExternalCoreInvariantsTest）与基线里全部合并配置（GoldenExternalInvariantsTest）。
//
// 白名单按核心各写各的：Xray 的键集不能套到 mihomo 上，反过来也一样。每一级的键集分三类：required 必须出现，
// optional 可以出现，其余一律不许；forbidden 是点了名要禁的键（报错里带原因），不在白名单里的键同样会被拒绝，
// forbidden 只是让报错说得清楚。以后加协议只改下面的表，不动检查逻辑。

/** 一级对象的键集。 */
class Keys(
    val required: Set<String>,
    val optional: Set<String> = emptySet(),
    val forbidden: Map<String, String> = emptyMap(),
)

private fun keys(vararg required: String, optional: Set<String> = emptySet(), forbidden: Map<String, String> = emptyMap()) =
    Keys(required.toSet(), optional, forbidden)

private fun forbid(reason: String, vararg names: String): Map<String, String> = names.associateWith { reason }

/** Xray 出站协议的写法：settings 里放节点的那个数组、数组项的键集，以及（有的话）数组项里 users 一级的键集。 */
class XrayOutboundSpec(
    val settings: Keys,
    val listKey: String,
    val entry: Keys,
    val userKey: String? = null,
    val user: Keys? = null,
    /** 对 user 的取值约束，返回违例说明。 */
    val checkUser: (Map<String, Any?>) -> List<String> = { emptyList() },
)

/** 检查结果：violations 是违例；exceptions 是命中了表里显式列出的例外（如 ECH 的 DNS 获取），由调用方核对。 */
class InvariantReport(val violations: List<String>, val exceptions: List<String>)

/** mihomo 的 Clash API（external-controller）是否应出现在配置里。 */
sealed class ControllerExpectation {
    /** 运行、导出：不许出现。 */
    object Absent : ControllerExpectation()

    /** 手工构造的计划里不关心：出现的话也要符合形状。 */
    object Either : ControllerExpectation()

    /** 测速：必须是 127.0.0.1:port，secret 一致。 */
    class Present(val port: Int, val secret: String) : ControllerExpectation()
}

object ExternalCoreInvariants {

    // ================= Xray 的表 =================

    val XRAY_TOP = keys(
        "log", "inbounds", "outbounds", "routing",
        forbidden = forbid(
            "路由之外的能力由 sing-box 负责", "dns", "policy", "api", "stats", "fakedns", "observatory", "burstObservatory",
            "reverse", "metrics", "transport", "browserDialer",
        ) +
            // v26.3.27 之后新增的顶层键（env 自 v26.7.11、geodata 自 v26.4.25），只有源码依据（infra/conf/xray.go
            // 的 Config：env 在进程启动时 setenv，geodata 可定时下载文件；K1b X1），没有实测
            forbid("不改核心进程的环境变量", "env") +
            forbid("不下载地理数据", "geodata"),
    )
    val XRAY_LOG = keys("loglevel", "access")
    val XRAY_LOG_LEVELS = setOf("warning", "info", "debug")

    // 没有 sniffing：入站只是端口，嗅探与分流由 sing-box 做
    val XRAY_INBOUND = keys(
        "tag", "listen", "port", "protocol", "settings",
        forbidden = forbid("入站不嗅探", "sniffing") + forbid("入站不分配端口段", "allocate"),
    )
    val XRAY_INBOUND_SETTINGS = keys("auth", "accounts", "udp")
    val XRAY_ACCOUNT = keys("user", "pass")

    // routing 只许按 inboundTag 把入站指到自己的出站
    val XRAY_ROUTING = keys(
        "domainStrategy", "rules",
        forbidden = forbid("不做负载均衡", "balancers") + forbid("不做域名匹配器切换", "domainMatcher"),
    )
    val XRAY_RULE = keys("type", "inboundTag", "outboundTag")

    // 第一个出站是 blackhole：没有规则命中的流量走它
    val XRAY_BLOCK_OUTBOUND = keys("tag", "protocol")

    // 出站：键按协议，下面是各协议 settings 的写法。trojan 为 K1 接入 Trojan 预留（只有一个 server，没有 flow）
    val XRAY_OUTBOUND = keys(
        "tag", "protocol", "settings", "streamSettings",
        optional = setOf("mux"),
        forbidden = forbid("会引入额外拨号路径或改变出口", "sendThrough", "proxySettings", "targetStrategy"),
    )
    val XRAY_OUTBOUND_PROTOCOLS: Map<String, XrayOutboundSpec> = mapOf(
        "vmess" to XrayOutboundSpec(
            settings = keys("vnext"), listKey = "vnext", entry = keys("address", "port", "users"),
            userKey = "users", user = keys("id", "alterId", "security"),
        ),
        "vless" to XrayOutboundSpec(
            settings = keys("vnext"), listKey = "vnext", entry = keys("address", "port", "users"),
            userKey = "users", user = keys("id", "encryption", optional = setOf("flow")),
            // VLESS 的 encryption 固定写 none
            checkUser = { u -> if (u["encryption"] == "none") emptyList() else listOf("encryption 是 ${u["encryption"]}，应为 none") },
        ),
        "trojan" to XrayOutboundSpec(
            settings = keys("servers"), listKey = "servers", entry = keys("address", "port", "password"),
        ),
    )

    // streamSettings：不得出现 sockopt、dialerProxy 之类引入额外拨号路径或改变出口的键
    val XRAY_STREAM = keys(
        "network",
        optional = setOf("security", "tcpSettings", "wsSettings", "grpcSettings", "httpupgradeSettings", "tlsSettings", "realitySettings"),
        forbidden = forbid("会引入额外拨号路径或改变出口", "sockopt", "dialerProxy"),
    )
    val XRAY_NETWORKS = setOf("tcp", "ws", "grpc", "httpupgrade")

    // network → 它自己的传输设置键与那一级的键集；tcp 的设置只用来写 http 头伪装，可以没有
    val XRAY_TRANSPORT_KEYS = mapOf(
        "tcp" to "tcpSettings", "ws" to "wsSettings", "grpc" to "grpcSettings", "httpupgrade" to "httpupgradeSettings",
    )
    val XRAY_TCP = keys("header")
    val XRAY_TCP_HEADER = keys("type", "request")
    val XRAY_TCP_REQUEST = keys("path", optional = setOf("headers"))
    val XRAY_TCP_HEADERS = keys("Host")
    // Host 写独立的 host 键；headers 里的 Host 在 Xray 里已弃用，生成器不再写 headers
    val XRAY_WS = keys("path", optional = setOf("host"), forbidden = forbid("Host 写独立的 host 键", "headers"))
    val XRAY_GRPC = keys("serviceName")
    val XRAY_HTTPUPGRADE = keys("path", optional = setOf("host"))

    // echConfigList 是内联的 ECH 配置，不涉及 DNS：Xray 的 ECH 自动查询需要别的键（查询服务器等），一个都不在表里。
    // 将来真要走自动查询，把那个例外键显式列在这里并在例外表里写明原因，不要放宽成「允许 dns」
    val XRAY_TLS = keys(
        optional = setOf("serverName", "alpn", "pinnedPeerCertSha256", "fingerprint", "certificates", "echConfigList"),
        forbidden = forbid("Xray 已移除，能力表不允许", "allowInsecure"),
    )
    val XRAY_CERTIFICATE = keys("usage", "certificate")
    val XRAY_REALITY = keys("publicKey", optional = setOf("serverName", "shortId", "mldsa65Verify", "fingerprint"))
    val XRAY_MUX = keys("enabled", "concurrency", optional = setOf("xudpConcurrency", "xudpProxyUDP443"))

    // ================= mihomo 的表 =================

    // 顶层：listeners / proxies / rules 加必要的模式设置；控制器（external-controller + secret）只在测速时出现，单独断言
    val MIHOMO_TOP = keys(
        "log-level", "mode", "listeners", "proxies", "rules",
        optional = setOf("external-controller", "secret"),
        forbidden = forbid("DNS 由 sing-box 接管", "dns", "hosts") +
            forbid("不启用 tun", "tun") +
            forbid("不嗅探", "sniffer") +
            forbid("分流由 sing-box 负责", "proxy-groups", "rule-providers", "proxy-providers", "sub-rules", "tunnels") +
            forbid("不下载地理数据", "geodata-mode", "geox-url", "geo-auto-update") +
            forbid("只许 listeners 绑定端口，不开别的入口", "port", "socks-port", "mixed-port", "redir-port", "tproxy-port", "allow-lan", "bind-address") +
            forbid("认证只写在各 listener 的 users 上", "authentication", "skip-auth-prefixes", "lan-allowed-ips", "lan-disallowed-ips") +
            forbid("不改出口网卡或路由标记", "interface-name", "routing-mark") +
            forbid("不写 profile、ntp、experimental 这类杂项", "profile", "ntp", "experimental", "external-ui", "external-controller-tls"),
    )
    val MIHOMO_LOG_LEVELS = setOf("warning", "info", "debug")

    // listener 只能是本机 socks，用 proxy 字段固定绑定到自己的代理（不经 rules）
    val MIHOMO_LISTENER = keys(
        "name", "type", "listen", "port", "udp", "proxy", "users",
        forbidden = forbid("listener 不挂自己的规则", "rule"),
    )
    val MIHOMO_LISTENER_TYPES = setOf("socks")
    val MIHOMO_USER = keys("username", "password")

    // 代理按类型，键集各写各的；除下面点名的，dialer-proxy 之类会引入额外拨号路径的键一律不许
    private val MIHOMO_PROXY_FORBIDDEN = forbid(
        "会引入额外拨号路径或改变出口", "dialer-proxy", "interface-name", "routing-mark", "ip-version", "tfo", "mptcp", "smux",
    )
    val MIHOMO_PROXIES: Map<String, Keys> = mapOf(
        "anytls" to keys(
            "name", "type", "server", "port", "password", "udp",
            optional = setOf("sni", "alpn", "fingerprint", "skip-cert-verify", "client-fingerprint", "ech-opts"),
            forbidden = MIHOMO_PROXY_FORBIDDEN,
        ),
    )

    // ech-opts：带 config 是内联配置；只有 enable 时 mihomo 自己去查 DNS，是计划里说明的 ECH 的 DNS 例外，
    // 命中时记进 InvariantReport.exceptions，由调用方核对是哪些场景
    val MIHOMO_ECH_OPTS = keys("enable", optional = setOf("config"))
    const val ECH_DNS_EXCEPTION = "mihomo ech-opts 只有 enable、没有内联 config：mihomo 自己查 DNS 取 ECH 配置"

    // ================= 入口 =================

    /** 检查一份合并后的 Xray 配置（JSON 文本）。 */
    @Suppress("UNCHECKED_CAST")
    fun xray(text: String): InvariantReport {
        val out = ArrayList<String>()
        val root = runCatching { JsonParser.parseString(text) }.getOrNull()
        if (root == null || !root.isJsonObject) return InvariantReport(listOf("不是 JSON 对象"), emptyList())
        XrayCheck(out).config(toTree(root) as Map<String, Any?>)
        return InvariantReport(out, emptyList())
    }

    /** 检查一份合并后的 mihomo 配置（YAML 文本）。 */
    @Suppress("UNCHECKED_CAST")
    fun mihomo(text: String, controller: ControllerExpectation): InvariantReport {
        val out = ArrayList<String>()
        val exceptions = ArrayList<String>()
        val root = runCatching { normalize(Yaml().load<Any?>(text)) }.getOrNull()
        if (root !is Map<*, *>) return InvariantReport(listOf("不是 YAML 映射"), emptyList())
        MihomoCheck(out, exceptions).config(root as Map<String, Any?>, controller)
        return InvariantReport(out, exceptions)
    }

    // ================= 公共：键集与树 =================

    // JSON 与 YAML 都转成 Map / List / String / Long / Double / Boolean / null，检查逻辑只面对这一种树
    private fun toTree(element: JsonElement): Any? = when {
        element.isJsonNull -> null
        element is JsonObject -> element.entrySet().associateTo(LinkedHashMap()) { (k, v) -> k to toTree(v) }
        element is JsonArray -> element.map { toTree(it) }
        else -> element.asJsonPrimitive.let { p ->
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asString.toLongOrNull() ?: p.asDouble
                else -> p.asString
            }
        }
    }

    private fun normalize(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associateTo(LinkedHashMap()) { (k, v) -> k.toString() to normalize(v) }
        is List<*> -> value.map { normalize(it) }
        is Int -> value.toLong()
        else -> value
    }

    private abstract class Walker(val out: MutableList<String>) {

        fun bad(path: String, message: String) {
            out += "$path：$message"
        }

        fun isInt(value: Any?) = value is Long

        @Suppress("UNCHECKED_CAST")
        fun obj(path: String, value: Any?): Map<String, Any?>? {
            if (value is Map<*, *>) return value as Map<String, Any?>
            bad(path, "不是对象")
            return null
        }

        @Suppress("UNCHECKED_CAST")
        fun list(path: String, value: Any?): List<Any?>? {
            if (value is List<*>) return value as List<Any?>
            bad(path, "不是数组")
            return null
        }

        // 对象的键集：缺必填、有禁用、有表外的键都算违例
        fun checkKeys(path: String, map: Map<String, Any?>, spec: Keys) {
            for (key in spec.required) if (key !in map) bad(path, "缺少键 $key")
            for (key in map.keys) {
                if (key in spec.required || key in spec.optional) continue
                val reason = spec.forbidden[key]
                if (reason != null) bad(path, "出现了禁用的键 $key（$reason）") else bad(path, "出现了表外的键 $key")
            }
        }

        // 取一级对象并按键集检查，返回它（键集有问题时也返回，让取值约束继续查）
        fun objectWithKeys(path: String, value: Any?, spec: Keys): Map<String, Any?>? =
            obj(path, value)?.also { checkKeys(path, it, spec) }

        fun requireEquals(path: String, actual: Any?, expected: Any?) {
            if (actual != expected) bad(path, "是 $actual，应为 $expected")
        }

        fun stringList(path: String, value: Any?, min: Int = 1) {
            val items = list(path, value) ?: return
            if (items.size < min || items.any { it !is String }) bad(path, "应是至少 $min 项的字符串数组")
        }
    }

    // ================= Xray =================

    private class XrayCheck(out: MutableList<String>) : Walker(out) {

        fun config(config: Map<String, Any?>) {
            checkKeys("顶层", config, XRAY_TOP)
            config["log"]?.let { log(it) }
            val inbounds = config["inbounds"]?.let { list("inbounds", it) }.orEmpty()
            val outbounds = config["outbounds"]?.let { list("outbounds", it) }.orEmpty()
            val tags = ArrayList<String>()
            outbounds.forEachIndexed { i, outbound ->
                val tag = obj("outbounds[$i]", outbound)?.get("tag") as? String
                if (tag != null) tags += tag
            }
            val inboundTags = inbounds.mapIndexedNotNull { i, inbound -> inbound(i, inbound) }
            outbounds.forEachIndexed { i, outbound -> outbound(i, outbound) }
            if (outbounds.isEmpty()) bad("outbounds", "没有出站，第一项应是 blackhole")
            config["routing"]?.let { routing(it, inboundTags, tags) }
        }

        fun log(value: Any?) {
            val log = objectWithKeys("log", value, XRAY_LOG) ?: return
            if (log["loglevel"] !in XRAY_LOG_LEVELS) bad("log.loglevel", "是 ${log["loglevel"]}，应在 $XRAY_LOG_LEVELS 里")
            requireEquals("log.access", log["access"], "none")
        }

        fun inbound(i: Int, value: Any?): String? {
            val path = "inbounds[$i]"
            val inbound = objectWithKeys(path, value, XRAY_INBOUND) ?: return null
            requireEquals("$path.listen", inbound["listen"], LOCALHOST)
            requireEquals("$path.protocol", inbound["protocol"], "socks")
            if (!isInt(inbound["port"])) bad("$path.port", "不是整数")
            val settings = inbound["settings"]?.let { objectWithKeys("$path.settings", it, XRAY_INBOUND_SETTINGS) }
            if (settings != null) {
                // auth 必须正好是小写的 "password"：别的值 run -test 照样通过，运行时却不要求认证
                requireEquals("$path.settings.auth", settings["auth"], "password")
                requireEquals("$path.settings.udp", settings["udp"], true)
                val accounts = settings["accounts"]?.let { list("$path.settings.accounts", it) }
                if (accounts != null) {
                    if (accounts.size != 1) bad("$path.settings.accounts", "有 ${accounts.size} 项，应恰好一项")
                    accounts.forEachIndexed { n, account ->
                        objectWithKeys("$path.settings.accounts[$n]", account, XRAY_ACCOUNT)?.let { a ->
                            if (a["user"] !is String || a["pass"] !is String) bad("$path.settings.accounts[$n]", "user / pass 应是字符串")
                        }
                    }
                }
            }
            return inbound["tag"] as? String
        }

        fun routing(value: Any?, inboundTags: List<String>, outboundTags: List<String>) {
            val routing = objectWithKeys("routing", value, XRAY_ROUTING) ?: return
            requireEquals("routing.domainStrategy", routing["domainStrategy"], "AsIs")
            val rules = routing["rules"]?.let { list("routing.rules", it) } ?: return
            // 每个入站恰有一条规则，规则只按入站 tag 把它指到自己的出站（不是 blackhole）
            val bound = ArrayList<String>()
            rules.forEachIndexed { i, rule ->
                val path = "routing.rules[$i]"
                val r = objectWithKeys(path, rule, XRAY_RULE) ?: return@forEachIndexed
                requireEquals("$path.type", r["type"], "field")
                val tag = r["inboundTag"]
                val tagList = (tag as? List<*>)?.filterIsInstance<String>()
                if (tag !is List<*> || tagList?.size != 1 || tag.size != 1) {
                    bad("$path.inboundTag", "应是恰好一项的字符串数组")
                } else {
                    bound += tagList[0]
                    if (tagList[0] !in inboundTags) bad("$path.inboundTag", "指向不存在的入站 ${tagList[0]}")
                }
                val target = r["outboundTag"]
                if (target !is String || target !in outboundTags) bad("$path.outboundTag", "指向不存在的出站 $target")
                if (target == XRAY_BLOCK_TAG) bad("$path.outboundTag", "不能指向 blackhole")
            }
            if (bound.sorted() != inboundTags.sorted()) bad("routing.rules", "规则绑定的入站 $bound 与入站 $inboundTags 不是一一对应")
        }

        fun outbound(i: Int, value: Any?) {
            val path = "outbounds[$i]"
            val outbound = obj(path, value) ?: return
            if (i == 0) {
                // 第一项固定是 blackhole，其余协议都不许出现在这里
                checkKeys(path, outbound, XRAY_BLOCK_OUTBOUND)
                requireEquals("$path.tag", outbound["tag"], XRAY_BLOCK_TAG)
                requireEquals("$path.protocol", outbound["protocol"], "blackhole")
                return
            }
            checkKeys(path, outbound, XRAY_OUTBOUND)
            val protocol = outbound["protocol"]
            val spec = XRAY_OUTBOUND_PROTOCOLS[protocol]
            if (spec == null) {
                bad("$path.protocol", "是 $protocol，只许 ${XRAY_OUTBOUND_PROTOCOLS.keys}（blackhole 只许第一项）")
            } else {
                settings(path, outbound["settings"], spec)
            }
            outbound["streamSettings"]?.let { stream("$path.streamSettings", it) }
            outbound["mux"]?.let { mux("$path.mux", it) }
        }

        // settings：只有一个节点，没有 flow（trojan）等多余写法
        fun settings(path: String, value: Any?, spec: XrayOutboundSpec) {
            val settings = objectWithKeys("$path.settings", value, spec.settings) ?: return
            val entries = settings[spec.listKey]?.let { list("$path.settings.${spec.listKey}", it) } ?: return
            if (entries.size != 1) bad("$path.settings.${spec.listKey}", "有 ${entries.size} 项，应恰好一项")
            entries.forEachIndexed { n, entry ->
                val at = "$path.settings.${spec.listKey}[$n]"
                val e = objectWithKeys(at, entry, spec.entry) ?: return@forEachIndexed
                if (e["address"] !is String || e["address"] == "") bad("$at.address", "不是非空字符串")
                if (!isInt(e["port"])) bad("$at.port", "不是整数")
                if ("password" in spec.entry.required && (e["password"] !is String || e["password"] == "")) {
                    bad("$at.password", "不是非空字符串")
                }
                val userKey = spec.userKey ?: return@forEachIndexed
                val users = e[userKey]?.let { list("$at.$userKey", it) } ?: return@forEachIndexed
                if (users.size != 1) bad("$at.$userKey", "有 ${users.size} 项，应恰好一项")
                users.forEachIndexed { u, user ->
                    objectWithKeys("$at.$userKey[$u]", user, spec.user!!)?.let { m ->
                        spec.checkUser(m).forEach { bad("$at.$userKey[$u]", it) }
                    }
                }
            }
        }

        fun stream(path: String, value: Any?) {
            val stream = objectWithKeys(path, value, XRAY_STREAM) ?: return
            val network = stream["network"]
            if (network !in XRAY_NETWORKS) bad("$path.network", "是 $network，只许 $XRAY_NETWORKS")
            // 传输设置只许与 network 对得上的那一项；ws / grpc / httpupgrade 必须有，tcp 只在写 http 头时有
            for ((net, key) in XRAY_TRANSPORT_KEYS) {
                if (key in stream && net != network) bad("$path.$key", "network 是 $network，不该有 $key")
                if (key !in stream && net == network && net != "tcp") bad("$path.$key", "network 是 $net，缺少 $key")
            }
            stream["tcpSettings"]?.let { tcp("$path.tcpSettings", it) }
            stream["wsSettings"]?.let { ws("$path.wsSettings", it) }
            stream["grpcSettings"]?.let { grpc ->
                objectWithKeys("$path.grpcSettings", grpc, XRAY_GRPC)?.let {
                    if (it["serviceName"] !is String) bad("$path.grpcSettings.serviceName", "不是字符串")
                }
            }
            stream["httpupgradeSettings"]?.let { upgrade ->
                objectWithKeys("$path.httpupgradeSettings", upgrade, XRAY_HTTPUPGRADE)
            }
            // security 与它的设置一一对应
            val security = stream["security"]
            if ("security" in stream && security != "tls" && security != "reality") bad("$path.security", "是 $security，只许 tls、reality")
            if ("tlsSettings" in stream && security != "tls") bad("$path.tlsSettings", "security 是 $security，不该有 tlsSettings")
            if ("realitySettings" in stream && security != "reality") bad("$path.realitySettings", "security 是 $security，不该有 realitySettings")
            if (security == "tls" && "tlsSettings" !in stream) bad("$path.tlsSettings", "security 是 tls，缺少 tlsSettings")
            if (security == "reality" && "realitySettings" !in stream) bad("$path.realitySettings", "security 是 reality，缺少 realitySettings")
            stream["tlsSettings"]?.let { tls("$path.tlsSettings", it) }
            stream["realitySettings"]?.let { reality ->
                objectWithKeys("$path.realitySettings", reality, XRAY_REALITY)
            }
        }

        fun tcp(path: String, value: Any?) {
            val tcp = objectWithKeys(path, value, XRAY_TCP) ?: return
            val header = tcp["header"]?.let { objectWithKeys("$path.header", it, XRAY_TCP_HEADER) } ?: return
            requireEquals("$path.header.type", header["type"], "http")
            val request = header["request"]?.let { objectWithKeys("$path.header.request", it, XRAY_TCP_REQUEST) } ?: return
            request["path"]?.let { stringList("$path.header.request.path", it) }
            request["headers"]?.let { headers ->
                objectWithKeys("$path.header.request.headers", headers, XRAY_TCP_HEADERS)?.let {
                    stringList("$path.header.request.headers.Host", it["Host"])
                }
            }
        }

        fun ws(path: String, value: Any?) {
            val ws = objectWithKeys(path, value, XRAY_WS) ?: return
            if (ws["path"] !is String) bad("$path.path", "不是字符串")
            ws["host"]?.let { if (it !is String) bad("$path.host", "不是字符串") }
        }

        fun tls(path: String, value: Any?) {
            val tls = objectWithKeys(path, value, XRAY_TLS) ?: return
            tls["certificates"]?.let { certificates ->
                list("$path.certificates", certificates)?.forEachIndexed { i, certificate ->
                    objectWithKeys("$path.certificates[$i]", certificate, XRAY_CERTIFICATE)?.let {
                        requireEquals("$path.certificates[$i].usage", it["usage"], "verify")
                    }
                }
            }
            tls["echConfigList"]?.let { if (it !is String || it.isEmpty()) bad("$path.echConfigList", "不是非空字符串（只许内联配置）") }
        }

        fun mux(path: String, value: Any?) {
            val mux = objectWithKeys(path, value, XRAY_MUX) ?: return
            requireEquals("$path.enabled", mux["enabled"], true)
            if (!isInt(mux["concurrency"])) bad("$path.concurrency", "不是整数")
            mux["xudpProxyUDP443"]?.let { requireEquals("$path.xudpProxyUDP443", it, "allow") }
        }
    }

    // ================= mihomo =================

    private class MihomoCheck(out: MutableList<String>, val exceptions: MutableList<String>) : Walker(out) {

        fun config(config: Map<String, Any?>, controller: ControllerExpectation) {
            checkKeys("顶层", config, MIHOMO_TOP)
            if (config["log-level"] !in MIHOMO_LOG_LEVELS) bad("log-level", "是 ${config["log-level"]}，应在 $MIHOMO_LOG_LEVELS 里")
            // 规则只有拒绝兜底：listener 靠 proxy 字段绑定，不经 rules；兜底不是 MATCH,<代理>，也没有任何用户分流规则
            requireEquals("mode", config["mode"], "rule")
            requireEquals("rules", config["rules"], listOf("MATCH,REJECT"))
            controller(config, controller)
            val proxies = config["proxies"]?.let { list("proxies", it) }.orEmpty()
            val names = proxies.mapIndexedNotNull { i, proxy -> proxy(i, proxy) }
            val listeners = config["listeners"]?.let { list("listeners", it) }.orEmpty()
            listeners.forEachIndexed { i, listener -> listener(i, listener, names) }
        }

        // external-controller 与 secret 成对，只在测速时出现；出现时必须是 127.0.0.1:<端口>
        fun controller(config: Map<String, Any?>, expectation: ControllerExpectation) {
            val address = config["external-controller"]
            val secret = config["secret"]
            if ((address == null) != (secret == null)) bad("顶层", "external-controller 与 secret 应成对出现")
            when (expectation) {
                is ControllerExpectation.Absent -> if (address != null || secret != null) bad("顶层", "不是测速，不该有 external-controller / secret")
                is ControllerExpectation.Present -> {
                    requireEquals("external-controller", address, "$LOCALHOST:${expectation.port}")
                    requireEquals("secret", secret, expectation.secret)
                }
                is ControllerExpectation.Either -> Unit
            }
            if (address != null && (address !is String || !Regex("""127\.0\.0\.1:\d+""").matches(address))) {
                bad("external-controller", "是 $address，应为 127.0.0.1:<端口>")
            }
            if (secret != null && (secret !is String || secret.isEmpty())) bad("secret", "应是非空字符串")
        }

        // 返回代理名
        fun proxy(i: Int, value: Any?): String? {
            val path = "proxies[$i]"
            val proxy = obj(path, value) ?: return null
            val spec = MIHOMO_PROXIES[proxy["type"]]
            if (spec == null) {
                bad("$path.type", "是 ${proxy["type"]}，只许 ${MIHOMO_PROXIES.keys}")
            } else {
                checkKeys(path, proxy, spec)
            }
            if (!isInt(proxy["port"])) bad("$path.port", "不是整数")
            requireEquals("$path.udp", proxy["udp"], true)
            if (proxy["server"] !is String || proxy["server"] == "") bad("$path.server", "不是非空字符串")
            proxy["ech-opts"]?.let { echOpts ->
                objectWithKeys("$path.ech-opts", echOpts, MIHOMO_ECH_OPTS)?.let { ech ->
                    requireEquals("$path.ech-opts.enable", ech["enable"], true)
                    if (ech["config"] == null) exceptions += ECH_DNS_EXCEPTION
                }
            }
            return proxy["name"] as? String
        }

        fun listener(i: Int, value: Any?, proxyNames: List<String>) {
            val path = "listeners[$i]"
            val listener = objectWithKeys(path, value, MIHOMO_LISTENER) ?: return
            if (listener["type"] !in MIHOMO_LISTENER_TYPES) bad("$path.type", "是 ${listener["type"]}，只许 $MIHOMO_LISTENER_TYPES")
            requireEquals("$path.listen", listener["listen"], LOCALHOST)
            requireEquals("$path.udp", listener["udp"], true)
            if (!isInt(listener["port"])) bad("$path.port", "不是整数")
            if (listener["proxy"] !in proxyNames) bad("$path.proxy", "指向不存在的代理 ${listener["proxy"]}")
            // users 恰好一项：为空或不写时 listener 不认证，-t 也不报错
            val users = listener["users"]?.let { list("$path.users", it) } ?: return
            if (users.size != 1) bad("$path.users", "有 ${users.size} 项，应恰好一项")
            users.forEachIndexed { n, user ->
                objectWithKeys("$path.users[$n]", user, MIHOMO_USER)?.let { u ->
                    if (u["username"] !is String || u["username"] == "" || u["password"] !is String || u["password"] == "") {
                        bad("$path.users[$n]", "用户名或密码不是非空字符串")
                    }
                }
            }
        }
    }
}
