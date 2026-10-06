package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_MIHOMO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_HTTP
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.LegacyV6Bytes
import io.nekohasekai.sagernet.fmt.kryo.KryoSamples
import io.nekohasekai.sagernet.fmt.putBean
import io.nekohasekai.sagernet.fmt.putByteArray
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 旧备份恢复时的标注（BackupRestore.upgradeLegacyProfiles / restoredGlobalAllowInsecure）。decode 本身要 Parcel 与
// org.json，JVM 上跑不了；这里用历史实现写出的真实字节构造 decode 解出的实体，测它在末尾调用的两个函数
class BackupRestoreLegacyTest {

    private fun sampleEntity(id: String) = KryoSamples.read(KryoSamples.samples.single { it.id == id }) as ProxyEntity

    private fun entity(type: Int, core: Int, bytes: ByteArray) = ProxyEntity(id = 1, groupId = 1, type = type).apply {
        putByteArray(bytes)
        this.core = core
    }

    private val ProxyEntity.standard get() = requireBean() as StandardV2RayBean

    private fun noGlobal(): Boolean = error("没有待标注的节点时不该读全局设置")

    @Test
    fun `备份里的实体按自己的 core 列标注，标注后清掉标记`() {
        // ProxyEntity v1、内嵌 v6 VMess：手动 Xray、开了 smux
        val xray = sampleEntity("ProxyEntity/dd8f56d3/entity-VMessBean")
        // ProxyEntity v0（没有 core 列）、内嵌 v4 VMess：自动、REALITY、没有证书指纹，当时走 sing-box
        val auto = sampleEntity("ProxyEntity/bbbdf577/entity-VMessBean")
        // v6 Trojan 的 core 列是 2：当时 Trojan 不读 core，规范回自动
        val trojan = entity(TYPE_TROJAN, CORE_XRAY, KryoSamples.samples.single { it.id == "TrojanBean/dd8f56d3/trojan-tcp-tls-ech" }.bytes)
        // v6 HTTP 的 core 列是 1：同样规范回自动
        val http = entity(TYPE_HTTP, CORE_SING_BOX, KryoSamples.samples.single { it.id == "HttpBean/dd8f56d3/http-tcp-tls-ech" }.bytes)
        // 不是 StandardV2Ray 系：没有标记，bean 不动；core 列是 2，Shadowsocks 不能选核，规范回自动
        val shadowsocks = sampleEntity("ProxyEntity/dd8f56d3/entity-ShadowsocksBean")
        val ssBeanBefore = KryoConverters.serialize(shadowsocks.requireBean())
        assertEquals(CORE_XRAY, shadowsocks.core)
        for (e in listOf(xray, auto, trojan, http)) assertTrue(e.standard.legacyUnlabeled)

        var reads = 0
        val changed = BackupRestore.upgradeLegacyProfiles(listOf(xray, auto, trojan, http, shadowsocks)) { reads++; false }
        assertEquals(4, changed)
        assertEquals("全局设置只读一次", 1, reads)

        assertEquals(CORE_XRAY, xray.core)
        assertEquals(3, xray.standard.muxType)
        assertEquals(CORE_AUTO, auto.core)
        assertEquals(1, auto.standard.muxType)
        assertEquals(CORE_AUTO, trojan.core)
        assertEquals(1, trojan.standard.muxType) // Trojan 当时走 sing-box，sing-mux 照旧
        assertEquals(CORE_AUTO, http.core)
        for (e in listOf(xray, auto, trojan, http)) assertFalse(e.standard.legacyUnlabeled)
        assertEquals(CORE_AUTO, shadowsocks.core)
        assertTrue(ssBeanBefore.contentEquals(KryoConverters.serialize(shadowsocks.requireBean())))

        // 再调用一次什么都不改，也不读全局设置
        assertEquals(0, BackupRestore.upgradeLegacyProfiles(listOf(xray, auto, trojan, http, shadowsocks), ::noGlobal))
    }

