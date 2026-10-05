package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// 构建查了采集范围之外的输入（闭包算法的缺漏）时整次构建失败：不能被选择器成员 / 路由目标的预检当成坏节点跳过，
// 也不能被包成某个节点的错误
class ConfigInputScopeTest {

    private fun socks(id: Long, groupId: Long) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .putBean(SOCKSBean().apply {
            name = "socks-$id"
            serverAddress = "192.0.2.$id"
            serverPort = 1080
            initializeDefaultValues()
        })

    // 选择器分组 1：主节点 1 与链 3（成员是分组 2 里的节点 5）
    private val main = socks(1, 1)
    private val source = MemoryConfigDataSource(
        groups = listOf(ProxyGroup(id = 1, isSelector = true), ProxyGroup(id = 2)),
        profiles = listOf(
            main,
            ProxyEntity(id = 3, groupId = 1, userOrder = 3).putBean(ChainBean().apply {
                name = "chain-3"
                proxies = listOf(5L)
                initializeDefaultValues()
            }),
            socks(5, 2),
        ),
        rules = emptyList(),
    )

    private fun input(snapshot: ConfigSnapshot, platform: ConfigPlatform) = ConfigInput(
        ConfigBuildMode.RUN, ProfileRecord.of(main), testConfigSettings(), snapshot, emptyMap(), platform,
    )

    private fun collect() = ConfigSnapshot.collect(source, ProfileRecord.of(main), ConfigBuildMode.RUN)

    @Test
    fun `完整的快照照常构建，链成员进选择器`() {
        val result = buildConfig(input(collect(), FakeConfigPlatform()))
        assertEquals(setOf(1L, 3L), result.profileTagMap.keys)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `快照漏采了成员引用的节点时整次构建失败，不产生跳过成员的诊断`() {
        val snapshot = collect()
        // 模拟闭包算法漏采：从已采集的节点表里去掉链 3 的成员 5
        @Suppress("UNCHECKED_CAST")
        val profiles = ConfigSnapshot::class.java.getDeclaredField("profiles")
            .apply { isAccessible = true }.get(snapshot) as MutableMap<Long, ProfileRecord?>
        profiles.remove(5L)
        val platform = FakeConfigPlatform()
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val e = assertThrows(ConfigInputScopeException::class.java) {
            buildConfig(input(snapshot, platform), diagnostics)
        }
        assertEquals("profile 5 is not in the config snapshot", e.message)
        assertTrue("不能记成跳过的成员：$diagnostics", diagnostics.isEmpty())
        assertTrue("不能告「成员被跳过」：${platform.warnings}", platform.warnings.isEmpty())
    }

    @Test
    fun `规则里的包名不在输入的 UID 表里时整次构建失败，不当成应用未安装`() {
        val rules = listOf(
            RuleEntity(id = 1, name = "rule-1", userOrder = 1, enabled = true, domains = "r1.example.com", outbound = 0),
            RuleEntity(
                id = 2, name = "apps", userOrder = 2, enabled = true, domains = "r2.example.com", outbound = 0,
                packages = setOf("com.example.listed", "com.example.unlisted"),
            ),
        )
        val withRules = MemoryConfigDataSource(listOf(ProxyGroup(id = 1)), listOf(main), rules)
        val record = ProfileRecord.of(main)
        val snapshot = ConfigSnapshot.collect(withRules, record, ConfigBuildMode.RUN)
        val platform = FakeConfigPlatform()
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        // 外壳只漏解析了一个包名（另一个解析到了：未安装也会记成 null，照样在表里）
        val input = ConfigInput(
            ConfigBuildMode.RUN, record, testConfigSettings(), snapshot, mapOf("com.example.listed" to null), platform,
        )
        val e = assertThrows(ConfigInputScopeException::class.java) { buildConfig(input, diagnostics) }
        assertEquals("package com.example.unlisted is not in the config input", e.message)
        assertTrue("不能记成应用未安装：$diagnostics", diagnostics.isEmpty())
        assertTrue("不能告「应用未安装」：${platform.warnings}", platform.warnings.isEmpty())
    }

    @Test
    fun `withProfileName 原样抛出采集范围之外的异常`() {
        val scope = ConfigInputScopeException("profile 9 is not in the config snapshot")
        val thrown = assertThrows(ConfigInputScopeException::class.java) {
            withProfileName(main.requireBean()) { throw scope }
        }
        assertSame(scope, thrown)
        // 对照：普通异常照常包上节点名
        assertThrows(ProfileBuildException::class.java) {
            withProfileName(main.requireBean()) { throw IllegalStateException("bad") }
        }
    }
}
