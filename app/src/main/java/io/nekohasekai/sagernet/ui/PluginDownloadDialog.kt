package io.nekohasekai.sagernet.ui

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.ktx.launchCustomTab

/** 缺插件时的下载确认对话框：说明缺哪个插件，确认后打开插件的下载页面 */
fun Context.showPluginDownloadDialog(pluginEntry: PluginEntry) {
    MaterialAlertDialogBuilder(this).setTitle(pluginEntry.displayName)
        .setMessage(getString(R.string.plugin_download_page, pluginEntry.displayName))
        .setPositiveButton(R.string.download) { _, _ ->
            launchCustomTab(pluginEntry.downloadSource.downloadLink)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
