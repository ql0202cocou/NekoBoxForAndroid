package io.nekohasekai.sagernet.uitest

import android.os.Bundle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.SettingsFragment
import io.nekohasekai.sagernet.ui.SettingsPreferenceFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * U4d d4 设置一级页的滚动状态：从一级页进入二级页面前记下，放在 [io.nekohasekai.sagernet.ui.MainViewModel]（SavedStateHandle）里，
 * 不再是 Activity 字段。
 * - 设置 → 分类页 → 在分类页上旋转 → 返回：一级页的滚动位置仍在（原先登记在 KnownUiGaps 里）；
 * - 同样的流程换成进程重建：状态经 SavedStateHandle 带回；
 * - 状态只取一次：取走之后再进一级页从顶部开始。
 * 一级页要能滚动，所以窗口压到 300dp 高。不截图，所有平台都跑。
 */
class SettingsScrollStateTest : UiTestBase() {

    private val scenarios by lazy { UiScenarios(ui) }

    private fun hubOf(main: MainActivity) =
        (main.supportFragmentManager.findFragmentById(R.id.fragment_holder) as SettingsFragment)
            .childFragmentManager.findFragmentById(R.id.settings) as SettingsPreferenceFragment

    private fun scrolledHub(): Pair<org.robolectric.android.controller.ActivityController<MainActivity>, Int> {
        RuntimeEnvironment.setQualifiers("en-rUS-w411dp-h300dp-port-notnight-xhdpi")
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        main.get().displayFragmentWithId(Page.SETTINGS.navId)
        UiSettle.settle()
        hubOf(main.get()).listView.scrollBy(0, 300)
        UiSettle.settle()
        val offset = hubOf(main.get()).listView.computeVerticalScrollOffset()
        assertTrue("前置条件：设置一级页没有滚动", offset > 0)
        return main to offset
    }

    @Test
    fun hubScrollKeptAfterRotatingInsideSection() {
        val check = scenarios.settingsHubScrollKept(rotateInside = true)
        assertTrue("分类页上旋转后一级页的滚动位置：$check", check.ok)
    }

    @Test
    fun hubScrollKeptAfterProcessRecreationInsideSection() {
        val (main, offset) = scrolledHub()
        main.get().displayFragment(SettingsFragment.section(SettingsPreferenceFragment.sections[0].first))
        UiSettle.settle()

        val state = Bundle()
        main.pause().saveInstanceState(state)
        ui.destroyNow(main)
        val restored = ui.launchRestored(MainActivity::class.java, null, state)
        restored.get().backToSettings()
        UiSettle.settle()

        assertEquals("进程重建后返回一级页，滚动位置经 SavedStateHandle 带回",
            offset, hubOf(restored.get()).listView.computeVerticalScrollOffset())
    }

    @Test
    fun savedStateIsTakenOnlyOnce() {
        val (main, offset) = scrolledHub()
        main.get().displayFragment(SettingsFragment.section(SettingsPreferenceFragment.sections[0].first))
        UiSettle.settle()
        main.get().backToSettings()
        UiSettle.settle()
        assertEquals("第一次回一级页恢复位置", offset, hubOf(main.get()).listView.computeVerticalScrollOffset())

        // 状态已被取走：换到别的页面再回设置一级页，从顶部开始
        main.get().displayFragmentWithId(Page.GROUPS.navId)
        UiSettle.settle()
        main.get().displayFragmentWithId(Page.SETTINGS.navId)
        UiSettle.settle()
        assertEquals(0, hubOf(main.get()).listView.computeVerticalScrollOffset())
    }
}
