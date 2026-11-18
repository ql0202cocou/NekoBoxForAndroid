package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

// 按选择器构建的判断（selectorGroupOf）：构建、快照采集与 selectorGroupIdOf 共用一个，这里核对判断本身，
// 以及构建结果的 selectorGroupId、快照是否采集选择器成员都与它一致
class SelectorGroupTest {

    private fun socks(id: Long, groupId: Long) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .putBean(SOCKSBean().apply {
            name = "socks-$id"
            serverAddress = "192.0.2.$id"
            serverPort = 1080
            initializeDefaultValues()
        })

    @Test
    fun `只有运行模式且分组是选择器时按选择器构建`() {
        val selector = ProxyGroup(id = 1, isSelector = true)
        val plain = ProxyGroup(id = 2)
        assertSame(selector, selectorGroupOf(selector, ConfigBuildMode.RUN))
        assertNull(selectorGroupOf(selector, ConfigBuildMode.TEST))
        assertNull(selectorGroupOf(selector, ConfigBuildMode.EXPORT))
        for (mode in ConfigBuildMode.entries) {
            assertNull(selectorGroupOf(plain, mode))
            assertNull(selectorGroupOf(null, mode))
        }
    }

    @Test
    fun `出站按分组顺序排列，同序按 id，默认仍是选中成员`() {
        val group = ProxyGroup(id = 1, isSelector = true)
        val selected = socks(1, 1).apply { userOrder = 2 }
        val first = socks(7, 1).apply { userOrder = 1 }
        val second = socks(9, 1).apply { userOrder = 1 }
        val record = ProfileRecord.of(selected)
        val source = MemoryConfigDataSource(listOf(group), listOf(selected, second, first), emptyList())
        val result = buildConfig(ConfigInput(ConfigBuildMode.RUN, record, testConfigSettings(),
            ConfigSnapshot.collect(source, record, ConfigBuildMode.RUN), emptyMap(), FakeConfigPlatform()))
        val selector = JsonParser.parseString(result.config).asJsonObject.getAsJsonArray("outbounds")
            .map { it.asJsonObject }.single { it["type"].asString == "selector" }
        assertEquals(listOf("socks-7", "socks-9", "socks-1"), selector.getAsJsonArray("outbounds").map { it.asString })
        assertEquals("socks-1", selector["default"].asString)
    }

    @Test
    fun `单跳链别名与成员共享 tag 时回调保留已选别名`() {
        val group = ProxyGroup(id = 1, isSelector = true)
        val member = socks(1, 1)
        val chain = ProxyEntity(id = 2, groupId = 1, userOrder = 2).putBean(ChainBean().apply {
            initializeDefaultValues()
            proxies = listOf(1L)
        })
        val source = MemoryConfigDataSource(listOf(group), listOf(member, chain), emptyList())
        val main = ProfileRecord.of(chain)
        val result = buildConfig(ConfigInput(ConfigBuildMode.RUN, main, testConfigSettings(),
            ConfigSnapshot.collect(source, main, ConfigBuildMode.RUN), emptyMap(), FakeConfigPlatform()))
        val tag = result.profileTagMap.getValue(1)
        assertEquals(tag, result.profileTagMap.getValue(2))
        assertEquals(2L, result.profileIdForTag(tag, 2))
        assertEquals(1L, result.profileIdForTag(tag, 1))
        assertEquals(1L, result.profileIdForTag(tag, 999))
        assertNull(result.profileIdForTag("missing", 1))
        // 没有匹配别名时也不能由 map 的插入顺序决定。
        val reversed = ConfigBuildResult(result.config, result.externalChains, result.mainEntId,
            result.traffic, linkedMapOf(2L to tag, 1L to tag), result.selectorGroupId)
        assertEquals(1L, reversed.profileIdForTag(tag, 999))
    }

    @Test
    fun `构建结果的 selectorGroupId 与快照采集的成员都跟着同一个判断`() {
        for (isSelector in listOf(true, false)) {
            for (mode in ConfigBuildMode.entries) {
                val group = ProxyGroup(id = 1, isSelector = isSelector)
                val main = socks(1, 1)
                val source = MemoryConfigDataSource(listOf(group), listOf(main, socks(2, 1)), emptyList())
                val record = ProfileRecord.of(main)
                val snapshot = ConfigSnapshot.collect(source, record, mode)
                val settings = testConfigSettings(enableClashAPI = false)
                val result = buildConfig(ConfigInput(mode, record, settings, snapshot, emptyMap(), FakeConfigPlatform()))

                val expected = selectorGroupOf(group, mode)
                val at = "isSelector=$isSelector mode=$mode"
                assertEquals(at, expected?.id ?: -1L, result.selectorGroupId)
                if (expected != null) {
                    // 选择器成员 2 进了引用闭包，也成了选择器里的一项
                    assertEquals(at, 2L, snapshot.profile(2)!!.id)
                    assertEquals(at, setOf(1L, 2L), result.profileTagMap.keys)
                } else {
                    // 不按选择器构建时成员不采集，查它就是采集范围之外
                    assertThrows(at, ConfigInputScopeException::class.java) { snapshot.profile(2) }
                }
            }
        }
    }
}
