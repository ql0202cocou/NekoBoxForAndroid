package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_MIHOMO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_ANYTLS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_HYSTERIA
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SOCKS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.fmt.LegacyProfileChange.CORE_NORMALIZED
import io.nekohasekai.sagernet.fmt.LegacyProfileChange.MUX_COOL
import io.nekohasekai.sagernet.fmt.LegacyProfileChange.MUX_TYPE_NORMALIZED
import io.nekohasekai.sagernet.fmt.LegacyProfileChange.PACKET_ENCODING_NORMALIZED
import io.nekohasekai.sagernet.fmt.LegacyProfileChange.UTLS_FIREFOX
import io.nekohasekai.sagernet.fmt.LegacyProfileChange.WS_EARLY_DATA_HEADER
import io.nekohasekai.sagernet.fmt.kryo.KryoSamples
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.MUX_H2MUX
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.ktx.byteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

// 数据库 8 → 9 迁移的逐行处理（upgradeLegacyRow）：输入是历史实现写出的真实 bean 字节（Kryo 兼容样本，
// 外加旧黄金基线 input.json 里的三个节点），期望按节点字段逐条手写
class LegacyRowUpgradeTest {

    private fun sample(id: String) = KryoSamples.samples.single { it.id == id }

    // 实体样本（ProxyEntity v0 / v1 的存储格式）里内嵌的 bean 字节与 core 列
    private fun embedded(id: String): Pair<ByteArray, Int> {
        val input = sample(id).bytes.byteBuffer()
        val version = input.readInt()
        input.readLong() // id
        input.readLong() // groupId
        input.readInt() // type
        input.readLong() // userOrder
        input.readLong() // tx
        input.readLong() // rx
        input.readInt() // status
        input.readInt() // ping
        input.readString() // uuid
        input.readString() // error
        val bean = input.readBytes(input.readVarInt(true))
        input.readBoolean() // dirty
        val core = if (version >= 1) input.readInt() else CORE_AUTO
        return bean to core
    }

    private fun decode(bytes: ByteArray) = KryoConverters.deserialize(VMessBean(), bytes)

