package io.nekohasekai.sagernet.fmt.trojan_go

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 基线没走到的 buildTrojanGoConfig 分支：预期值按改写前的逻辑人工推出。 */
class TrojanGoConfigTest {

    private fun bean(
        serverAddress: String = "node.example.com",
        finalAddress: String = "127.0.0.1",
        sni: String = "",
        type: String = "original",
        encryption: String = "none",
        allowInsecure: Boolean = false,
    ) = TrojanGoBean().apply {
        this.serverAddress = serverAddress
        this.serverPort = 443
        this.finalAddress = finalAddress
        this.finalPort = 1234
        this.password = "pw"
        this.sni = sni
        this.type = type
        this.host = "h.example.com"
        this.path = "/p"
        this.encryption = encryption
        this.allowInsecure = allowInsecure
    }

    private fun build(
        b: TrojanGoBean,
        logLevel: Int = 2,
        ipv6Mode: Int = IPv6Mode.DISABLE,
    ): JsonObject = JsonParser.parseString(
        b.buildTrojanGoConfig(1080, ExternalCoreSettings(logLevel, ipv6Mode, false))
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
    fun `finalAddress 与端口写入 remote`() {
        val c = build(bean(finalAddress = "10.0.0.2"))
        assertEquals("10.0.0.2", c["remote_addr"].asString)
        assertEquals(1234, c["remote_port"].asInt)
        assertEquals(1080, c["local_port"].asInt)
    }

    @Test
    fun `sni 为空且本地中转到域名节点时回退到域名`() {
        val ssl = build(bean(serverAddress = "node.example.com"))["ssl"].asJsonObject
        assertEquals("node.example.com", ssl["sni"].asString)
    }

    @Test
    fun `sni 为空且节点是 IP 时不写 sni`() {
        val ssl = build(bean(serverAddress = "1.2.3.4"))["ssl"].asJsonObject
        assertFalse(ssl.has("sni"))
    }

    @Test
    fun `sni 为空且不经本地中转时不写 sni`() {
        val ssl = build(bean(finalAddress = "10.0.0.2"))["ssl"].asJsonObject
        assertFalse(ssl.has("sni"))
    }

    @Test
    fun `只有 allowInsecure 时 ssl 只含 verify`() {
        val ssl = build(bean(serverAddress = "1.2.3.4", allowInsecure = true))["ssl"].asJsonObject
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
