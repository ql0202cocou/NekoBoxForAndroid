package io.nekohasekai.sagernet.uitest

import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.EditorActivity
import io.nekohasekai.sagernet.ui.EditorScope
import kotlinx.coroutines.Job

/**
 * 只给测试用的最小编辑器：把 [EditorActivity.launchInSession] 暴露出来，用门闩（CompletableDeferred）把协程
 * 停在指定的挂起点，在挂起期间模拟会话被接管 / 页面销毁，再放行，检查迟到的写入是否被丢弃。
 * 不在清单里声明：Robolectric 的 buildActivity 不要求。
 */
class EditorSessionProbeActivity : EditorActivity(R.layout.layout_config_settings) {

    override fun onCreate(savedInstanceState: Bundle?) {
        beginEditorSession(savedInstanceState)
        super.onCreate(savedInstanceState)
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
    }

    override suspend fun save() {
    }

    override fun deleteConfirmationDialog() = DialogFragment()

    fun probe(block: suspend EditorScope.() -> Unit): Job = launchInSession(block = block)

    fun holds() = holdsEditorSession()
}
