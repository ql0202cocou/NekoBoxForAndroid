package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import moe.matsuri.nb4a.SingBoxOptions.Outbound_VMessOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// BUG-001 短期部分：sing-box 只在创建出站时才校验的几项枚举取值，构建内部核心出站时先按名单检查
// （SingBoxValueSets.kt）。名单里的值全部原样写出；名单外的值报错，消息里有字段与取值；外核跳不受影响；
// 选择器里没选中的坏成员被跳过，选中的坏节点整次构建失败
class SingBoxValueSetsTest {

    private fun ss(method: String?, plugin: String = "") = ShadowsocksBean().apply {
        name = "ss"
        serverAddress = "192.0.2.10"
        serverPort = 8388
        password = "fake-password"
        initializeDefaultValues()
        this.method = method
        this.plugin = plugin
    }

    private fun vmess(security: String, core: Int? = null) = ProxyEntity(id = 1, groupId = 1, userOrder = 1)
        .apply { if (core != null) this.core = core }
        .putBean(CoreTestNodes.vmess { encryption = security })

    private fun tuic(congestion: String?) = TuicBean().apply {
        name = "tuic"
        serverAddress = "q.example.com"
        serverPort = 443
        initializeDefaultValues()
        congestionController = congestion
    }

    private fun assertRejected(field: String, value: String?, block: () -> Unit) {
        val e = assertThrows(UnsupportedSingBoxValueException::class.java) { block() }
        assertEquals(field, e.field)
        assertEquals(value, e.value)
        assertEquals("sing-box does not support $field ${shownSingBoxValue(value)}", e.message)
    }

    // ---- Shadowsocks

    @Test
    fun `Shadowsocks 加密方式：名单里的全部通过，名单外与大小写不同的被拒`() {
        assertEquals(18, SING_BOX_SHADOWSOCKS_METHODS.size)
        for (method in SING_BOX_SHADOWSOCKS_METHODS) {
            assertEquals(method, buildSingBoxOutboundShadowsocksBean(ss(method)).method)
        }
        // Clash 导入原样保留的写法（BUG-001 的例子），以及 sing-box 不认的大写
        assertRejected("shadowsocks method", "not-a-cipher") { buildSingBoxOutboundShadowsocksBean(ss("not-a-cipher")) }
        assertRejected("shadowsocks method", "AES-256-GCM") { buildSingBoxOutboundShadowsocksBean(ss("AES-256-GCM")) }
        assertRejected("shadowsocks method", null) { buildSingBoxOutboundShadowsocksBean(ss(null)) }
    }

    @Test
    fun `报错只回显形态合格的取值，其余不回显也不暴露长度`() {
        val hidden = "sing-box does not support shadowsocks method (value not shown)"
        // 畸形 ss:// 可能把密码落进 method：含空格、非 ASCII、超长、空串与 null 都不回显
        for (value in listOf("pass word", "密码-123", "a".repeat(65), "x\"y", "", null)) {
            val e = assertThrows(UnsupportedSingBoxValueException::class.java) {
                buildSingBoxOutboundShadowsocksBean(ss(value))
            }
            assertEquals(hidden, e.message)
        }
        // 形态合格的照常回显，64 个字符为止
        val longest = "b".repeat(64)
        assertEquals(
            "sing-box does not support shadowsocks method \"$longest\"",
            assertThrows(UnsupportedSingBoxValueException::class.java) {
                buildSingBoxOutboundShadowsocksBean(ss(longest))
            }.message,
        )
        assertEquals("\"aes-256-gcm+x_1.0\"", shownSingBoxValue("aes-256-gcm+x_1.0"))
    }

    @Test
    fun `Shadowsocks 插件：名单里的通过，none、空串与空插件名不写，名单外的被拒`() {
        for (plugin in SING_BOX_SHADOWSOCKS_PLUGINS) {
            val out = buildSingBoxOutboundShadowsocksBean(ss("aes-128-gcm", "$plugin;mode=websocket"))
            assertEquals(plugin, out.plugin)
            assertEquals("mode=websocket", out.plugin_opts)
        }
        assertNull(buildSingBoxOutboundShadowsocksBean(ss("aes-128-gcm", "none;x=1")).plugin)
        assertNull(buildSingBoxOutboundShadowsocksBean(ss("aes-128-gcm", "")).plugin)
        // ss:// 的 ?plugin= 原样保留、插件名为空只有参数：与 none 一样不写，不查名单（改动前 sing-box 同样不建插件）
        for (plugin in listOf(";obfs=http", " ;obfs=http", ";")) {
            val out = buildSingBoxOutboundShadowsocksBean(ss("aes-128-gcm", plugin))
            assertNull(out.plugin)
            assertNull(out.plugin_opts)
        }
        // ss:// 链接原样保留的插件名（只有 simple-obfs 会被换成 obfs-local）
        assertRejected("shadowsocks plugin", "kcptun") {
            buildSingBoxOutboundShadowsocksBean(ss("aes-128-gcm", "kcptun;crypt=none"))
        }
    }

