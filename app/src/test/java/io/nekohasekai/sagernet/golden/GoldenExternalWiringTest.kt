package io.nekohasekai.sagernet.golden

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.LOCALHOST
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.URI

// 直接在基线上核对 sing-box 与外核两端接得上（plan.md K0 验收第二层）：每个场景每种模式里，sing-box 配置中
// 每个指向本机的 socks 出站端口，都恰好是某份外核配置里一个入站的端口（反过来每个外核入站也恰好被一个 socks 出站用到）；
// 该入站绑定的出站拨向的地址端口等于这个跳实例的映射目标；映射目标是本机时，sing-box 配置里有监听这个端口的映射入站。
// 认证上也要一致（K0b）：入站支持认证的合并核心（Xray、mihomo）的跳实例两端都有凭据且相同、非空，Xray 入站的 auth 是
// "password" 且 accounts 恰好一项，mihomo listener 的 users 恰好一项；插件核心的跳实例两端都没有；同一份 sing-box 配置里
// 本机凭据只有一组。运行 / 测速按 result.json 的 external 逐个跳实例核对（含记录的凭据）；导出没有这份记录，外核配置
// 从 export.txt 的各段里取
class GoldenExternalWiringTest {

    // 外核配置里的一个入站：监听端口、绑定的出站拨向的地址与端口（端口不是单个数字时为 null，如 hysteria 的端口跳跃），
    // 以及入站要求的凭据（不认证为 null）
    private class Inbound(
        val core: String,
        val port: Int,
        val address: String,
        val dialPort: Int?,
        val inboundTag: String? = null,
        val outboundTag: String? = null,
        val auth: Pair<String, String>? = null,
    )

    // 入站支持本机认证的核心
    private val authCores = setOf("xray-plugin", "mihomo-plugin")

    private val failures = ArrayList<String>()
    private var checkedSocks = 0
    private var checkedHops = 0
    private var checkedExportSegments = 0
    private var checkedAuthSocks = 0
    private var checkedPlainSocks = 0

    private fun expect(condition: Boolean, where: String, message: () -> String) {
        if (!condition) failures += "$where：${message()}"
    }

    @Test
    fun `sing-box 的本机 socks 出站与外核入站、外核出站与映射入站两端一致`() {
        val baseline = GoldenBaseline.load()
        for (id in baseline.scenarioIds) {
            val dir = baseline.root.resolve("scenarios/$id")
            for (mode in GoldenBoxMode.entries) {
                val groups = baseline.externalGroups(id, mode)
                val modeDir = File(dir, mode.dir)
                if (!File(modeDir, "sing-box.json").isFile) continue
                val singBox = JsonParser.parseString(File(modeDir, "sing-box.json").readText()).asJsonObject
                val configs = groups.map { group -> group to File(modeDir, group.file).readText() }
                checkRunOrTest("$id/$mode", singBox, configs)
            }
            val export = File(dir, "export/export.txt")
            if (export.isFile) checkExport("$id/export", export.readText())
        }
        assertTrue(failures.take(30).joinToString("\n", "两端对不上 ${failures.size} 处：\n"), failures.isEmpty())
        assertTrue("基线里应有接外核的 socks 出站", checkedSocks > 0)
        assertTrue("基线里应有跳实例", checkedHops > 0)
        assertTrue("基线里应有导出的外核配置", checkedExportSegments > 0)
        assertTrue("基线里应有带认证的 socks 出站", checkedAuthSocks > 0)
        assertTrue("基线里应有不带认证的 socks 出站（插件核心）", checkedPlainSocks > 0)
    }

