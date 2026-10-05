package io.nekohasekai.sagernet.fmt.hysteria

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.ExternalDialTarget
import io.nekohasekai.sagernet.fmt.ExternalDialTarget.Direct
import io.nekohasekai.sagernet.fmt.ExternalDialTarget.Mapped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// 基线没有走到的 buildHysteria1Config 分支：预期值按生成函数的逻辑人工推出
class Hysteria1ConfigTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bean(block: HysteriaBean.() -> Unit = {}): HysteriaBean = HysteriaBean().apply {
        protocolVersion = 1
        serverAddress = "hy1.example.com"
        serverPorts = "8443"
        initializeDefaultValues()
        block()
    }

    // 拨号目标默认不映射（最先拨号的一跳免映射）；链成员经映射，拨本机的映射端口
    private fun HysteriaBean.build(
        port: Int = 2080,
        target: ExternalDialTarget = Direct,
        cacheFile: (() -> File)? = null,
    ): JsonObject = JsonParser.parseString(buildHysteria1Config(port, target, cacheFile)).asJsonObject

    private fun JsonObject.str(key: String) = get(key).asString

    @Test
    fun `默认值只输出必有的字段且顺序固定`() {
        // 协议为 UDP、没有认证、没有 alpn、没有 SNI：这些字段都不出现
        val json = bean().build()
        assertEquals(
            listOf("server", "up_mbps", "down_mbps", "socks5", "retry", "fast_open", "lazy_start", "obfs", "hop_interval"),
            json.keySet().toList(),
        )
        assertEquals("hy1.example.com:8443", json.str("server"))
        assertEquals(10, json["up_mbps"].asInt)
        assertEquals(50, json["down_mbps"].asInt)
        assertEquals("127.0.0.1:2080", json.getAsJsonObject("socks5").str("listen"))
        assertEquals(5, json["retry"].asInt)
        assertTrue(json["fast_open"].asBoolean)
        assertTrue(json["lazy_start"].asBoolean)
        assertEquals("", json.str("obfs"))
        assertEquals(10, json["hop_interval"].asInt)
    }

    @Test
    fun `上下行带宽与跳跃间隔取节点的值`() {
        val json = bean { uploadMbps = 77; downloadMbps = 300; hopInterval = 8 }.build()
        assertEquals(77, json["up_mbps"].asInt)
        assertEquals(300, json["down_mbps"].asInt)
        assertEquals(8, json["hop_interval"].asInt)
    }

    @Test
    fun `跳跃间隔为零时仍输出`() {
        assertEquals(0, bean { hopInterval = 0 }.build()["hop_interval"].asInt)
    }

    @Test
    fun `IPv6 服务器地址加方括号`() {
        assertEquals("[2001:db8::1]:8443", bean { serverAddress = "2001:db8::1" }.build().str("server"))
    }

    @Test
    fun `多端口范围合并输出`() {
        assertEquals(
            "hy1.example.com:20000-20010,443",
            bean { serverPorts = "20000-20010, 443" }.build().str("server"),
        )
    }

    @Test
    fun `链成员连映射端口`() {
        val json = bean().build(target = Mapped(41234))
        assertEquals("127.0.0.1:41234", json.str("server"))
    }

    @Test
    fun `服务器本身就是本机时不走映射端口`() {
        val json = bean { serverAddress = "127.0.0.1" }.build(target = Mapped(41234))
        assertEquals("127.0.0.1:8443", json.str("server"))
        assertFalse(json.has("server_name"))
    }

    @Test
    fun `链成员且没有 SNI 时域名当作 server_name`() {
        assertEquals("hy1.example.com", bean().build(target = Mapped(40000)).str("server_name"))
    }

    @Test
    fun `链成员且没有 SNI 时 IP 地址不当作 server_name`() {
        assertFalse(bean { serverAddress = "192.0.2.10" }.build(target = Mapped(40000)).has("server_name"))
        assertFalse(bean { serverAddress = "2001:db8::1" }.build(target = Mapped(40000)).has("server_name"))
    }

    @Test
    fun `非链成员且没有 SNI 时不输出 server_name`() {
        for (server in listOf("hy1.example.com", "192.0.2.10", "2001:db8::1", "127.0.0.1")) {
            assertFalse(server, bean { serverAddress = server }.build().has("server_name"))
        }
    }

    @Test
    fun `不映射时按 serverPorts 直拨服务器，与 serverPort 无关`() {
        val json = bean { serverPorts = "20000-20010"; serverPort = 1080 }.build()
        assertEquals("hy1.example.com:20000-20010", json.str("server"))
        assertEquals("[2001:db8::1]:8443", bean { serverAddress = "2001:db8::1" }.build().str("server"))
        assertEquals("127.0.0.1:8443", bean { serverAddress = "127.0.0.1" }.build().str("server"))
    }

    @Test
    fun `经映射时拨映射端口，不论服务器是域名、IPv4 还是 IPv6`() {
        for (server in listOf("hy1.example.com", "192.0.2.10", "2001:db8::1")) {
            assertEquals(server, "127.0.0.1:41234", bean { serverAddress = server }.build(target = Mapped(41234)).str("server"))
        }
    }

    @Test
    fun `SNI 优先于回退值`() {
        assertEquals("sni.example.org", bean { sni = "sni.example.org" }.build(target = Mapped(40000)).str("server_name"))
        assertEquals("sni.example.org", bean { sni = "sni.example.org" }.build().str("server_name"))
    }

    @Test
    fun `协议字段`() {
        assertEquals("faketcp", bean { protocol = HysteriaBean.PROTOCOL_FAKETCP }.build().str("protocol"))
        assertEquals("wechat-video", bean { protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO }.build().str("protocol"))
        assertFalse(bean { protocol = HysteriaBean.PROTOCOL_UDP }.build().has("protocol"))
        // protocol 排在 server 之后、up_mbps 之前
        val keys = bean { protocol = HysteriaBean.PROTOCOL_FAKETCP }.build().keySet().toList()
        assertEquals(listOf("server", "protocol", "up_mbps"), keys.take(3))
    }

    @Test
    fun `认证方式`() {
        val none = bean { authPayloadType = HysteriaBean.TYPE_NONE; authPayload = "ignored" }.build()
        assertFalse(none.has("auth"))
        assertFalse(none.has("auth_str"))
        val base64 = bean { authPayloadType = HysteriaBean.TYPE_BASE64; authPayload = "Zm9v" }.build()
        assertEquals("Zm9v", base64.str("auth"))
        assertFalse(base64.has("auth_str"))
        val string = bean { authPayloadType = HysteriaBean.TYPE_STRING; authPayload = "foo" }.build()
        assertEquals("foo", string.str("auth_str"))
        assertFalse(string.has("auth"))
    }

    @Test
    fun `混淆口令原样输出`() {
        assertEquals("p/a\"ss", bean { obfuscation = "p/a\"ss" }.build().str("obfs"))
    }

    @Test
    fun `alpn 只取第一个`() {
        assertEquals("h3", bean { alpn = "h3\nhysteria" }.build().str("alpn"))
        assertEquals("h3", bean { alpn = " h3 , hysteria" }.build().str("alpn"))
    }

    @Test
    fun `alpn 为空或只有分隔符时不输出`() {
        assertFalse(bean { alpn = "" }.build().has("alpn"))
        assertFalse(bean { alpn = " ,\n, " }.build().has("alpn"))
    }

    @Test
    fun `没有 cacheFile 回调时不写 CA`() {
        val json = bean { caText = "CERT" }.build(cacheFile = null)
        assertFalse(json.has("ca"))
    }

    @Test
    fun `CA 文本为空白时不领临时文件`() {
        var called = false
        val json = bean { caText = "  \n" }.build { called = true; tmp.newFile() }
        assertFalse(called)
        assertFalse(json.has("ca"))
    }

    @Test
    fun `CA 写入临时文件并输出路径`() {
        val file = tmp.newFile("hy.ca")
        val json = bean { caText = "-----BEGIN CERTIFICATE-----" }.build { file }
        assertEquals(file.absolutePath, json.str("ca"))
        assertEquals("-----BEGIN CERTIFICATE-----", file.readText())
        // ca 排在 alpn 之后、hop_interval 之前
        val keys = json.keySet().toList()
        assertEquals(listOf("ca", "hop_interval"), keys.takeLast(2))
    }

    @Test
    fun `可选开关`() {
        val off = bean().build()
        assertFalse(off.has("insecure"))
        assertFalse(off.has("disable_mtu_discovery"))
        val on = bean { allowInsecure = true; disableMtuDiscovery = true }.build()
        assertTrue(on["insecure"].asBoolean)
        assertTrue(on["disable_mtu_discovery"].asBoolean)
    }

    @Test
    fun `接收窗口只输出大于零的`() {
        val none = bean().build()
        assertFalse(none.has("recv_window_conn"))
        assertFalse(none.has("recv_window"))
        val streamOnly = bean { streamReceiveWindow = 65536 }.build()
        assertEquals(65536, streamOnly["recv_window_conn"].asInt)
        assertFalse(streamOnly.has("recv_window"))
        val connOnly = bean { connectionReceiveWindow = 70000 }.build()
        assertEquals(70000, connOnly["recv_window"].asInt)
        assertFalse(connOnly.has("recv_window_conn"))
    }

    @Test
    fun `全部可选字段齐全时的顺序`() {
        val file = tmp.newFile("hy.ca")
        val json = bean {
            protocol = HysteriaBean.PROTOCOL_FAKETCP
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = "pw"
            obfuscation = "ob"
            sni = "sni.example.org"
            alpn = "hysteria"
            caText = "CERT"
            allowInsecure = true
            streamReceiveWindow = 65536
            connectionReceiveWindow = 131072
            disableMtuDiscovery = true
        }.build { file }
        assertEquals(
            listOf(
                "server", "protocol", "up_mbps", "down_mbps", "socks5", "retry", "fast_open", "lazy_start",
                "obfs", "auth_str", "server_name", "alpn", "ca", "insecure", "recv_window_conn", "recv_window",
                "disable_mtu_discovery", "hop_interval",
            ),
            json.keySet().toList(),
        )
    }

    private fun assertFails(expected: Class<out Throwable>, message: String, block: () -> Unit) {
        try {
            block()
            fail("应当抛出 ${expected.name}")
        } catch (e: Throwable) {
            assertEquals(expected, e.javaClass)
            assertEquals(message, e.message)
        }
    }

    @Test
    fun `带证书固定时报错`() {
        assertFails(IllegalStateException::class.java, "certificate pin reached the hysteria plugin builder") {
            bean { certificateFingerprint = "AA" }.build()
        }
    }

    @Test
    fun `协议版本不是 1 时报错`() {
        assertFails(Exception::class.java, "error version: 2") {
            bean { protocolVersion = 2 }.build()
        }
    }

    @Test
    fun `证书固定的检查先于版本检查`() {
        assertFails(IllegalStateException::class.java, "certificate pin reached the hysteria plugin builder") {
            bean { certificateFingerprint = "AA"; protocolVersion = 2 }.build()
        }
    }

    @Test
    fun `接收窗口取值范围`() {
        val message = "hysteria 1 receive windows must be 0 or at least 65536"
        assertFails(IllegalArgumentException::class.java, message) { bean { streamReceiveWindow = 1 }.build() }
        assertFails(IllegalArgumentException::class.java, message) { bean { connectionReceiveWindow = 65535 }.build() }
        assertFails(IllegalArgumentException::class.java, message) { bean { streamReceiveWindow = -1 }.build() }
        // 边界值
        assertEquals(65536, bean { connectionReceiveWindow = 65536 }.build()["recv_window"].asInt)
    }

    @Test
    fun `跳跃间隔取值范围`() {
        val message = "hysteria 1 hop interval must be 0 or at least 8 seconds"
        assertFails(IllegalArgumentException::class.java, message) { bean { hopInterval = 7 }.build() }
        assertFails(IllegalArgumentException::class.java, message) { bean { hopInterval = -1 }.build() }
        assertEquals(8, bean { hopInterval = 8 }.build()["hop_interval"].asInt)
    }

    @Test
    fun `端口格式错误向外抛出`() {
        assertFails(IllegalArgumentException::class.java, "Invalid Hysteria port") {
            bean { serverPorts = "abc" }.build()
        }
    }
}
