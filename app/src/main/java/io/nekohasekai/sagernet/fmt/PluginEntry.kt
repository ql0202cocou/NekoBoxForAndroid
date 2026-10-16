package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet

enum class PluginEntry(
    val pluginId: String,
    val displayName: String,
    val downloadSource: DownloadSource
) {
    TrojanGo(
        "trojan-go-plugin",
        SagerNet.application.getString(R.string.action_trojan_go),
        DownloadSource(
            downloadLink = "https://github.com/SagerNet/SagerNet/releases?q=trojan-go-plugin"
        )
    ),
    MieruProxy(
        "mieru-plugin",
        SagerNet.application.getString(R.string.action_mieru),
        DownloadSource(
            downloadLink = "https://github.com/MatsuriDayo/plugins/releases?q=mieru"
        )
    ),
    NaiveProxy(
        "naive-plugin",
        SagerNet.application.getString(R.string.action_naive),
        DownloadSource(
            downloadLink = "https://github.com/MatsuriDayo/plugins/releases?q=naive"
        )
    ),
    Hysteria(
        "hysteria-plugin",
        SagerNet.application.getString(R.string.action_hysteria),
        DownloadSource(
            downloadLink = "https://github.com/MatsuriDayo/plugins/releases?q=Hysteria"
        )
    ),
    ;

    /** 插件包托管的 GitHub Releases 页面 */
    data class DownloadSource(
        val downloadLink: String
    )

    companion object {

        fun find(name: String): PluginEntry? {
            for (pluginEntry in enumValues<PluginEntry>()) {
                if (name == pluginEntry.pluginId) {
                    return pluginEntry
                }
            }
            return null
        }

    }

}
