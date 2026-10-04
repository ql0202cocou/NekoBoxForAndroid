package io.nekohasekai.sagernet.fmt.mieru

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MieruFmtTest {

    private fun bean() = MieruBean().apply {
        initializeDefaultValues()
        finalAddress = "192.0.2.1"
        finalPort = 8964
        username = "u"
        password = "p"
    }

    private fun build(b: MieruBean, logLevel: Int = 2): JsonObject = JsonParser.parseString(
        b.buildMieruConfig(1080, ExternalCoreSettings(logLevel, 0, false))
    ).asJsonObject

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
            finalAddress = null
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
        val text = bean().buildMieruConfig(1080, ExternalCoreSettings(2, 0, false))
        val root = gson.fromJson(text, JsonObject::class.java)
        assertEquals(listOf("activeProfile", "socks5Port", "loggingLevel", "profiles"), root.keySet().toList())
        assertTrue(text.contains("\"socks5Port\": 1080,"))
        assertTrue(text.contains("\"mtu\": 1400"))
    }
}
