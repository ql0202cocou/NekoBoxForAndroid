package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.kryo.KryoSamples
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.universalBean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// XHTTP 节点的存储：字段、归一化、Kryo 8 的读写、旧版残缺节点的判定
class XhttpBeanTest {

    private fun <T : StandardV2RayBean> roundTrip(bean: T, blank: T): T =
        KryoConverters.deserialize(blank, KryoConverters.serialize(bean))

    private fun vless(block: VMessBean.() -> Unit = {}) = VMessBean().apply {
        alterId = -1
        serverAddress = "xhttp.example.com"
        serverPort = 443
        uuid = "00000000-0000-4000-8000-000000000001"
        block()
        initializeDefaultValues()
    }

    // ---- 归一化

    @Test
    fun `默认值：mode 是 auto，extra 是空串`() {
        val bean = VMessBean().apply { initializeDefaultValues() }
        assertEquals("auto", bean.xhttpMode)
        assertEquals("", bean.xhttpExtra)
    }

    @Test
    fun `空白 mode 归一为 auto，空白 extra 归一为空串，已有值不动`() {
        for (blank in listOf(null, "", "  ", "\n")) {
            val bean = VMessBean().apply { xhttpMode = blank; xhttpExtra = blank; initializeDefaultValues() }
            assertEquals("auto", bean.xhttpMode)
            assertEquals("", bean.xhttpExtra)
        }
        val bean = VMessBean().apply { xhttpMode = "stream-up"; xhttpExtra = """{"noSSEHeader":true}"""; initializeDefaultValues() }
        assertEquals("stream-up", bean.xhttpMode)
        assertEquals("""{"noSSEHeader":true}""", bean.xhttpExtra)
    }

    @Test
    fun `splithttp 归一为 xhttp，大小写不敏感，其它传输方式不受影响`() {
        for (name in listOf("splithttp", "SplitHTTP", "SPLITHTTP")) {
            assertEquals("xhttp", VMessBean().apply { type = name; initializeDefaultValues() }.type)
        }
        assertEquals("xhttp", VMessBean().apply { type = "XHTTP"; initializeDefaultValues() }.type)
        assertEquals("http", VMessBean().apply { type = "h2"; initializeDefaultValues() }.type)
        assertEquals("tcp", VMessBean().apply { initializeDefaultValues() }.type)
        assertEquals("grpc", VMessBean().apply { type = "grpc"; initializeDefaultValues() }.type)
    }

    // ---- Kryo 8

    @Test
    fun `xhttp 的 host、path、mode、extra 经 Kryo 往返不丢`() {
        val bean = vless {
            type = "xhttp"
            host = "cdn.example.net"
            path = "/xh"
            xhttpMode = "packet-up"
            xhttpExtra = """{"xPaddingBytes":"100-1000"}"""
        }
        val back = roundTrip(bean, VMessBean())
        assertEquals("xhttp", back.type)
        assertEquals("cdn.example.net", back.host)
        assertEquals("/xh", back.path)
        assertEquals("packet-up", back.xhttpMode)
        assertEquals("""{"xPaddingBytes":"100-1000"}""", back.xhttpExtra)
        assertFalse(back.xhttpParametersLost())

        val trojan = TrojanBean().apply {
            type = "xhttp"; path = "/t"; xhttpMode = "stream-one"; password = "fake-trojan-password"
            initializeDefaultValues()
        }
        val trojanBack = roundTrip(trojan, TrojanBean())
        assertEquals("/t", trojanBack.path)
        assertEquals("stream-one", trojanBack.xhttpMode)
        assertEquals("fake-trojan-password", trojanBack.password)
    }

    @Test
    fun `splithttp 没来得及归一就序列化，读回后也归一为 xhttp 并带着参数`() {
        val bean = vless { type = "xhttp"; host = "h.example.net"; path = "/p" }
        bean.type = "splithttp"
        val back = roundTrip(bean, VMessBean())
        assertEquals("xhttp", back.type)
        assertEquals("/p", back.path)
        assertEquals("h.example.net", back.host)
    }

