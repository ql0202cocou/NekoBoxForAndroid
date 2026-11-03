package io.nekohasekai.sagernet.uitest

import android.os.Looper
import android.view.View
import io.nekohasekai.sagernet.ui.RouteFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * U4c c2 路由列表：整表重读与撤销窗口的次序。重读在途（读库被门挡住）时滑掉一行，
 * 放行后读到的结果里仍有这一行（库里还没删）；应用结果必须走统一入口并滤掉它，之后点撤销也不能插出重复项。
 */
class RouteReloadUndoTest : UiTestBase() {

    private fun RouteFragment.ids() = ruleAdapter.ruleList.map { it.id }


    /** 滑掉一行并取到撤销按钮；只空转主 looper（门开着不能 settle） */
    private fun RouteFragment.swipe(index: Int): View {
        val rule = ruleAdapter.ruleList[index]
        ruleAdapter.remove(index)
        undoManager.remove(index to rule)
        shadowOf(Looper.getMainLooper()).idle()
        // IO 门关着时不能 settle
        return requireActivity().undoAction(settle = false)
    }

    @Test
    fun reloadInFlightDuringUndoWindowDoesNotBringBackSwipedRule() {
        val (_, fragment) = ui.openRoutePage()
        val before = fragment.ids()
        val swiped = before[1]

        val action: View
        IoGate().use {
            fragment.routeModel.reload()
            action = fragment.swipe(1)
        }
        // 放行：重读此刻才读库，撤销窗口还开着，库里仍有被滑掉的那条
        UiSettle.settle()
        assertFalse("重读的结果不应把滑掉的规则带回来：${fragment.ids()}", swiped in fragment.ids())

        action.performClick()
        UiSettle.settle()
        val after = fragment.ids()
        assertEquals("撤销不得产生重复项：$after", after.toSet().size, after.size)
        assertFalse("被滑掉的规则已随窗口提交，不应出现：$after", swiped in after)
        assertEquals("列表与库一致", UiFixtures.ruleIds(), after)
        assertEquals(after.size, fragment.ruleAdapter.itemCount)
    }

    @Test
    fun undoBeforeReloadAppliesKeepsTheRule() {
        val (_, fragment) = ui.openRoutePage()
        val before = fragment.ids()

        IoGate().use {
            fragment.routeModel.reload()
            val action = fragment.swipe(1)
            // 重读还没应用就撤销：规则回到列表，也不再被过滤
            action.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(before, fragment.ids())
        }
        UiSettle.settle()
        assertEquals("重读应用后规则仍在、没有重复", before, fragment.ids())
        assertEquals(before, UiFixtures.ruleIds())
        assertEquals(before.size, fragment.ruleAdapter.itemCount)
    }

    @Test
    fun reloadAfterCommittedDeletionKeepsItGone() {
        val (_, fragment) = ui.openRoutePage()
        val before = fragment.ids()
        val swiped = before[0]
        fragment.swipe(0)

        // 撤销窗口走完，删除落库并确认；之后的整表重读读不到它，列表与库一致
        UiSettle.settle(advanceMs = 30_000)
        assertEquals(before - swiped, UiFixtures.ruleIds())
        fragment.routeModel.reload()
        UiSettle.settle()
        assertEquals(before - swiped, fragment.ids())
        fragment.routeModel.reload()
        UiSettle.settle()
        assertEquals(before - swiped, fragment.ids())
        assertEquals(fragment.ids().size, fragment.ruleAdapter.itemCount)
    }
}
