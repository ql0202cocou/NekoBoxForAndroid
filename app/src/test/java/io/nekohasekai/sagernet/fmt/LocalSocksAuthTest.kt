package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.utils.Util
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

// 本机 socks 凭据（plan.md K0b）：格式、校验、不经 toString 泄露，以及写日志前的按值遮蔽
class LocalSocksAuthTest {

    private val usernameShape = Regex("u[0-9a-f]{16}")
    private val passwordShape = Regex("p[0-9a-f]{32}")

    @Test
    fun `随机生成的格式与长度`() {
        repeat(50) {
            val auth = LocalSocksAuth.random()
            assertTrue(auth.username, usernameShape.matches(auth.username))
            assertTrue(auth.password, passwordShape.matches(auth.password))
            assertEquals(17, auth.username.length)
            assertEquals(33, auth.password.length)
        }
        // 每次构建各不相同
        assertEquals(50, List(50) { LocalSocksAuth.random() }.distinct().size)
    }

    @Test
    fun `固定的随机源可复现`() {
        val first = LocalSocksAuth.generate(Random(42))
        val second = LocalSocksAuth.generate(Random(42))
        assertEquals(first, second)
        assertEquals(first.username, second.username)
        assertEquals(first.password, second.password)
        assertNotEquals(first, LocalSocksAuth.generate(Random(43)))
        assertTrue(usernameShape.matches(first.username) && passwordShape.matches(first.password))
    }

    @Test
    fun `toString 不带出用户名与密码`() {
        val auth = LocalSocksAuth.generate(Random(1))
        val text = auth.toString()
        assertFalse(text, auth.username in text || auth.password in text)
        // 拼进字符串模板、放进集合打印都一样
        val nested = "${listOf(auth)} / ${mapOf("auth" to auth)}"
        assertFalse(nested, auth.username in nested || auth.password in nested)
    }

    @Test
    fun `校验非空与每项不超过 255 字节，报错里不带值`() {
        assertThrows(IllegalArgumentException::class.java) { LocalSocksAuth("", "p") }
        assertThrows(IllegalArgumentException::class.java) { LocalSocksAuth("u", "") }
        val long = "x".repeat(256)
        val e = assertThrows(IllegalArgumentException::class.java) { LocalSocksAuth("u", long) }
        assertFalse(e.message!!, long in e.message!!)
        assertThrows(IllegalArgumentException::class.java) { LocalSocksAuth(long, "p") }
        // 按 UTF-8 字节数算：128 个两字节字符是 256 字节
        assertThrows(IllegalArgumentException::class.java) { LocalSocksAuth("é".repeat(128), "p") }
        LocalSocksAuth("x".repeat(255), "é".repeat(127))
    }

    // ---- 认证检查用的错误密码：用户名不变，密码与真实密码 UTF-8 字节数相同且不同

    private fun assertWrongPassword(real: LocalSocksAuth) {
        val wrong = real.withWrongPassword()
        assertEquals(real.username, wrong.username)
        assertNotEquals(real.password, wrong.password)
        assertEquals(
            real.password,
            real.password.toByteArray(Charsets.UTF_8).size,
            wrong.password.toByteArray(Charsets.UTF_8).size,
        )
        // 只动最后一个字符
        val kept = real.password.length - Character.charCount(real.password.codePointBefore(real.password.length))
        assertEquals(real.password.substring(0, kept), wrong.password.substring(0, kept))
    }

