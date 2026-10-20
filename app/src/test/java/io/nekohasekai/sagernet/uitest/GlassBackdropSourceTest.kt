package io.nekohasekai.sagernet.uitest

import android.view.View
import io.nekohasekai.sagernet.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U5f F3：BLUR 档下层来源（fragment_holder）的结构版本号。子视图增删会让已录的下层过期（版本号加一），
 * Activity 销毁时来源摘掉监听。不截图，所有平台都跑。
 */
class GlassBackdropSourceTest : UiTestBase() {

    @Test
    fun sourceIsTheFragmentHolderAndStructureChangesBumpTheVersion() {
        UiFixtures.install()
        val main = ui.launch<MainActivity>().get()
        val source = main.glass.backdropSource
        assertTrue(source.hasSource)
        assertEquals(main.binding.fragmentHolder, source.view)

        val before = source.version
        val child = View(main)
        main.binding.fragmentHolder.addView(child)
        assertTrue("子视图挂上应使下层过期", source.version > before)

        val afterAdd = source.version
        main.binding.fragmentHolder.removeView(child)
        assertTrue("子视图摘下应使下层过期", source.version > afterAdd)
    }

    @Test
    fun releaseDropsTheListenerAndTheSource() {
        UiFixtures.install()
        val main = ui.launch<MainActivity>().get()
        val source = main.glass.backdropSource
        source.release()
        assertFalse(source.hasSource)
        val before = source.version
        main.binding.fragmentHolder.addView(View(main))
        assertEquals("摘掉监听后不再有版本变化", before, source.version)
    }
}
