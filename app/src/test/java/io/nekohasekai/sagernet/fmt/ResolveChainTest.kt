package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// 根节点的整条链（resolveChain，ChainPlan.kt）：直接对快照调用，不经过 ConfigBuild。
// 结果是倒序的：首项是出口（落地在最前），末项是最先拨号的一跳（前置在最后）
class ResolveChainTest {

    private fun socks(id: Long, groupId: Long = 1) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .putBean(SOCKSBean().apply {
            name = "socks-$id"
            serverAddress = "192.0.2.$id"
            serverPort = 1080
            initializeDefaultValues()
        })

    private fun chain(id: Long, vararg members: Long, groupId: Long = 1) =
        ProxyEntity(id = id, groupId = groupId, userOrder = id).putBean(ChainBean().apply {
            name = "chain-$id"
            proxies = members.toList()
            initializeDefaultValues()
        })

    private fun rule(id: Long, outbound: Long) =
        RuleEntity(id = id, name = "rule-$id", userOrder = id, enabled = true, domains = "r$id.example.com", outbound = outbound)

    private val warnings = ArrayList<String>()

    // 主节点 1 所在的分组 1 是选择器：分组里的节点都是根；rules 指向的节点（可在别的分组）也是根
    private fun snapshot(groups: List<ProxyGroup>, profiles: List<ProxyEntity>, rules: List<RuleEntity> = emptyList()) =
        ConfigSnapshot.collect(
            MemoryConfigDataSource(groups, profiles, rules), ProfileRecord.of(profiles.first { it.id == 1L }),
            ConfigBuildMode.RUN,
        )

    private fun ConfigSnapshot.resolve(id: Long, mainGroup: ProxyGroup? = group(1)): List<Long> =
        profile(id)!!.resolveChain(this, 1, mainGroup) { warnings += it }.map { it.id }

