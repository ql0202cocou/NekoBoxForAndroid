package io.nekohasekai.sagernet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.checkEditorSession
import io.nekohasekai.sagernet.database.permitsCacheWrite
import io.nekohasekai.sagernet.database.permitsSave
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * 编辑器（节点 / 分组 / 路由）的页面级状态，旋转时留存、页面真正关闭时随 [onCleared] 清掉：
 * - 会话令牌与归属（[session]、[owns]）：令牌在 EditorSession.kt 里认领，进程重建时由 Activity 的实例状态带回
 *   （要在 `super.onCreate()` 之前读，SavedStateHandle 此时还取不到，所以仍走 Bundle）；
 * - 初始化的读库与写缓存（[startInit]）、正在编辑的节点（[loadProxyEntity]）、「移动」菜单可见性
 *   （[canMoveProfile]）：旋转后不再重读库，只有进程重建（ViewModel 是新的）才重新载入；
 * - 选择器 / 文件选择的暂存结果（[setPending]）。
 *
 * 在 [viewModelScope] 里跑的协程（[launchInSession]、[startInit]）可能比创建它们的 Activity 实例活得久，所以
 * 不得碰 Activity 与 View，也不查页面是否销毁：会话归属只看令牌（[holds]），页面关闭由 [viewModelScope] 取消。
 * 界面刷新由当前的 Activity 订阅状态（如 [canMoveProfile]）后自己做。
 */
class EditorViewModel : ViewModel() {

    // ------------------------------------------------------------- 会话

    /** 缓存归属令牌；0 表示还没认领。由 [EditorActivity.beginEditorSession] 设置 */
    @Volatile
    var session = 0L
        internal set

    /** 缓存已被别的顶层编辑器接管时为假；接管后本编辑器不能再存 */
    @Volatile
    var owns = true
        internal set

    /**
     * 缓存此刻是否仍属于本编辑器（令牌比对，不看页面状态，任意线程可调）。
     * [forSave] 为真要求缓存里就是本编辑器的内容（见 [permitsSave]），否则只要没被接管（见 [permitsCacheWrite]）。
     */
    fun holds(forSave: Boolean = false): Boolean {
        if (!owns) return false
        val state = checkEditorSession(session)
        return if (forSave) state.permitsSave() else state.permitsCacheWrite()
    }

    /** 在 [viewModelScope] 里跑 [block]，会话检查同 [EditorActivity.launchInSession]，但只看令牌（见类注释） */
    fun launchInSession(
        context: CoroutineContext = Dispatchers.Default,
        block: suspend EditorScope.() -> Unit,
    ): Job = viewModelScope.launch(context) {
        val scope = EditorScope(this, ::holds)
        scope.ensureHeld()
        scope.block()
    }

    // ------------------------------------------------------------- 初始化

    private val initResult = CompletableDeferred<Boolean>()
    private var initStarted = false

    /**
     * 编辑器初始化的读库与写缓存：整个 ViewModel 生命周期只跑一次。旋转后新的 Activity 再调用时拿到的是
     * 同一个结果（在途就继续等，已完成就直接用），不会再读一遍库；进程重建后 ViewModel 是新的，才重新跑。
     * [load] 返回假表示实体已不存在；会话失效时结果被取消。只在主线程调用；[load] 里不得碰 View。
     */
    fun startInit(load: suspend EditorScope.() -> Boolean): CompletableDeferred<Boolean> {
        if (!initStarted) {
            initStarted = true
            viewModelScope.launch(Dispatchers.Default) {
                val scope = EditorScope(this, ::holds)
                scope.ensureHeld()
                initResult.complete(scope.load())
            }.invokeOnCompletion { cause ->
                if (cause != null) initResult.completeExceptionally(cause)
            }
        }
        return initResult
    }

    /** 重建后缓存与 Fragment 都还在，不需要初始化：记为已完成（已经启动过初始化则不动） */
    fun markInitDone() {
        if (!initStarted) {
            initStarted = true
            initResult.complete(true)
        }
    }

    /**
     * 等初始化把实体写进缓存。实体已不存在（[startInit] 的 load 返回假）或会话失效时抛
     * [CancellationException]，调用方的协程随之静默结束。
     */
    suspend fun awaitInit() {
        if (!initResult.await()) throw CancellationException("编辑的实体已不存在")
    }

    // ------------------------------------------------------------- 正在编辑的节点