    @Test
    fun `其它传输方式不写 xhttp 的字段，字节与 mode、extra 无关`() {
        val a = vless { type = "ws"; host = "h.example.net"; path = "/w" }
        val b = vless { type = "ws"; host = "h.example.net"; path = "/w"; xhttpMode = "stream-up"; xhttpExtra = """{"noSSEHeader":true}""" }
        assertArrayEquals(KryoConverters.serialize(a), KryoConverters.serialize(b))
        val back = roundTrip(b, VMessBean())
        assertEquals("auto", back.xhttpMode)
        assertEquals("", back.xhttpExtra)
    }

    // 旧版（v7）字节里 xhttp / splithttp 只有 type：读出来 host / path 是空串，mode / extra 是默认值
    @Test
    fun `v7 字节里的 xhttp 与 splithttp 读成残缺节点`() {
        val ids = listOf(
            "VMessBean/9d46aca1/vless-xhttp-tls", "VMessBean/9d46aca1/vmess-xhttp-none",
            "VMessBean/9d46aca1/vless-splithttp-tls", "VMessBean/9d46aca1/vmess-splithttp-none",
            "TrojanBean/9d46aca1/trojan-xhttp-tls", "TrojanBean/9d46aca1/trojan-splithttp-tls",
        )
        for (id in ids) {
            val sample = KryoSamples.samples.single { it.id == id }
            assertEquals(id, 7, sample.versions.getValue("StandardV2RayBean"))
            val bean = KryoSamples.read(sample) as StandardV2RayBean
            assertEquals(id, "xhttp", bean.type)
            assertEquals(id, "", bean.host)
            assertEquals(id, "", bean.path)
            assertEquals(id, "auto", bean.xhttpMode)
            assertEquals(id, "", bean.xhttpExtra)
            assertTrue(id, bean.xhttpParametersLost())
        }
    }

    // ---- 残缺判定

    @Test
    fun `xhttp 节点 path 为空即参数已丢失，非空不算`() {
        assertTrue(vless { type = "xhttp" }.xhttpParametersLost())
        assertTrue(vless { type = "splithttp" }.xhttpParametersLost())
        assertTrue(VMessBean().apply { type = "XHTTP"; path = "  " }.xhttpParametersLost())
        assertFalse(vless { type = "xhttp"; path = "/" }.xhttpParametersLost())
        assertFalse(vless { type = "xhttp"; path = "/xh" }.xhttpParametersLost())
        // 其它传输方式的空 path 是常态
        for (t in listOf("tcp", "ws", "grpc", "http", "httpupgrade", "quic")) {
            assertFalse(t, vless { type = t }.xhttpParametersLost())
        }
    }

    @Test
    fun `新入口把 xhttp 的空 path 规范成斜杠`() {
        assertEquals("/", normalizedXhttpPath(null))
        assertEquals("/", normalizedXhttpPath(""))
        assertEquals("/", normalizedXhttpPath("  "))
        assertEquals("/", normalizedXhttpPath("/"))
        assertEquals("/xh", normalizedXhttpPath("/xh"))
        assertEquals("xh", normalizedXhttpPath("xh"))
    }

    // ---- sn:// 通用链接

    @Test
    fun `sn 通用链接：带参数的 xhttp 节点能导入，旧版残缺的拒绝`() {
        val ok = vless { type = "xhttp"; host = "cdn.example.net"; path = "/xh"; xhttpMode = "stream-up" }
        val imported = universalBean(TYPE_VMESS, KryoConverters.serialize(ok)) { false } as VMessBean
        assertEquals("/xh", imported.path)
        assertEquals("stream-up", imported.xhttpMode)

        val stale = KryoSamples.samples.single { it.id == "VMessBean/9d46aca1/vless-xhttp-tls" }
        val e = assertThrows(XhttpParametersLostException::class.java) {
            universalBean(TYPE_VMESS, stale.bytes) { false }
        }
        assertEquals("xhttp", e.transport)
        assertTrue(e is UnsupportedTransportException)
        assertTrue(e.message!!.contains("re-import"))

        val trojanStale = KryoSamples.samples.single { it.id == "TrojanBean/9d46aca1/trojan-splithttp-tls" }
        assertThrows(XhttpParametersLostException::class.java) {
            universalBean(TYPE_TROJAN, trojanStale.bytes) { false }
        }
    }
}