    // ---- VMess

    @Test
    fun `VMess 加密方式：名单里的全部通过，空值写 auto，名单外的被拒`() {
        for (security in SING_BOX_VMESS_SECURITIES) {
            val out = buildSingBoxOutboundStandardV2RayBean(CoreTestNodes.vmess { encryption = security }, false)
            assertEquals(security, (out as Outbound_VMessOptions).security)
        }
        val blank = buildSingBoxOutboundStandardV2RayBean(CoreTestNodes.vmess { encryption = "" }, false)
        assertEquals("auto", (blank as Outbound_VMessOptions).security)
        assertRejected("vmess security", "aes-256-gcm") {
            buildSingBoxOutboundStandardV2RayBean(CoreTestNodes.vmess { encryption = "aes-256-gcm" }, false)
        }
    }

    @Test
    fun `VMess 走 Xray 的跳不经 sing-box 的名单`() {
        // 手动指定 Xray：外核跳，名单外的加密方式由 Xray 自己处理，规划不报错
        val onXray = vmess("aes-256-gcm", ProxyEntity.CORE_XRAY)
        val hop = planHop(
            onXray, onXray.requireBean(), HopCoreChoice.of(onXray, false), firstDialing = false, muxApplied = false,
            globalAllowInsecure = false, plugins = PluginQueries(FakeConfigPlatform()),
        )
        assertTrue(hop.core is HopCore.External)
        // 同一个节点自动选核走 sing-box：规划时就被拒
        val onSingBox = vmess("aes-256-gcm")
        assertRejected("vmess security", "aes-256-gcm") {
            planHop(
                onSingBox, onSingBox.requireBean(), HopCoreChoice.of(onSingBox, false), firstDialing = false,
                muxApplied = false, globalAllowInsecure = false, plugins = PluginQueries(FakeConfigPlatform()),
            )
        }
    }

    // ---- TUIC

    @Test
    fun `TUIC 拥塞控制：名单里的全部通过，不写时留给 sing-box，名单外的被拒`() {
        for (cc in SING_BOX_TUIC_CONGESTION_CONTROLS) {
            assertEquals(cc, buildSingBoxOutboundTuicBean(tuic(cc), false).congestion_control)
        }
        assertNull(buildSingBoxOutboundTuicBean(tuic(null), false).congestion_control)
        assertRejected("tuic congestion control", "brutal") { buildSingBoxOutboundTuicBean(tuic("brutal"), false) }
    }

    // ---- 整次构建（仿 ConfigPrecheckTest）

    private fun socks(id: Long) = ProxyEntity(id = id, groupId = 1, userOrder = id).putBean(SOCKSBean().apply {
        name = "socks-$id"
        serverAddress = "192.0.2.$id"
        serverPort = 1080
        initializeDefaultValues()
    })

    private fun ssEntity(id: Long, method: String) = ProxyEntity(id = id, groupId = 1, userOrder = id)
        .putBean(ss(method).apply { name = "ss-$id" })

    private fun build(
        main: ProxyEntity,
        profiles: List<ProxyEntity>,
        diagnostics: MutableList<ConfigBuildDiagnostic> = ArrayList(),
    ): ConfigBuildResult {
        val source = MemoryConfigDataSource(listOf(ProxyGroup(id = 1, isSelector = true)), profiles, emptyList())
        val record = ProfileRecord.of(main)
        val snapshot = ConfigSnapshot.collect(source, record, ConfigBuildMode.RUN)
        val input = ConfigInput(ConfigBuildMode.RUN, record, testConfigSettings(), snapshot, emptyMap(), FakeConfigPlatform())
        return buildConfig(input, diagnostics)
    }

    @Test
    fun `选择器里没选中的坏成员被跳过，正常节点照常启动`() {
        val main = socks(1)
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val result = build(main, listOf(main, ssEntity(2, "not-a-cipher"), ssEntity(3, "aes-128-gcm")), diagnostics)
        assertEquals(
            listOf(
                ConfigBuildDiagnostic.ProfileSkipped(
                    2, "ss-2", "ss-2: sing-box does not support shadowsocks method \"not-a-cipher\"", true,
                ),
            ),
            diagnostics,
        )
        assertEquals(setOf(1L, 3L), result.profileTagMap.keys)
    }

    @Test
    fun `选中的坏节点整次构建失败，报出节点名、字段与取值`() {
        val main = ssEntity(2, "not-a-cipher")
        val e = assertThrows(ProfileBuildException::class.java) { build(main, listOf(main, socks(1))) }
        assertEquals("ss-2", e.profileName)
        assertEquals("ss-2: sing-box does not support shadowsocks method \"not-a-cipher\"", e.message)
        assertEquals("shadowsocks method", (e.cause as UnsupportedSingBoxValueException).field)
    }
}
