package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.graphics.Color
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.SendLog

class LogcatFragment : ToolbarFragment(R.layout.layout_logcat),
    Toolbar.OnMenuItemClickListener {

    lateinit var binding: LayoutLogcatBinding

    @SuppressLint("RestrictedApi", "WrongConstant")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_log)

        toolbar.inflateMenu(R.menu.logcat_menu)
        toolbar.setOnMenuItemClickListener(this)

        binding = LayoutLogcatBinding.bind(view)

        binding.textview.breakStrategy = 0 // simple

        binding.scroolview.padForSystemBars()

        reloadSession()
    }

    private fun getColorForLine(line: String): ForegroundColorSpan {
        var color = ForegroundColorSpan(Color.GRAY)
        when {
            line.contains("INFO[") || line.contains(" [Info]") -> {
                color = ForegroundColorSpan((0xFF86C166).toInt())
            }

            line.contains("ERROR[") || line.contains(" [Error]") -> {
                color = ForegroundColorSpan(Color.RED)
            }

            line.contains("WARN[") || line.contains(" [Warning]") -> {
                color = ForegroundColorSpan(Color.RED)
            }
        }
        return color
    }

    private fun reloadSession() {
        val span = SpannableString(
            String(SendLog.getNekoLog(50 * 1024))
        )
        var offset = 0
        for (line in span.lines()) {
            val color = getColorForLine(line)
            span.setSpan(
                color, offset, offset + line.length, SPAN_EXCLUSIVE_EXCLUSIVE
            )
            offset += line.length + 1
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
                    val logFile = SendLog.prepareLog("NB4A")
                    withContext(Dispatchers.Main.immediate) {
                        SendLog.shareLog(context, logFile)
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
