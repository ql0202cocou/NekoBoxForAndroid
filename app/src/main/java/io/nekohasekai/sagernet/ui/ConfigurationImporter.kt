package io.nekohasekai.sagernet.ui

import android.net.Uri
import androidx.core.net.toUri
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.ClashImportException
import io.nekohasekai.sagernet.fmt.v2ray.UnsupportedTransportException
import io.nekohasekai.sagernet.group.ImportBatch
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.MAX_IMPORT_BYTES
import io.nekohasekai.sagernet.ktx.readBytesLimited
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.displayName
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.showSnackbar
import io.nekohasekai.sagernet.ktx.userMessage
import java.io.FileNotFoundException
import java.util.zip.ZipInputStream

/**
 * 配置页的文件 / 剪贴板导入解析（从 ConfigurationFragment 拆出，逻辑与协程作用域不变）。
 * 由 ConfigurationFragment 的字段持有，只存 fragment 引用；解析跑在 appScope，捕获的仍是同一个 fragment。
 * 文件选择器仍由 fragment 注册（注册顺序决定请求 key），选到文件后交给这里
 */
class ConfigurationImporter(private val fragment: ConfigurationFragment) {

    // 进程级：解析文件并建节点，页面销毁后导入仍要完成；界面提示只在页面存活时做（fragment.import / showSnackbar）
    fun importFile(file: Uri) = runOnDefaultDispatcher {
        try {
            val fileName = app.contentResolver.displayName(file)
            val proxies = mutableListOf<AbstractBean>()
            // SAF 提供方可能授予了 uri，却在打开时失败
            val inputStream = app.contentResolver.openInputStream(file)
                ?: throw FileNotFoundException(file.toString())
            if (fileName.endsWith(".zip")) {
                // 尝试按 WireGuard 的 zip 解析
                // 用 use() 关闭：parseRaw 抛异常时曾泄漏文件描述符
                // 单个条目被拒（传输方式不支持、Clash 一个节点都没导入）时记下继续，见 ImportBatch
                val batch = ImportBatch<AbstractBean>()
                ZipInputStream(inputStream).use { zip ->
                    var remaining = MAX_IMPORT_BYTES
                    var entries = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(++entries <= 4096) { "Too many ZIP entries" }
                        val bytes = zip.readBytesLimited(remaining)
                        remaining -= bytes.size
                        if (entry.isDirectory) continue
                        val fileText = bytes.toString(Charsets.UTF_8)
                        try {
                            batch.add(RawUpdater.parseRaw(fileText, entry.name))
                        } catch (e: UnsupportedTransportException) {
                            Logs.w("ZIP entry rejected: ${e.readableMessage}")
                            batch.reject(e)
                        } catch (e: ClashImportException) {
                            Logs.w("ZIP entry rejected: ${e.readableMessage}")
                            batch.reject(e)
                        }
                        zip.closeEntry()
                    }
                }
                proxies.addAll(batch.result())
            } else {
                val fileText = inputStream.use {
                    it.readBytesLimited().toString(Charsets.UTF_8)
                }
                RawUpdater.parseRaw(fileText, fileName)
                    ?.let { pl -> proxies.addAll(pl) }
            }
            if (proxies.isEmpty()) fragment.showSnackbar(R.string.no_proxies_found_in_file)
            else fragment.import(proxies)
        } catch (e: SubscriptionFoundException) {
            (fragment.activity as? MainActivity)?.importSubscription(e.link.toUri())
        } catch (e: Exception) {
            Logs.w(e)
            fragment.showSnackbar(e.userMessage)
        }
    }

    // 进程级：解析剪贴板并建节点，页面销毁后导入仍要完成；界面提示只在页面存活时做
    fun importClipboard(text: String) = runOnDefaultDispatcher {
        try {
            val proxies = RawUpdater.parseRaw(text)
            if (proxies.isNullOrEmpty()) fragment.showSnackbar(R.string.no_proxies_found_in_clipboard)
            else fragment.import(proxies)
        } catch (e: SubscriptionFoundException) {
            (fragment.activity as? MainActivity)?.importSubscription(e.link.toUri())
        } catch (e: Exception) {
            Logs.w(e)
            fragment.showSnackbar(e.userMessage)
        }
    }
}
