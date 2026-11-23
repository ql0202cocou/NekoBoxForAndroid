package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.pm.ShortcutManager
import android.os.Build
import androidx.core.content.getSystemService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection

// 无界面的快捷方式：连上服务做一次操作就结束。绑定被拒（connect 内部已回滚）、超时或服务断开时
// onServiceConnected 永不到达，这里自己 finish，否则这个 Activity 永远残留
abstract class ServiceShortcutActivity : Activity(), SagerConnection.Callback {
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_SHORTCUT)

    // 返回 false 时已 finish
    protected fun connectOrFinish(): Boolean {
        if (connection.connect(this, this, SagerConnection.SHORTCUT_CONNECT_TIMEOUT_MS)) return true
        finish()
        return false
    }

    protected fun reportShortcutUsed(id: String) {
        if (Build.VERSION.SDK_INT >= 25) getSystemService<ShortcutManager>()!!.reportShortcutUsed(id)
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}

    override fun onServiceDisconnected() = finish()

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }
}