    // 正在编辑的那一行，新建节点时为 null。载入完成前是 null，要用它的地方先 awaitProxyEntity()
    @Volatile
    var proxyEntity: ProxyEntity? = null
        private set
    private val proxyEntityLoad = CompletableDeferred<ProxyEntity?>()
    private var proxyEntityRequested = false

    suspend fun awaitProxyEntity(): ProxyEntity? = proxyEntityLoad.await()

    /**
     * 载入 [editingId] 对应的节点（0 表示新建，不查库）。只在主线程调用，一个 ViewModel 只载入一次：
     * 旋转后不重读库。不挂在 [viewModelScope] 上：保存跑在 appScope 里等这个结果，页面在载入途中被关闭也不能
     * 让它半路被取消。读库抛出的异常交给每个等待方。
     */
    fun loadProxyEntity(editingId: Long) {
        if (proxyEntityRequested) return
        proxyEntityRequested = true
        if (editingId == 0L) {
            proxyEntityLoad.complete(null)
            return
        }
        // 进程级：保存（appScope）在等这个结果，页面在载入途中关闭也不能让它半路取消；协程不碰 View
        runOnDefaultDispatcher {
            try {
                val entity = ProfileRepository.getProfile(editingId)
                proxyEntity = entity
                proxyEntityLoad.complete(entity)
            } catch (e: Throwable) {
                proxyEntityLoad.completeExceptionally(e)
            }
        }
    }

    // ------------------------------------------------------------- 「移动」菜单

    private val _canMoveProfile = MutableStateFlow(false)

    /** action_move 的可见性要查库得出；[prefetchCanMoveProfile] 算好后由 Activity 订阅并刷新菜单，默认值与菜单 XML 一致（隐藏） */
    val canMoveProfile: StateFlow<Boolean> get() = _canMoveProfile

    private var canMoveRequested = false

    /** 初始化写好 editingGroup 后异步算一次（旋转不重算）。只在主线程调用 */
    fun prefetchCanMoveProfile() {
        if (canMoveRequested) return
        canMoveRequested = true
        launchInSession {
            // 节点已不存在或初始化失败时 awaitInit 抛取消，这里随之结束
            checked { awaitInit() }
            // 读 editingGroup 与查库都在会话内：被接管后缓存里是别人的内容，不能拿来算菜单
            val can = EditorCache.editingId != 0L // 非新建
                    && GroupRepository.getGroup(EditorCache.editingGroup)?.type == GroupType.BASIC // 不在订阅组
                    && GroupRepository.getAllGroups()
                .filter { it.type == GroupType.BASIC }.size > 1 // 还有其他普通分组
            onMain { _canMoveProfile.value = can }
        }
    }

    // ------------------------------------------------------------- 选择器暂存

    // 选择器 / 文件选择的结果可能在异步初始化之前到达（进程重建后系统重放 Activity 结果），
    // 初始化要把它们补回缓存，否则实体里的值会盖掉用户刚选的。主线程与后台都会读写
    private val pending = ConcurrentHashMap<String, Any>()

    fun setPending(key: String, value: Any) {
        pending[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> pending(key: String): T? = pending[key] as T?

    override fun onCleared() {
        // 从没启动过初始化就关闭时放掉等它的协程
        initResult.cancel()
    }
}

/**
 * 编辑器协程里会话检查的接收者，由 [EditorActivity.launchInSession] 与 [EditorViewModel.launchInSession] 提供。
 * [holds] 决定「会话还属于本编辑器」：Activity 版同时看页面是否销毁，ViewModel 版只看令牌。
 */
class EditorScope internal constructor(
    scope: CoroutineScope,
    private val holds: () -> Boolean,
) : CoroutineScope by scope {

    /** 会话已失效就抛 [CancellationException]，结束当前协程。 */
    fun ensureHeld() {
        if (!holds()) throw CancellationException("编辑器会话已失效")
    }

    /** 运行 [block]（可挂起或阻塞读库），返回前再查一次会话；调用方只在这之后才可以碰缓存与 View。 */
    suspend fun <T> checked(block: suspend () -> T): T {
        val result = block()
        ensureHeld()
        return result
    }

    /**
     * 切到主线程，先查会话再运行 [block]；[block] 本身不再挂起，所以查完到写完之间没有间隙。
     * 写缓存、改 View 都放在这里面。
     */
    suspend fun <T> onMain(block: () -> T): T = onMainDispatcher {
        ensureHeld()
        block()
    }
}
