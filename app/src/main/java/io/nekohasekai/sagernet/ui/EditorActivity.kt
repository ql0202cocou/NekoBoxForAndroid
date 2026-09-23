package io.nekohasekai.sagernet.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.database.EditorSessionState
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.STATE_EDITOR_SESSION
import io.nekohasekai.sagernet.database.checkEditorSession
import io.nekohasekai.sagernet.database.claimEditorSession
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.renewEditorSession
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.OutboundPreference
import io.nekohasekai.sagernet.widget.padForSystemBars

/**
 * Base of the top-level editors (profile / route / group), which own the shared
 * in-memory profileCacheStore one at a time; see EditorSession.kt. The claim runs
 * before `super.onCreate()` — a restored preference fragment reads the cache from
 * there — while the give-up has to run after it, so the two halves stay separate
 * calls instead of one wrapper.
 */
abstract class EditorActivity : ThemedActivity, OnPreferenceDataStoreChangeListener {
    constructor() : super()
    constructor(@LayoutRes contentLayoutId: Int) : super(contentLayoutId)

    // Token proving this Activity still owns the cache; persisted so a restore
    // can detect another editor taking it over.
    private var editorSession = 0L

    /** False once another editor claimed the cache; saving would hit the wrong entity. */
    protected var ownsEditorSession = true
        private set

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_EDITOR_SESSION, editorSession)
    }

    /**
     * A first creation resets the cache and claims it, a restore verifies the claim.
     * Call before `super.onCreate()`, and before writing any of the editor's own keys.
     */
    protected fun beginEditorSession(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            editorSession = claimEditorSession()
            return
        }
        editorSession = savedInstanceState.getLong(STATE_EDITOR_SESSION, 0L)
        ownsEditorSession = checkEditorSession(editorSession) != EditorSessionState.TAKEN_OVER
    }

    /**
     * Call right after `super.onCreate()` and return when true: the cache is gone and
     * the user has been told why.
     */
    protected fun finishIfEditorSessionLost(): Boolean {
        if (ownsEditorSession) return false
        Toast.makeText(this, R.string.editor_session_lost, Toast.LENGTH_LONG).show()
        finish()
        return true
    }

    /** Re-claim after a process-death re-init rewrote the cache. */
    protected fun renewEditorSessionToken() = renewEditorSession(editorSession)

    // 以下是三个编辑器共用的偏好页骨架：R.id.settings 里的 EditorPreferenceFragment、
    // profile_config_menu 菜单与脏标记监听；各编辑器只实现下面的钩子

    /** 偏好列表是否是屏幕最底部的可滚动视图；链式代理设置下方另有节点列表，由它承接底部 inset。 */
    open val preferenceListReachesBottom = true

    abstract fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    )

    open fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
    }

    abstract suspend fun saveAndExit()

    /** 菜单「删除」已有条目时弹出的确认框，arg 与 key 已设好。 */
    abstract fun deleteConfirmationDialog(): DialogFragment

    // 用 getter 而不是 lazy：fragment 在异步读库后才提交，提交前点菜单会把 null
    // （进程死亡后则是随后被 init 替换掉的恢复 fragment）永久缓存下来
    val child get() = supportFragmentManager.findFragmentById(R.id.settings) as? EditorPreferenceFragment

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    // 点菜单时 fragment 可能还没提交
    override fun onOptionsItemSelected(item: MenuItem) = child?.onMenuItemSelected(item) == true

    override fun onDestroy() {
        EditorCache.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            EditorCache.dirty = true
        }
    }

    /**
     * 注册一个 ProfileSelectActivity 结果回调，选中后把 [preference] 切到「3」（指定配置）。
     * onSelected 要先写 pending 再写 DataStore：重新初始化若在回调之后运行会补回 pending，
     * 在之前运行则被这些写入覆盖。
     */
    protected fun profilePicker(
        onSelected: (Long) -> Unit,
        preference: () -> OutboundPreference?,
    ) = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) runOnDefaultDispatcher {
            // RESULT_OK 也可能不带 data（picker 被重建等）
            val data = it.data ?: return@runOnDefaultDispatcher
            val profile = ProfileManager.getProfile(
                data.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            onSelected(profile.id)
            onMainDispatcher {
                // 进程死亡恢复时 fragment 可能还没提交；它创建时会读 DataStore 里的值
                preference()?.value = "3"
            }
        }
    }

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                // 在主线程取 activity：点击后对话框马上 detach，
                // 放到 Default 调度器里再 requireActivity() 会和它竞争
                val activity = requireActivity() as EditorActivity
                runOnDefaultDispatcher {
                    activity.saveAndExit()
                }
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    open class EditorPreferenceFragment : PreferenceFragmentCompat() {

        // createPreferences 抛异常时保持 null
        var activity: EditorActivity? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = EditorCache.profileCacheStore
            try {
                activity = (requireActivity() as EditorActivity).apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "Error on createPreferences, please try again.",
                    Toast.LENGTH_SHORT
                ).show()
                Logs.e(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            listView.padForSystemBars(bottom = activity?.preferenceListReachesBottom ?: true)

            activity?.apply {
                viewCreated(view, savedInstanceState)
                // 只在首次创建时清 dirty：重建（旋转）后再清会悄悄丢掉未保存的修改
                if (savedInstanceState == null) {
                    EditorCache.dirty = false
                }
                EditorCache.profileCacheStore.registerChangeListener(this)
            }
        }

        open fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
            R.id.action_delete -> {
                if (EditorCache.editingId == 0L) {
                    requireActivity().finish()
                } else {
                    (requireActivity() as EditorActivity).deleteConfirmationDialog()
                        .show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
                true
            }

            else -> false
        }

    }
}