    @Test
    fun `错误密码与真实密码等长且不同`() {
        repeat(50) { assertWrongPassword(LocalSocksAuth.random()) }
        assertEquals("p0123x", LocalSocksAuth("u", "p0123f").withWrongPassword().password)
        // 最后一个字符恰好是替换字符
        assertEquals("p0123y", LocalSocksAuth("u", "p0123x").withWrongPassword().password)
        assertEquals("x", LocalSocksAuth("u", "a").withWrongPassword().password)
        assertEquals("y", LocalSocksAuth("u", "x").withWrongPassword().password)
        assertWrongPassword(LocalSocksAuth("u", "x"))
        // 255 字节（上限）
        assertWrongPassword(LocalSocksAuth("u", "a".repeat(255)))
        assertWrongPassword(LocalSocksAuth("u", "x".repeat(255)))
        assertWrongPassword(LocalSocksAuth("u", "a".repeat(253) + "é"))
        // 非 ASCII 的最后一个字符换成同样字节数的 ASCII
        assertEquals("pxx", LocalSocksAuth("u", "pé").withWrongPassword().password)
        assertEquals("pxxx", LocalSocksAuth("u", "p中").withWrongPassword().password)
        assertEquals("pxxxx", LocalSocksAuth("u", "p\uD83D\uDE00").withWrongPassword().password)
        assertWrongPassword(LocalSocksAuth("u", "\uD83D\uDE00"))
    }

    // ---- 写日志前的遮蔽：三种文本里都搜不到本次的用户名与密码

    private val auth = LocalSocksAuth.generate(Random(7))

    private val result = ConfigBuildResult("{}", emptyList(), 1L, emptyMap(), emptyMap(), -1L, localAuth = auth)

    private fun assertRedacted(text: String) {
        assertTrue("遮蔽前应当有凭据", auth.username in text && auth.password in text)
        // 构建里没有凭据时原样返回
        assertEquals(text, ConfigBuildResult("{}", emptyList(), 1L, emptyMap(), emptyMap(), -1L).redactLocalAuth(text))
    }

    private fun assertClean(text: String) {
        assertFalse(text, auth.username in text)
        assertFalse(text, auth.password in text)
    }

    @Test
    fun `sing-box 配置经遮蔽后搜不到凭据`() {
        // 与 ConfigBuild 写出的本机 socks 出站同形
        val config = """
            {
              "log": {"level": "warn"},
              "outbounds": [
                {"type": "socks", "server": "127.0.0.1", "server_port": 21000, "username": "${auth.username}", "password": "${auth.password}", "tag": "proxy"},
                {"type": "direct", "tag": "direct"}
              ]
            }
        """.trimIndent()
        assertRedacted(config)
        // 与 ProxyInstance.buildConfig / TestInstance.loadConfig 相同的顺序
        val logged = Util.redactConfig(result.redactLocalAuth(config))
        assertClean(logged)
        assertTrue(logged, "\"username\": \"***\"" in logged)
    }

    private fun plan(): ExternalRunPlan {
        val xray = VMessBean().apply {
            serverAddress = "x.example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-000000000001"
            alterId = -1
            initializeDefaultValues()
        }
        val mihomo = AnyTLSBean().apply {
            serverAddress = "m.example.com"
            serverPort = 8443
            password = "fake-password"
            initializeDefaultValues()
        }
        return ExternalRunPlan(
            listOf(
                ExternalHop(0, 0, 1L, xray, 21000, ExternalDialTarget.Mapped(31000), localAuth = auth),
                ExternalHop(1, 0, 2L, mihomo, 21001, ExternalDialTarget.Mapped(31001), localAuth = auth),
                ExternalHop(2, 1, 3L, xray, 21002, ExternalDialTarget.Mapped(31002), localAuth = auth),
            )
        )
    }

    @Test
    fun `Xray 与 mihomo 配置经遮蔽后搜不到凭据`() {
        val settings = ExternalCoreSettings(logLevel = 4, ipv6Mode = 0, globalAllowInsecure = false)
        val processes = plan().assemble({ _, _ -> error("不应申请临时文件") }, null, settings)
        assertEquals(listOf("xray-plugin", "mihomo-plugin"), processes.map { it.pluginId })
        for (process in processes) {
            assertRedacted(process.config)
            // 与 ProxyInstance.init 相同的顺序
            assertClean(Util.redactSecrets(result.redactLocalAuth(process.config)))
        }
        // mihomo 的 users 是行内写法：{username: …, password: …}
        assertTrue(processes[1].config, "- {username: ${auth.username}, password: ${auth.password}}" in processes[1].config)
    }

    private val ExternalCoreProcess.pluginId get() = group.pluginId
}
