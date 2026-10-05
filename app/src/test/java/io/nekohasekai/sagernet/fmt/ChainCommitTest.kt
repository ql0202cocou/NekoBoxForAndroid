package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.SingBoxOptions.MyOptions
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.InvocationTargetException
import java.util.Random

// 两段式构建（规划 planChain → 提交 ConfigBuild.commitChain）在整次构建上的性质：规划失败的成员不留下任何东西；
// 每个根节点的链只展开一次；端口按跳的顺序分配，复用的最后一跳不分；规划失败时不分端口、不生成凭据；提交时分配
// 端口失败，这条链什么都不写（本次构建共用的凭据可能已经生成）
class ChainCommitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun socks(id: Long, groupId: Long = 1, userOrder: Long = id) =
        ProxyEntity(id = id, groupId = groupId, userOrder = userOrder).putBean(SOCKSBean().apply {
            name = "socks-$id"
            serverAddress = "192.0.2.$id"
            serverPort = 1080
            initializeDefaultValues()
        })

    // VLESS + TLS，自动选核走 Xray
    private fun vless(id: Long, groupId: Long = 1, userOrder: Long = id) =
        ProxyEntity(id = id, groupId = groupId, userOrder = userOrder).putBean(VMessBean().apply {
            name = "vless-$id"
            serverAddress = "x$id.example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-0000000000%02x".format(id)
            alterId = -1
            initializeDefaultValues()
            security = "tls"
            sni = "x$id.example.com"
        })

    private fun anytls(id: Long, groupId: Long = 1) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .putBean(AnyTLSBean().apply {
            name = "anytls-$id"
            serverAddress = "a$id.example.net"
            serverPort = 9443
            password = "fake-password-$id"
            initializeDefaultValues()
        })

    private fun hysteria(id: Long, groupId: Long = 1) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .putBean(HysteriaBean().apply {
            name = "hy1-$id"
            protocolVersion = 1
            serverAddress = "hy$id.example.com"
            serverPorts = "8443"
            initializeDefaultValues()
            protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
        })

    private fun trojanGo(id: Long, groupId: Long = 1) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .putBean(TrojanGoBean().apply {
            name = "trojan-go-$id"
            serverAddress = "t$id.example.org"
            serverPort = 4443
            password = "fake-password"
            initializeDefaultValues()
        })

    private fun trojan(id: Long, pinned: Boolean, groupId: Long = 1) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .putBean(TrojanBean().apply {
            name = "trojan-$id"
            serverAddress = "tj$id.example.com"
            serverPort = 443
            password = "fake-password"
            initializeDefaultValues()
            security = "tls"
            if (pinned) certificateFingerprint = "AA".repeat(32)
        })

    // members 按用户填写的顺序：首个是最先拨号的一跳
    private fun chain(id: Long, vararg members: Long, groupId: Long = 1, userOrder: Long = id) =
        ProxyEntity(id = id, groupId = groupId, userOrder = userOrder).putBean(ChainBean().apply {
            name = "chain-$id"
            proxies = members.toList()
            initializeDefaultValues()
        })

    private fun rule(id: Long, outbound: Long) =
        RuleEntity(id = id, name = "rule-$id", userOrder = id, enabled = true, domains = "r$id.example.com", outbound = outbound)

    // 每次构建的凭据生成：固定种子，记下调用次数
    private class Auths {
        private val random = Random(1)
        var calls = 0
        fun next(): LocalSocksAuth = LocalSocksAuth.generate(random).also { calls++ }
    }

    private fun input(main: ProxyEntity, source: MemoryConfigDataSource, platform: ConfigPlatform): ConfigInput {
        val record = ProfileRecord.of(main)
        val snapshot = ConfigSnapshot.collect(source, record, ConfigBuildMode.RUN)
        return ConfigInput(ConfigBuildMode.RUN, record, testConfigSettings(), snapshot, emptyMap(), platform)
    }

    private fun build(
        main: ProxyEntity,
        source: MemoryConfigDataSource,
        platform: ConfigPlatform,
        diagnostics: MutableList<ConfigBuildDiagnostic> = ArrayList(),
        auths: Auths = Auths(),
    ) = buildConfig(input(main, source, platform), diagnostics, auths::next)

    // 构建结果里与输出有关的全部内容：配置原文、外核跳实例（端口、拨号目标、凭据）、流量与选择器的映射（按节点 id）
    private fun ConfigBuildResult.outputs(): List<Any?> = listOf(
        config,
        ExternalRunPlan.from(this).hops.map { "${it.profileId}@${it.localPort}->${it.target}/${it.localAuth?.username}" },
        externalChains.map { chain -> chain.hops.map { it.profileId } },
        traffic.tags.toSortedMap(),
        profileTagMap.toSortedMap(),
        selectorGroupId,
        localAuth,
    )

    private val missingTrojanGo = mapOf("trojan-go-plugin" to IllegalStateException("plugin trojan-go-plugin is not installed"))

    @Test
    fun `规划失败的成员不留下任何输出：与去掉它的同一分组构建结果相同`() {
        // 选择器分组 1：主节点 1、Xray 成员 2、链成员 5（出口 6 Xray 通过检查，最先拨号的 7 Trojan-Go 插件未装）、Xray 成员 3
        val group1 = ProxyGroup(id = 1, isSelector = true)
        val members = listOf(socks(1, userOrder = 1), vless(2, userOrder = 2), chain(5, 7, 6, userOrder = 3), vless(3, userOrder = 4))
        val others = listOf(vless(6, groupId = 2), trojanGo(7, groupId = 2))
        val groups = listOf(group1, ProxyGroup(id = 2))

        val withBad = FakeConfigPlatform(pluginErrors = missingTrojanGo, tempDir = tmp.newFolder())
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val auths = Auths()
        val result = build(members[0], MemoryConfigDataSource(groups, members + others, emptyList()), withBad, diagnostics, auths)

        val without = FakeConfigPlatform(pluginErrors = missingTrojanGo, tempDir = tmp.newFolder())
        val referenceAuths = Auths()
        val reference = build(
            members[0], MemoryConfigDataSource(groups, members.filter { it.id != 5L } + others, emptyList()), without,
            auths = referenceAuths,
        )

        assertEquals(
            listOf(ConfigBuildDiagnostic.ProfileSkipped(5, "chain-5", "trojan-go-7: plugin trojan-go-plugin is not installed", false)),
            diagnostics,
        )
        // 坏成员的出口 6 已规划并通过检查（试生成了 Xray 配置、查了插件），仍没有分端口、没有生成凭据
        assertEquals(reference.outputs(), result.outputs())
        assertEquals(without.ports, withBad.ports)
        assertEquals(listOf(50001, 50002, 50003, 50004), withBad.ports)
        assertEquals(1, auths.calls)
        assertEquals(referenceAuths.calls, auths.calls)
        assertEquals(listOf("pluginError(xray-plugin)", "pluginError(trojan-go-plugin)"), withBad.pluginQueries)
    }

    @Test
    fun `每个成员的链只展开一次：缺成员的告警各一条`() {
        // 选择器分组 1：主节点 1、链成员 5（成员 2 与不存在的 98）。以前预检与正式构建各展开一次，告警重复
        val platform = FakeConfigPlatform()
        val result = build(
            socks(1),
            MemoryConfigDataSource(
                listOf(ProxyGroup(id = 1, isSelector = true)),
                listOf(socks(1), chain(5, 2, 98), socks(2, groupId = 2)),
                emptyList(),
            ),
            platform,
        )
        assertEquals(setOf(1L, 5L), result.profileTagMap.keys)
        assertEquals(listOf("chain profile 5 references missing profile 98, skipped"), platform.warnings)
    }

    @Test
    fun `复用判断用规划的跳数：路由规则目标只展开一次`() {
        // 规则 1 指向分组 3 的链 5（3 → 6，6 最先拨号，建成 g-6）；规则 2 指向分组 2 的节点 6：分组 2 的前置 99 不存在、
        // 落地是 8，链有两跳，不能直接复用 g-6，要提交整条链。以前预检、判断复用、正式构建各展开一次，同一条告警出现三次
        val platform = FakeConfigPlatform()
        val main = socks(1)
        val result = build(
            main,
            MemoryConfigDataSource(
                listOf(ProxyGroup(id = 1), ProxyGroup(id = 2, frontProxy = 99, landingProxy = 8), ProxyGroup(id = 3)),
                listOf(main, socks(3, groupId = 3), chain(5, 6, 3, groupId = 3), socks(6, groupId = 2), socks(8, groupId = 4)),
                listOf(rule(1, outbound = 5), rule(2, outbound = 6)),
            ),
            platform,
        )
        assertEquals(listOf("group 2 front proxy 99 no longer exists, ignored"), platform.warnings)
        assertEquals("c-5-3", result.profileTagMap[5L])
        assertEquals("c-6-8", result.profileTagMap[6L])
        // 节点 6 的链：落地 8 是出口，最先拨号的 6 复用 g-6
        assertEquals(listOf(8L, 6L), result.traffic.tags.getValue("c-6-8"))
        val outbounds = config(result).getAsJsonArray("outbounds").map { it.asJsonObject }
        assertEquals("g-6", outbounds.single { it.string("tag") == "c-6-8" }.string("detour"))
        assertEquals(1, outbounds.count { it.string("tag") == "g-6" })
    }

    @Test
    fun `端口按跳的顺序分配：每跳先本机 socks 端口、后映射端口`() {
        // 主节点是链 1：5 hysteria 1（最先拨号，免映射）→ 4 AnyTLS → 3 socks → 2 vless（出口）
        val platform = FakeConfigPlatform()
        val auths = Auths()
        val main = chain(1, 5, 4, 3, 2)
        val result = build(
            main,
            MemoryConfigDataSource(listOf(ProxyGroup(id = 1)), listOf(main, vless(2), socks(3), anytls(4), hysteria(5)), emptyList()),
            platform, auths = auths,
        )
        assertEquals(listOf(50001, 50002, 50003, 50004, 50005), platform.ports)
        assertEquals(
            listOf(
                "2@50001->Mapped(port=50002)",
                "4@50003->Mapped(port=50004)",
                "5@50005->Direct",
            ),
            ExternalRunPlan.from(result).hops.map { "${it.profileId}@${it.localPort}->${it.target}" },
        )
        // 凭据只在第一个需要它的跳实例（vless）处生成一次，AnyTLS 共用；hysteria 1 不要
        assertEquals(1, auths.calls)
        val hops = ExternalRunPlan.from(result).hops
        assertSame(hops[0].localAuth, hops[1].localAuth)
        assertEquals(null, hops[2].localAuth)
        val mapping = config(result).getAsJsonArray("inbounds").map { it.asJsonObject }
            .filter { it.string("tag")?.contains("-mapping-") == true }
            .map { "${it.string("tag")}:${it["listen_port"].asInt}" }
        assertEquals(listOf("c-0-mapping-2:50002", "c-0-mapping-4:50004"), mapping)
        assertEquals(listOf("pluginExternalAuthority(hysteria-plugin)"), platform.pluginQueries)
    }

    @Test
    fun `共享前置的选择器分组：前置只建一次，复用它的链不再分端口`() {
        // 选择器分组 1 的前置是分组 2 的 Xray 节点 9。成员按顺序：2、主节点 1、3，每条链的最先拨号都是 9：
        // 成员 2 的链建成 g-9（本机端口与映射端口），主节点与成员 3 的链复用它
        val group1 = ProxyGroup(id = 1, isSelector = true, frontProxy = 9)
        val main = socks(1, userOrder = 2)
        val platform = FakeConfigPlatform()
        val auths = Auths()
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val result = build(
            main,
            MemoryConfigDataSource(
                listOf(group1, ProxyGroup(id = 2)),
                listOf(main, socks(2, userOrder = 1), socks(3, userOrder = 3), vless(9, groupId = 2)),
                emptyList(),
            ),
            platform, diagnostics, auths,
        )
        assertTrue(diagnostics.isEmpty())
        assertEquals(listOf(50001, 50002), platform.ports)
        assertEquals(1, auths.calls)
        // 成员 2 的检查查了一次 Xray 插件；主节点不做成员检查；成员 3 用记住的结果
        assertEquals(listOf("pluginError(xray-plugin)"), platform.pluginQueries)
        assertEquals(listOf(listOf(9L), emptyList(), emptyList()), result.externalChains.map { c -> c.hops.map { it.profileId } })
        val json = config(result)
        val outbounds = json.getAsJsonArray("outbounds").map { it.asJsonObject }
        assertEquals(1, outbounds.count { it.string("tag") == "g-9" })
        for (tag in listOf("socks-1", "socks-2", "socks-3")) {
            // 每个成员的出口都经 9 的本机 socks 出站 g-9 出去
            assertEquals(tag, "g-9", outbounds.single { it.string("tag") == tag }.string("detour"))
        }
        assertEquals(1, json.getAsJsonArray("inbounds").count { it.asJsonObject.string("tag")?.contains("-mapping-") == true })
        for (id in 1L..3L) {
            assertEquals(listOf(id, 9L), result.traffic.tags.getValue("socks-$id"))
        }
    }

    @Test
    fun `复用的最后一跳在规划里照样检查：规则目标按快照里的记录跳过`() {
        // 主节点 1 是调用方的对象（没有证书指纹），快照里同 id 的记录带了不受支持的证书指纹。规则目标是分组 2 的链 5
        // （1 最先拨号 → 6 出口）：1 复用主节点建成的 proxy，但检查的是链 5 自己的那份记录，链 5 跳过
        val caller = trojan(1, pinned = false)
        val platform = FakeConfigPlatform()
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val result = build(
            caller,
            MemoryConfigDataSource(
                listOf(ProxyGroup(id = 1), ProxyGroup(id = 2)),
                listOf(trojan(1, pinned = true), chain(5, 1, 6, groupId = 2), socks(6, groupId = 2)),
                listOf(rule(1, outbound = 5)),
            ),
            platform, diagnostics,
        )
        val reason = "trojan-1: this core cannot pin certificates; clear the fingerprint or use a core that supports it"
        assertEquals(
            listOf(
                ConfigBuildDiagnostic.ProfileSkipped(5, "chain-5", reason, false),
                ConfigBuildDiagnostic.RuleOutboundMissing(1, "rule-1", 5),
            ),
            diagnostics,
        )
        // 不是选择器分组，tagMap 只记规则目标；链 5 跳过，什么都不记
        assertTrue(result.profileTagMap.isEmpty())
        assertEquals(listOf(listOf<Long>()), result.externalChains.map { c -> c.hops.map { it.profileId } })
    }

    @Test
    fun `规划在分端口与生成凭据之前：一条链里既有检查不过的跳又分不到端口时，报的是检查，凭据也没有生成`() {
        // 主节点是链 1：3 trojan（证书固定不受支持，最先拨号）→ 2 vless（出口，Xray 要凭据）。以前出口先分端口、
        // 生成凭据，分不到端口时报端口
        val failing = FailingPorts(FakeConfigPlatform(), failAt = 1)
        val auths = Auths()
        val main = chain(1, 3, 2)
        val e = assertThrows(ProfileBuildException::class.java) {
            build(
                main, MemoryConfigDataSource(listOf(ProxyGroup(id = 1)), listOf(main, vless(2), trojan(3, pinned = true)), emptyList()),
                failing, auths = auths,
            )
        }
        assertEquals("trojan-3", e.profileName)
        assertEquals(0, failing.calls)
        assertEquals(0, auths.calls)
    }

    @Test
    fun `提交时分不到端口：异常与以前相同，包上分端口的那一跳的节点名；每跳先本机端口、再凭据、后映射端口`() {
        // 主节点是链 1：3 AnyTLS（最先拨号）→ 2 vless（出口）。第 3 次分端口（AnyTLS 的本机端口）失败
        val main = chain(1, 3, 2)
        val source = MemoryConfigDataSource(listOf(ProxyGroup(id = 1)), listOf(main, vless(2), anytls(3)), emptyList())
        val failing = FailingPorts(FakeConfigPlatform(), failAt = 3)
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val auths = Auths()
        val e = assertThrows(ProfileBuildException::class.java) { build(main, source, failing, diagnostics, auths) }
        assertEquals("anytls-3", e.profileName)
        assertSame(failing.failure, e.cause)
        assertEquals(3, failing.calls)
        // 凭据已在出口 vless 处生成
        assertEquals(1, auths.calls)
        assertTrue(diagnostics.isEmpty())

        // 第 2 次分端口（出口 vless 的映射端口）失败：它的本机端口已分到，凭据已生成
        val mappedFails = FailingPorts(FakeConfigPlatform(), failAt = 2)
        val mappedAuths = Auths()
        val e2 = assertThrows(ProfileBuildException::class.java) { build(main, source, mappedFails, auths = mappedAuths) }
        assertEquals("vless-2", e2.profileName)
        assertSame(mappedFails.failure, e2.cause)
        assertEquals(1, mappedAuths.calls)
    }

    @Test
    fun `提交时分不到端口：这条链什么都不写，已提交的链不受影响`() {
        // 选择器分组 1：主节点 1、Xray 成员 2、链成员 5（出口 6 Xray、最先拨号的 7 AnyTLS）。第 5 次分端口（7 的本机端口）
        // 失败：成员 2 已提交，链 5 的端口分到一半。以前链 5 的出口此时已写进配置
        val groups = listOf(ProxyGroup(id = 1, isSelector = true), ProxyGroup(id = 2))
        val members = listOf(socks(1, userOrder = 1), vless(2, userOrder = 2), chain(5, 7, 6, userOrder = 3))
        val others = listOf(vless(6, groupId = 2), anytls(7, groupId = 2))
        val main = members[0]

        val failing = FailingPorts(FakeConfigPlatform(), failAt = 5)
        val failingAuths = Auths()
        val probe = BuildProbe(input(main, MemoryConfigDataSource(groups, members + others, emptyList()), failing), failingAuths::next)
        probe.step("buildInbounds")
        probe.step("initRoute")
        val e = assertThrows(InvocationTargetException::class.java) { probe.step("buildOutbounds") }.targetException
        assertTrue(e is ProfileBuildException)
        assertEquals("anytls-7", (e as ProfileBuildException).profileName)
        assertSame(failing.failure, e.cause)
        assertEquals(5, failing.calls)

        // 对照：同一分组去掉链成员 5，buildOutbounds 照常走完（之后另加 selector、direct、bypass 出站）
        val referenceAuths = Auths()
        val reference = BuildProbe(
            input(main, MemoryConfigDataSource(groups, members.filter { it.id != 5L } + others, emptyList()), FakeConfigPlatform()),
            referenceAuths::next,
        )
        reference.step("buildInbounds")
        reference.step("initRoute")
        reference.step("buildOutbounds")

        val referenceOutbounds = reference.json("outbounds").filter { it.string("type") !in setOf("selector", "direct") }
        assertEquals(referenceOutbounds, probe.json("outbounds"))
        assertEquals(reference.json("inbounds"), probe.json("inbounds"))
        assertEquals(reference.route(), probe.route())
        for (field in listOf(
            "selectorNames", "globalOutbounds", "tagMap", "hopNames", "groupNsDomains", "domainListDNSDirectForce",
            "bypassDNSBeans", "localAuth",
        )) {
            assertEquals(field, reference.field(field), probe.field(field))
        }
        assertEquals(reference.trafficIds(), probe.trafficIds())
        assertEquals(reference.externalHopIds(), probe.externalHopIds())
        assertEquals(referenceAuths.calls, failingAuths.calls)
    }

    // 第 failAt 次分端口时抛出 failure，其余交给 inner
    private class FailingPorts(private val inner: FakeConfigPlatform, private val failAt: Int) : ConfigPlatform by inner {
        val failure = IllegalStateException("no free local port")
        var calls = 0
        override fun newPort(): Int {
            calls++
            if (calls == failAt) throw failure
            return inner.newPort()
        }
    }

    /**
     * 构建抛错时 ConfigBuild 的中间状态从外面看不到。这里经反射新建私有的 ConfigBuild，按 build() 的次序逐段调用它的
     * 私有方法（buildInbounds、initRoute、buildOutbounds），出错后再读它的字段与 MyOptions。方法与字段改名时这里跟着改。
     */
    private class BuildProbe(input: ConfigInput, newLocalAuth: () -> LocalSocksAuth) {
        private val cls = Class.forName("io.nekohasekai.sagernet.fmt.ConfigBuild")
        private val build: Any = cls.declaredConstructors.single().let { ctor ->
            ctor.isAccessible = true
            ctor.newInstance(input, input.main.newEntity(), ArrayList<ConfigBuildDiagnostic>(), newLocalAuth)
        }
        private val options = MyOptions()

        fun step(name: String) {
            cls.getDeclaredMethod(name, MyOptions::class.java).apply { isAccessible = true }.invoke(build, options)
        }

        fun field(name: String): Any? = cls.getDeclaredField(name).apply { isAccessible = true }.get(build)

        private val map get() = JsonParser.parseString(com.google.gson.Gson().toJson(options.asMap())).asJsonObject

        fun json(key: String): List<JsonObject> = map.getAsJsonArray(key)?.map { it.asJsonObject }.orEmpty()

        fun route(): String = map.getAsJsonObject("route").toString()

        @Suppress("UNCHECKED_CAST")
        fun trafficIds() = (field("trafficMap") as Map<String, List<ProxyEntity>>).mapValues { (_, l) -> l.map { it.id } }

        @Suppress("UNCHECKED_CAST")
        fun externalHopIds() = (field("externalChains") as List<ExternalChainRecord>)
            .map { chain -> chain.hops.map { "${it.profileId}@${it.localPort}->${it.target}" } }
    }

    private fun config(result: ConfigBuildResult) = JsonParser.parseString(result.config).asJsonObject

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
