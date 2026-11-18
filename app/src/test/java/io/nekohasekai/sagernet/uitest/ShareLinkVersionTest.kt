package io.nekohasekai.sagernet.uitest

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.putBean
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import org.junit.Assert.*
import org.junit.Test

class ShareLinkVersionTest : UiTestBase() {
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
