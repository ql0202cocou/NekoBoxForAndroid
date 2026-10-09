package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutLogcatBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.padForSystemBars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.SendLog

class LogcatFragment : ToolbarFragment(R.layout.layout_logcat),
    Toolbar.OnMenuItemClickListener {

    override val opensFromSettings = true

    lateinit var binding: LayoutLogcatBinding

    @SuppressLint("RestrictedApi", "WrongConstant")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_log)

        toolbar.inflateMenu(R.menu.logcat_menu)
        toolbar.setOnMenuItemClickListener(this)

        binding = LayoutLogcatBinding.bind(view)

        binding.textview.breakStrategy = 0 // simple

        binding.scroolview.padForSystemBars(bottomExtra = mainBottomClearance())

        reloadSession()
    }

    // 日志三色（res/color 的 log_info / log_error / log_other，亮暗各一套）：在主线程取好再交给 IO 上的着色
    private class LogColors(val info: Int, val error: Int, val other: Int)

    private fun getColorForLine(line: String, colors: LogColors): ForegroundColorSpan {
        val color = when {
            line.contains("INFO[") || line.contains(" [Info]") -> colors.info
            line.contains("ERROR[") || line.contains(" [Error]") -> colors.error
            line.contains("WARN[") || line.contains(" [Warning]") -> colors.error
            else -> colors.other
        }
        return ForegroundColorSpan(color)
    }

    // 读日志文件（最多 50KB）和着色放到 IO 上，只把显示留在主线程
    private fun reloadSession() = viewLifecycleOwner.lifecycleScope.launch {
        val context = requireContext()
        val colors = LogColors(
            context.getColour(R.color.log_info),
            context.getColour(R.color.log_error),
            context.getColour(R.color.log_other),
        )
        val span = withContext(Dispatchers.IO) {
            SpannableString(String(SendLog.getNekoLog(50 * 1024))).also { span ->
                var offset = 0
                for (line in span.lines()) {
                    val color = getColorForLine(line, colors)
                    span.setSpan(
                        color, offset, offset + line.length, SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    offset += line.length + 1
                }
            }
        }
        binding.textview.text = span
        binding.textview.clearFocus()
        // 等 textview 完成最终 layout 再滚动到底部
        binding.textview.doOnLayout {
            binding.scroolview.scrollTo(0, binding.textview.height)
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear_logcat -> {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                    try {
                        Libcore.nekoLogClear()
                        // reap the child: the Process keeps its pipes open until destroyed
                        val process = Runtime.getRuntime().exec(arrayOf("/system/bin/logcat", "-c"))
                        try {
                            process.waitFor()
                        } finally {
                            process.destroy()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main.immediate) {
                            snackbar(e.readableMessage).show()
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main.immediate) {
                        binding.textview.text = ""
                    }
                }

            }

            R.id.action_send_logcat -> {
                val context = requireContext()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                    // 同上：失败只提示，协程里漏出的异常会让整个应用崩溃
                    try {
                        val logFile = SendLog.prepareLog("NB4A")
                        withContext(Dispatchers.Main.immediate) {
                            SendLog.shareLog(context, logFile)
                        }
                    } catch (e: CancellationException) {
                        // 离开页面导致的取消照常传播，不当作失败记日志
                        throw e
                    } catch (e: Exception) {
                        Logs.w(e)
                        withContext(Dispatchers.Main.immediate) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }

            R.id.action_refresh -> {
                reloadSession()
            }
        }
        return true
    }

}
