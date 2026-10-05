package io.nekohasekai.sagernet.fmt.mieru

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import io.nekohasekai.sagernet.fmt.ExternalDialTarget
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MieruFmtTest {

    private fun bean() = MieruBean().apply {
        serverAddress = "192.0.2.1"
        serverPort = 8964
        initializeDefaultValues()
        username = "u"
        password = "p"
    }

    // 拨号目标默认不映射（拨服务器本身）
    private fun build(
        b: MieruBean,
        logLevel: Int = 2,
        target: ExternalDialTarget = ExternalDialTarget.Direct,
    ): JsonObject = JsonParser.parseString(
        b.buildMieruConfig(1080, target, ExternalCoreSettings(logLevel, 0, false))
    ).asJsonObject

    private fun JsonObject.server() =
        getAsJsonArray("profiles")[0].asJsonObject.getAsJsonArray("servers")[0].asJsonObject

    @Test
    fun `经映射时拨本机的映射端口，不映射时拨服务器本身`() {
        for (server in listOf("192.0.2.1", "mieru.example.com", "2001:db8::1", "127.0.0.1")) {
            val b = bean().apply { serverAddress = server }
            val mapped = build(b, target = ExternalDialTarget.Mapped(40000)).server()
            assertEquals(server, "127.0.0.1", mapped["ipAddress"].asString)
            assertEquals(server, 40000, mapped.getAsJsonArray("portBindings")[0].asJsonObject["port"].asInt)
            val direct = build(b).server()
            assertEquals(server, server, direct["ipAddress"].asString)
            assertEquals(server, 8964, direct.getAsJsonArray("portBindings")[0].asJsonObject["port"].asInt)
        }
    }

    @Test
    fun `日志档位 2 与档位之外的值都是 INFO`() {
        for (level in listOf(2, 5, -1)) {
            assertEquals("INFO", build(bean(), level).get("loggingLevel").asString)
        }
    }

    @Test
    fun `null 字段的键被省略`() {
        val b = bean().apply {
            username = null
            password = null
            protocol = null
            // 不映射时拨号地址取服务器地址
            serverAddress = null
        }
        val root = build(b)
        val profile = root.getAsJsonArray("profiles")[0].asJsonObject
        val user = profile.getAsJsonObject("user")
        assertFalse(user.has("name"))
        assertFalse(user.has("password"))
        val server = profile.getAsJsonArray("servers")[0].asJsonObject
        assertFalse(server.has("ipAddress"))
        assertFalse(server.getAsJsonArray("portBindings")[0].asJsonObject.has("protocol"))
    }

    @Test
    fun `键的顺序与整数写法`() {
        val text = bean().buildMieruConfig(1080, ExternalDialTarget.Direct, ExternalCoreSettings(2, 0, false))
        val root = gson.fromJson(text, JsonObject::class.java)
        assertEquals(listOf("activeProfile", "socks5Port", "loggingLevel", "profiles"), root.keySet().toList())
        assertTrue(text.contains("\"socks5Port\": 1080,"))
        assertTrue(text.contains("\"mtu\": 1400"))
    }
}
