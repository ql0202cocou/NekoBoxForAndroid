package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import moe.matsuri.nb4a.domainStrategyOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// 构建输入的快照（ConfigSnapshot / ProfileRecord）：DAO 式的拷贝语义、越界即抛、引用闭包的范围
class ConfigSnapshotTest {

    private fun socks(id: Long, groupId: Long, order: Long = id) = ProxyEntity(id = id, groupId = groupId, userOrder = order)
        .putBean(SOCKSBean().apply {
            name = "socks-$id"
            serverAddress = "s$id.example.com"
            serverPort = 20000 + id.toInt()
            initializeDefaultValues()
        })

    private fun chain(id: Long, groupId: Long, vararg members: Long) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .putBean(ChainBean().apply {
            name = "chain-$id"
            proxies = members.toList()
            initializeDefaultValues()
        })

    private fun group(id: Long, front: Long = -1, landing: Long = -1, selector: Boolean = false) =
        ProxyGroup(id = id, isSelector = selector, frontProxy = front, landingProxy = landing)

    private fun rule(id: Long, outbound: Long, enabled: Boolean = true, order: Long = id, packages: Set<String> = emptySet()) =
        RuleEntity(
            id = id, name = "rule-$id", userOrder = order, enabled = enabled, domains = "r$id.example.com",
            outbound = outbound, packages = packages,
        )

    private fun collect(source: ConfigDataSource, main: ProxyEntity, mode: ConfigBuildMode) =
        ConfigSnapshot.collect(source, ProfileRecord.of(main), mode)

    // 嵌套链 + 主分组的前置（自己是链）与落地 + 跨组成员 + 缺失成员
    private fun chainsSource(): Pair<MemoryConfigDataSource, ProxyEntity> {
        val main = chain(10, 1, 11, 50, 99)
        val source = MemoryConfigDataSource(
            groups = listOf(group(1, front = 20, landing = 30), group(2, front = 40)),
            profiles = listOf(
                main, chain(11, 1, 12, 13), socks(12, 1), socks(13, 1),
                chain(20, 1, 21, 22), socks(21, 1), socks(22, 1), socks(30, 1),
                socks(40, 2), socks(50, 2),
            ),
            rules = emptyList(),
        )
        return source to main
    }

    @Test
    fun `闭包覆盖嵌套链与主分组的前置 落地，成员所在分组的前置不采`() {
        val (source, main) = chainsSource()
        val snapshot = collect(source, main, ConfigBuildMode.TEST)
        // 主节点自己不经数据源，没人引用就不在节点表里；99 是已知缺失；50 所在分组 2 的前置 40 不采
        assertEquals(setOf(11L, 12, 13, 50, 99, 20, 21, 22, 30), snapshot.profileIds)
        assertEquals(setOf(1L), snapshot.groupIds)
        assertNull(snapshot.profile(99))
        assertEquals(listOf(11L), snapshot.profiles(listOf(11, 99)).map { it.id })
        assertThrows(ConfigInputScopeException::class.java) { snapshot.profile(40) }
        assertThrows(ConfigInputScopeException::class.java) { snapshot.group(2) }
        assertThrows(ConfigInputScopeException::class.java) { snapshot.profiles(listOf(11, 40)) }
    }

    @Test
    fun `每次查询都是新的深拷贝，改返回的对象不影响下一次查询`() {
        val (source, main) = chainsSource()
        val snapshot = collect(source, main, ConfigBuildMode.TEST)
        val first = snapshot.profile(12)!!
        val second = snapshot.profile(12)!!
        assertNotSame(first, second)
        assertNotSame(first.requireBean(), second.requireBean())
        first.tx = 99
        first.requireBean().apply {
            serverAddress = "changed.example.com"
            serverPort = 1
        }
        val third = snapshot.profile(12)!!
        assertEquals(0L, third.tx)
        assertEquals("s12.example.com", third.requireBean().serverAddress)
        assertEquals(20012, third.requireBean().serverPort)

        val g1 = snapshot.group(1)!!
        g1.frontProxy = 77
        assertEquals(20L, snapshot.group(1)!!.frontProxy)
        assertNotSame(g1, snapshot.group(1))
    }

    @Test
    fun `一次批量查询里同一 id 只给一个对象，各次查询互不共享`() {
        val (source, main) = chainsSource()
        val snapshot = collect(source, main, ConfigBuildMode.TEST)
        val batch = snapshot.profiles(listOf(13, 12, 13, 12))
        assertEquals(listOf(12L, 13), batch.map { it.id })
        val again = snapshot.profiles(listOf(12))
        assertNotSame(batch[0], again[0])
        assertNotSame(batch[0].requireBean(), again[0].requireBean())
    }

