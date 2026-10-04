package io.nekohasekai.sagernet.fmt.naive

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** 基线没覆盖到的 buildNaiveConfig 分支：键的取舍、顺序与 IPv6 本地地址。 */
class NaiveConfigTest {

    private fun settings(logLevel: Int = 0) = ExternalCoreSettings(logLevel, 0, false)

    private fun bean(
        server: String = "naive.example.com",
        sni: String = "",
        extraHeaders: String = "",
        insecureConcurrency: Int = 0,
        username: String = "u",
        password: String = "p",
        finalAddress: String = "127.0.0.1",
    ) = NaiveBean().apply {
        serverAddress = server
        serverPort = 443
        this.sni = sni
        this.extraHeaders = extraHeaders
        this.insecureConcurrency = insecureConcurrency
        this.username = username
        this.password = password
        initializeDefaultValues()
        this.finalAddress = finalAddress
        finalPort = 1080
    }

    private fun build(bean: NaiveBean, logLevel: Int = 0): Map<String, Any?> {
        val obj = JsonParser.parseString(bean.buildNaiveConfig(2080, settings(logLevel))).asJsonObject
        // 键的顺序也是输出的一部分
        return obj.entrySet().associateTo(LinkedHashMap()) { (k, v) ->
            k to if (v.asJsonPrimitive.isNumber) v.asInt else v.asString
        }
    }

    @Test
    fun `域名无 SNI 且各可选项全开`() {
        val out = build(
            bean(extraHeaders = "A: 1\nB: 2", insecureConcurrency = 3),
            logLevel = 2,
        )
        assertEquals(
            listOf("host-resolver-rules", "listen", "proxy", "extra-headers", "log", "insecure-concurrency"),
            out.keys.toList(),
        )
        assertEquals("MAP naive.example.com 127.0.0.1", out["host-resolver-rules"])
        assertEquals("socks://127.0.0.1:2080", out["listen"])
        assertEquals("https://u:p@naive.example.com:1080/", out["proxy"])
        assertEquals("A: 1\r\nB: 2", out["extra-headers"])
        assertEquals("", out["log"])
        assertEquals(3, out["insecure-concurrency"])
    }

    @Test
    fun `可选项全关时只有三个键`() {
        val out = build(bean(), logLevel = 0)
        assertEquals(listOf("host-resolver-rules", "listen", "proxy"), out.keys.toList())
    }

    @Test
    fun `IP 服务器无 SNI 不写 host-resolver-rules`() {
        val out = build(bean(server = "192.0.2.1", extraHeaders = "A: 1"))
        assertEquals(listOf("listen", "proxy", "extra-headers"), out.keys.toList())
        assertEquals("https://u:p@127.0.0.1:1080/", out["proxy"])
    }

    @Test
    fun `IP 服务器配 SNI 时按 SNI 映射`() {
        val out = build(bean(server = "192.0.2.1", sni = "s.example.com"))
        assertEquals("MAP s.example.com 127.0.0.1", out["host-resolver-rules"])
        assertEquals("https://u:p@s.example.com:1080/", out["proxy"])
    }

    @Test
    fun `本地地址是 IPv6 时映射目标带方括号`() {
        val withSni = build(bean(sni = "s.example.com", finalAddress = "2001:db8::1"))
        assertEquals("MAP s.example.com [2001:db8::1]", withSni["host-resolver-rules"])
        val withoutSni = build(bean(finalAddress = "2001:db8::1"))
        assertEquals("MAP naive.example.com [2001:db8::1]", withoutSni["host-resolver-rules"])
        assertEquals("https://u:p@naive.example.com:1080/", withoutSni["proxy"])
    }

    @Test
    fun `凭据为空或只有用户名`() {
        assertEquals("https://naive.example.com:1080/", build(bean(username = "", password = ""))["proxy"])
        assertEquals("https://u@naive.example.com:1080/", build(bean(password = ""))["proxy"])
    }
}
