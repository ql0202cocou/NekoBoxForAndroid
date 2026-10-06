package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.fmt.kryo.KryoSamples
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// 旧通用链接（sn://）导入时的标注（universalBean）：链接里没有 core 列，按自动选核标注。链接本身的 Base64 / zlib
// 解码走 android.util.Base64，JVM 上跑不了；这里直接喂解码后的 bean 字节（历史实现写出的真实字节）
class UniversalLegacyTest {

    private fun sample(id: String) = KryoSamples.samples.single { it.id == id }.bytes

    private fun parse(type: Int, bytes: ByteArray, global: Boolean = false) =
        universalBean(type, bytes) { global } as StandardV2RayBean

    private fun noGlobal(): Boolean = error("K1 写出的链接不该读全局设置")

    @Test
    fun `旧链接按自动选核标注`() {
        // VLESS、TLS、h2mux：当时自动选核走 Xray，跑的是 Mux.Cool
        parse(TYPE_VMESS, LegacyV6Bytes.vlessMux).let {
            assertEquals(3, it.muxType)
            assertEquals("", it.utlsFingerprint)
            assertFalse(it.legacyUnlabeled)
        }
        // VLESS、TLS、没填指纹：会换到 sing-box，补 firefox；全局「允许不安全」打开时当时就走 sing-box，不动
        assertEquals("firefox", parse(TYPE_VMESS, LegacyV6Bytes.vlessTlsTcp).utlsFingerprint)
        assertEquals("", parse(TYPE_VMESS, LegacyV6Bytes.vlessTlsTcp, global = true).utlsFingerprint)
        // ws early data 没有头名：补 Sec-WebSocket-Protocol
        parse(TYPE_VMESS, LegacyV6Bytes.vlessWsEarlyData).let {
            assertEquals("Sec-WebSocket-Protocol", it.earlyDataHeaderName)
            assertEquals("firefox", it.utlsFingerprint)
        }
        // v6 VMess：证书指纹 + smux，自动选核时走 Xray
        assertEquals(3, parse(TYPE_VMESS, sample("VMessBean/dd8f56d3/vmess-tcp-tls")).muxType)
        // v5 VLESS：REALITY、节点 allowInsecure、没有证书指纹，自动选核当时走 sing-box（备份里若是手动 Xray 则会标，
        // 链接没有 core 列，只能按自动）
        assertEquals(1, parse(TYPE_VMESS, sample("VMessBean/329572d1/vless-tcp-tls")).muxType)
        // Trojan 当时一律 sing-box
        parse(TYPE_TROJAN, sample("TrojanBean/dd8f56d3/trojan-tcp-tls-ech")).let {
            assertEquals(1, it.muxType)
            assertFalse(it.legacyUnlabeled)
        }
    }

    @Test
    fun `K1 写出的链接不标注`() {
        val bean = KryoConverters.deserialize(VMessBean(), LegacyV6Bytes.vlessMux)
        val parsed = universalBean(TYPE_VMESS, KryoConverters.serialize(bean), ::noGlobal) as VMessBean
        assertEquals(0, parsed.muxType)
        assertFalse(parsed.legacyUnlabeled)
    }
}
