package io.nekohasekai.sagernet.uitest

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.SettingsFragment
import io.nekohasekai.sagernet.ui.SettingsPreferenceFragment
import io.nekohasekai.sagernet.utils.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog

class ThemePickerTest : UiTestBase() {
    @Test
    fun restoredSelectionAndLastRowRemainReachableOnShortScreens() {
        UiFixtures.install()
        RuntimeEnvironment.setQualifiers("en-rUS-w640dp-h360dp-land-notnight-xhdpi")
        DataStore.appTheme = Theme.BLUE_GREY
        DataStore.nightTheme = 2
        Theme.currentNightMode = -1
        val main = ui.launch<MainActivity>().get()
        main.displayFragment(SettingsFragment.section(SettingsPreferenceFragment.sections[1].first))
        UiSettle.settle()
        clickRow(main.window.decorView, "Theme")
        UiSettle.settle()
        val dialog = ShadowDialog.getLatestDialog() ?: error("选色面板没有打开")
        val root = dialog.window!!.decorView
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        val views = descendants(root)
        val swatches = views.filter { it.contentDescription?.toString() in Theme.KEPT.map { id -> main.getString(Theme.nameRes(id)) } }
        assertEquals(21, swatches.size)
        assertEquals("Blue grey", swatches.single { it.isSelected }.contentDescription.toString())
        assertEquals(Theme.BLUE_GREY, DataStore.appTheme)
        val scroll = generateSequence(swatches.first().parent) { it.parent }.filterIsInstance<ScrollView>().first()
        assertTrue("矮屏必须可以滚动到最后一行", scroll.canScrollVertically(1))
        scroll.isSmoothScrollingEnabled = false
        scroll.fullScroll(View.FOCUS_DOWN)
        UiSettle.settle()
        val black = swatches.single { it.contentDescription == "Black" }
        val visible = Rect()
        assertTrue("最后一行不可见：滚动位置 ${scroll.scrollY}，容器高 ${scroll.height}", black.getLocalVisibleRect(visible))
        assertEquals("最后一行的点击区域必须完整露出", black.height, visible.height())
        black.performClick()
        UiSettle.settle()
        assertEquals(Theme.BLACK, DataStore.appTheme)
    }
}
