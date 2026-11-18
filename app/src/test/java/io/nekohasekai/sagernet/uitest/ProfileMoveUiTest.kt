package io.nekohasekai.sagernet.uitest

import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.descendants
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowDialog

class ProfileMoveUiTest : UiTestBase() {
    @Test
    fun `编辑器移动节点后列表刷新及重新打开仍排在目标组末尾`() {
        val fixtures = UiFixtures.install()
        val target = ui.bg { GroupRepository.createGroup(ProxyGroup(name = "Move target")) }
        UiFixtures.addProfiles(target, 2)
        val originalIds = UiFixtures.profileIds(target.id)
        val moved = fixtures.profiles[1]
        assertEquals(2L, moved.userOrder)
        DataStore.selectedGroup = target.id
        val main = ui.launch<MainActivity>()
        fun visibleIds() = main.hostedFragment<ConfigurationFragment>()
            .getCurrentGroupFragment()!!.adapter!!.items.map { it.id }
        assertEquals(originalIds, visibleIds())

        val editor = ui.launchEditor(ShadowsocksSettingsActivity::class.java, moved.id)
        assertTrue(editor.get().onOptionsItemSelected(RoboMenuItem(R.id.action_move)))
        UiSettle.settle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        try {
            val root = dialog.window!!.decorView as ViewGroup
            val cards = root.descendants.filter { it.id == R.id.content }.toList()
            assertEquals("只有一个可移动的普通目标组", 1, cards.size)
            assertTrue(cards.single().performClick())
            UiSettle.settle()
            assertTrue(editor.get().isFinishing)
            assertEquals(originalIds + moved.id, visibleIds())
            assertEquals(originalIds + moved.id, UiFixtures.profileIds(target.id))
            assertFalse(UiFixtures.profileIds(fixtures.normalGroup.id).contains(moved.id))
        } finally {
            dialog.dismiss()
        }

        // 销毁所有页面，创建新的 ViewModel，从数据库重新读出默认顺序。
        ui.finishActivities()
        val reopened = ui.launch<MainActivity>()
        val ids = reopened.hostedFragment<ConfigurationFragment>()
            .getCurrentGroupFragment()!!.adapter!!.items.map { it.id }
        assertEquals(originalIds + moved.id, ids)
    }
}