    @Test
    fun `采集之后改数据源与调用方对象，不影响已采集的内容`() {
        val (source, main) = chainsSource()
        val record = ProfileRecord.of(main)
        val snapshot = ConfigSnapshot.collect(source, record, ConfigBuildMode.TEST)
        source.clear()
        (main.requireBean() as ChainBean).proxies = listOf(12)
        main.requireBean().name = "changed"
        assertEquals("socks-12", snapshot.profile(12)!!.displayName())
        assertEquals(listOf(11L, 50, 99), (record.newEntity().requireBean() as ChainBean).proxies)
        assertEquals("chain-10", record.newEntity().displayName())
    }

    @Test
    fun `记录从字节新建 bean，与调用方的对象互不影响`() {
        val entity = socks(1, 1)
        val record = ProfileRecord.of(entity)
        entity.requireBean().serverPort = 40000
        val copy = record.newEntity()
        assertNotSame(entity.requireBean(), copy.requireBean())
        assertEquals("s1.example.com", copy.requireBean().serverAddress)
        assertEquals(20001, copy.requireBean().serverPort)
        // 改拷贝也不动调用方的对象
        copy.requireBean().serverAddress = LOCALHOST
        assertEquals("s1.example.com", entity.requireBean().serverAddress)
    }

    @Test
    fun `选择器成员只在运行模式采集，规则与规则目标在测速以外采集`() {
        val main = socks(1, 1)
        val source = MemoryConfigDataSource(
            groups = listOf(group(1, selector = true), group(5, front = 6)),
            profiles = listOf(main, chain(2, 1, 3), socks(3, 3), socks(4, 1), socks(6, 5), socks(7, 5), socks(8, 5)),
            rules = listOf(
                rule(1, outbound = 7), rule(2, outbound = 0), rule(3, outbound = 98),
                rule(4, outbound = 8, enabled = false), rule(5, outbound = 1), rule(6, outbound = -1),
            ),
        )
        val run = collect(source, main, ConfigBuildMode.RUN)
        assertEquals(listOf(1L, 2, 4), run.profilesByGroup(1).map { it.id })
        assertEquals(listOf(1L, 2, 3, 5, 6), run.enabledRules().map { it.id })
        val targetIds = ruleTargetIds(run.enabledRules(), mainId = 1)
        assertEquals(setOf(7L, 98), targetIds.toSet())
        assertEquals(listOf(7L), run.ruleTargets(targetIds).map { it.id })
        assertNull(run.profile(98))
        // 主分组没设前置 / 落地时按 -1 查过，得 null
        assertNull(run.profile(-1))
        // 规则目标所在分组的前置也采；禁用规则的目标不采；成员链的成员（3）照常展开
        assertEquals(setOf(1L, 2, 3, 4, 7, 98, -1, 6), run.profileIds)
        assertEquals(setOf(1L, 5), run.groupIds)
        assertThrows(ConfigInputScopeException::class.java) { run.profile(8) }
        assertThrows(ConfigInputScopeException::class.java) { run.ruleTargets(listOf(7)) }

        val export = collect(source, main, ConfigBuildMode.EXPORT)
        assertThrows(ConfigInputScopeException::class.java) { export.profilesByGroup(1) }
        assertEquals(listOf(7L), export.ruleTargets(targetIds).map { it.id })
        assertEquals(setOf(7L, 98, -1, 6), export.profileIds)

        val before = source.queries.size
        val test = collect(source, main, ConfigBuildMode.TEST)
        assertThrows(ConfigInputScopeException::class.java) { test.profilesByGroup(1) }
        assertThrows(ConfigInputScopeException::class.java) { test.enabledRules() }
        assertThrows(ConfigInputScopeException::class.java) { test.ruleTargets(emptyList()) }
        assertEquals("测速只查主分组与它的前置 / 落地", listOf("group(1)", "profile(-1)"), source.queries.drop(before))
        assertEquals(setOf(-1L), test.profileIds)
    }

