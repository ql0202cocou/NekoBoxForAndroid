package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.SendLog

class BlankActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 崩溃重启后发送日志
        val title = intent?.getStringExtra("sendLog")
        if (title == null) {
            finish()
            return
        }
        // prepareLog 要跑 logcat -d 并脱敏整份日志，放到后台线程。本 Activity 是
        // CrashHandler 的重启目标，这里再抛异常会触发无限重启，所以全部兜住；
        // finish 放在分享之后，提前 finish 会取消 lifecycleScope
        lifecycleScope.launch(Dispatchers.IO) {
            val logFile = runCatching { SendLog.prepareLog(title) }
                .onFailure { Logs.w(it) }
                .getOrNull()
            withContext(Dispatchers.Main) {
                if (logFile != null) {
                    runCatching { SendLog.shareLog(this@BlankActivity, logFile) }
                        .onFailure { Logs.w(it) }
                }
                finish()
            }
        }
    }

}
