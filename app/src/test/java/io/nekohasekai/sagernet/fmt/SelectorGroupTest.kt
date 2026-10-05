package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
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