    // 改写后的字节：StandardV2Ray 层版本号是 7，字段 = 样本的 expected 加上 overrides
    private fun assertRewritten(where: String, bytes: ByteArray, expected: JsonObject, overrides: Map<String, Any>) {
        assertEquals("$where 版本号", 7, bytes.byteBuffer().readInt())
        val bean = decode(bytes)
        assertFalse("$where 不再带未标注标记", bean.legacyUnlabeled)
        val want = expected.deepCopy().apply {
            for ((k, v) in overrides) add(k, if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()))
        }
        assertEquals(where, emptyList<String>(), KryoSamples.diff(where, bean, want))
    }

    private fun upgrade(type: Int, core: Int, bytes: ByteArray?, global: Boolean = false) =
        upgradeLegacyRow(type, core, bytes) { global }

    // ---- I3 §3a 点名的两个实体样本

    @Test
    fun `实体样本 dd8f56d3：手动 Xray、开了 smux 的 VMess 标为 Mux Cool`() {
        val (bytes, core) = embedded("ProxyEntity/dd8f56d3/entity-VMessBean")
        assertEquals(CORE_XRAY, core)
        assertEquals(6, bytes.byteBuffer().readInt())
        val result = upgrade(TYPE_VMESS, core, bytes) as LegacyRowUpgrade.Changed
        assertEquals(CORE_XRAY, result.core)
        assertEquals(setOf(MUX_COOL), result.changes)
        val expected = sample("ProxyEntity/dd8f56d3/entity-VMessBean").expected
            .getAsJsonObject("bean").getAsJsonObject("fields")
        assertRewritten("entity dd8f56d3", result.bean!!, expected, mapOf("muxType" to 3))
    }

    @Test
    fun `实体样本 bbbdf577：自动选核、没有证书指纹的 VMess 不动`() {
        val (bytes, core) = embedded("ProxyEntity/bbbdf577/entity-VMessBean")
        assertEquals(CORE_AUTO, core) // ProxyEntity v0 没有 core 列
        assertEquals(4, bytes.byteBuffer().readInt())
        // 开了 smux，但 VMess 没有证书指纹，当时走 sing-box；全局「允许不安全」不影响这个结论
        assertSame(LegacyRowUpgrade.Unchanged, upgrade(TYPE_VMESS, core, bytes, global = false))
        assertSame(LegacyRowUpgrade.Unchanged, upgrade(TYPE_VMESS, core, bytes, global = true))
    }

    // ---- VMessBean 样本：同一份字节在不同 core 列下的结论

    private class SampleCase(
        val sample: String,
        val core: Int,
        val global: Boolean,
        // null 表示 Unchanged
        val expectedCore: Int?,
        val changes: Set<LegacyProfileChange> = emptySet(),
        val overrides: Map<String, Any> = emptyMap(),
    )

    private val sampleCases = listOf(
        // v6 VMess：REALITY 字段 + 证书指纹 + smux，自动选核时按证书指纹走 Xray
        SampleCase("VMessBean/dd8f56d3/vmess-tcp-tls", CORE_AUTO, false, CORE_AUTO, setOf(MUX_COOL), mapOf("muxType" to 3)),
        // v6 VLESS：证书指纹压过 allowInsecure，走 Xray；vision 流控照常标
        SampleCase("VMessBean/dd8f56d3/vless-tcp-tls", CORE_AUTO, true, CORE_AUTO, setOf(MUX_COOL), mapOf("muxType" to 3)),
        // v6 VMess ws：已有头名，规则 b 不动；证书指纹走 Xray
        SampleCase("VMessBean/dd8f56d3/vmess-ws-tls-ech", CORE_AUTO, false, CORE_AUTO, setOf(MUX_COOL), mapOf("muxType" to 3)),
        // v5 VLESS：没有证书指纹、节点开了 allowInsecure，自动时当时走 sing-box，不动
        SampleCase("VMessBean/329572d1/vless-tcp-tls", CORE_AUTO, false, null),
        // 同一份字节手动 Xray：当时走 Xray（构建会报 allowInsecure，但标注只看承载）
        SampleCase("VMessBean/329572d1/vless-tcp-tls", CORE_XRAY, false, CORE_XRAY, setOf(MUX_COOL), mapOf("muxType" to 3)),
        // v5 VMess http 不带 TLS：自动走 sing-box 不动，手动 Xray 标 Mux.Cool
        SampleCase("VMessBean/329572d1/vmess-http-none", CORE_AUTO, false, null),
        SampleCase("VMessBean/329572d1/vmess-http-none", CORE_XRAY, false, CORE_XRAY, setOf(MUX_COOL), mapOf("muxType" to 3)),
        // v4 VMess quic 手动 Xray：quic 在 Xray 上报错，但当时选的就是 Xray
        SampleCase("VMessBean/04da8864/vmess-quic-none", CORE_XRAY, false, CORE_XRAY, setOf(MUX_COOL), mapOf("muxType" to 3)),
        // v0 VMess ws：没开 mux、已有头名，手动 Xray 也没什么可标
        SampleCase("VMessBean/9d78e4f2/vmess-ws-tls", CORE_XRAY, false, null),
        // 默认值样本：VMess、不带 TLS；core 为 mihomo 或非法值时规范成手动 sing-box，bean 列不改
        SampleCase("VMessBean/dd8f56d3/default", CORE_AUTO, false, null),
        SampleCase("VMessBean/dd8f56d3/default", CORE_MIHOMO, false, CORE_SING_BOX, setOf(CORE_NORMALIZED)),
        SampleCase("VMessBean/2c3a6164/default", 7, false, CORE_SING_BOX, setOf(CORE_NORMALIZED)),
        // VMess 手动 mihomo 且开了 smux：当时走 sing-box，只规范 core
        SampleCase("VMessBean/aa275d5e/vmess-tcp-tls", CORE_MIHOMO, false, CORE_SING_BOX, setOf(CORE_NORMALIZED)),
    )

    @Test
    fun `Kryo 样本的真实字节逐类核对`() {
        for (case in sampleCases) {
            val s = sample(case.sample)
            val where = "${case.sample} core=${case.core} global=${case.global}"
            assertTrue(where, s.versions.getValue("StandardV2RayBean") < 7)
            val result = upgrade(TYPE_VMESS, case.core, s.bytes, case.global)
            if (case.expectedCore == null) {
                assertSame(where, LegacyRowUpgrade.Unchanged, result)
                continue
            }
            result as LegacyRowUpgrade.Changed
            assertEquals(where, case.expectedCore, result.core)
            assertEquals(where, case.changes, result.changes)
            if (case.overrides.isEmpty()) {
                assertEquals("$where：只改 core 时不写 bean 列", null, result.bean)
            } else {
                assertRewritten(where, result.bean!!, s.expected, case.overrides)
            }
        }
    }

    // ---- 旧黄金基线（K1 重新采集之前，1.8.0-a1 起的实现在模拟器上写出的 v6 字节）里的三个 VLESS 节点，
    // 覆盖 Kryo 样本没有的取值：没填 uTLS 指纹的非 REALITY TLS、没有头名的 ws early data

    private fun golden(b64: String): ByteArray = Base64.getDecoder().decode(b64)

    // xray-vless-tls-tcp：VLESS、tcp、TLS、没填指纹、没开 mux
    private val vlessTlsTcp = golden(
        "BgAAAHZsZXNzLmV4YW1wbGUuY2/tuwEAAKU2YjFkMmYzYS00YzVlLTRmN2EtOWIwYy0xZDJlM2Y0YTViNmOB/////3Rj8HRs83ZsZXNzLmV4YW1wbGUu" +
            "Y2/taDIsaHR0cC8xLrGBAIGBgQCBAAAAAAAAAAAAAAEAAACBgQEAAABnb2xkZW4teHJheS10bPOBgQ==",
    )

    // xray-vless-ws-maxearlydata：VLESS、ws、TLS、wsMaxEarlyData = 1024、没有头名、路径 /golden-ws?x=1
    private val vlessWsEarlyData = golden(
        "BgAAAHZsZXNzLmV4YW1wbGUuY2/tuwEAAKU2YjFkMmYzYS00YzVlLTRmN2EtOWIwYy0xZDJlM2Y0YTViNmOB/////3fzY2RuLmV4YW1wbGUub3LnL2dv" +
            "bGRlbi13cz94PbEABAAAgXRs82Nkbi5leGFtcGxlLm9y54GBAIGBgQCBAAAAAAAAAAAAAAEAAACBgQEAAABnb2xkZW4teHJheS13cy1l5IGB",
    )

    // xray-vless-mux：VLESS、tcp、TLS、没填指纹、开了 h2mux
    private val vlessMux = golden(
        "BgAAAHZsZXNzLmV4YW1wbGUuY2/tuwEAAKU2YjFkMmYzYS00YzVlLTRmN2EtOWIwYy0xZDJlM2Y0YTViNmOB/////3Rj8HRs83ZsZXNzLmV4YW1wbGUu" +
            "Y2/tgYEAgYGBAIEAAAAAAQAAAAAAAAAAAIGBAQAAAGdvbGRlbi14cmF5LW11+IGB",
    )

    @Test
    fun `旧黄金基线的 VLESS：自动选核时补指纹、补头名、标 Mux Cool`() {
        val before = decode(vlessTlsTcp)
        assertTrue(before.isVLESS)
        assertEquals("", before.utlsFingerprint)

        val tls = upgrade(TYPE_VMESS, CORE_AUTO, vlessTlsTcp) as LegacyRowUpgrade.Changed
        assertEquals(setOf(UTLS_FIREFOX), tls.changes)
        assertEquals(CORE_AUTO, tls.core)
        decode(tls.bean!!).let {
            assertEquals("firefox", it.utlsFingerprint)
            assertEquals(before.apply { utlsFingerprint = "firefox" }, it)
        }
        // 全局「允许不安全」打开时当时就走 sing-box，不动
        assertSame(LegacyRowUpgrade.Unchanged, upgrade(TYPE_VMESS, CORE_AUTO, vlessTlsTcp, global = true))
        // 手动 Xray 留在 Xray，不补
        assertSame(LegacyRowUpgrade.Unchanged, upgrade(TYPE_VMESS, CORE_XRAY, vlessTlsTcp))

        val ws = upgrade(TYPE_VMESS, CORE_AUTO, vlessWsEarlyData) as LegacyRowUpgrade.Changed
        assertEquals(setOf(WS_EARLY_DATA_HEADER, UTLS_FIREFOX), ws.changes)
        decode(ws.bean!!).let {
            assertEquals(WS_EARLY_DATA_PROTOCOL_HEADER, it.earlyDataHeaderName)
            assertEquals(1024, it.wsMaxEarlyData)
            assertEquals("/golden-ws?x=1", it.path)
            assertEquals("firefox", it.utlsFingerprint)
        }

        val mux = upgrade(TYPE_VMESS, CORE_AUTO, vlessMux) as LegacyRowUpgrade.Changed
        // 标成 Mux.Cool 之后留在 Xray，不补指纹
        assertEquals(setOf(MUX_COOL), mux.changes)
        decode(mux.bean!!).let {
            assertEquals(3, it.muxType)
            assertEquals("", it.utlsFingerprint)
        }
        // 手动 Xray 同样标 Mux.Cool
        assertEquals(setOf(MUX_COOL), (upgrade(TYPE_VMESS, CORE_XRAY, vlessMux) as LegacyRowUpgrade.Changed).changes)
    }

    // ---- 没有 bean 可读的行：只做规则 c，不读 bean，也不取全局设置

    @Test
    fun `不读 bean 的行只规范 core`() {
        val cases = listOf(
            Triple(TYPE_TROJAN, CORE_XRAY, CORE_AUTO),
            Triple(TYPE_TROJAN, CORE_AUTO, null),
            Triple(TYPE_ANYTLS, CORE_XRAY, CORE_SING_BOX),
            Triple(TYPE_ANYTLS, CORE_MIHOMO, null),
            Triple(TYPE_ANYTLS, CORE_AUTO, null),
            Triple(TYPE_SOCKS, CORE_MIHOMO, CORE_AUTO),
            Triple(TYPE_HYSTERIA, CORE_AUTO, null),
            // VMess / Trojan 行没有 bean 字节（损坏数据）：同样只规范 core
            Triple(TYPE_VMESS, CORE_MIHOMO, CORE_SING_BOX),
            Triple(TYPE_VMESS, CORE_XRAY, null),
        )
        for ((type, core, expected) in cases) {
            for (bytes in listOf(null, ByteArray(0))) {
                if (type != TYPE_VMESS && type != TYPE_TROJAN && bytes != null) continue
                val result = upgradeLegacyRow(type, core, bytes) { error("不该读全局设置") }
                if (expected == null) {
                    assertSame("type=$type core=$core", LegacyRowUpgrade.Unchanged, result)
                } else {
                    result as LegacyRowUpgrade.Changed
                    assertEquals("type=$type core=$core", expected, result.core)
                    assertEquals(null, result.bean)
                    assertEquals(setOf(CORE_NORMALIZED), result.changes)
                }
            }
        }
        // VMess / Trojan 以外的行即使带着字节也不读（迁移不查它们的 bean 列，这里传什么都一样）
        assertSame(LegacyRowUpgrade.Unchanged, upgradeLegacyRow(TYPE_ANYTLS, CORE_AUTO, byteArrayOf(1, 2, 3)) { error("不该读") })
        assertSame(LegacyRowUpgrade.Unchanged, upgradeLegacyRow(TYPE_SOCKS, CORE_AUTO, byteArrayOf(1, 2, 3)) { error("不该读") })
    }

    // bean 列不动；core 列照样按规则 c 规范（不看 bean），也不取全局设置
    @Test
    fun `读不出的 bean 只规范 core`() {
        val vmess = sample("VMessBean/dd8f56d3/vmess-tcp-tls").bytes
        val trojan = sample("TrojanBean/aa275d5e/trojan-tcp-tls-ech").bytes
        val cases = listOf(
            Triple(TYPE_VMESS, CORE_XRAY, null),
            Triple(TYPE_VMESS, CORE_MIHOMO, CORE_SING_BOX),
            Triple(TYPE_VMESS, -1, CORE_SING_BOX),
            Triple(TYPE_TROJAN, CORE_AUTO, null),
            Triple(TYPE_TROJAN, CORE_XRAY, CORE_AUTO),
        )
        for ((type, core, expected) in cases) {
            val bytes = if (type == TYPE_VMESS) vmess else trojan
            for (broken in listOf(bytes.copyOf(bytes.size / 2), byteArrayOf(7, 0, 0, 0, 1))) {
                val result = upgradeLegacyRow(type, core, broken) { error("读不出时不该读全局设置") }
                result as LegacyRowUpgrade.Unreadable
                assertEquals("type=$type core=$core", expected, result.core)
            }
        }
    }

    // ---- 规则 e：越界的 packetEncoding / muxType（被旧版读错位又写回的行）。历史样本里没有这种取值，用样本读出的
    // bean 改出越界值再序列化

    private fun <T : StandardV2RayBean> oddBytes(bean: T, id: String, block: T.() -> Unit): ByteArray =
        KryoConverters.serialize(KryoConverters.deserialize(bean, sample(id).bytes).apply(block))

    @Test
    fun `规则 e：越界取值规范成 1_8_0-a3 实际生效的值`() {
        // VMess：当时走 sing-box（没有证书指纹），packetEncoding = 5、开了 mux 且 muxType = 3
        val vmess = oddBytes(VMessBean(), "VMessBean/329572d1/vmess-http-none") { packetEncoding = 5; enableMux = true; muxType = 3 }
        val vmessResult = upgrade(TYPE_VMESS, CORE_AUTO, vmess) as LegacyRowUpgrade.Changed
        assertEquals(setOf(PACKET_ENCODING_NORMALIZED, MUX_TYPE_NORMALIZED), vmessResult.changes)
        decode(vmessResult.bean!!).let {
            assertEquals(0, it.packetEncoding)
            assertEquals(MUX_H2MUX, it.muxType)
        }
        // Trojan 行读 trojanBean：开了 mux 且 muxType = 7
        val trojan = oddBytes(TrojanBean(), "TrojanBean/aa275d5e/trojan-tcp-tls-ech") { muxType = 7 }
        val trojanResult = upgrade(TYPE_TROJAN, CORE_AUTO, trojan) as LegacyRowUpgrade.Changed
        assertEquals(setOf(MUX_TYPE_NORMALIZED), trojanResult.changes)
        assertEquals(CORE_AUTO, trojanResult.core)
        KryoConverters.deserialize(TrojanBean(), trojanResult.bean!!).let {
            assertEquals(MUX_H2MUX, it.muxType)
            assertTrue(it.enableMux)
        }
        // Trojan 的手动 Xray 同时改回自动
        val trojanXray = upgrade(TYPE_TROJAN, CORE_XRAY, trojan) as LegacyRowUpgrade.Changed
        assertEquals(setOf(MUX_TYPE_NORMALIZED, CORE_NORMALIZED), trojanXray.changes)
        assertEquals(CORE_AUTO, trojanXray.core)
        // 没越界的 Trojan 行：读了 bean，什么都不改
        assertSame(LegacyRowUpgrade.Unchanged, upgrade(TYPE_TROJAN, CORE_AUTO, sample("TrojanBean/aa275d5e/trojan-tcp-tls-ech").bytes))
    }

    // 全部 v7 以前的 VMessBean / TrojanBean 样本 × 各种 core × 全局开关：严格读都读得出；改写过的字节都是 v7、能读回，
    // 除了报告的字段外与原样一致；没改写的结论与直接对 bean 标注一致
    @Test
    fun `全部旧样本：改写的字节只差报告的字段`() {
        val fieldOf = mapOf(
            MUX_COOL to "muxType", WS_EARLY_DATA_HEADER to "earlyDataHeaderName", UTLS_FIREFOX to "utlsFingerprint",
            PACKET_ENCODING_NORMALIZED to "packetEncoding", MUX_TYPE_NORMALIZED to "muxType",
        )
        val types = mapOf(VMessBean::class.java.name to TYPE_VMESS, TrojanBean::class.java.name to TYPE_TROJAN)
        var rewritten = 0
        var trojanSamples = 0
        for (s in KryoSamples.samples.filter { it.className in types && it.versions.getValue("StandardV2RayBean") < 7 }) {
            val type = types.getValue(s.className)
            if (type == TYPE_TROJAN) trojanSamples++
            fun read(bytes: ByteArray): StandardV2RayBean =
                if (type == TYPE_VMESS) decode(bytes) else KryoConverters.deserialize(TrojanBean(), bytes)
            for (core in listOf(CORE_AUTO, CORE_SING_BOX, CORE_XRAY, CORE_MIHOMO, -1)) for (global in listOf(false, true)) {
                val where = "${s.id} core=$core global=$global"
                val direct = read(s.bytes).let { upgradeLegacyProfile(type, core, it, global) }
                when (val result = upgrade(type, core, s.bytes, global)) {
                    LegacyRowUpgrade.Unchanged -> assertFalse(where, direct.changed)
                    is LegacyRowUpgrade.Unreadable -> throw AssertionError(where, result.error)
                    is LegacyRowUpgrade.Changed -> {
                        assertEquals(where, direct.changes, result.changes)
                        assertEquals(where, direct.core, result.core)
                        val bytes = result.bean ?: continue
                        rewritten++
                        val after = KryoSamples.fieldsOf(read(bytes))
                        val before = KryoSamples.fieldsOf(read(s.bytes))
                        val changed = after.keys.filter { after[it] != before[it] }.toSet()
                        assertEquals(where, result.changes.mapNotNull { fieldOf[it] }.toSet(), changed)
                    }
                }
            }
        }
        assertTrue(rewritten > 0)
        assertTrue(trojanSamples > 0)
    }
}
