package io.nekohasekai.sagernet.plugin

import android.content.pm.ProviderInfo
import io.nekohasekai.sagernet.fmt.ConfigBuildDiagnostic
import io.nekohasekai.sagernet.fmt.configBuildNotices
import io.nekohasekai.sagernet.fmt.recordPluginConflict
import moe.matsuri.nb4a.plugin.Plugins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PluginSelectionTest {
    private val diagnostics = ArrayList<ConfigBuildDiagnostic>()
    private fun provider(pkg: String, prefix: String) = ProviderInfo().apply {
        packageName = pkg
        name = "$pkg.Provider"
        authority = prefix + pkg
    }
    private fun select(vararg candidates: ProviderInfo) =
        Plugins.selectProvider("xray-plugin", candidates.toList(), { it != "untrusted" }, diagnostics::recordPluginConflict)

    @Test
    fun `过滤不可信组件，双入口命中同一组件仍保留唯一首选项`() {
        val first = provider("first", Plugins.AUTHORITIES_PREFIX_SEKAI_EXE)
        val preferred = provider("preferred", Plugins.AUTHORITIES_PREFIX_NEKO_EXE)
        val untrusted = provider("untrusted", Plugins.AUTHORITIES_PREFIX_NEKO_EXE)
        assertNull(select(untrusted))
        assertSame(preferred, select(first, untrusted, preferred, ProviderInfo(preferred)))
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `多个可信候选沿用首项，多次查询只记录一次且不弹提示`() {
        val first = provider("first", Plugins.AUTHORITIES_PREFIX_SEKAI_EXE)
        val second = provider("second", Plugins.AUTHORITIES_PREFIX_SEKAI_EXE)
        repeat(2) { assertSame(first, select(first, second, ProviderInfo(first))) }
        assertEquals(listOf(ConfigBuildDiagnostic.PluginConflict("xray-plugin", listOf("first", "second"))), diagnostics)
        assertEquals(0, ShadowToast.shownToastCount())
        val notice = configBuildNotices(diagnostics) { _, _ -> error("unexpected resource") }.single()
        assertEquals("Conflicting plugins for xray-plugin: first, second", notice.text)
    }
}
