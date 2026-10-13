package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Shadowsocks 的 UDP over TCP 版本：sing-box 的 udp_over_tcp 写 true 时用版本 2（也是 sUoTVersion 的默认值），
// 版本 1 要写成带 version 的对象。默认值的输出与加这个字段之前逐字节相同（黄金基线 sb-ss-2022-uot 钉着）
class ShadowsocksUotVersionTest {

    private fun ss(uot: Boolean, version: Int?) = ProxyEntity(id = 1, groupId = 1, userOrder = 1).putBean(
        ShadowsocksBean().apply {
            name = "ss"
            serverAddress = "192.0.2.2"
            serverPort = 8388
            method = "aes-128-gcm"
            password = "fake-password"
            initializeDefaultValues()
            sUoT = uot
            if (version != null) sUoTVersion = version
        }
    )

    private fun outbound(entity: ProxyEntity): JsonObject {
        val source = MemoryConfigDataSource(groups = listOf(ProxyGroup(id = 1)), profiles = listOf(entity), rules = emptyList())
        val record = ProfileRecord.of(entity)
        val snapshot = ConfigSnapshot.collect(source, record, ConfigBuildMode.RUN)
        val result = buildConfig(
            ConfigInput(ConfigBuildMode.RUN, record, testConfigSettings(), snapshot, emptyMap(), FakeConfigPlatform()),
            ArrayList(),
        )
        return JsonParser.parseString(result.config).asJsonObject.getAsJsonArray("outbounds")
            .map { it.asJsonObject }.single { it["type"].asString == "shadowsocks" }
    }

    @Test
    fun `默认版本写成 true，输出与没有版本字段时相同`() {
        assertEquals(2, (ss(uot = true, version = null).requireBean() as ShadowsocksBean).sUoTVersion)
        assertEquals("true", outbound(ss(uot = true, version = null))["udp_over_tcp"].toString())
        assertEquals("true", outbound(ss(uot = true, version = 2))["udp_over_tcp"].toString())
    }

    @Test
    fun `版本 1 写成带 version 的对象`() {
        val uot = outbound(ss(uot = true, version = 1))["udp_over_tcp"].asJsonObject
        assertEquals(setOf("enabled", "version"), uot.keySet())
        assertTrue(uot["enabled"].asBoolean)
        assertEquals(1, uot["version"].asInt)
    }

    @Test
    fun `没开 UoT 时版本不写`() {
        assertFalse(outbound(ss(uot = false, version = 1)).has("udp_over_tcp"))
    }
}