    private fun checkRunOrTest(where: String, singBox: JsonObject, configs: List<Pair<GoldenExternalGroup, String>>) {
        val inbounds = ArrayList<Inbound>()
        for ((group, text) in configs) {
            val at = "$where ${group.file}"
            val own = parseExternal(at, group.pluginId, text)
            inbounds += own
            expect(own.size == group.hops.size, at) { "${own.size} 个入站，记录了 ${group.hops.size} 个跳实例" }
            for (hop in group.hops) {
                checkedHops++
                val inbound = own.singleOrNull { it.port == hop.port }
                if (inbound == null) {
                    expect(false, at) { "跳实例 ${hop.index} 的本机端口 ${hop.port} 没有入站" }
                    continue
                }
                expect(inbound.address == hop.finalAddress, at) {
                    "跳实例 ${hop.index} 拨向 ${inbound.address}，映射目标是 ${hop.finalAddress}"
                }
                // hysteria 1 免映射时按节点的 serverPorts 拨号（可能是端口跳跃），finalPort 不参与拨号
                val skipPort = group.pluginId == "hysteria-plugin" && hop.finalAddress != LOCALHOST
                expect(skipPort || inbound.dialPort == hop.finalPort, at) {
                    "跳实例 ${hop.index} 拨向端口 ${inbound.dialPort}，映射目标是 ${hop.finalPort}"
                }
                expect(inbound.inboundTag == hop.inboundTag && inbound.outboundTag == hop.outboundTag, at) {
                    "跳实例 ${hop.index} 的标识是 ${inbound.inboundTag} / ${inbound.outboundTag}，记录的是 ${hop.inboundTag} / ${hop.outboundTag}"
                }
                // 记录的凭据就是入站要求的（不在消息里写出值）
                expect(inbound.auth == hop.localAuth?.let { it.username to it.password }, at) {
                    "跳实例 ${hop.index} 入站要求的凭据与记录的不一致（入站${if (inbound.auth == null) "不" else ""}认证，" +
                        "记录${if (hop.localAuth == null) "没有" else "有"}凭据）"
                }
            }
        }
        checkWiring(where, singBox, inbounds)
    }

    private fun checkExport(where: String, text: String) {
        val segments = text.split("\n\n")
        val singBox = JsonParser.parseString(segments[0]).asJsonObject
        val inbounds = ArrayList<Inbound>()
        val cores = ArrayList<String>()
        segments.drop(1).forEachIndexed { i, segment ->
            val at = "$where export.txt#${i + 1}"
            val core = detectCore(segment)
            if (core == null) {
                expect(false, at) { "认不出是哪个外核的配置" }
                return@forEachIndexed
            }
            cores += core
            checkedExportSegments++
            inbounds += parseExternal(at, core, segment)
        }
        // 合并后导出里 Xray、mihomo 各最多一段
        for (merged in listOf("xray-plugin", "mihomo-plugin")) {
            expect(cores.count { it == merged } <= 1, where) { "$merged 有 ${cores.count { it == merged }} 段" }
        }
        checkWiring(where, singBox, inbounds)
    }

    // 两端的端口对应：socks 出站 ↔ 外核入站一一对应，认证一致；外核拨本机时有对应的映射入站
    private fun checkWiring(where: String, singBox: JsonObject, inbounds: List<Inbound>) {
        val socksOutbounds = singBox.objects("outbounds").filter { it.str("type") == "socks" && it.str("server") == LOCALHOST }
        val socksPorts = socksOutbounds.map { it["server_port"].asInt }
        val mappingPorts = singBox.objects("inbounds")
            .filter { it.str("type") == "direct" && it.str("listen") == LOCALHOST && it.str("tag")?.contains("-mapping-") == true }
            .map { it["listen_port"].asInt }
        for (outbound in socksOutbounds) {
            val port = outbound["server_port"].asInt
            checkedSocks++
            val matched = inbounds.filter { it.port == port }
            expect(matched.size == 1, where) { "socks 出站端口 $port 对上 ${matched.size} 个外核入站" }
            val inbound = matched.singleOrNull() ?: continue
            val user = outbound.str("username")
            val pass = outbound.str("password")
            if (inbound.core in authCores) {
                checkedAuthSocks++
                expect(!user.isNullOrEmpty() && !pass.isNullOrEmpty(), where) { "socks 出站 $port 接 ${inbound.core}，没带凭据" }
                expect(inbound.auth != null && inbound.auth == user to pass, where) {
                    "socks 出站 $port 带的凭据与 ${inbound.core} 入站要求的不一致"
                }
            } else {
                checkedPlainSocks++
                expect(user == null && pass == null && inbound.auth == null, where) {
                    "socks 出站 $port 接 ${inbound.core}，两端都不应带凭据"
                }
            }
        }
        // 一次构建只有一组本机凭据
        val credentials = socksOutbounds.mapNotNull { o -> o.str("username")?.let { it to o.str("password") } }.distinct()
        expect(credentials.size <= 1, where) { "本机 socks 出站带了 ${credentials.size} 组不同的凭据" }
        for (inbound in inbounds) {
            val matched = socksPorts.count { it == inbound.port }
            expect(matched == 1, where) { "${inbound.core} 入站端口 ${inbound.port} 对上 $matched 个 socks 出站" }
            if (inbound.address == LOCALHOST) {
                expect(inbound.dialPort in mappingPorts, where) {
                    "${inbound.core} 入站 ${inbound.port} 拨向本机 ${inbound.dialPort}，sing-box 没有监听它的映射入站"
                }
            }
        }
    }

