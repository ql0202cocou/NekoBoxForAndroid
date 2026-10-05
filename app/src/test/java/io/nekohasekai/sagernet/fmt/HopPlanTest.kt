package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.SingBoxOptions.Outbound_SocksOptions
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// 单跳规划（planHop，ChainPlan.kt）：直接调用，不经过 ConfigBuild。规划不分配端口、不生成凭据、不查插件可用性，
// 只有最先拨号的、能映射的 hysteria 查一次外部插件 app；出错时抛出的异常不带节点名（由调用方包上）
class HopPlanTest {

    private val platform = FakeConfigPlatform()

    private fun plan(
        entity: ProxyEntity,
        firstDialing: Boolean = false,
        muxApplied: Boolean = false,
        globalAllowInsecure: Boolean = false,
        plugins: PluginQueries = PluginQueries(platform),
    ) = planHop(entity, entity.requireBean(), firstDialing, muxApplied, globalAllowInsecure, plugins)

    private fun entity(bean: AbstractBean, core: Int? = null) = ProxyEntity(id = 1, groupId = 1, userOrder = 1)
        .apply { if (core != null) this.core = core }
        .putBean(bean)

    private fun socks(uot: Boolean = false) = entity(SOCKSBean().apply {
        name = "socks"
        serverAddress = "192.0.2.1"
        serverPort = 1080
        initializeDefaultValues()
        sUoT = uot
    })

    // VMess（不是 VLESS）走 sing-box
    private fun vmess(mux: Boolean) = entity(VMessBean().apply {
        name = "vmess"
        serverAddress = "v.example.com"
        serverPort = 443
        uuid = "00000000-0000-0000-0000-000000000001"
        alterId = 0
        initializeDefaultValues()
        enableMux = mux
        muxType = 1
        muxConcurrency = 4
    })

    // VLESS + TLS，自动选核时走 Xray
    private fun vless(core: Int? = null, configure: VMessBean.() -> Unit = {}) = entity(VMessBean().apply {
        name = "vless"
        serverAddress = "x.example.com"
        serverPort = 8443
        uuid = "00000000-0000-0000-0000-000000000002"
        alterId = -1
        initializeDefaultValues()
        security = "tls"
        sni = "x.example.com"
        configure()
    }, core)

    private fun anytls() = entity(AnyTLSBean().apply {
        name = "anytls"
        serverAddress = "a.example.net"
        serverPort = 9443
        password = "fake-password"
        initializeDefaultValues()
    })

    private fun trojanGo() = entity(TrojanGoBean().apply {
        name = "trojan-go"
        serverAddress = "t.example.org"
        serverPort = 4443
        password = "fake-password"
        initializeDefaultValues()
    })

    // hysteria 1：真实端口在 serverPorts，serverPort 是没同步的默认值
    private fun hysteria(protocol: Int) = entity(HysteriaBean().apply {
        name = "hy1"
        protocolVersion = 1
        serverAddress = "hy.example.com"
        serverPorts = "8443"
        initializeDefaultValues()
        this.protocol = protocol
    })

    private fun trojanPinned(customOutboundJson: String = "") = entity(TrojanBean().apply {
        name = "trojan-pin"
        serverAddress = "tp.example.com"
        serverPort = 443
        password = "fake-password"
        initializeDefaultValues()
        security = "tls"
        certificateFingerprint = "AA".repeat(32)
        this.customOutboundJson = customOutboundJson
    })

    private fun tuicV4(customOutboundJson: String = "") = entity(TuicBean().apply {
        name = "tuic-v4"
        serverAddress = "q.example.com"
        serverPort = 443
        initializeDefaultValues()
        protocolVersion = 4
        this.customOutboundJson = customOutboundJson
    })

    private fun config(type: Int, config: String) = entity(ConfigBean().apply {
        name = "custom"
        this.type = type
        this.config = config
        initializeDefaultValues()
    })

    @Test
    fun `内部核心节点在规划时建好出站，不分端口、不查插件`() {
        val hop = plan(socks())
        val core = hop.core as HopCore.Internal
        assertTrue(core.outbound is Outbound_SocksOptions)
        assertNull(core.multiplex)
        assertFalse(hop.udpOverTcp)
        assertTrue(platform.ports.isEmpty())
        assertTrue(platform.pluginQueries.isEmpty())
        // 规划用的就是传入的实体与 bean，不另拷贝
        val entity = socks()
        assertSame(entity, plan(entity).entity)
    }

