package io.nekohasekai.sagernet.ui

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.ktx.launchCustomTab

/** 缺插件时的下载来源选择对话框（从 MainActivity 拆出，逻辑不变） */
fun Context.showPluginDownloadDialog(pluginEntry: PluginEntry) {
    var index = 0
    var playIndex = -1
    var fdroidIndex = -1

    val items = mutableListOf<String>()
    if (pluginEntry.downloadSource.playStore) {
        items.add(getString(R.string.install_from_play_store))
        playIndex = index++
    }
    if (pluginEntry.downloadSource.fdroid) {
        items.add(getString(R.string.install_from_fdroid))
        fdroidIndex = index++
    }

    items.add(getString(R.string.download))
    val downloadIndex = index

    MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
        .setItems(items.toTypedArray()) { _, which ->
            when (which) {
                playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
            }
        }
        .show()
}