    private fun detectCore(text: String): String? {
        val json = runCatching { JsonParser.parseString(text) }.getOrNull()
        if (json == null || !json.isJsonObject) {
            @Suppress("UNCHECKED_CAST")
            val yaml = runCatching { Yaml().load<Any?>(text) as? Map<String, Any?> }.getOrNull()
            return if (yaml?.containsKey("listeners") == true) "mihomo-plugin" else null
        }
        val obj = json.asJsonObject
        return when {
            obj.has("routing") && obj.has("inbounds") -> "xray-plugin"
            obj.has("run_type") -> "trojan-go-plugin"
            obj.has("socks5Port") -> "mieru-plugin"
            obj.has("socks5") -> "hysteria-plugin"
            obj.has("listen") && obj.has("proxy") -> "naive-plugin"
            else -> null
        }
    }

    // 一份外核配置里的全部入站；合并配置另核对绑定关系本身（Xray 的 blackhole 兜底与逐入站规则，mihomo 的拒绝兜底）
    private fun parseExternal(where: String, core: String, text: String): List<Inbound> = when (core) {
        "xray-plugin" -> {
            val config = JsonParser.parseString(text).asJsonObject
            val outbounds = config.objects("outbounds")
            expect(outbounds.firstOrNull()?.str("protocol") == "blackhole", where) { "第一个出站不是 blackhole" }
            val rules = config.getAsJsonObject("routing")?.objects("rules").orEmpty()
            config.objects("inbounds").map { inbound ->
                expect(inbound.str("listen") == LOCALHOST && inbound.str("protocol") == "socks", where) { "入站不是本机 socks" }
                val tag = inbound.str("tag")
                val own = rules.filter { rule -> rule.getAsJsonArray("inboundTag")?.map { it.asString } == listOf(tag) }
                expect(own.size == 1, where) { "入站 $tag 有 ${own.size} 条规则" }
                val target = own.firstOrNull()?.str("outboundTag")
                val outbound = outbounds.singleOrNull { it.str("tag") == target }
                expect(outbound != null && outbound.str("protocol") != "blackhole", where) { "入站 $tag 的规则指向 $target" }
                val vnext = outbound?.getAsJsonObject("settings")?.getAsJsonArray("vnext")?.get(0)?.asJsonObject
                Inbound(
                    core, inbound["port"].asInt, vnext?.str("address").orEmpty(), vnext?.get("port")?.asInt, tag, target,
                    xrayInboundAuth("$where 入站 $tag", inbound.getAsJsonObject("settings")),
                )
            }
        }

        "mihomo-plugin" -> {
            @Suppress("UNCHECKED_CAST")
            val config = Yaml().load<Any?>(text) as Map<String, Any?>
            expect(config["rules"] == listOf("MATCH,REJECT"), where) { "rules 是 ${config["rules"]}" }
            // 认证只在各 listener 上，不写全局的
            expect("authentication" !in config && "skip-auth-prefixes" !in config, where) { "有全局的认证设置" }
            @Suppress("UNCHECKED_CAST")
            val proxies = config["proxies"] as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST")
            (config["listeners"] as List<Map<String, Any?>>).map { listener ->
                expect(listener["listen"] == LOCALHOST && listener["type"] == "socks", where) { "listener 不是本机 socks" }
                val proxy = proxies.singleOrNull { it["name"] == listener["proxy"] }
                expect(proxy != null, where) { "listener ${listener["name"]} 指向的代理 ${listener["proxy"]} 不存在" }
                Inbound(
                    core, listener["port"] as Int, proxy?.get("server") as String? ?: "", proxy?.get("port") as Int?,
                    listener["name"] as String?, listener["proxy"] as String?,
                    mihomoListenerAuth("$where listener ${listener["name"]}", listener["users"]),
                )
            }
        }

        "trojan-go-plugin" -> {
            val config = JsonParser.parseString(text).asJsonObject
            expect(config.str("local_addr") == LOCALHOST, where) { "local_addr 不是本机" }
            listOf(Inbound(core, config["local_port"].asInt, config.str("remote_addr").orEmpty(), config["remote_port"].asInt))
        }

        "mieru-plugin" -> {
            val config = JsonParser.parseString(text).asJsonObject
            val server = config.objects("profiles")[0].objects("servers")[0]
            listOf(Inbound(core, config["socks5Port"].asInt, server.str("ipAddress").orEmpty(), server.objects("portBindings")[0]["port"].asInt))
        }

        "hysteria-plugin" -> {
            val config = JsonParser.parseString(text).asJsonObject
            val listen = config.getAsJsonObject("socks5").str("listen").orEmpty()
            expect(listen.startsWith("$LOCALHOST:"), where) { "socks5 监听的是 $listen" }
            val (host, port) = splitHostPort(config.str("server").orEmpty())
            listOf(Inbound(core, listen.substringAfterLast(':').toInt(), host, port.toIntOrNull()))
        }

        "naive-plugin" -> {
            val config = JsonParser.parseString(text).asJsonObject
            val listen = config.str("listen").orEmpty()
            expect(listen.startsWith("socks://$LOCALHOST:"), where) { "listen 是 $listen" }
            val proxy = URI(config.str("proxy").orEmpty())
            // host-resolver-rules 把代理 URL 里的主机名映射到真正拨号的地址
            val rule = config.str("host-resolver-rules")?.split(' ')
            val host = proxy.host.removeSurrounding("[", "]")
            val address = if (rule != null && rule.size == 3 && rule[0] == "MAP" && rule[1].removeSurrounding("[", "]") == host) {
                rule[2].removeSurrounding("[", "]")
            } else {
                host
            }
            listOf(Inbound(core, listen.substringAfterLast(':').toInt(), address, proxy.port))
        }

        else -> emptyList<Inbound>().also { expect(false, where) { "不认识的外核 $core" } }
    }

