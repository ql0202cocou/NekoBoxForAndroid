package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// sing-box 逐出站试建的 Kotlin 管道（SingBoxTrial.kt）：类别与消息、libcore 返回值的读法、一次构建内的记忆、
// 试建 JSON 的生成，这里直接调用。接进 planHop 之后的行为见 HopPlanTest 与 ConfigPrecheckTest
class SingBoxTrialTest {

    private fun entity(bean: AbstractBean) = ProxyEntity(id = 1, groupId = 1, userOrder = 1).putBean(bean)

    private fun vmess(mux: Boolean = false, customOutboundJson: String = "") = entity(VMessBean().apply {
        name = "vmess"
        serverAddress = "v.example.com"
        serverPort = 443
        uuid = "00000000-0000-0000-0000-000000000001"
        alterId = 0
        initializeDefaultValues()
        enableMux = mux
        muxType = 1
        muxConcurrency = 4
        this.customOutboundJson = customOutboundJson
    })

    private fun ss(uotVersion: Int) = entity(ShadowsocksBean().apply {
        name = "ss"
        serverAddress = "192.0.2.2"
        serverPort = 8388
        method = "aes-128-gcm"
        password = "fake-password"
        initializeDefaultValues()
        sUoT = true
        sUoTVersion = uotVersion
    })

    private fun wireguard() = entity(WireGuardBean().apply {
        name = "wg"
        serverAddress = "wg.example.com"
        serverPort = 51820
        localAddress = "10.0.0.2/32"
        privateKey = "A".repeat(43) + "="
        peerPublicKey = "B".repeat(43) + "="
        initializeDefaultValues()
    })

    // 走 planHop 的内部核心分支拿到规划结果，再生成试建 JSON
    private fun trialJson(entity: ProxyEntity, firstDialing: Boolean, muxApplied: Boolean = false): JsonObject {
        val bean = entity.requireBean()
        val hop = planHop(
            entity, bean, HopCoreChoice.of(entity, false), firstDialing, muxApplied, false,
            PluginQueries(FakeConfigPlatform()), SingBoxTrials(FakeConfigPlatform()),
        )
        val core = hop.core as HopCore.Internal
        return JsonParser.parseString(singBoxTrialJson(core.outbound, core.multiplex, bean, firstDialing)).asJsonObject
    }

    @Test
    fun `试建 JSON 的 tag 是占位，detour 只在不是最先拨号的一跳时有`() {
        val first = trialJson(vmess(), firstDialing = true)
        assertEquals(SING_BOX_TRIAL_TAG, first["tag"].asString)
        assertFalse(first.has("detour"))
        assertFalse(first.has("domain_resolver"))
        val middle = trialJson(vmess(), firstDialing = false)
        assertEquals(SING_BOX_TRIAL_DETOUR, middle["detour"].asString)
        assertEquals("vmess", middle["type"].asString)
        assertEquals("v.example.com", middle["server"].asString)
    }

    @Test
    fun `多路复用与 udp_over_tcp 照规划与 commitHop 写入`() {
        assertTrue(trialJson(vmess(mux = true), firstDialing = true).has("multiplex"))
        // 链上已有内部核心跳带了多路复用时这一跳不带
        assertFalse(trialJson(vmess(mux = true), firstDialing = true, muxApplied = true).has("multiplex"))
        assertEquals(JsonParser.parseString("true"), trialJson(ss(2), firstDialing = true)["udp_over_tcp"])
        assertEquals(
            JsonParser.parseString("""{"enabled":true,"version":1}"""),
            trialJson(ss(1), firstDialing = true)["udp_over_tcp"],
        )
    }

    @Test
    fun `自定义出站 JSON 最后合并，连它写的 domain_resolver 与 tag 一起`() {
        val json = trialJson(
            vmess(customOutboundJson = """{"tag":"user-tag","domain_resolver":"dns-remote","packet_encoding":"xudp"}"""),
            firstDialing = true,
        )
        // 与写进配置时同一个合并次序：自定义 JSON 覆盖构建写的键
        assertEquals("user-tag", json["tag"].asString)
        assertEquals("dns-remote", json["domain_resolver"].asString)
        assertEquals("xudp", json["packet_encoding"].asString)
    }

    @Test
    fun `规划得到的出站不被改动，提交写过的不能拿来试建`() {
        val entity = vmess(mux = true)
        val bean = entity.requireBean()
        val core = planHop(
            entity, bean, HopCoreChoice.of(entity, false), false, false, false, PluginQueries(FakeConfigPlatform()),
            SingBoxTrials(FakeConfigPlatform()),
        ).core as HopCore.Internal
        singBoxTrialJson(core.outbound, core.multiplex, bean, firstDialing = false)
        assertTrue(core.outbound._hack_config_map.isEmpty())
        assertNull(core.outbound._hack_custom_config)
        core.outbound._hack_config_map["tag"] = "g-1"
        assertThrows(IllegalStateException::class.java) {
            singBoxTrialJson(core.outbound, core.multiplex, bean, firstDialing = false)
        }
    }

    @Test
    fun `WireGuard 按端点试建，其余按出站`() {
        assertEquals(SingBoxTrialKind.ENDPOINT, SingBoxTrialKind.of(buildInternalOutbound(wireguard().requireBean(), false)))
        assertEquals(SingBoxTrialKind.OUTBOUND, SingBoxTrialKind.of(buildInternalOutbound(vmess().requireBean(), false)))
        assertEquals(listOf("outbound", "endpoint"), SingBoxTrialKind.entries.map { it.wire })
    }

