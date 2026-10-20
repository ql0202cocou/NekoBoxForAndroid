package io.nekohasekai.sagernet.uitest

import android.app.Activity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController

/**
 * U4d 状态层用例共用的小工具（场景本身在各 *Test 里）。
 * 旋转的做法与 UiScenarios.change(Change.ROTATE) 相同：configurationChange 重建出来的 Activity 不会自己 attach 到窗口，
 * 补一次 visible()。
 */
internal fun <A : Activity> ActivityController<A>.rotate() {
    RuntimeEnvironment.setQualifiers("+land")
    configurationChange()
    if (!get().window.decorView.isAttachedToWindow) visible()
    UiSettle.settle()
}

/** 仓库监听器表里现有的监听器个数（反射读 GroupManager / ProfileManager 的内部表，UiTestRule 清表用的也是它们） */
internal enum class ListenerTable(private val owner: Any, private val field: String) {
    GROUP(GroupManager, "listeners"),
    RULE(ProfileManager, "ruleListeners");

    fun count(): Int {
        val list = owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(owner)
        return (list as List<*>).size
    }
}

internal fun RecyclerView.firstVisible(): Int = (layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
