package io.nekohasekai.sagernet.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.database.EditorSessionState
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.STATE_EDITOR_SESSION
import io.nekohasekai.sagernet.database.checkEditorSession
import io.nekohasekai.sagernet.database.claimEditorSession
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.renewEditorSession
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onLiveActivity
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.OutboundPreference
import io.nekohasekai.sagernet.widget.liftAncestorAppBar
import io.nekohasekai.sagernet.widget.padForSystemBars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

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

    /**
     * 页面级状态的持有者（会话令牌、初始化结果、选择器暂存等），旋转时留存。
     * 在 `super.onCreate()` 之前就要用（认领会话），所以不带 SavedStateHandle。
     */
    protected val editor: EditorViewModel by viewModels()

    /** False once another editor claimed the cache; saving would hit the wrong entity. */
    protected val ownsEditorSession get() = editor.owns

    // 令牌也存进实例状态：进程重建后 ViewModel 是新的，靠它认出缓存是被接管还是只是丢了
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_EDITOR_SESSION, editor.session)
    }

    /**
     * A first creation resets the cache and claims it, a restore verifies the claim.
     * Call before `super.onCreate()`, and before writing any of the editor's own keys.
     */
    protected fun beginEditorSession(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            editor.session = claimEditorSession()
            editor.owns = true
            return
        }
        // 旋转：ViewModel 还在，令牌以它为准；进程重建：ViewModel 是新的，令牌取自实例状态
        if (editor.session == 0L) editor.session = savedInstanceState.getLong(STATE_EDITOR_SESSION, 0L)
        editor.owns = checkEditorSession(editor.session) != EditorSessionState.TAKEN_OVER
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
    protected fun renewEditorSessionToken() = renewEditorSession(editor.session)

    /**
     * 会话归属的统一检查入口：初始化写缓存、提交 Fragment、选择器回调写缓存、保存，
     * 都先过这里；不属于当前会话（页面已销毁、被别的编辑器接管、保存时缓存不是本编辑器的）
     * 的迟到结果直接丢弃。任意线程可调用。
     *
     * @param forSave true 表示保存：要求缓存里就是本编辑器的内容（见 [permitsSave]）；
     * false 表示初始化 / 回调写缓存（见 [permitsCacheWrite]）。
     */
    protected fun holdsEditorSession(forSave: Boolean = false): Boolean {
        // 保存不看页面是否销毁：缓存归属由令牌决定（旋转重建后令牌不变），保存在途中
        // 被旋转时，写完比丢掉用户的保存更合理
        if (!forSave && lifecycle.currentState == Lifecycle.State.DESTROYED) return false
        return editor.holds(forSave)
    }

    /**
     * 恢复时是否需要走 [launchEditorInit]：首次创建、进程死亡后缓存被清空，
     * 或上一个实例在 Fragment 提交前就被销毁（此时用户还没见过界面，不会丢修改）。
     * 读库与写缓存只在 ViewModel 里跑一次，旋转后的这种情况只是重新提交 Fragment。
     */
    protected fun needsEditorInit(savedInstanceState: Bundle?, cacheMarkerKey: String): Boolean =
        savedInstanceState == null ||
                EditorCache.profileCacheStore.getString(cacheMarkerKey) == null ||
                supportFragmentManager.findFragmentById(R.id.settings) == null

    /**
     * 编辑器协程的统一入口（选择器回调、子编辑器结果回写等要碰 View 的工作经这里）。在 [scope]（默认页面的
     * [lifecycleScope]，Fragment 里的调用传自己的）里的 [context] 上启动 [block]：
     * - 启动时先查一次会话归属，不属于本编辑器（页面已销毁、缓存被别的编辑器接管）就不跑；
     * - [block] 里每个挂起点（含阻塞读库）之后、碰缓存或 View 之前，必须再查一次：读库用
     *   [EditorScope.checked]，切主线程用 [EditorScope.onMain]，两者检查失败都抛 [CancellationException]，
     *   协程静默结束，迟到的结果被丢弃（不弹提示，被接管的编辑器此时已不可见或已提示过）；
     * - 页面销毁时协程随 [scope] 取消。
     * 要跨旋转存活、且不碰 View 的工作（初始化读库等）走 [EditorViewModel.launchInSession]；
     * 必须在页面关闭后仍写完的保存不走这里（见 [saveAndExit]）。
     */
    protected fun launchInSession(
        scope: CoroutineScope = lifecycleScope,
        context: CoroutineContext = Dispatchers.Default,
        block: suspend EditorScope.() -> Unit,
    ): Job = scope.launch(context) {
        val session = EditorScope(this, ::holdsEditorSession)
        session.ensureHeld()
        session.block()
    }

    /**
     * 编辑器初始化：[load] 在后台把实体写进缓存，返回 false 表示实体已不存在（直接关闭页面）；
     * 随后提交偏好 Fragment（[now] 为真时同步提交）。
     * [load] 由 [EditorViewModel.startInit] 在 ViewModel 作用域里只跑一次：旋转后新的 Activity 再调本函数只会
     * 等同一个结果并重新提交 Fragment（上一个实例可能在提交前就被销毁了），不再读库；因此 [load] 里不得碰
     * Activity 的 View。读库要用 [EditorScope.checked] 包起来，再写缓存。会话失效时静默结束，不提交、不关闭页面。
     */
    protected fun launchEditorInit(
        createFragment: () -> Fragment,
        now: Boolean = false,
        load: suspend EditorScope.() -> Boolean,
    ) {
        val loaded = editor.startInit(load)
        launchInSession {
            if (!checked { loaded.await() }) {
                onMainDispatcher { finish() }
                return@launchInSession
            }
            commitEditorFragment(createFragment(), now)
        }
    }

    /**
     * 提交编辑器的偏好 Fragment。等到页面至少处于 STARTED 再提交（已经 onSaveInstanceState
     * 之后提交会抛 IllegalStateException，也不用 commitAllowingStateLoss 掩盖），销毁则取消；
     * 提交前再确认一次会话归属，并重新认领缓存（进程死亡后初始化重写了缓存）。
     * 返回是否真的提交了。
     */
    protected suspend fun commitEditorFragment(fragment: Fragment, now: Boolean = false): Boolean =
        lifecycle.withStarted {
            if (!holdsEditorSession()) return@withStarted false
            renewEditorSessionToken()
            supportFragmentManager.beginTransaction().replace(R.id.settings, fragment).apply {
                if (now) commitNow() else commit()
            }
            true
        }

    /**
     * 保存入口：会话不属于本编辑器时不保存、提示并关闭，否则交给 [save]。
     * 保存留在调用方的作用域里跑（不绑页面），页面在保存途中关闭也要写完；
     * 写完之后的界面收尾（提示、对话框、关闭页面）一律经 [onLiveActivity]，页面已销毁时整段丢弃。
     */
    suspend fun saveAndExit() {
        if (!holdsEditorSession(forSave = true)) {
            onLiveActivity {
                Toast.makeText(this@EditorActivity, R.string.editor_session_lost, Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }
        save()
    }

    // 以下是三个编辑器共用的偏好页骨架：R.id.settings 里的 EditorPreferenceFragment、
    // profile_config_menu 菜单与脏标记监听；各编辑器只实现下面的钩子

    /** 偏好列表是否是屏幕最底部的可滚动视图；链式代理设置下方另有节点列表，由它承接底部 inset。 */
    open val preferenceListReachesBottom = true

    /** 顶部栏是否随偏好列表的滚动抬升；偏好列表之外还有内容紧贴顶部栏时关掉（见链式代理设置）。 */
    open val liftAppBarWithPreferenceList = true

    abstract fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    )

    open fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
    }

    /** 写库并关闭页面；只经 [saveAndExit] 调用，调用时会话归属已确认。 */
    protected abstract suspend fun save()

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
     * onSelected 要先写 pending 再写 EditorCache：重新初始化若在回调之后运行会补回 pending，
     * 在之前运行则被这些写入覆盖。
     */
    protected fun profilePicker(
        onSelected: (Long) -> Unit,
        preference: () -> OutboundPreference?,
    ) = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) launchInSession {
            // RESULT_OK 也可能不带 data（picker 被重建等）
            val data = it.data ?: return@launchInSession
            // 查库期间页面可能已销毁或缓存已被别的编辑器接管
            val profile = checked {
                ProfileRepository.getProfile(
                    data.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
                )
            } ?: return@launchInSession
            onSelected(profile.id)
            // 进程死亡恢复时 fragment 可能还没提交；它创建时会读 EditorCache 里的值
            onMain { preference()?.value = "3" }
        }
    }

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                // 在主线程取 activity：点击后对话框马上 detach，
                // 放到 Default 调度器里再 requireActivity() 会和它竞争
                val activity = requireActivity() as EditorActivity
                // 进程级：保存必须写完，页面在保存途中关闭或旋转也不能中断；界面收尾在 saveAndExit 里走 onLiveActivity
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
            if (activity?.liftAppBarWithPreferenceList != false) listView.liftAncestorAppBar()

            activity?.apply {
                viewCreated(view, savedInstanceState)
                // 只在首次创建时清 dirty：重建（旋转）后再清会悄悄丢掉未保存的修改
                if (savedInstanceState == null) {
                    EditorCache.dirty = false
                }
                EditorCache.profileCacheStore.registerChangeListener(this)
            }
        }

        // 文本编辑项统一弹 Outlined 对话框，登记过输入规则的（整数、端口、服务器地址、证书指纹等）带行内校验
        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (!showOutlinedDialog(preference)) super.onDisplayPreferenceDialog(preference)
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
                // 进程级：保存必须写完，页面在保存途中关闭或旋转也不能中断；界面收尾在 saveAndExit 里走 onLiveActivity
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
                true
            }

            else -> false
        }

    }
}
