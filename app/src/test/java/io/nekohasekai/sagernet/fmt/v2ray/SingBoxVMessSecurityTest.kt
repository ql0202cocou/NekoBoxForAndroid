package io.nekohasekai.sagernet.fmt.v2ray

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.CoreTestNodes
import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import moe.matsuri.nb4a.SingBoxOptions.Outbound_VMessOptions
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// K1b 待决定项 A（维护者 2026-10-06 定）：sing-box 的 VMess 出站带 TLS 时把 auto（或空）写成 aes-128-gcm，
// 因为 vendored sing-box 会把 auto 换成 Xray v26.7.11 起的服务端不再接受的 zero；不带 TLS 的 auto 与用户显式选的
// 加密方式照写；走 Xray 的 VMess 不受影响（Xray 自己把 auto 按平台选）
class SingBoxVMessSecurityTest {

    private fun singBox(bean: VMessBean): Outbound_VMessOptions =
        buildSingBoxOutboundStandardV2RayBean(bean, false) as Outbound_VMessOptions

    private fun vmess(tls: Boolean, encryption: String) = CoreTestNodes.vmess {
        security = if (tls) "tls" else "none"
        this.encryption = encryption
    }

    @Test
    fun `带 TLS 时 auto 与空值写 aes-128-gcm`() {
        for (encryption in listOf("auto", "")) {
            val out = singBox(vmess(tls = true, encryption))
            assertNotNull(out.tls)
            assertEquals(encryption, "aes-128-gcm", out.security)
        }
    }

    @Test
    fun `REALITY 也算带 TLS`() {
        val out = singBox(CoreTestNodes.vmess { encryption = "auto"; realityPubKey = "A".repeat(43) })
        assertNotNull(out.tls?.reality)
        assertEquals("aes-128-gcm", out.security)
    }

    @Test
    fun `不带 TLS 时 auto 与空值照写 auto`() {
        for (encryption in listOf("auto", "")) {
            val out = singBox(vmess(tls = false, encryption))
            assertNull(out.tls)
            assertEquals(encryption, "auto", out.security)
        }
    }

    @Test
    fun `显式选的加密方式照写，带不带 TLS 都一样`() {
        for (encryption in listOf("none", "zero", "aes-128-cfb", "aes-128-gcm", "chacha20-poly1305")) {
            for (tls in listOf(true, false)) {
                assertEquals("$encryption tls=$tls", encryption, singBox(vmess(tls, encryption)).security)
            }
        }
    }

    @Test
    fun `走 Xray 的 VMess 不改写 auto`() {
        val settings = ExternalCoreSettings(logLevel = 0, ipv6Mode = 0, globalAllowInsecure = false)
        for (encryption in listOf("auto", "")) {
            val outbound = JsonParser.parseString(
                gson.toJson(buildXrayOutbound(vmess(tls = true, encryption), "127.0.0.1", 20000, settings)),
            ).asJsonObject
            val user = outbound.getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject
                .getAsJsonArray("users")[0].asJsonObject
            assertEquals(encryption, "auto", user.get("security").asString)
        }
    }
}
