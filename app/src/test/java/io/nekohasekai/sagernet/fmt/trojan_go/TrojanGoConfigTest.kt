package io.nekohasekai.sagernet.fmt.trojan_go

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import io.nekohasekai.sagernet.fmt.ExternalDialTarget
import io.nekohasekai.sagernet.fmt.ExternalDialTarget.Direct
import io.nekohasekai.sagernet.fmt.ExternalDialTarget.Mapped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 基线没走到的 buildTrojanGoConfig 分支：预期值按改写前的逻辑人工推出。 */
class TrojanGoConfigTest {

    private fun bean(
        serverAddress: String = "node.example.com",
        sni: String = "",
        type: String = "original",
        encryption: String = "none",
        allowInsecure: Boolean = false,
    ) = TrojanGoBean().apply {
        this.serverAddress = serverAddress
        this.serverPort = 443
        this.password = "pw"
        this.sni = sni
        this.type = type
        this.host = "h.example.com"
        this.path = "/p"
        this.encryption = encryption
        this.allowInsecure = allowInsecure
    }

    // 拨号目标默认经映射（本机的映射端口 1234）
    private fun build(
        b: TrojanGoBean,
        logLevel: Int = 2,
        ipv6Mode: Int = IPv6Mode.DISABLE,
        target: ExternalDialTarget = Mapped(1234),
    ): JsonObject = JsonParser.parseString(
        b.buildTrojanGoConfig(1080, target, ExternalCoreSettings(logLevel, ipv6Mode, false))
    ).asJsonObject

    @Test
    fun `log_level 逐档映射`() {
        val expected = mapOf(0 to 2, 1 to 2, 2 to 1, 3 to 0, 4 to 0, 9 to 2)
        for ((input, out) in expected) {
            assertEquals("logLevel=$input", out, build(bean(), logLevel = input)["log_level"].asInt)
        }
    }

    @Test
    fun `prefer_ipv4 随 ipv6 模式`() {
        assertTrue(build(bean(), ipv6Mode = IPv6Mode.DISABLE)["tcp"].asJsonObject["prefer_ipv4"].asBoolean)
        assertTrue(build(bean(), ipv6Mode = IPv6Mode.ENABLE)["tcp"].asJsonObject["prefer_ipv4"].asBoolean)
        assertFalse(build(bean(), ipv6Mode = IPv6Mode.PREFER)["tcp"].asJsonObject["prefer_ipv4"].asBoolean)
        assertFalse(build(bean(), ipv6Mode = IPv6Mode.ONLY)["tcp"].asJsonObject["prefer_ipv4"].asBoolean)
    }

    @Test
    fun `拨号目标写入 remote：经映射时是本机的映射端口，不映射时是服务器本身`() {
        val mapped = build(bean(serverAddress = "192.0.2.2"))
        assertEquals("127.0.0.1", mapped["remote_addr"].asString)
        assertEquals(1234, mapped["remote_port"].asInt)
        assertEquals(1080, mapped["local_port"].asInt)
        val direct = build(bean(serverAddress = "192.0.2.2"), target = Direct)
        assertEquals("192.0.2.2", direct["remote_addr"].asString)
        assertEquals(443, direct["remote_port"].asInt)
        assertEquals(1080, direct["local_port"].asInt)
        // IPv6 服务器原样写，不加方括号
        assertEquals("2001:db8::2", build(bean(serverAddress = "2001:db8::2"), target = Direct)["remote_addr"].asString)
    }

    @Test
    fun `不映射且服务器地址为空时 remote_addr 省略`() {
        // 经 initializeDefaultValues 的节点不会这样，这里只钉住生成器本身的写法
        val b = bean(sni = "s.example.org").apply { serverAddress = null }
        assertFalse(build(b, target = Direct).has("remote_addr"))
        assertEquals("127.0.0.1", build(b)["remote_addr"].asString)
    }

    @Test
    fun `sni 为空且本地中转到域名节点时回退到域名`() {
        val ssl = build(bean(serverAddress = "node.example.com"))["ssl"].asJsonObject
        assertEquals("node.example.com", ssl["sni"].asString)
    }

    @Test
    fun `sni 为空且节点是 IP 时不写 sni`() {
        for (server in listOf("192.0.2.4", "2001:db8::4", "127.0.0.1")) {
            for (target in listOf(Mapped(1234), Direct)) {
                val ssl = build(bean(serverAddress = server), target = target)["ssl"].asJsonObject
                assertFalse("$server $target", ssl.has("sni"))
            }
        }
    }

    @Test
    fun `sni 为空且不经本地中转时不写 sni`() {
        val ssl = build(bean(serverAddress = "node.example.com"), target = Direct)["ssl"].asJsonObject
        assertFalse(ssl.has("sni"))
    }

    @Test
    fun `显式 sni 不论是否经映射都原样写`() {
        for (target in listOf(Mapped(1234), Direct)) {
            val ssl = build(bean(serverAddress = "192.0.2.4", sni = "s.example.org"), target = target)["ssl"].asJsonObject
            assertEquals("s.example.org", ssl["sni"].asString)
        }
    }

    @Test
    fun `只有 allowInsecure 时 ssl 只含 verify`() {
        val ssl = build(bean(serverAddress = "192.0.2.4", allowInsecure = true))["ssl"].asJsonObject
        assertEquals(setOf("verify"), ssl.keySet())
        assertFalse(ssl["verify"].asBoolean)
    }

    @Test
    fun `ws 与 ss 同时存在，键顺序保持`() {
        val c = build(bean(type = "ws", encryption = "ss;aes-256-gcm:a:b"))
        assertEquals(
            listOf(
                "run_type", "local_addr", "local_port", "remote_addr", "remote_port", "password",
                "log_level", "tcp", "websocket", "ssl", "shadowsocks"
            ),
            c.keySet().toList()
        )
        val ss = c["shadowsocks"].asJsonObject
        assertEquals("aes-256-gcm", ss["method"].asString)
        // password 取第一个冒号之后的全部
        assertEquals("a:b", ss["password"].asString)
    }

    @Test
    fun `ss 没有冒号时 method 取到末尾且 password 为空`() {
        val ss = build(bean(encryption = "ss;aes-128-gcm"))["shadowsocks"].asJsonObject
        assertEquals("aes-128-gcm", ss["method"].asString)
        assertEquals("", ss["password"].asString)
    }

    @Test
    fun `其它 encryption 与 type 不产生对应段`() {
        val c = build(bean(type = "h2", encryption = "plain"))
        assertFalse(c.has("websocket"))
        assertFalse(c.has("shadowsocks"))
        val d = build(bean(encryption = ""))
        assertFalse(d.has("shadowsocks"))
    }
}