    @Test
    fun `udp_over_tcp 取节点的设置`() {
        assertTrue(plan(socks(uot = true)).udpOverTcp)
        val ss = entity(ShadowsocksBean().apply {
            name = "ss"
            serverAddress = "192.0.2.2"
            serverPort = 8388
            method = "aes-128-gcm"
            password = "fake-password"
            initializeDefaultValues()
            sUoT = true
        })
        assertTrue(plan(ss).udpOverTcp)
    }

    @Test
    fun `多路复用只给链上第一个开了它的内部核心跳`() {
        val first = plan(vmess(mux = true)).core as HopCore.Internal
        assertEquals("smux", first.multiplex!!["protocol"])
        assertEquals(true, first.multiplex!!["enabled"])
        // 链上之前已有内部核心跳带了多路复用：不再带
        assertNull((plan(vmess(mux = true), muxApplied = true).core as HopCore.Internal).multiplex)
        // 节点没开：不带
        assertNull((plan(vmess(mux = false)).core as HopCore.Internal).multiplex)
    }

    @Test
    fun `Xray 与 mihomo 节点经映射，入站转到节点服务器，本机入站要凭据`() {
        for ((entity, pluginId) in listOf(vless() to "xray-plugin", anytls() to "mihomo-plugin")) {
            for (firstDialing in listOf(false, true)) {
                val core = plan(entity, firstDialing = firstDialing).core as HopCore.External
                assertEquals(pluginId, core.core!!.pluginId)
                assertTrue(core.needsLocalAuth)
                val bean = entity.requireBean()
                assertEquals(bean.serverAddress, core.mapping!!.address)
                assertEquals(bean.serverPort, core.mapping!!.port)
            }
        }
        assertTrue(platform.ports.isEmpty())
        assertTrue("只有 hysteria 查插件：${platform.pluginQueries}", platform.pluginQueries.isEmpty())
    }

    @Test
    fun `插件核心节点经映射，本机入站不要凭据`() {
        val core = plan(trojanGo(), firstDialing = true).core as HopCore.External
        assertEquals("trojan-go-plugin", core.core!!.pluginId)
        assertFalse(core.needsLocalAuth)
        assertEquals(HopMapping::class.java, core.mapping!!.javaClass)
        assertEquals(4443, core.mapping!!.port)
        assertTrue(platform.pluginQueries.isEmpty())
    }

    @Test
    fun `hysteria 1 不是最先拨号时经映射，映射端口取 serverPorts，不查插件`() {
        val core = plan(hysteria(HysteriaBean.PROTOCOL_WECHAT_VIDEO)).core as HopCore.External
        assertEquals("hysteria-plugin", core.core!!.pluginId)
        assertEquals("hy.example.com", core.mapping!!.address)
        assertEquals(8443, core.mapping!!.port)
        assertTrue(platform.pluginQueries.isEmpty())
    }

    @Test
    fun `最先拨号的 hysteria 1 在没有外部插件 app 或装的是 Matsuri exe 时免映射`() {
        val core = plan(hysteria(HysteriaBean.PROTOCOL_WECHAT_VIDEO), firstDialing = true).core as HopCore.External
        assertNull(core.mapping)
        assertEquals(listOf("pluginExternalAuthority(hysteria-plugin)"), platform.pluginQueries)

        val neko = FakeConfigPlatform(
            externalAuthorities = mapOf("hysteria-plugin" to Plugins.AUTHORITIES_PREFIX_NEKO_EXE + "hysteria"),
        )
        val viaNeko = plan(hysteria(HysteriaBean.PROTOCOL_WECHAT_VIDEO), firstDialing = true, plugins = PluginQueries(neko))
        assertNull((viaNeko.core as HopCore.External).mapping)
        assertEquals(listOf("pluginExternalAuthority(hysteria-plugin)"), neko.pluginQueries)
    }

    @Test
    fun `最先拨号的 hysteria 1 装的是别的外部插件 app 时报不受支持`() {
        val other = FakeConfigPlatform(externalAuthorities = mapOf("hysteria-plugin" to "com.example.hysteria"))
        val plugins = PluginQueries(other)
        val entity = hysteria(HysteriaBean.PROTOCOL_WECHAT_VIDEO)
        val e = assertThrows(Exception::class.java) { plan(entity, firstDialing = true, plugins = plugins) }
        assertEquals("You are using an unsupported hysteria-plugin, please download the correct plugin.", e.message)
        // 同一次构建里再规划一次：用记住的结果，不再查
        assertThrows(Exception::class.java) { plan(entity, firstDialing = true, plugins = plugins) }
        assertEquals(listOf("pluginExternalAuthority(hysteria-plugin)"), other.pluginQueries)
    }