    @Test
    fun `消息只有类别说明与合格的字段路径`() {
        assertEquals(
            "sing-box rejected the outbound options: invalid option at tls.certificate_path",
            SingBoxTrialException(SingBoxTrialCategory.DECODE, "tls.certificate_path").message,
        )
        assertEquals(
            "sing-box rejected the outbound options: invalid option at peers[0].allowed_ips",
            SingBoxTrialException(SingBoxTrialFailure(SingBoxTrialCategory.DECODE, "peers[0].allowed_ips")).message,
        )
        assertEquals(
            "sing-box rejected the outbound options: certificate is not a valid PEM certificate",
            SingBoxTrialException(SingBoxTrialCategory.TLS_CERTIFICATE, null).message,
        )
        assertEquals("sing-box rejected the outbound options", SingBoxTrialException(SingBoxTrialCategory.OTHER, null).message)
        // 与 UnsupportedSingBoxValueException 同为 IllegalArgumentException，经同一条路跳过
        assertTrue(SingBoxTrialException(SingBoxTrialCategory.OTHER, null) is IllegalArgumentException)
    }

    @Test
    fun `不合格的字段路径丢掉`() {
        for (path in listOf(
            "Password", "server port", "tls.certificate: -----BEGIN", "a..b", "peers[x]", "", ".tag", "a".repeat(65),
            "obfs\nsecret",
        )) {
            assertNull(path, SingBoxTrialFailure(SingBoxTrialCategory.DECODE, path).path)
            assertEquals(
                "sing-box rejected the outbound options: invalid option",
                SingBoxTrialException(SingBoxTrialFailure(SingBoxTrialCategory.DECODE, path)).message,
            )
        }
        assertEquals("a".repeat(64), SingBoxTrialFailure(SingBoxTrialCategory.DECODE, "a".repeat(64)).path)
    }

    @Test
    fun `每个类别的消息都不带取值`() {
        for (category in SingBoxTrialCategory.entries) {
            val message = SingBoxTrialException(category, "secret_field").message!!
            assertTrue(message, message.startsWith("sing-box rejected the outbound options"))
            if (category != SingBoxTrialCategory.DECODE) assertFalse(message, "secret_field" in message)
        }
    }

    @Test
    fun `读 libcore 的返回值`() {
        val warnings = ArrayList<String>()
        val reader = SingBoxTrialResultReader { warnings += it }
        assertNull(reader.read(""))
        assertEquals(SingBoxTrialFailure(SingBoxTrialCategory.SS_KEY_BASE64, null), reader.read("SS_KEY_BASE64"))
        assertEquals(SingBoxTrialFailure(SingBoxTrialCategory.DECODE, "multiplex.padding"), reader.read("DECODE\tmultiplex.padding"))
        // 路径不合格时丢掉，类别照常
        assertEquals(SingBoxTrialFailure(SingBoxTrialCategory.DECODE, null), reader.read("DECODE\t-----BEGIN KEY-----"))
        // 不认识的类别归 OTHER
        assertEquals(SingBoxTrialFailure(SingBoxTrialCategory.OTHER, null), reader.read("SOMETHING_NEW"))
        assertTrue(warnings.isEmpty())
        // 没有结论放行，每种只告警一次
        assertNull(reader.read("UNAVAILABLE"))
        assertNull(reader.read("UNAVAILABLE"))
        assertNull(reader.read("PANIC"))
        assertEquals(
            listOf("sing-box outbound trial gave no verdict: UNAVAILABLE", "sing-box outbound trial gave no verdict: PANIC"),
            warnings,
        )
    }

    @Test
    fun `类别标识不认识的归 OTHER，认识的原样对应`() {
        for (category in SingBoxTrialCategory.entries) assertEquals(category, SingBoxTrialCategory.of(category.name))
        assertEquals(SingBoxTrialCategory.OTHER, SingBoxTrialCategory.of("decode"))
        for (wire in SING_BOX_TRIAL_NO_VERDICT) assertEquals(SingBoxTrialCategory.OTHER, SingBoxTrialCategory.of(wire))
    }

    @Test
    fun `一次构建里同一份 JSON 只试建一次，失败每次都抛`() {
        val failing = """{"type":"vmess","tag":"neko-trial"}"""
        val platform = FakeConfigPlatform(
            trials = FakeConfigPlatform.trialTable(
                mapOf((SingBoxTrialKind.OUTBOUND to failing) to SingBoxTrialFailure(SingBoxTrialCategory.TLS_CERTIFICATE, null)),
            ),
        )
        val trials = SingBoxTrials(platform)
        trials.require(SingBoxTrialKind.OUTBOUND, """{"type":"direct"}""")
        trials.require(SingBoxTrialKind.OUTBOUND, """{"type":"direct"}""")
        repeat(2) {
            val e = assertThrows(SingBoxTrialException::class.java) {
                // 表按结构比较：键的次序与空白不计
                trials.require(SingBoxTrialKind.OUTBOUND, """{ "tag": "neko-trial", "type": "vmess" }""")
            }
            assertEquals(SingBoxTrialCategory.TLS_CERTIFICATE, e.category)
        }
        // 种类不同是不同的查询
        trials.require(SingBoxTrialKind.ENDPOINT, failing)
        assertEquals(
            listOf(
                SingBoxTrialKind.OUTBOUND to """{"type":"direct"}""",
                SingBoxTrialKind.OUTBOUND to """{ "tag": "neko-trial", "type": "vmess" }""",
                SingBoxTrialKind.ENDPOINT to failing,
            ),
            platform.trialQueries,
        )
    }
}
