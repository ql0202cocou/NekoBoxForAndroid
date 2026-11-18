package io.nekohasekai.sagernet.uitest

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.ui.AppListActivity
import io.nekohasekai.sagernet.ui.AppManagerActivity
import io.nekohasekai.sagernet.ui.AppSelectActivity
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.fakes.RoboMenuItem

class AppSelectionClipboardTest : UiTestBase() {
    private fun AppSelectActivity.paste(text: String) {
        assertTrue(SagerNet.trySetPrimaryClip(text))
        onOptionsItemSelected(RoboMenuItem(R.id.action_import_clipboard))
    }

    @Test
    fun invalidPastePreservesModeAndPackagesAndValidPasteAcceptsCrlf() {
        DataStore.bypass = true
        DataStore.individual = "com.example.original"
        val page = ui.launch<AppManagerActivity>().get()
        for (text in listOf("ordinary text", "false", "bad\ncom.example.ok", "false\ncom.example.ok\nbad entry")) {
            page.paste(text)
            assertTrue(DataStore.bypass)
            assertEquals("com.example.original", DataStore.individual)
        }
        page.paste("false\r\nandroid\r\ncom.example.uninstalled\r\n")
        assertFalse(DataStore.bypass)
        assertEquals("android\ncom.example.uninstalled", DataStore.individual)
        page.paste("true\r\n")
        assertTrue(DataStore.bypass)
        assertEquals("", DataStore.individual)
    }

    @Test
    fun routeEditorValidatesTheSameFormatBeforeChangingItsDraft() {
        EditorCache.routePackages = "android"
        val page = ui.launch<AppListActivity>().get()
        page.paste("oops")
        assertEquals("android", EditorCache.routePackages)
        page.paste("false\ncom.example.another")
        assertEquals("com.example.another", EditorCache.routePackages)
    }
}