    @Test
    fun `不能映射的节点不映射，也不查插件`() {
        val core = plan(hysteria(HysteriaBean.PROTOCOL_FAKETCP), firstDialing = true).core as HopCore.External
        assertNull(core.mapping)
        assertTrue(platform.pluginQueries.isEmpty())
    }

    @Test
    fun `拒绝证书固定不受支持的节点`() {
        val e = assertThrows(IllegalStateException::class.java) { plan(trojanPinned()) }
        assertEquals("this core cannot pin certificates; clear the fingerprint or use a core that supports it", e.message)
    }

    @Test
    fun `拒绝落到 sing-box 上的 mldsa65Verify`() {
        val entity = vless(core = ProxyEntity.CORE_SING_BOX) {
            realityPubKey = "fake-public-key"
            realityMldsa65Verify = "fake-mldsa65"
        }
        val e = assertThrows(IllegalStateException::class.java) { plan(entity) }
        assertEquals(
            "REALITY mldsa65Verify only works on the Xray core; switch this profile to the Xray core or clear mldsa65Verify",
            e.message,
        )
        // 走 Xray 时放行
        val onXray = vless(core = ProxyEntity.CORE_XRAY) {
            realityPubKey = "fake-public-key"
            realityMldsa65Verify = "fake-mldsa65"
        }
        assertTrue(plan(onXray).core is HopCore.External)
    }

    @Test
    fun `拒绝作为链成员的完整配置节点，自定义出站节点照常建出站`() {
        val e = assertThrows(IllegalStateException::class.java) { plan(config(0, "{}")) }
        assertEquals("a full-config profile can only run on its own", e.message)
        val outbound = plan(config(1, """{"type":"direct","tag":"x"}""")).core as HopCore.Internal
        assertTrue(outbound.outbound is moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption)
        // 自定义出站的 JSON 在规划时就解析，坏的在这里报错
        assertThrows(Exception::class.java) { plan(config(1, """{"type":""")) }
    }

    @Test
    fun `拒绝损坏的自定义出站 JSON`() {
        val broken = entity(SOCKSBean().apply {
            name = "socks"
            serverAddress = "192.0.2.3"
            serverPort = 1080
            initializeDefaultValues()
            customOutboundJson = """{"a":"""
        })
        val e = assertThrows(Exception::class.java) { plan(broken) }
        assertTrue(e.message, e.message!!.contains("End of input"))
    }

    @Test
    fun `内部核心建出站时的拒绝在规划里抛出`() {
        val e = assertThrows(Exception::class.java) { plan(tuicV4()) }
        assertEquals("TUIC v4 is no longer supported", e.message)
    }

    @Test
    fun `一跳有多处错误时按固定次序报第一处`() {
        // 证书固定先于自定义出站 JSON
        val pinned = assertThrows(IllegalStateException::class.java) { plan(trojanPinned(customOutboundJson = "{")) }
        assertEquals("this core cannot pin certificates; clear the fingerprint or use a core that supports it", pinned.message)
        // 单跳检查（自定义出站 JSON）先于建出站（TUIC v4）
        val json = assertThrows(Exception::class.java) { plan(tuicV4(customOutboundJson = "{")) }
        assertTrue(json.message, json.message!!.contains("End of input"))
        // 单跳检查先于外核的插件查询
        val other = FakeConfigPlatform(externalAuthorities = mapOf("hysteria-plugin" to "com.example.hysteria"))
        val pinnedHysteria = entity(HysteriaBean().apply {
            name = "hy1-pin"
            protocolVersion = 1
            serverAddress = "hp.example.com"
            serverPorts = "8443"
            initializeDefaultValues()
            protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
            certificateFingerprint = "BB".repeat(32)
        })
        val e = assertThrows(IllegalStateException::class.java) {
            plan(pinnedHysteria, firstDialing = true, plugins = PluginQueries(other))
        }
        assertEquals("this core cannot pin certificates; clear the fingerprint or use a core that supports it", e.message)
        assertTrue(other.pluginQueries.isEmpty())
    }
}