    @Test
    fun `单个节点展开成自己`() {
        val data = snapshot(listOf(ProxyGroup(id = 1, isSelector = true)), listOf(socks(1)))
        assertEquals(listOf(1L), data.resolve(1))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `链按填写顺序展开并倒序：末项是最先拨号的一跳`() {
        val data = snapshot(
            listOf(ProxyGroup(id = 1, isSelector = true)),
            listOf(socks(1), socks(2, 2), socks(3, 2), socks(4, 2), chain(5, 2, 3, 4)),
        )
        assertEquals(listOf(4L, 3L, 2L), data.resolve(5))
    }

    @Test
    fun `嵌套的链逐层展开`() {
        // 链 6 = [2, 链 5, 4]，链 5 = [3, 7]：拨号顺序 2 → 3 → 7 → 4
        val data = snapshot(
            listOf(ProxyGroup(id = 1, isSelector = true)),
            listOf(socks(1), socks(2, 2), socks(3, 2), socks(4, 2), socks(7, 2), chain(5, 3, 7, groupId = 2), chain(6, 2, 5, 4)),
        )
        assertEquals(listOf(4L, 7L, 3L, 2L), data.resolve(6))
    }

    @Test
    fun `分组的前置接在最先拨号处，落地接在出口处`() {
        val data = snapshot(
            listOf(ProxyGroup(id = 1, isSelector = true, frontProxy = 8, landingProxy = 9)),
            listOf(socks(1), socks(8, 2), socks(9, 2), chain(5, 2, 3), socks(2, 2), socks(3, 2)),
        )
        assertEquals(listOf(9L, 1L, 8L), data.resolve(1))
        assertEquals(listOf(9L, 3L, 2L, 8L), data.resolve(5))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `前置与落地本身是链时展开成成员`() {
        val data = snapshot(
            listOf(ProxyGroup(id = 1, isSelector = true, frontProxy = 20, landingProxy = 30)),
            listOf(
                socks(1), socks(21, 2), socks(22, 2), socks(31, 2), socks(32, 2),
                chain(20, 21, 22, groupId = 2), chain(30, 31, 32, groupId = 2),
            ),
        )
        // 拨号顺序：前置 21 → 22 → 主节点 1 → 落地 31 → 32
        assertEquals(listOf(32L, 31L, 1L, 22L, 21L), data.resolve(1))
    }

    @Test
    fun `前置或落地是成员被删光的链时报错`() {
        val data = snapshot(
            listOf(ProxyGroup(id = 1, isSelector = true, frontProxy = 20)),
            listOf(socks(1), chain(20, 98, 99, groupId = 2)),
        )
        val e = assertThrows(IllegalStateException::class.java) { data.resolve(1) }
        assertEquals("group 1 front proxy 20 (chain-20) has no valid member", e.message)
        // 报错之前，成员缺失照常各告一次警
        assertEquals(
            listOf(
                "chain profile 20 references missing profile 98, skipped",
                "chain profile 20 references missing profile 99, skipped",
            ),
            warnings,
        )

        val landing = snapshot(
            listOf(ProxyGroup(id = 1, isSelector = true, landingProxy = 30)),
            listOf(socks(1), chain(30, 97, groupId = 2)),
        )
        val e2 = assertThrows(IllegalStateException::class.java) { landing.resolve(1) }
        assertEquals("group 1 landing proxy 30 (chain-30) has no valid member", e2.message)
    }

    @Test
    fun `缺失的成员跳过并告警，其余照常`() {
        val data = snapshot(
            listOf(ProxyGroup(id = 1, isSelector = true)),
            listOf(socks(1), socks(2, 2), socks(3, 2), chain(5, 2, 98, 3), chain(6, 5, 99)),
        )
        assertEquals(listOf(3L, 2L), data.resolve(6))
        assertEquals(
            listOf(
                "chain profile 5 references missing profile 98, skipped",
                "chain profile 6 references missing profile 99, skipped",
            ),
            warnings,
        )
        // 链的成员全部缺失时得到空列表（是否报错由整链检查决定）
        val empty = snapshot(listOf(ProxyGroup(id = 1, isSelector = true)), listOf(socks(1), chain(7, 98)))
        assertTrue(empty.resolve(7).isEmpty())
    }

    @Test
    fun `前置或落地的 id 指向已删除的节点时告警并忽略`() {
        val data = snapshot(
            listOf(ProxyGroup(id = 1, isSelector = true, frontProxy = 88, landingProxy = 99)),
            listOf(socks(1)),
        )
        assertEquals(listOf(1L), data.resolve(1))
        assertEquals(
            listOf(
                "group 1 front proxy 88 no longer exists, ignored",
                "group 1 landing proxy 99 no longer exists, ignored",
            ),
            warnings,
        )
    }

    @Test
    fun `循环引用报错`() {
        val data = snapshot(
            listOf(ProxyGroup(id = 1, isSelector = true)),
            listOf(socks(1), socks(2, 2), chain(5, 2, 6), chain(6, 5)),
        )
        val e = assertThrows(IllegalStateException::class.java) { data.resolve(5) }
        assertEquals("chain loop detected: profile 5 (chain-5)", e.message)
    }

    @Test
    fun `别的分组里的根节点用它自己分组的前置与落地，链成员所在的分组不参与`() {
        // 分组 1（主分组）前置 8；分组 2 前置 20、落地 30；分组 3 前置 40。
        // 路由规则指向分组 2 的链 5（成员 3 在分组 3）
        val data = snapshot(
            listOf(
                ProxyGroup(id = 1, isSelector = true, frontProxy = 8),
                ProxyGroup(id = 2, frontProxy = 20, landingProxy = 30),
                ProxyGroup(id = 3, frontProxy = 40),
            ),
            listOf(socks(1), socks(8, 4), socks(20, 4), socks(30, 4), socks(40, 4), socks(3, 3), chain(5, 3, groupId = 2)),
            listOf(rule(1, outbound = 5)),
        )
        assertEquals(listOf(30L, 3L, 20L), data.resolve(5))
        // 主分组的节点照常用主分组的前置
        assertEquals(listOf(1L, 8L), data.resolve(1))
    }

    @Test
    fun `主分组的节点用调用方给的那一行，不再查快照`() {
        val data = snapshot(listOf(ProxyGroup(id = 1, isSelector = true, frontProxy = 8)), listOf(socks(1), socks(8, 2)))
        // 快照里分组 1 有前置 8；传入「行不存在」时就不接前置，也不告警
        assertEquals(listOf(1L), data.resolve(1, mainGroup = null))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `每次展开都是快照的新对象`() {
        val data = snapshot(listOf(ProxyGroup(id = 1, isSelector = true, frontProxy = 8)), listOf(socks(1), socks(8, 2)))
        val root = data.profile(1)!!
        val first = root.resolveChain(data, 1, data.group(1)) {}
        val second = root.resolveChain(data, 1, data.group(1)) {}
        // 根节点自己原样放进结果；前置每次从快照新取
        assertEquals(listOf(1L, 8L), first.map { it.id })
        assertTrue(first[0] === root && second[0] === root)
        assertNotSame(first[1], second[1])
    }

    @Test
    fun `根节点所在分组不在快照里时抛采集范围之外的异常`() {
        val data = snapshot(listOf(ProxyGroup(id = 1, isSelector = true)), listOf(socks(1)))
        val stray = socks(50, groupId = 7)
        val e = assertThrows(ConfigInputScopeException::class.java) { stray.resolveChain(data, 1, data.group(1)) {} }
        assertEquals("group 7 is not in the config snapshot", e.message)
    }
}
