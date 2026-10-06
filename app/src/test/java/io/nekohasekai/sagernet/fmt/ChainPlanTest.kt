package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// 一条链的规划（planChain，ChainPlan.kt）：直接对快照调用，不经过 ConfigBuild。只展开一次链，整链检查之后逐跳规划，
// 每跳之后调 checkHop；逐跳的报错包上该跳的节点名，链级的不包；不分配端口、不生成凭据
class ChainPlanTest {

    // 规划中按发生顺序记下的事件：插件查询与 checkHop
    private val events = ArrayList<String>()

    private val platform = object : ConfigPlatform by FakeConfigPlatform() {
        override fun newPort(): Int = error("规划不能分配端口")
        override fun pluginExternalAuthority(pluginId: String): String? {
            events += "authority($pluginId)"
            return null
        }

        override fun pluginError(pluginId: String): Exception? = error("规划本身不查插件可用性")
    }

    private val warnings = ArrayList<String>()

    private fun socks(id: Long, groupId: Long = 1) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .putBean(SOCKSBean().apply {
            name = "socks-$id"
            serverAddress = "192.0.2.$id"
            serverPort = 1080
            initializeDefaultValues()
        })

    private fun vmessMux(id: Long, groupId: Long = 1) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .putBean(VMessBean().apply {
            name = "vmess-$id"
            serverAddress = "v$id.example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-0000000000%02x".format(id)
            alterId = 0
            initializeDefaultValues()
            enableMux = true
        })