    @Test
    fun `规则目标与选择器成员保持数据源返回的顺序`() {
        val main = socks(1, 1)
        val memory = MemoryConfigDataSource(
            groups = listOf(group(1, selector = true), group(2)),
            profiles = listOf(main, socks(2, 1, order = 3), socks(3, 1, order = 2), socks(7, 2), socks(8, 2), socks(9, 2)),
            rules = listOf(rule(1, outbound = 8), rule(2, outbound = 7), rule(3, outbound = 9)),
        )
        // 数据源按 id 倒序返回 getEntities 的结果：快照不自己重新排序
        val reversed = object : ConfigDataSource by memory {
            override fun profiles(ids: List<Long>) = memory.profiles(ids).reversed()
        }
        val snapshot = collect(reversed, main, ConfigBuildMode.RUN)
        assertEquals(listOf(9L, 8, 7), snapshot.ruleTargets(ruleTargetIds(snapshot.enabledRules(), 1)).map { it.id })
        // getByGroup 按 userOrder：3 在 2 前面
        assertEquals(listOf(1L, 3, 2), snapshot.profilesByGroup(1).map { it.id })
    }

    @Test
    fun `规则拷贝互不影响，包名集合不可改`() {
        val main = socks(1, 1)
        val source = MemoryConfigDataSource(
            groups = listOf(group(1)),
            profiles = listOf(main),
            rules = listOf(rule(1, outbound = 0, packages = linkedSetOf("com.example.a", "com.example.b"))),
        )
        val snapshot = collect(source, main, ConfigBuildMode.RUN)
        val rule = snapshot.enabledRules().single()
        rule.outbound = -2
        assertEquals(0L, snapshot.enabledRules().single().outbound)
        assertEquals(listOf("com.example.a", "com.example.b"), rule.packages.toList())
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) { (rule.packages as MutableSet<String>).add("x") }
    }

    @Test
    fun `循环引用照样采集，不报错`() {
        val main = chain(1, 1, 2)
        val source = MemoryConfigDataSource(
            groups = listOf(group(1, front = 3)),
            profiles = listOf(main, chain(2, 1, 1), chain(3, 1, 3)),
            rules = emptyList(),
        )
        val snapshot = collect(source, main, ConfigBuildMode.EXPORT)
        assertEquals(setOf(1L, 2, 3, -1), snapshot.profileIds)
        // 库里那一行 1 被成员 2 引用到，按它自己的内容展开过一次
        assertEquals(listOf(2L), (snapshot.profile(1)!!.requireBean() as ChainBean).proxies)
    }

    @Test
    fun `bean 为 null 或类型未知的损坏行原样记录，报错留给构建`() {
        val main = chain(1, 1, 2, 3)
        val nullBean = ProxyEntity(id = 2, groupId = 1, type = ProxyEntity.TYPE_VMESS)
        val unknownType = ProxyEntity(id = 3, groupId = 1, type = 12345)
        val source = MemoryConfigDataSource(listOf(group(1)), listOf(main, nullBean, unknownType), emptyList())
        val snapshot = collect(source, main, ConfigBuildMode.TEST)
        val two = snapshot.profile(2)!!
        assertEquals(ProxyEntity.TYPE_VMESS, two.type)
        assertNull(two.vmessBean)
        assertEquals("Null VMess profile", assertThrows(IllegalStateException::class.java) { two.requireBean() }.message)
        val three = snapshot.profile(3)!!
        assertEquals(12345, three.type)
        assertThrows(IllegalStateException::class.java) { three.requireBean() }
        // 主节点自己 bean 为 null 时也只记录
        val brokenMain = ProxyEntity(id = 1, groupId = 1, type = ProxyEntity.TYPE_CHAIN)
        val broken = collect(source, brokenMain, ConfigBuildMode.TEST)
        assertEquals(setOf(-1L), broken.profileIds)
    }

    @Test
    fun `测速与导出不能同时为真`() {
        assertEquals(ConfigBuildMode.RUN, ConfigBuildMode.of(forTest = false, forExport = false))
        assertEquals(ConfigBuildMode.TEST, ConfigBuildMode.of(forTest = true, forExport = false))
        assertEquals(ConfigBuildMode.EXPORT, ConfigBuildMode.of(forTest = false, forExport = true))
        assertThrows(IllegalArgumentException::class.java) { ConfigBuildMode.of(forTest = true, forExport = true) }
    }

    @Test
    fun `设置快照按 tag 取 domain strategy 的分支与 domainStrategyOf 一致`() {
        val stored = mapOf(
            "domain_strategy_for_remote" to "prefer_ipv6",
            "domain_strategy_for_direct" to "auto",
            "domain_strategy_for_server" to "ipv4_only",
        )
        val settings = settings(
            remote = domainStrategyOf("dns-remote", stored::get),
            direct = domainStrategyOf("dns-direct", stored::get),
            server = domainStrategyOf("server", stored::get),
        )
        for (tag in listOf("dns-remote", "dns-direct", "server", "dns-group-0", "dns-group-7", "dns-local")) {
            assertEquals(tag, domainStrategyOf(tag, stored::get), settings.domainStrategy(tag))
        }
    }

    @Test
    fun `外核设置取原值，toString 不带 secret、DNS 服务器与自定义配置的原文`() {
        val settings = settings(remote = "", direct = "", server = "prefer_ipv4")
        assertEquals(ExternalCoreSettings(logLevel = 3, ipv6Mode = 2, globalAllowInsecure = true), settings.externalCore)
        val text = settings.toString()
        for (secret in listOf("secret-value", "dns-token", "192.0.2.53", "custom-password")) {
            assertFalse("$secret 出现在 $text", text.contains(secret))
        }
        for (field in listOf("clashApiSecret", "remoteDns", "directDns", "globalCustomConfig")) {
            assertTrue(text, text.contains("$field=***"))
        }
        // 没有值时照样看得出是空的
        val empty = settings.copy(remoteDns = "", globalCustomConfig = "", clashApiSecret = null).toString()
        assertTrue(empty, empty.contains("remoteDns=, "))
        assertTrue(empty, empty.contains("globalCustomConfig=, "))
        assertTrue(empty, empty.contains("clashApiSecret=null"))
    }

    @Test
    fun `只有运行模式且开了 Clash API 时取 secret`() {
        for (mode in ConfigBuildMode.entries) {
            assertFalse("$mode 没开 Clash API", needsClashApiSecret(mode, enableClashAPI = false))
        }
        assertTrue("运行模式开了 Clash API", needsClashApiSecret(ConfigBuildMode.RUN, enableClashAPI = true))
        assertFalse("测速的配置不带 secret", needsClashApiSecret(ConfigBuildMode.TEST, enableClashAPI = true))
        assertFalse("导出的配置不带 secret", needsClashApiSecret(ConfigBuildMode.EXPORT, enableClashAPI = true))
    }

    @Test
    fun `要解析的包名：测速不带规则，其余取启用规则里的包名，按规则顺序去重`() {
        val main = socks(1, 1)
        val source = MemoryConfigDataSource(
            groups = listOf(group(1)),
            profiles = listOf(main),
            rules = listOf(
                rule(1, outbound = 0, order = 2, packages = linkedSetOf("com.example.b", "com.example.a")),
                rule(2, outbound = 0, order = 1, packages = linkedSetOf("com.example.c", "com.example.b")),
                rule(3, outbound = 0, enabled = false, packages = linkedSetOf("com.example.disabled")),
                rule(4, outbound = 0),
            ),
        )
        val expected = listOf("com.example.c", "com.example.b", "com.example.a")
        for (mode in listOf(ConfigBuildMode.RUN, ConfigBuildMode.EXPORT)) {
            assertEquals(mode.name, expected, packagesToResolve(mode, collect(source, main, mode)).toList())
        }
        // 测速的快照没有规则，也不去查它
        assertEquals(emptySet<String>(), packagesToResolve(ConfigBuildMode.TEST, collect(source, main, ConfigBuildMode.TEST)))
        // 规则都不带应用时为空
        val noApps = MemoryConfigDataSource(listOf(group(1)), listOf(main), listOf(rule(1, outbound = 0)))
        assertEquals(emptySet<String>(), packagesToResolve(ConfigBuildMode.RUN, collect(noApps, main, ConfigBuildMode.RUN)))
    }

    private fun settings(remote: String, direct: String, server: String) = ConfigSettings(
        serviceMode = "vpn", allowAccess = false, bypassLanInCore = false,
        remoteDns = "https://dns.example.com/dns-token/dns-query", directDns = "https://192.0.2.53/dns-query",
        enableDnsRouting = true, enableFakeDns = true, trafficSniffing = 1, resolveDestination = false,
        ipv6Mode = 2, logLevel = 3, mixedPort = 2080, mtu = 9000, tunImplementation = 0,
        globalCustomConfig = "{\"outbounds\": [{\"password\": \"custom-password\"}]}",
        enableClashAPI = true, clashApiSecret = "secret-value", globalAllowInsecure = true,
        domainStrategyRemote = remote, domainStrategyDirect = direct, domainStrategyServer = server,
    )
}
