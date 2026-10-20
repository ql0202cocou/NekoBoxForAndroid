package io.nekohasekai.sagernet.uitest

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceRegistry
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * U4b b5：DataStore 的变更监听在写入线程里同步回调。MainActivity 的界面动作（「需重载」提示、重连）
 * 必须落在主线程：从后台线程写分应用代理的设置不得崩（Snackbar 只能在主线程建），提示照常出现；
 * binder 线程上的 cbSelectorUpdate 同理。不截图，所有平台都跑。
 */
class MainStoreChangeTest : UiTestBase() {

    private fun View.hasText(text: String): Boolean {
        if (this is TextView && this.text.toString() == text && isShown) return true
        if (this is ViewGroup) for (i in 0 until childCount) if (getChildAt(i).hasText(text)) return true
        return false
    }

    private fun MainActivity.needReloadShown() =
        window.decorView.hasText(getString(R.string.need_reload))

    /** 后台线程写分应用代理：不崩，提示在主线程上出现 */
    @Test
    fun backgroundWriteOfPerAppProxyShowsReloadHint() {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        ServiceRegistry.state = BaseService.State.Connected
        assertFalse("前置条件：写之前不该已有提示", main.get().needReloadShown())
        // 抛出的异常（例如在后台线程建 Snackbar）经 offMain 重新抛出
        ui.offMain { DataStore.proxyApps = true }
        UiSettle.settle()
        assertTrue("后台写入后没有出现「需重载」提示", main.get().needReloadShown())
    }

    /**
     * 界面动作是「投到主线程」而不是在写入线程里直接做：后台写完、主 looper 还没空转时，主 looper 上已排着任务
     * （服务没在跑，所以任务本身什么也不显示）；与界面无关的键不排任务。
     */
    @Test
    fun backgroundWriteIsDeferredToMainThread() {
        UiFixtures.install()
        ui.launch<MainActivity>()
        val looper = shadowOf(Looper.getMainLooper())
        assertTrue("前置条件：启动后主 looper 应已空闲", looper.isIdle)
        ui.offMain { DataStore.selectedProxy = 1L }
        assertTrue("与界面无关的键不该往主线程排任务", looper.isIdle)
        ui.offMain { DataStore.proxyApps = true }
        assertFalse("后台写入的界面动作没有投到主线程（仍在写入线程里执行）", looper.isIdle)
        UiSettle.settle()
    }

    /** 服务没在跑时不提示（分支条件仍在主线程上判断） */
    @Test
    fun backgroundWriteWhileStoppedShowsNoHint() {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        ui.offMain { DataStore.proxyApps = true }
        UiSettle.settle()
        assertFalse(main.get().needReloadShown())
    }

    /** 主线程写入仍然当场处理（切到主线程用的是立即调度） */
    @Test
    fun mainThreadWriteShowsReloadHint() {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        ServiceRegistry.state = BaseService.State.Connected
        DataStore.proxyApps = true
        UiSettle.settle()
        assertTrue(main.get().needReloadShown())
    }

    /** 页面销毁之后监听器已摘掉，后台再写也不会碰已销毁的页面 */
    @Test
    fun backgroundWriteAfterDestroyIsHarmless() {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        ServiceRegistry.state = BaseService.State.Connected
        ui.destroyNow(main)
        ui.offMain { DataStore.proxyApps = true }
        UiSettle.settle()
    }

    /** binder 线程上的选中节点回调：写 DataStore 照常、不崩 */
    @Test
    fun selectorUpdateFromBinderThreadIsSafe() {
        val fixtures = UiFixtures.install()
        val main = ui.launch<MainActivity>()
        val target = fixtures.profiles[1].id
        ui.offMain { main.get().cbSelectorUpdate(target) }
        UiSettle.settle()
        assertEquals(target, DataStore.selectedProxy)
        assertEquals(target, DataStore.currentProfile)
    }
}
