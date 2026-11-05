package io.nekohasekai.sagernet.uitest

import android.content.Context
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.ui.AssetsActivity
import io.nekohasekai.sagernet.widget.pageScopeOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 摘要读取发生在列表绑定期间；即使后台立即返回，刷新也必须等本次布局结束。 */
class PreferenceScopeTest : UiTestBase() {

    @Test
    fun immediateSummaryResultDoesNotNotifyDuringLayout() {
        val context = ui.launch<AssetsActivity>().get()
        val preference = ImmediateSummaryPreference(context)
        val screen = PreferenceManager(context).createPreferenceScreen(context).apply {
            addPreference(preference)
        }
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = PreferenceGroupAdapter(screen)
        }
        val exact = View.MeasureSpec.EXACTLY
        list.measure(View.MeasureSpec.makeMeasureSpec(800, exact), View.MeasureSpec.makeMeasureSpec(1200, exact))
        list.layout(0, 0, 800, 1200)
        assertTrue("必须实际绑定过偏好行", preference.requested)
        assertFalse("布局返回前不能刷新摘要", preference.loaded)
        UiSettle.settle()
        assertTrue("布局结束后仍须应用结果", preference.loaded)
        assertEquals("loaded", preference.summary)
        list.adapter = null
    }

    @Test
    fun summaryResultIsCancelledWithThePage() {
        val host = ui.launch<AssetsActivity>()
        val preference = Preference(host.get())
        val gate = CompletableDeferred<Unit>()
        var started = false
        var applied = false
        val job = preference.pageScopeOrNull()!!.launch {
            started = true
            gate.await()
            applied = true
        }
        UiSettle.settle()
        assertTrue(started)
        ui.destroyNow(host)
        gate.complete(Unit)
        UiSettle.settle()
        assertTrue(job.isCancelled)
        assertFalse(applied)
    }

    private class ImmediateSummaryPreference(context: Context) : Preference(context) {
        var requested = false
            private set
        var loaded = false
            private set

        init {
            key = "immediate-summary"
            title = "Summary"
        }

        override fun getSummary(): CharSequence {
            if (!requested) {
                requested = true
                pageScopeOrNull()!!.launch {
                    // 不挂起，稳定覆盖后台在 withContext 返回前就已完成的情况。
                    loaded = true
                    notifyChanged()
                }
            }
            return if (loaded) "loaded" else "loading"
        }
    }
}