    // Xray socks 入站要求的凭据：auth 正好是 "password"、accounts 恰好一项且用户名与密码都非空，udp 仍开着
    private fun xrayInboundAuth(where: String, settings: JsonObject?): Pair<String, String>? {
        expect(settings?.get("udp")?.asBoolean == true, where) { "udp 没有打开" }
        expect(settings?.str("auth") == "password", where) { "auth 是 ${settings?.str("auth")}" }
        val accounts = settings?.objects("accounts").orEmpty()
        expect(accounts.size == 1, where) { "accounts 有 ${accounts.size} 项" }
        val user = accounts.singleOrNull()?.str("user")
        val pass = accounts.singleOrNull()?.str("pass")
        expect(!user.isNullOrEmpty() && !pass.isNullOrEmpty(), where) { "账户的用户名或密码为空" }
        return if (user != null && pass != null) user to pass else null
    }

    // mihomo listener 要求的凭据：users 恰好一项且用户名与密码都非空
    private fun mihomoListenerAuth(where: String, users: Any?): Pair<String, String>? {
        val list = users as? List<*>
        expect(list?.size == 1, where) { if (list == null) "没有 users" else "users 有 ${list.size} 项" }
        val user = (list?.singleOrNull() as? Map<*, *>)?.get("username") as? String
        val pass = (list?.singleOrNull() as? Map<*, *>)?.get("password") as? String
        expect(!user.isNullOrEmpty() && !pass.isNullOrEmpty(), where) { "users 的用户名或密码为空" }
        return if (user != null && pass != null) user to pass else null
    }

    // "host:port" 或 "[v6]:port"；port 可能是端口跳跃的范围
    private fun splitHostPort(value: String): Pair<String, String> =
        if (value.startsWith("[")) value.substring(1, value.indexOf(']')) to value.substringAfter("]:")
        else value.substringBeforeLast(':') to value.substringAfterLast(':')

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (get(key) as? JsonArray)?.map { it.asJsonObject }.orEmpty()
}
