package io.nekohasekai.sagernet.uitest

import android.net.Uri
import androidx.appcompat.app.AlertDialog
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowDialog

class SubscriptionDeepLinkTest : UiTestBase() {
    @Test fun `订阅名称中的换行不能在确认框中另起一行`() {
        val activity = ui.launchMain().get()
        val link = "https://example.org/sub"
        val uri = Uri.parse("sn://subscription").buildUpon()
            .appendQueryParameter("url", link)
            .appendQueryParameter("name", "第一行\r\n第二行")
            .build()
        runBlocking { activity.importSubscription(uri) }
        val expected = activity.getString(R.string.subscription_import_message, "第一行⏎第二行")
        assertEquals("$expected\n\n$link", latestDialogMessage())
    }

    @Test fun `确认之前校验明文和编码订阅`() {
        val activity = ui.launchMain().get()
        val before = ui.bg { SagerDatabase.groupDao.allGroups().size }
        fun encoded(type: Int, link: String) = Uri.parse(ProxyGroup(
            name = "Shared group", type = type, subscription = SubscriptionBean().apply {
                initializeDefaultValues()
                this.link = link
            },
        ).toUniversalLink())
        fun plain(link: String) = Uri.parse("sn://subscription").buildUpon()
            .appendQueryParameter("url", link).build()
        val valid = "HtTpS://example.org/sub?key=a%2Fb"
        for (uri in listOf(plain("http://"), plain(""), plain("https:///missing-host"),
            encoded(GroupType.SUBSCRIPTION, ""), encoded(GroupType.BASIC, valid))) {
            runBlocking { activity.importSubscription(uri) }
            assertEquals(activity.getString(R.string.invalid_subscription_url), latestDialogMessage())
            val error = ShadowDialog.getLatestDialog() as AlertDialog
            error.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            UiSettle.settle()
            assertEquals(before, ui.bg { SagerDatabase.groupDao.allGroups().size })
        }
        for (uri in listOf(plain(valid), encoded(GroupType.SUBSCRIPTION, valid))) {
            runBlocking { activity.importSubscription(uri) }
            assertTrue(latestDialogMessage().contains(valid))
            ShadowDialog.getLatestDialog().dismiss()
        }
    }
}
