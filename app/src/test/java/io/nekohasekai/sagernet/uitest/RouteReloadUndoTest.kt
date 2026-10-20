package io.nekohasekai.sagernet.uitest

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.RuleRepository
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.RouteFragment
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * U4c c2 路由列表：整表重读与撤销窗口的次序。重读在途（读库被门挡住）时滑掉一行，
 * 放行后读到的结果里仍有这一行（库里还没删）；应用结果必须走统一入口并滤掉它，之后点撤销也不能插出重复项。
 */
class RouteReloadUndoTest : UiTestBase() {

    /**
     * 占满 IO 调度器，让之后提交的读库排队不动；关门（[close]）后排队的任务照常跑完。
     * 门开着时不能 settle（占着的线程在等门闩），出了 use 块再 settle。与 UiScenarios.IoGate 同一做法（那个是私有的）
     */
    @OptIn(DelicateCoroutinesApi::class)
    private class IoGate : AutoCloseable {
        private val release = CountDownLatch(1)

        init {
            val slots = System.getProperty("kotlinx.coroutines.io.parallelism")?.toIntOrNull()
                ?: maxOf(64, Runtime.getRuntime().availableProcessors())
            val occupied = CountDownLatch(slots)
            repeat(slots) {
                GlobalScope.launch(Dispatchers.IO) {
                    occupied.countDown()
                    release.await()
                }
            }
            try {
                check(occupied.await(10, TimeUnit.SECONDS)) { "IO 调度器没能在 10 s 内被占满" }
                val probe = CountDownLatch(1)
                GlobalScope.launch(Dispatchers.IO) { probe.countDown() }
                check(!probe.await(100, TimeUnit.MILLISECONDS)) { "IO 门没有挡住后续的 IO 任务" }
            } catch (t: Throwable) {
                release.countDown()
                throw t
            }
        }

        override fun close() = release.countDown()
    }

    private fun open(): RouteFragment {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        main.get().displayFragmentWithId(R.id.nav_route)
        UiSettle.settle()
        val fragment = main.get().supportFragmentManager.findFragmentById(R.id.fragment_holder) as RouteFragment
        check(fragment.ruleAdapter.ruleList.size >= 3) { "前置条件：路由页不足 3 条规则" }
        return fragment
    }

    private fun View.findUndoAction(): View? {
        if (id == com.google.android.material.R.id.snackbar_action) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findUndoAction()?.let { return it }
        return null
    }

    private fun RouteFragment.ids() = ruleAdapter.ruleList.map { it.id }

    private fun dbIds(): List<Long> = ui.offMain { runBlocking { RuleRepository.getRules() } }.map { it.id }

    /** 滑掉一行并取到撤销按钮；只空转主 looper（门开着不能 settle） */
    private fun RouteFragment.swipe(index: Int): View {
        val rule = ruleAdapter.ruleList[index]
        ruleAdapter.remove(index)
        undoManager.remove(index to rule)
        shadowOf(Looper.getMainLooper()).idle()
        val action = requireActivity().window.decorView.findUndoAction()
        assertNotNull("前置条件：没有撤销按钮", action)
        return action!!
    }

    @Test
    fun reloadInFlightDuringUndoWindowDoesNotBringBackSwipedRule() {
        val fragment = open()
        val before = fragment.ids()
        val swiped = before[1]

        val action: View
        IoGate().use {
            fragment.ruleAdapter.reload()
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
        assertEquals("列表与库一致", dbIds(), after)
        assertEquals(after.size, fragment.ruleAdapter.itemCount)
    }

    @Test
    fun undoBeforeReloadAppliesKeepsTheRule() {
        val fragment = open()
        val before = fragment.ids()

        IoGate().use {
            fragment.ruleAdapter.reload()
            val action = fragment.swipe(1)
            // 重读还没应用就撤销：规则回到列表，也不再被过滤
            action.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(before, fragment.ids())
        }
        UiSettle.settle()
        assertEquals("重读应用后规则仍在、没有重复", before, fragment.ids())
        assertEquals(before, dbIds())
        assertEquals(before.size, fragment.ruleAdapter.itemCount)
    }

    @Test
    fun reloadAfterCommittedDeletionKeepsItGone() {
        val fragment = open()
        val before = fragment.ids()
        val swiped = before[0]
        fragment.swipe(0)

        // 撤销窗口走完，删除落库并确认；之后的整表重读读不到它，列表与库一致
        UiSettle.settle(advanceMs = 30_000)
        assertEquals(before - swiped, dbIds())
        fragment.ruleAdapter.reload()
        UiSettle.settle()
        assertEquals(before - swiped, fragment.ids())
        fragment.ruleAdapter.reload()
        UiSettle.settle()
        assertEquals(before - swiped, fragment.ids())
        assertEquals(fragment.ids().size, fragment.ruleAdapter.itemCount)
    }
}