    // VLESS + REALITY，自动选核走 Xray（外核跳）
    private fun vless(id: Long, groupId: Long = 1) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .putBean(VMessBean().apply {
            name = "vless-$id"
            serverAddress = "x$id.example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-0000000000%02x".format(id)
            alterId = -1
            initializeDefaultValues()
            security = "tls"
            sni = "x$id.example.com"
            realityPubKey = CoreTestNodes.REALITY_KEY
            realityShortId = CoreTestNodes.SHORT_ID
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

    // 带证书指纹又手动指定 sing-box：选核判定拒绝（K1 起 Trojan 带指纹自动走 Xray）
    private fun trojanPinned(id: Long, groupId: Long = 1) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .apply { core = ProxyEntity.CORE_SING_BOX }
        .putBean(TrojanBean().apply {
            name = "trojan-pin-$id"
            serverAddress = "tp$id.example.com"
            serverPort = 443
            password = "fake-password"
            initializeDefaultValues()
            security = "tls"
            certificateFingerprint = "AA".repeat(32)
        })

    // members 按用户填写的顺序：首个是最先拨号的一跳
    private fun chain(id: Long, vararg members: Long, groupId: Long = 1) =
        ProxyEntity(id = id, groupId = groupId, userOrder = id).putBean(ChainBean().apply {
            name = "chain-$id"
            proxies = members.toList()
            initializeDefaultValues()
        })

    // 按主节点（profiles 的首个）采集快照，规划主节点的链；主节点用它的记录新建，同构建
    private fun plan(
        profiles: List<ProxyEntity>,
        group: ProxyGroup = ProxyGroup(id = 1),
        checkHop: (HopPlan) -> Unit = {},
    ): ChainPlan {
        val record = ProfileRecord.of(profiles.first())
        val snapshot = ConfigSnapshot.collect(
            MemoryConfigDataSource(listOf(group), profiles, emptyList()), record, ConfigBuildMode.TEST,
        )
        val main = record.newEntity()
        val context = ChainPlanContext(
            snapshot, main.groupId, snapshot.group(main.groupId), false, PluginQueries(platform), { warnings += it },
        )
        return planChain(main, context, checkHop)
    }

    @Test
    fun `规划得到倒序的各跳，不分配端口`() {
        // 链 1：5 hysteria（最先拨号）→ 4 vless → 3 socks（出口）
        val plan = plan(listOf(chain(1, 5, 4, 3), socks(3), vless(4), hysteria(5)))
        assertEquals(1L, plan.entity.id)
        assertEquals(listOf(3L, 4L, 5L), plan.hops.map { it.entity.id })
        assertTrue(plan.hops[0].core is HopCore.Internal)
        // vless 不是最先拨号，经映射；最先拨号的 hysteria 查过外部插件 app 后免映射
        assertEquals(443, (plan.hops[1].core as HopCore.External).mapping!!.port)
        assertNull((plan.hops[2].core as HopCore.External).mapping)
        assertEquals(listOf("authority(hysteria-plugin)"), events)
        // 每跳的实体就是 bean 所在的那一个
        plan.hops.forEach { assertTrue(it.bean === it.entity.requireBean()) }
    }

    @Test
    fun `逐跳规划与 checkHop 交替进行：每跳先规划、再检查，然后才轮到下一跳`() {
        plan(listOf(chain(1, 5, 4, 3), socks(3), vless(4), hysteria(5))) { events += "check(${it.entity.id})" }
        // hysteria 的插件查询在规划它这一跳时发生，排在前两跳的检查之后、它自己的检查之前
        assertEquals(listOf("check(3)", "check(4)", "authority(hysteria-plugin)", "check(5)"), events)
    }

    @Test
    fun `checkHop 出错时包上该跳的节点名，后面的跳不再规划`() {
        val e = assertThrows(ProfileBuildException::class.java) {
            plan(listOf(chain(1, 5, 4, 3), socks(3), vless(4), hysteria(5))) {
                if (it.entity.id == 4L) error("plugin xray-plugin is not installed")
            }
        }
        assertEquals("vless-4", e.profileName)
        assertEquals("vless-4: plugin xray-plugin is not installed", e.message)
        // 最先拨号的 hysteria 没有规划到，也就没查插件
        assertTrue(events.isEmpty())
    }

    @Test
    fun `单跳规划的报错包上该跳的节点名，排在它前面的跳照常规划过`() {
        // 链 1：3 trojan（手动 sing-box 却带证书指纹，最先拨号）→ 2 socks（出口）
        val checked = ArrayList<Long>()
        val e = assertThrows(ProfileBuildException::class.java) {
            plan(listOf(chain(1, 3, 2), socks(2), trojanPinned(3))) { checked += it.entity.id }
        }
        assertEquals("trojan-pin-3", e.profileName)
        assertEquals(
            "trojan-pin-3: the manually chosen core sing-box cannot run this profile: " +
                "[certificateFingerprint] sing-box cannot pin a whole-certificate SHA-256 (it only pins public keys)",
            e.message,
        )
        assertEquals(listOf(2L), checked)
    }

    @Test
    fun `链级的报错不带节点名，也不规划任何一跳`() {
        // 成员都缺失：展开后为空
        val e1 = assertThrows(IllegalStateException::class.java) { plan(listOf(chain(1, 98))) { error("不该检查") } }
        assertEquals("chain profile 1 (chain-1) has no valid member", e1.message)
        assertEquals(listOf("chain profile 1 references missing profile 98, skipped"), warnings)
        // 同一个外核节点在链里出现两次
        val e2 = assertThrows(IllegalStateException::class.java) {
            plan(listOf(chain(1, 4, 2, 4), socks(2), vless(4))) { error("不该检查") }
        }
        assertEquals("profile 4 (vless-4) runs on an external core and appears twice in chain 1", e2.message)
    }

    @Test
    fun `多路复用只给从出口数起第一个开了它的内部核心跳`() {
        // 链 1：4 vmess（开了多路复用，最先拨号）→ 3 vless（外核）→ 2 vmess（开了多路复用，出口）
        val plan = plan(listOf(chain(1, 4, 3, 2), vmessMux(2), vless(3), vmessMux(4)))
        assertEquals("h2mux", (plan.hops[0].core as HopCore.Internal).multiplex!!["protocol"])
        assertNull((plan.hops[2].core as HopCore.Internal).multiplex)
        // 出口不是内部核心时，第一个内部核心跳带上
        val second = plan(listOf(chain(1, 4, 3, 2), socks(2), vless(3), vmessMux(4)))
        assertNull((second.hops[0].core as HopCore.Internal).multiplex)
        assertEquals("h2mux", (second.hops[2].core as HopCore.Internal).multiplex!!["protocol"])
    }

    @Test
    fun `分组的前置与落地接进规划，只展开一次`() {
        val group = ProxyGroup(id = 1, frontProxy = 8, landingProxy = 99)
        val plan = plan(listOf(socks(1), socks(8, groupId = 2)), group)
        assertEquals(listOf(1L, 8L), plan.hops.map { it.entity.id })
        assertEquals(listOf("group 1 landing proxy 99 no longer exists, ignored"), warnings)
    }
}