    @Test
    fun `不是 StandardV2Ray 系的节点只规范手动核心值，不读全局设置`() {
        fun anytls(core: Int) = ProxyEntity(id = 1, groupId = 1).putBean(AnyTLSBean().apply {
            name = "anytls"
            serverAddress = "203.0.113.10"
            serverPort = 8443
            password = "fake-password"
            initializeDefaultValues()
        }).apply { this.core = core }
        fun socks(core: Int) = ProxyEntity(id = 2, groupId = 1).putBean(SOCKSBean().apply {
            name = "socks"
            serverAddress = "192.0.2.1"
            serverPort = 1080
            initializeDefaultValues()
        }).apply { this.core = core }
        // AnyTLS 能选 0 / 1 / 3，其余值（Xray、非法值）当时落到 sing-box，规范成手动 sing-box
        val anyTlsCases = listOf(CORE_AUTO to CORE_AUTO, CORE_SING_BOX to CORE_SING_BOX, CORE_MIHOMO to CORE_MIHOMO,
            CORE_XRAY to CORE_SING_BOX, 7 to CORE_SING_BOX, -1 to CORE_SING_BOX)
        for ((before, after) in anyTlsCases) {
            val entity = anytls(before)
            val beanBefore = KryoConverters.serialize(entity.requireBean())
            assertEquals("AnyTLS core $before", if (before == after) 0 else 1, BackupRestore.upgradeLegacyProfiles(listOf(entity), ::noGlobal))
            assertEquals("AnyTLS core $before", after, entity.core)
            assertTrue(beanBefore.contentEquals(KryoConverters.serialize(entity.requireBean())))
        }
        // 其余不能选核的协议一律改回自动
        for (before in listOf(CORE_AUTO, CORE_SING_BOX, CORE_XRAY, CORE_MIHOMO, 7)) {
            val entity = socks(before)
            assertEquals(if (before == CORE_AUTO) 0 else 1, BackupRestore.upgradeLegacyProfiles(listOf(entity), ::noGlobal))
            assertEquals(CORE_AUTO, entity.core)
        }
        // K1 写出的备份：取值都在范围内，什么都不改
        val current = listOf(anytls(CORE_AUTO), anytls(CORE_SING_BOX), anytls(CORE_MIHOMO), socks(CORE_AUTO))
        assertEquals(0, BackupRestore.upgradeLegacyProfiles(current, ::noGlobal))
    }

    @Test
    fun `全局允许不安全决定 VLESS 当时走哪个核心`() {
        val insecure = entity(TYPE_VMESS, CORE_AUTO, LegacyV6Bytes.vlessTlsTcp)
        assertEquals(0, BackupRestore.upgradeLegacyProfiles(listOf(insecure)) { true })
        assertEquals("", insecure.standard.utlsFingerprint)

        val secure = entity(TYPE_VMESS, CORE_AUTO, LegacyV6Bytes.vlessTlsTcp)
        assertEquals(1, BackupRestore.upgradeLegacyProfiles(listOf(secure)) { false })
        assertEquals("firefox", secure.standard.utlsFingerprint)

        val ws = entity(TYPE_VMESS, CORE_AUTO, LegacyV6Bytes.vlessWsEarlyData)
        BackupRestore.upgradeLegacyProfiles(listOf(ws)) { false }
        assertEquals("Sec-WebSocket-Protocol", ws.standard.earlyDataHeaderName)
        assertEquals("firefox", ws.standard.utlsFingerprint)
    }

    @Test
    fun `K1 写出的节点不标注`() {
        // 当前实现写出的 v7：自动选核、TLS、没填指纹、开了 h2mux——若当作旧数据会被标成 Mux.Cool
        val bean = KryoConverters.deserialize(VMessBean(), LegacyV6Bytes.vlessMux)
        val current = entity(TYPE_VMESS, CORE_AUTO, KryoConverters.serialize(bean))
        assertFalse(current.standard.legacyUnlabeled)
        assertEquals(0, BackupRestore.upgradeLegacyProfiles(listOf(current), ::noGlobal))
        assertEquals(0, current.standard.muxType)
        // 同样字段的 v6 字节会标
        val legacy = entity(TYPE_VMESS, CORE_AUTO, LegacyV6Bytes.vlessMux)
        assertEquals(1, BackupRestore.upgradeLegacyProfiles(listOf(legacy)) { false })
        assertEquals(3, legacy.standard.muxType)
    }

    @Test
    fun `全局允许不安全：导入设置时取备份里的值，否则取本机的值`() {
        fun pair(value: Boolean) = KeyValuePair(Key.GLOBAL_ALLOW_INSECURE).put(value)
        val other = KeyValuePair(Key.LOG_LEVEL).put("3")
        assertTrue(BackupRestore.restoredGlobalAllowInsecure(listOf(other, pair(true))) { false })
        assertFalse(BackupRestore.restoredGlobalAllowInsecure(listOf(pair(false))) { true })
        // 导入设置但备份里没有这个键：导入后本机的设置整表替换，取默认值 false，不读本机
        assertFalse(BackupRestore.restoredGlobalAllowInsecure(listOf(other)) { error("不该读本机设置") })
        assertFalse(BackupRestore.restoredGlobalAllowInsecure(emptyList()) { error("不该读本机设置") })
        // 不导入设置
        assertTrue(BackupRestore.restoredGlobalAllowInsecure(null) { true })
        assertFalse(BackupRestore.restoredGlobalAllowInsecure(null) { false })
    }
}
