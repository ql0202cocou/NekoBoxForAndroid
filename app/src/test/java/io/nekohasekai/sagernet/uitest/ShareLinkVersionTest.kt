package io.nekohasekai.sagernet.uitest

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.CoreTestNodes
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.putBean
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.v2ray.XhttpParametersLostException
import io.nekohasekai.sagernet.fmt.v2ray.parseV2RayNJson
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.*
import org.junit.Test

class ShareLinkVersionTest : UiTestBase() {
    @Test
    fun scopedIpv6SurvivesGroupExport() {
        val host = "fe80::1%wlan0"
        for (node in listOf(CoreTestNodes.vless(), CoreTestNodes.anytls(), ShadowsocksBean().apply { initializeDefaultValues() })) {
            node.serverAddress = host
            val row = ProxyEntity().putBean(node)
            assertFalse(row.haveStandardLink())
            val copy = parseUniversal(row.toStdLink()) { false }
            assertEquals(node.javaClass, copy.javaClass)
            assertEquals(host, copy.serverAddress)
        }

        val vmess = ProxyEntity().putBean(CoreTestNodes.vmess { serverAddress = host })
        assertTrue(vmess.haveStandardLink())
        val link = vmess.toStdLink()
        assertTrue(link.startsWith("vmess://"))
        val json = String(java.util.Base64.getDecoder().decode(link.removePrefix("vmess://")), Charsets.UTF_8)
        assertEquals(host, parseV2RayNJson(json).serverAddress)
    }

    @Test
    fun scopedAddressKeepsExistingExportChecks() {
        val host = "fe80::1%2"
        val incomplete = CoreTestNodes.vless { serverAddress = host; type = "xhttp"; path = "" }
        assertThrows(XhttpParametersLostException::class.java) { ProxyEntity().putBean(incomplete).toStdLink() }
        val unresolved = CoreTestNodes.anytls { serverAddress = host; certificatePolicy = AnyTLSBean.CERTIFICATE_POLICY_LEGACY }
        assertThrows(IllegalArgumentException::class.java) { ProxyEntity().putBean(unresolved).toStdLink() }
    }

    @Test
    fun uotV1UsesLosslessGroupExportAndOnlyTheSnMenu() {
        val bean = ShadowsocksBean().apply {
            initializeDefaultValues()
            serverAddress = "192.0.2.1"
            sUoT = true
            sUoTVersion = 1
            password = "fixture"
        }
        val row = ProxyEntity().putBean(bean)
        assertFalse(row.haveStandardLink())
        val copy = parseUniversal(row.toStdLink()) { false } as ShadowsocksBean
        assertEquals(true, copy.sUoT)
        assertEquals(1, copy.sUoTVersion)
        bean.sUoT = false
        assertFalse(row.haveStandardLink())
        val disabled = parseUniversal(row.toStdLink()) { false } as ShadowsocksBean
        assertEquals(false, disabled.sUoT)
        assertEquals(1, disabled.sUoTVersion)
        bean.sUoT = true
        bean.sUoTVersion = 2
        assertTrue(row.haveStandardLink())
        val standard = row.toStdLink()
        assertTrue(standard.startsWith("ss://"))
        val imported = parseShadowsocks(standard).apply { initializeDefaultValues() }
        assertEquals(true, imported.sUoT)
        assertEquals(2, imported.sUoTVersion)
    }

    @Test
    fun mieruOnlyOffersItsUniversalFormat() {
        val bean = MieruBean().apply { initializeDefaultValues() }
        val row = ProxyEntity().putBean(bean)
        assertFalse(row.haveStandardLink())
        assertTrue(parseUniversal(row.toStdLink()) { false } is MieruBean)
    }
}
