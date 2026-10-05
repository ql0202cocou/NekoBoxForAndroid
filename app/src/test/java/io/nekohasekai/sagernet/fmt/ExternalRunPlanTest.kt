package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.XRAY_BLOCK_TAG
import io.nekohasekai.sagernet.fmt.v2ray.buildXrayConfig
import io.nekohasekai.sagernet.fmt.v2ray.buildXrayOutbound
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildMihomoConfig
import moe.matsuri.nb4a.proxy.anytls.buildMihomoProxy
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.Random

// 外核运行计划与合并配置（plan.md K0 验收第一层）：输入是手工构造的计划，不经过 ConfigBuild
class ExternalRunPlanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val settings = ExternalCoreSettings(logLevel = 1, ipv6Mode = 0, globalAllowInsecure = false)

    private fun vless(name: String, server: String) = VMessBean().apply {
        this.name = name
        serverAddress = server
        serverPort = 443
        uuid = "00000000-0000-0000-0000-000000000001"
        alterId = -1
        initializeDefaultValues()
    }

    private fun vmessWs(name: String, server: String) = VMessBean().apply {
        this.name = name
        serverAddress = server
        serverPort = 8443
        uuid = "00000000-0000-0000-0000-0000000000ff"
        alterId = 0
        initializeDefaultValues()
        type = "ws"
        path = "/ws"
        host = "cdn.example.com"
    }

    private fun anytls(name: String, server: String) = AnyTLSBean().apply {
        this.name = name
        serverAddress = server
        serverPort = 8443
        password = "fake-password-$name"
        initializeDefaultValues()
    }

    private fun trojanGo(name: String) = TrojanGoBean().apply {
        this.name = name
        serverAddress = "trojan-go.example.com"
        serverPort = 443
        password = "fake-password"
        initializeDefaultValues()
    }

    private fun naive(name: String) = NaiveBean().apply {
        this.name = name
        serverAddress = "naive.example.com"
        serverPort = 443
        username = "u"
        password = "p"
        initializeDefaultValues()
    }

    private fun mieru(name: String) = MieruBean().apply {
        this.name = name
        serverAddress = "mieru.example.com"
        serverPort = 2999
        username = "u"
        password = "p"
        initializeDefaultValues()
    }

    // 带 CA：生成时经 cacheFile 领一个临时文件
    private fun hysteria(name: String) = HysteriaBean().apply {
        this.name = name
        protocolVersion = 1
        serverAddress = "hy1.example.com"
        serverPorts = "8443"
        initializeDefaultValues()
        caText = "-----BEGIN CERTIFICATE-----\nfake\n-----END CERTIFICATE-----"
    }

    // 一个计划的本机 socks 凭据（固定种子，可复现）
    private val auth = LocalSocksAuth.generate(Random(1))

    // 按顺序编号建计划；mappingPort 是经映射时本机映射入站的端口，null 表示不映射（外核直接拨服务器）。
    // 与 ExternalRunPlan.from 一样，入站支持认证的核心的跳实例带凭据
    private class Spec(
        val bean: AbstractBean,
        val localPort: Int,
        val mappingPort: Int?,
        val chainIndex: Int = 0,
        val profileId: Long = localPort.toLong(),
    ) {
        val target: ExternalDialTarget get() = mappingPort?.let { ExternalDialTarget.Mapped(it) } ?: ExternalDialTarget.Direct
    }

    private fun hop(i: Int, spec: Spec) = ExternalHop(
        i, spec.chainIndex, spec.profileId, spec.bean, spec.localPort, spec.target,
        localAuth = auth.takeIf { externalCore(spec.bean)!!.inboundAuth },
    )

    private fun plan(vararg specs: Spec) = ExternalRunPlan(specs.mapIndexed { i, spec ->
        // 过渡：插件核心的生成器仍从 bean 读这两个字段，下一个提交移除
        spec.bean.finalAddress = spec.target.dialAddress(spec.bean)
        spec.bean.finalPort = spec.target.dialPort(spec.bean)
        hop(i, spec)
    })

    private fun noCacheFile(prefix: String, ext: String): File = error("不应申请临时文件：$prefix.$ext")

    private fun ExternalRunPlan.configs(controller: Pair<Int, String>? = null) =
        assemble(::noCacheFile, controller, settings).map { it.config }

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    @Suppress("UNCHECKED_CAST")
    private fun yaml(text: String): Map<String, Any?> = Yaml().load<Any?>(text) as Map<String, Any?>

    // ---- Xray

    // 核对一份合并的 Xray 配置，返回「入站端口 → 绑定的出站拨向的地址端口」
    private fun checkXray(config: JsonObject): Map<Int, Pair<String, Int>> {
        val outbounds = config.getAsJsonArray("outbounds").map { it.asJsonObject }
        // 没有规则命中的流量走第一个出站：必须是 blackhole
        assertEquals(XRAY_BLOCK_TAG, outbounds[0]["tag"].asString)
        assertEquals("blackhole", outbounds[0]["protocol"].asString)
        val outboundTags = outbounds.map { it["tag"].asString }
        assertEquals("出站 tag 不重复", outboundTags.size, outboundTags.toSet().size)
        val routing = config.getAsJsonObject("routing")
        assertEquals("AsIs", routing["domainStrategy"].asString)
        val rules = routing.getAsJsonArray("rules").map { it.asJsonObject }
        val inbounds = config.getAsJsonArray("inbounds").map { it.asJsonObject }
        val inboundTags = inbounds.map { it["tag"].asString }
        assertEquals("入站 tag 不重复", inboundTags.size, inboundTags.toSet().size)
        val ports = inbounds.map { it["port"].asInt }
        assertEquals("入站端口不重复", ports.size, ports.toSet().size)
        // 访问日志关掉，就绪探测不留行
        assertEquals("none", config.getAsJsonObject("log")["access"].asString)
        val bound = LinkedHashMap<Int, Pair<String, Int>>()
        for (inbound in inbounds) {
            assertEquals(LOCALHOST, inbound["listen"].asString)
            assertEquals("socks", inbound["protocol"].asString)
            // 每个入站都要求计划里的凭据：auth 正好是 "password"，accounts 恰好一项，键序 auth、accounts、udp
            val settings = inbound.getAsJsonObject("settings")
            assertEquals(listOf("auth", "accounts", "udp"), settings.keySet().toList())
            assertEquals("password", settings["auth"].asString)
            val accounts = settings.getAsJsonArray("accounts").map { it.asJsonObject }
            assertEquals(1, accounts.size)
            assertEquals(setOf("user", "pass"), accounts[0].keySet())
            assertEquals(auth.username, accounts[0]["user"].asString)
            assertEquals(auth.password, accounts[0]["pass"].asString)
            assertTrue(settings["udp"].asBoolean)
            val tag = inbound["tag"].asString
            // 每个入站恰有一条规则，规则只按入站分流（TCP 与 UDP 都走它）
            val own = rules.filter { rule -> rule.getAsJsonArray("inboundTag").map { it.asString } == listOf(tag) }
            assertEquals("入站 $tag 的规则", 1, own.size)
            assertEquals(setOf("type", "inboundTag", "outboundTag"), own[0].keySet())
            assertEquals("field", own[0]["type"].asString)
            val target = own[0]["outboundTag"].asString
            assertTrue("规则指向的出站 $target 不存在", target in outboundTags)
            assertTrue(target != XRAY_BLOCK_TAG)
            val vnext = outbounds.single { it["tag"].asString == target }
                .getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject
            bound[inbound["port"].asInt] = vnext["address"].asString to vnext["port"].asInt
        }
        assertEquals("规则数等于入站数", inbounds.size, rules.size)
        assertEquals("出站数等于入站数加 blackhole", inbounds.size + 1, outbounds.size)
        return bound
    }

    @Test
    fun `一组一个跳实例：Xray 入站、规则、出站一一绑定`() {
        val bean = vless("solo", "xray.example.com")
        val plan = plan(Spec(bean, localPort = 21000, mappingPort = 31000))
        val config = json(plan.configs().single())

        assertEquals(mapOf(21000 to (LOCALHOST to 31000)), checkXray(config))
        assertEquals("in-0", config.getAsJsonArray("inbounds")[0].asJsonObject["tag"].asString)
        val outbound = config.getAsJsonArray("outbounds")[1].asJsonObject
        assertEquals("out-0", outbound["tag"].asString)
        // 出站内容就是单个跳实例的出站，只多了 tag
        val entry = json(gson.toJson(buildXrayOutbound(bean, LOCALHOST, 31000, settings)))
        assertEquals(entry, outbound.deepCopy().apply { remove("tag") })
        assertEquals("warning", config.getAsJsonObject("log")["loglevel"].asString)
    }

    @Test
    fun `一组多个跳实例：每个跳实例独立的入站，端口与映射目标同计划`() {
        val plan = plan(
            Spec(vless("a", "a.example.com"), 21000, 31000, chainIndex = 0),
            Spec(anytls("m", "m.example.com"), 21001, 31001, chainIndex = 0),
            Spec(vmessWs("b", "b.example.com"), 21002, 31002, chainIndex = 1),
            // 首跳不经映射：外核直接拨服务器
            Spec(vless("c", "c.example.com"), 21003, null, chainIndex = 2),
        )
        val processes = plan.assemble(::noCacheFile, null, settings)
        assertEquals(listOf("xray-plugin", "mihomo-plugin"), processes.map { it.group.pluginId })
        assertEquals(listOf(0, 2, 3), processes[0].group.hops.map { it.index })

        val bound = checkXray(json(processes[0].config))
        assertEquals(
            mapOf(21000 to (LOCALHOST to 31000), 21002 to (LOCALHOST to 31002), 21003 to ("c.example.com" to 443)),
            bound,
        )
        val config = json(processes[0].config)
        assertEquals(listOf("in-0", "in-2", "in-3"), config.getAsJsonArray("inbounds").map { it.asJsonObject["tag"].asString })
        assertEquals(
            listOf(XRAY_BLOCK_TAG, "out-0", "out-2", "out-3"),
            config.getAsJsonArray("outbounds").map { it.asJsonObject["tag"].asString },
        )
        // 各跳实例自己的出站内容（传输方式）没有串
        assertEquals("ws", config.getAsJsonArray("outbounds")[2].asJsonObject.getAsJsonObject("streamSettings")["network"].asString)
        assertEquals("tcp", config.getAsJsonArray("outbounds")[1].asJsonObject.getAsJsonObject("streamSettings")["network"].asString)
    }

    // ---- mihomo

    // 核对一份合并的 mihomo 配置，返回「listener 端口 → 绑定的代理拨向的地址端口」
    @Suppress("UNCHECKED_CAST")
    private fun checkMihomo(config: Map<String, Any?>): Map<Int, Pair<String, Int>> {
        // 没绑定代理的 listener 不能走直连
        assertEquals(listOf("MATCH,REJECT"), config["rules"])
        // 认证只写在各 listener 上
        assertFalse(config.containsKey("authentication"))
        assertFalse(config.containsKey("skip-auth-prefixes"))
        val reserved = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "PASS-RULE", "COMPATIBLE", "GLOBAL")
        val listeners = config["listeners"] as List<Map<String, Any?>>
        val proxies = config["proxies"] as List<Map<String, Any?>>
        val proxyNames = proxies.map { it["name"] as String }
        val listenerNames = listeners.map { it["name"] as String }
        assertEquals("代理名不重复", proxyNames.size, proxyNames.toSet().size)
        assertEquals("listener 名不重复", listenerNames.size, listenerNames.toSet().size)
        assertTrue((proxyNames + listenerNames).none { it in reserved })
        assertTrue("标识只用 ASCII", (proxyNames + listenerNames).all { name -> name.all { it.code < 128 } })
        val ports = listeners.map { it["port"] as Int }
        assertEquals("listener 端口不重复", ports.size, ports.toSet().size)
        val bound = LinkedHashMap<Int, Pair<String, Int>>()
        for (listener in listeners) {
            assertEquals("socks", listener["type"])
            assertEquals(LOCALHOST, listener["listen"])
            assertEquals(true, listener["udp"])
            // users 恰好一项，就是计划里的凭据
            assertEquals(listOf(mapOf("username" to auth.username, "password" to auth.password)), listener["users"])
            val target = listener["proxy"] as String
            assertTrue("listener 指向的代理 $target 不存在", target in proxyNames)
            val proxy = proxies.single { it["name"] == target }
            bound[listener["port"] as Int] = proxy["server"] as String to proxy["port"] as Int
        }
        assertEquals("代理数等于 listener 数", listeners.size, proxies.size)
        return bound
    }

    @Test
    fun `一组一个跳实例：mihomo listener 绑定唯一的代理`() {
        val plan = plan(Spec(anytls("solo", "m.example.com"), 25000, 35000))
        val config = yaml(plan.configs().single())
        assertEquals(mapOf(25000 to (LOCALHOST to 35000)), checkMihomo(config))
        assertEquals("rule", config["mode"])
        assertNull(config["external-controller"])
    }

    @Test
    fun `一组多个跳实例：mihomo 每个 listener 绑定自己的代理`() {
        val plan = plan(
            Spec(anytls("a", "a.example.com"), 25000, 35000),
            Spec(vless("x", "x.example.com"), 25001, 35001),
            Spec(anytls("b", "b.example.com"), 25002, 35002, chainIndex = 1),
            Spec(anytls("c", "c.example.com"), 25003, null, chainIndex = 2),
        )
        val processes = plan.assemble(::noCacheFile, null, settings)
        assertEquals(listOf("mihomo-plugin", "xray-plugin"), processes.map { it.group.pluginId })
        val config = yaml(processes[0].config)
        assertEquals(
            mapOf(25000 to (LOCALHOST to 35000), 25002 to (LOCALHOST to 35002), 25003 to ("c.example.com" to 8443)),
            checkMihomo(config),
        )
        @Suppress("UNCHECKED_CAST")
        val proxies = config["proxies"] as List<Map<String, Any?>>
        assertEquals(listOf("out-0", "out-2", "out-3"), proxies.map { it["name"] })
        assertEquals(listOf("fake-password-a", "fake-password-b", "fake-password-c"), proxies.map { it["password"] })
        // SNI 兜底取各自的服务器地址
        assertEquals(listOf("a.example.com", "b.example.com", "c.example.com"), proxies.map { it["sni"] })
    }

    @Test
    fun `测速的 Clash API 只进 mihomo 的配置，按计划里的代理名查延迟`() {
        val plan = plan(Spec(anytls("solo", "m.example.com"), 25000, 35000), Spec(vless("x", "x.example.com"), 25001, 35001))
        val (mihomo, xray) = plan.configs(29090 to "fake-secret")
        val config = yaml(mihomo)
        assertEquals("$LOCALHOST:29090", config["external-controller"])
        assertEquals("fake-secret", config["secret"])
        assertTrue("fake-secret" !in xray && "29090" !in xray)
        assertEquals("out-0", plan.hops.single { it.profileId == 25000L }.outboundTag)
    }

    // ---- 同一个节点、分组与标识

    @Test
    fun `同一个节点出现在两条链里是两个跳实例，各有入站与映射`() {
        // 不同链各自从数据库取实体，bean 是两份；节点 id 相同
        val plan = plan(
            Spec(vless("shared", "s.example.com"), 21000, 31000, chainIndex = 0, profileId = 3),
            Spec(anytls("shared-m", "sm.example.com"), 25000, 35000, chainIndex = 0, profileId = 4),
            Spec(vless("shared", "s.example.com"), 21001, 31001, chainIndex = 1, profileId = 3),
            Spec(anytls("shared-m", "sm.example.com"), 25001, 35001, chainIndex = 1, profileId = 4),
        )
        val (xray, mihomo) = plan.configs()
        assertEquals(mapOf(21000 to (LOCALHOST to 31000), 21001 to (LOCALHOST to 31001)), checkXray(json(xray)))
        assertEquals(mapOf(25000 to (LOCALHOST to 35000), 25001 to (LOCALHOST to 35001)), checkMihomo(yaml(mihomo)))
        assertEquals(listOf(0, 2), plan.groups[0].hops.map { it.index })
        assertEquals(listOf(3L, 3L), plan.groups[0].hops.map { it.profileId })
    }

    @Test
    fun `分组：Xray 与 mihomo 各一组，插件核心每个跳实例一组，组序按首个跳实例`() {
        val plan = plan(
            Spec(anytls("m1", "m1.example.com"), 20000, 30000),
            Spec(trojanGo("t1"), 20001, 30001),
            Spec(vless("x1", "x1.example.com"), 20002, 30002),
            Spec(trojanGo("t2"), 20003, 30003, chainIndex = 1),
            Spec(anytls("m2", "m2.example.com"), 20004, 30004, chainIndex = 1),
            Spec(vless("x2", "x2.example.com"), 20005, 30005, chainIndex = 1),
        )
        assertEquals(
            listOf(
                "mihomo-plugin" to listOf(0, 4),
                "trojan-go-plugin" to listOf(1),
                "xray-plugin" to listOf(2, 5),
                "trojan-go-plugin" to listOf(3),
            ),
            plan.groups.map { group -> group.pluginId to group.hops.map { it.index } },
        )
        // 插件核心的配置就是单节点生成器的结果，格式不变
        val configs = plan.configs()
        assertEquals((plan.hops[1].bean as TrojanGoBean).buildTrojanGoConfig(20001, settings), configs[1])
        assertEquals((plan.hops[3].bean as TrojanGoBean).buildTrojanGoConfig(20003, settings), configs[3])
    }

    @Test
    fun `标识由计划序号决定，能从标识找回跳实例与节点`() {
        val plan = plan(
            Spec(vless("x", "x.example.com"), 21000, 31000, profileId = 7),
            Spec(trojanGo("t"), 21001, 31001, profileId = 8),
            Spec(anytls("m", "m.example.com"), 21002, 31002, profileId = 9),
        )
        assertEquals(listOf("in-0", "in-1", "in-2"), plan.hops.map { it.inboundTag })
        assertEquals(listOf("out-0", "out-1", "out-2"), plan.hops.map { it.outboundTag })
        assertEquals(7L, plan.hopByTag("in-0")!!.profileId)
        assertEquals(9L, plan.hopByTag("out-2")!!.profileId)
        assertEquals("m", plan.hopByTag("out-2")!!.bean.displayName())
        // 插件核心的配置不带标识
        assertNull(plan.hopByTag("in-1"))
        assertNull(plan.hopByTag(XRAY_BLOCK_TAG))
    }

    @Test
    fun `计划拒绝重复的本机端口与错位的序号`() {
        val e = assertThrows(IllegalStateException::class.java) {
            plan(Spec(vless("a", "a.example.com"), 21000, 31000), Spec(trojanGo("t"), 21000, 31001))
        }
        assertTrue(e.message!!, "share local port 21000" in e.message!!)
        val bean = vless("a", "a.example.com")
        assertThrows(IllegalArgumentException::class.java) {
            ExternalRunPlan(listOf(ExternalHop(1, 0, 1L, bean, 21000, ExternalDialTarget.Mapped(31000))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExternalRunPlan(listOf(ExternalHop(0, 0, 1L, bean, 0, ExternalDialTarget.Mapped(31000))))
        }
    }

    @Test
    fun `合并生成器拒绝重复的标识与端口`() {
        val bean = vless("a", "a.example.com")
        val entry = buildXrayOutbound(bean, LOCALHOST, 31000, settings)
        // 不经计划直接拼出两个序号相同的跳实例
        val twins = listOf(
            ExternalHop(0, 0, 1L, bean, 21000, ExternalDialTarget.Mapped(31000), localAuth = auth),
            ExternalHop(0, 1, 1L, bean, 21001, ExternalDialTarget.Mapped(31001), localAuth = auth),
        )
        assertThrows(IllegalStateException::class.java) { buildXrayConfig(twins, listOf(entry, entry), settings) }
        val samePort = listOf(
            ExternalHop(0, 0, 1L, bean, 21000, ExternalDialTarget.Mapped(31000), localAuth = auth),
            ExternalHop(1, 1, 1L, bean, 21000, ExternalDialTarget.Mapped(31001), localAuth = auth),
        )
        assertThrows(IllegalStateException::class.java) { buildXrayConfig(samePort, listOf(entry, entry), settings) }
    }

    // ---- 本机 socks 认证（plan.md K0b）

    // 构建登记的一个外核节点：凭据按核心的声明取 localAuth（与构建一样，入站不认证的核心不带）
    private fun record(id: Long, bean: AbstractBean, localPort: Int, mappingPort: Int, localAuth: LocalSocksAuth?): ExternalHopRecord {
        val core = externalCore(bean)
        // 过渡：插件核心的生成器仍从 bean 读这两个字段，下一个提交移除
        bean.finalAddress = LOCALHOST
        bean.finalPort = mappingPort
        return ExternalHopRecord(
            id, bean, core, localPort, ExternalDialTarget.Mapped(mappingPort), localAuth.takeIf { core?.inboundAuth == true },
        )
    }

    // 手工构造的构建结果：每条链是构建登记的外核节点
    private fun buildResult(localAuth: LocalSocksAuth?, vararg chains: List<ExternalHopRecord>) = ConfigBuildResult(
        "{}", chains.map { ExternalChainRecord(it) }, 1L, emptyMap(), emptyMap(), -1L, localAuth = localAuth,
    )

    @Test
    fun `计划从构建结果取凭据：合并核心的跳实例共用同一组，插件核心的没有`() {
        val result = buildResult(
            auth,
            listOf(record(1, vless("x", "x.example.com"), 21000, 31000, auth), record(2, trojanGo("t"), 21001, 31001, auth)),
            listOf(record(3, anytls("m", "m.example.com"), 21002, 31002, auth), record(4, naive("n"), 21003, 31003, auth)),
        )
        val plan = ExternalRunPlan.from(result)
        assertEquals(listOf("xray-plugin", "trojan-go-plugin", "mihomo-plugin", "naive-plugin"), plan.hops.map { it.pluginId })
        assertEquals(listOf(true, false, true, false), plan.hops.map { it.core.inboundAuth })
        assertSame(auth, plan.hops[0].localAuth)
        assertSame(auth, plan.hops[2].localAuth)
        assertNull(plan.hops[1].localAuth)
        assertNull(plan.hops[3].localAuth)
        // 生成的配置里两端是同一组：Xray / mihomo 入站要求的就是构建结果的凭据，插件核心的配置不变
        val configs = plan.assemble(::noCacheFile, null, settings).map { it.config }
        assertEquals(mapOf(21000 to (LOCALHOST to 31000)), checkXray(json(configs[0])))
        assertEquals(mapOf(21002 to (LOCALHOST to 31002)), checkMihomo(yaml(configs[2])))
        assertEquals((plan.hops[1].bean as TrojanGoBean).buildTrojanGoConfig(21001, settings), configs[1])
        for (plugin in listOf(configs[1], configs[3])) {
            assertFalse(auth.username in plugin)
            assertFalse(auth.password in plugin)
        }
    }

    @Test
    fun `核心需要认证而构建结果里没有凭据时建计划即抛错`() {
        val result = buildResult(
            null,
            listOf(record(1, trojanGo("t"), 21000, 31000, null), record(2, anytls("m", "m.example.com"), 21001, 31001, null)),
        )
        val e = assertThrows(IllegalStateException::class.java) { ExternalRunPlan.from(result) }
        assertTrue(e.message!!, "external hop 1 (mihomo-plugin) needs local socks credentials" in e.message!!)
        // 只有插件核心时不需要凭据
        val plugins = buildResult(null, listOf(record(1, trojanGo("t"), 21000, 31000, null)))
        assertNull(ExternalRunPlan.from(plugins).hops.single().localAuth)
    }

    @Test
    fun `插件核心的跳实例带凭据、或一个计划里有两组凭据时拒绝`() {
        val other = LocalSocksAuth.generate(Random(2))
        assertThrows(IllegalStateException::class.java) {
            ExternalRunPlan(listOf(ExternalHop(0, 0, 1L, trojanGo("t"), 21000, ExternalDialTarget.Mapped(31000), localAuth = auth)))
        }
        assertThrows(IllegalStateException::class.java) {
            ExternalRunPlan(listOf(ExternalHop(0, 0, 1L, vless("x", "x.example.com"), 21000, ExternalDialTarget.Mapped(31000))))
        }
        val e = assertThrows(IllegalStateException::class.java) {
            ExternalRunPlan(
                listOf(
                    ExternalHop(0, 0, 1L, vless("x", "x.example.com"), 21000, ExternalDialTarget.Mapped(31000), localAuth = auth),
                    ExternalHop(1, 0, 2L, anytls("m", "m.example.com"), 21001, ExternalDialTarget.Mapped(31001), localAuth = other),
                )
            )
        }
        // 报错里不带凭据
        for (secret in listOf(auth.username, auth.password, other.username, other.password)) {
            assertFalse(secret in e.message!!)
        }
    }

    @Test
    fun `合并生成器拿不到凭据时直接报错，生成不出不认证的配置`() {
        val xray = vless("x", "x.example.com")
        val mihomo = anytls("m", "m.example.com")
        val xrayHops = listOf(
            ExternalHop(0, 0, 1L, xray, 21000, ExternalDialTarget.Mapped(31000), localAuth = auth),
            ExternalHop(1, 0, 2L, xray, 21001, ExternalDialTarget.Mapped(31001)),
        )
        val xrayEntry = buildXrayOutbound(xray, LOCALHOST, 31000, settings)
        val e = assertThrows(IllegalStateException::class.java) {
            buildXrayConfig(xrayHops, listOf(xrayEntry, xrayEntry), settings)
        }
        assertEquals("external hop 1 (xray-plugin) has no local socks credentials", e.message)
        val mihomoHop = ExternalHop(0, 0, 1L, mihomo, 21000, ExternalDialTarget.Mapped(31000))
        assertThrows(IllegalStateException::class.java) {
            buildMihomoConfig(listOf(mihomoHop), listOf(buildMihomoProxy(mihomo, LOCALHOST, 31000, settings)), settings)
        }
    }

    // ---- 报错与调用顺序

    @Test
    fun `生成出错时带节点名，报计划顺序上第一个出错的节点`() {
        val plan = plan(
            Spec(vless("ok", "ok.example.com"), 21000, 31000),
            Spec(anytls("bad-mihomo", "m.example.com").apply { certificateFingerprint = "abc" }, 21001, 31001),
            Spec(vless("bad-xray", "x.example.com").apply { type = "quic" }, 21002, 31002),
        )
        val e = assertThrows(ProfileBuildException::class.java) { plan.configs() }
        assertEquals("bad-mihomo", e.profileName)
        assertTrue(e.message!!, e.message!!.startsWith("bad-mihomo: Invalid AnyTLS certificate fingerprint"))
    }

    @Test
    fun `beforeHop 按计划顺序在每个跳实例生成之前调用`() {
        val plan = plan(
            Spec(vless("x", "x.example.com"), 21000, 31000),
            Spec(trojanGo("t"), 21001, 31001),
            Spec(vless("bad", "x.example.com").apply { type = "quic" }, 21002, 31002),
            Spec(anytls("m", "m.example.com"), 21003, 31003),
        )
        val seen = ArrayList<Int>()
        assertThrows(ProfileBuildException::class.java) {
            plan.assemble(::noCacheFile, null, settings, beforeHop = { seen += it.index })
        }
        assertEquals("出错的跳实例之后不再继续", listOf(0, 1, 2), seen)
    }

    // ---- 共用组装入口（plan.md K0 验收：运行 / 测速 / 导出三种模式对同一计划产出相同的外核配置）

    @Test
    fun `运行、测速、导出三种调用方对同一个计划得到相同的外核配置`() {
        // 六种外核都在：Xray、mihomo 各两个跳实例，hysteria 带 CA（经 cacheFile 领临时文件）
        fun newPlan() = plan(
            Spec(vless("x1", "x1.example.com"), 21000, 31000),
            Spec(anytls("m1", "m1.example.com"), 21001, 31001),
            Spec(trojanGo("t"), 21002, 31002),
            Spec(naive("n"), 21003, 31003),
            Spec(mieru("u"), 21004, 31004, chainIndex = 1),
            Spec(hysteria("h"), 21005, 31005, chainIndex = 1),
            Spec(vmessWs("x2", "x2.example.com"), 21006, 31006, chainIndex = 2),
            Spec(anytls("m2", "m2.example.com"), 21007, 31007, chainIndex = 2),
        )

        // 三个调用方各用自己的临时文件目录；BoxInstance（运行 / 测速）另在每个跳实例之前确认插件
        class Caller(val name: String, val checksPlugins: Boolean) {
            val dir: File = tmp.newFolder(name)
            val checked = ArrayList<String>()
            fun run(plan: ExternalRunPlan) = plan.assemble(
                { prefix, ext -> File.createTempFile(prefix + "_", ".$ext", dir) },
                null,
                settings,
                beforeHop = { if (checksPlugins) checked += it.pluginId },
            )
        }

        // 临时文件路径因调用方而异，换成占位符再比
        fun Caller.configs(plan: ExternalRunPlan) = run(plan).map { process ->
            dir.listFiles().orEmpty().fold(process.config) { text, file -> text.replace(file.absolutePath, "<CA>") }
        }

        val callers = listOf(Caller("run", true), Caller("test", true), Caller("export", false))
        val plan = newPlan()
        val results = callers.map { it.configs(plan) }
        assertEquals(results[0], results[1])
        assertEquals(results[0], results[2])
        assertEquals(plan.hops.map { it.pluginId }, callers[0].checked)
        assertEquals(callers[0].checked, callers[1].checked)
        assertTrue(callers[2].checked.isEmpty())
        // 每组一份：Xray、mihomo 各一份，插件核心各一份
        assertEquals(
            listOf("xray-plugin", "mihomo-plugin", "trojan-go-plugin", "naive-plugin", "mieru-plugin", "hysteria-plugin"),
            plan.groups.map { it.pluginId },
        )
        assertEquals(6, results[0].size)
        assertTrue(results[0][5].contains("\"ca\": \"<CA>\""))
        // 新建的同一个计划（bean 也是新的）结果相同：只取决于计划与设置
        assertEquals(results[0], callers[2].configs(newPlan()))
        // 同一个计划的组与组装结果里的组是同一个对象
        callers[0].run(plan).zip(plan.groups).forEach { (process, group) -> assertSame(group, process.group) }
    }
}
