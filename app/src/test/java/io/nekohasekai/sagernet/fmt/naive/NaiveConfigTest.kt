package io.nekohasekai.sagernet.fmt.naive

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import io.nekohasekai.sagernet.fmt.ExternalDialTarget
import io.nekohasekai.sagernet.fmt.ExternalDialTarget.Direct
import io.nekohasekai.sagernet.fmt.ExternalDialTarget.Mapped
import org.junit.Assert.assertEquals
import org.junit.Test

/** 基线没覆盖到的 buildNaiveConfig 分支：键的取舍、顺序与 IPv6 本地地址。 */
class NaiveConfigTest {

    private fun settings(logLevel: Int = 0) = ExternalCoreSettings(logLevel, 0, false)

    private fun bean(
        server: String = "naive.example.com",
        port: Int = 443,
        sni: String = "",
        extraHeaders: String = "",
        insecureConcurrency: Int = 0,
        username: String = "u",
        password: String = "p",
    ) = NaiveBean().apply {
        serverAddress = server
        serverPort = port
        this.sni = sni
        this.extraHeaders = extraHeaders
        this.insecureConcurrency = insecureConcurrency
        this.username = username
        this.password = password
        initializeDefaultValues()
    }

    // 拨号目标默认经映射（本机的映射端口 1080）
    private fun build(bean: NaiveBean, logLevel: Int = 0, target: ExternalDialTarget = Mapped(1080)): Map<String, Any?> {
        val obj = JsonParser.parseString(bean.buildNaiveConfig(2080, target, settings(logLevel))).asJsonObject
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
    fun `不映射时拨服务器本身：IPv6 地址带方括号，端口是服务器端口`() {
        val withSni = build(bean(server = "2001:db8::1", port = 8443, sni = "s.example.com"), target = Direct)
        assertEquals("MAP s.example.com [2001:db8::1]", withSni["host-resolver-rules"])
        assertEquals("https://u:p@s.example.com:8443/", withSni["proxy"])
        val withoutSni = build(bean(server = "2001:db8::1", port = 8443), target = Direct)
        assertEquals(listOf("listen", "proxy"), withoutSni.keys.toList())
        assertEquals("https://u:p@[2001:db8::1]:8443/", withoutSni["proxy"])
    }

    @Test
    fun `不映射且服务器是域名时映射到它自己`() {
        val out = build(bean(port = 8443), target = Direct)
        assertEquals("MAP naive.example.com naive.example.com", out["host-resolver-rules"])
        assertEquals("https://u:p@naive.example.com:8443/", out["proxy"])
        val ip = build(bean(server = "192.0.2.1", port = 8443), target = Direct)
        assertEquals("https://u:p@192.0.2.1:8443/", ip["proxy"])
    }

    @Test
    fun `服务器是 IP 字面量且没有 SNI 时不写 host-resolver-rules，拨号目标照常`() {
        for (server in listOf("2001:db8::1", "192.0.2.1", "127.0.0.1")) {
            val mapped = build(bean(server = server, port = 8443))
            assertEquals(server, listOf("listen", "proxy"), mapped.keys.toList())
            assertEquals(server, "https://u:p@127.0.0.1:1080/", mapped["proxy"])
            val direct = build(bean(server = server, port = 8443), target = Direct)
            assertEquals(server, listOf("listen", "proxy"), direct.keys.toList())
        }
        assertEquals("https://u:p@127.0.0.1:8443/", build(bean(server = "127.0.0.1", port = 8443), target = Direct)["proxy"])
    }

    @Test
    fun `分享链接只用服务器地址端口与节点参数，与拨号目标无关`() {
        val b = bean(server = "2001:db8::1", port = 8443, sni = "s.example.com").apply { name = "n" }
        build(b, target = Mapped(40000))
        assertEquals("naive+https://u:p@[2001:db8::1]:8443/?sni=s.example.com#n", b.toUri())
    }

    @Test
    fun `凭据为空或只有用户名`() {
        assertEquals("https://naive.example.com:1080/", build(bean(username = "", password = ""))["proxy"])
        assertEquals("https://u@naive.example.com:1080/", build(bean(password = ""))["proxy"])
    }
}
