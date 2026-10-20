package io.nekohasekai.sagernet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleRepository
import io.nekohasekai.sagernet.widget.insertClamped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// removedIds 里「删除落库尚未确认」的标记：大于任何重读序号，永不被清掉
private const val UNCONFIRMED = Long.MAX_VALUE

/**
 * 路由页的页面级状态，旋转时留存、页面真正关闭时随 [onCleared] 清掉：
 * - [ruleList] 与 [outboundNames]：列表的工作副本和出站节点名。旋转后新的适配器直接用它们，不重读库、
 *   滚动位置也就能恢复；只有进程重建（ViewModel 是新的）才重新读库；
 * - 滑掉的规则（[removedIds]）与重读序号（[reloadGen]）：过滤「读库早于删除落库」的陈旧结果，
 *   与列表同寿命，旋转时撤销窗口提交的删除仍被它们盖住；
 * - 对 [RuleRepository] 的监听器订阅：ViewModel 创建时订阅、清除时摘掉，旋转期间到达的通知也落在列表上。
 *
 * 状态只在主线程读写。监听回调跑在调用方线程，节点名在那里查，改列表一律切回主线程。
 * 界面刷新由当前的 [Host]（页面里的适配器）完成；没有 [Host]（旋转的空档）时只改状态，
 * 新适配器建好时直接取现成的列表。ViewModel 不碰 Activity 与 View。
 */
class RouteViewModel : ViewModel() {

    /** 页面里的适配器与 Fragment：ViewModel 把「改列表」的通知交给它 */
    interface Host {
        /** 列表变更的统一入口：先提交撤销窗口（UndoSnackbarManager.mutate），再执行 [block] */
        fun mutate(block: () -> Unit)

        /** 状态变了：按 [RouteViewModel.ruleList] 重新生成行提交，由 DiffUtil 发通知 */
        fun refresh()

        /** 规则变了且服务在运行：提示重载（Fragment.needReload） */
        fun needReload()
    }

    /** 主线程上的工作副本，是列表内容的唯一来源；拖动换位、滑动删除、撤销都就地改它 */
    val ruleList = ArrayList<RuleEntity>()

    /** 出站指定节点的规则要显示的节点名，键是节点 id；名字在后台查好再放进来，绑定时不查库 */
    val outboundNames = HashMap<Long, String>()

    /** 是否已经读过一次库；为假时页面不显示空列表占位，免得刚建好页面时闪一下 */
    var loaded = false
        private set

    // 滑掉的规则：id → 「落库已确认」时的 reloadGen，未确认为 UNCONFIRMED。整表重读开始得早于删除落库时，
    // 读到的结果里仍有这些规则，应用结果时按它过滤，滑掉的行才不会回来、撤销也不会插出重复项。
    // id 是自增主键、不复用，过滤在确认之后也不会误伤别的规则
    private val removedIds = HashMap<Long, Long>()

    // 整表重读的序号，每次在主线程上、读库之前递增
    private var reloadGen = 0L

    private var host: Host? = null

    // 没有页面时到达的「提示重载」，等下一个页面接上时补上
    private var needReloadPending = false

    override fun onCleared() {
        RuleRepository.removeListener(listener)
    }

    fun attach(host: Host) {
        this.host = host
        if (needReloadPending) {
            needReloadPending = false
            host.needReload()
        }
    }

    fun detach(host: Host) {
        if (this.host === host) this.host = null
    }

    private fun mutateList(block: () -> Unit) {
        val host = host
        if (host == null) block() else host.mutate(block)
    }

    private fun notifyNeedReload() {
        val host = host
        if (host == null) needReloadPending = true else host.needReload()
    }

    /** 整表重读。可从任意线程调用（含「重置路由」的进程级协程）；ViewModel 清除后什么也不做 */
    fun reload() {
        viewModelScope.launch {
            val gen = ++reloadGen
            val (rules, names) = withContext(Dispatchers.IO) {
                val rules = RuleRepository.getRules()
                rules to RuleRepository.outboundProfileNames(rules)
            }
            // 列表变更的统一入口：先提交撤销窗口，再换列表（撤销下标只对删除当时的列表有效）
            mutateList {
                // 提交只是把删除交给后台，读库可能早于它，所以过滤不能省
                ruleList.clear()
                rules.filterTo(ruleList) { it.id !in removedIds }
                outboundNames.clear()
                outboundNames.putAll(names)
                // 删除确认于本次读库之前（gen 大于确认时的序号），读到的结果已不含它们，可以不再过滤
                removedIds.values.removeAll { it < gen }
                loaded = true
                host?.refresh()
            }
        }
    }

    // ------------------------------------------------------------- 滑动删除与撤销

    /** 滑掉第 [index] 行：记入待确认，返回被滑掉的规则。不通知界面，由调用方紧跟 refresh */
    fun removeAt(index: Int): RuleEntity {
        val rule = ruleList.removeAt(index)
        removedIds[rule.id] = UNCONFIRMED
        return rule
    }

    /**
     * 撤销插回：下标夹在当前范围内，随后通知当前的页面刷新。撤销可能来自旋转前的那个撤销窗口，
     * 所以不经调用方的适配器，而是交给此刻接着的那一个
     */
    fun restore(actions: List<Pair<Int, RuleEntity>>) {
        for ((index, item) in actions) {
            removedIds.remove(item.id)
            ruleList.insertClamped(index, item)
        }
        host?.refresh()
    }

    /** 删除已落库：记下确认时的序号，此后才开始的重读一定读不到这些规则。主线程调用 */
    fun confirmRemoved(rules: List<RuleEntity>) {
        for (rule in rules) {
            if (removedIds.containsKey(rule.id)) removedIds[rule.id] = reloadGen
        }
    }

    // ------------------------------------------------------------- 仓库通知
    // 不让 ViewModel 自己实现 RuleListener：它的 onCleared() 与 ViewModel.onCleared() 同名同签名，会冲突
    private val listener = object : ProfileManager.RuleListener {
        override suspend fun onAdd(rule: RuleEntity) {
            // 监听器跑在调用方的后台线程上，节点名在这里查
            val names = RuleRepository.outboundProfileNames(listOf(rule))
            postToMain {
                mutateList {
                    outboundNames.putAll(names)
                    ruleList.add(rule)
                    host?.refresh()
                }
                notifyNeedReload()
            }
        }

        override suspend fun onUpdated(rule: RuleEntity) {
            // 回调跑在调用方（后台）线程，拖动排序在主线程改列表；只在主线程碰列表
            val names = RuleRepository.outboundProfileNames(listOf(rule))
            postToMain {
                var found = false
                mutateList {
                    outboundNames.putAll(names)
                    val index = ruleList.indexOfFirst { it.id == rule.id }
                    if (index == -1) return@mutateList
                    found = true
                    ruleList[index] = rule
                    host?.refresh()
                }
                // 列表里没有这条（已被滑掉或没读到）时不提示
                if (found) notifyNeedReload()
            }
        }

        override suspend fun onRemoved(ruleId: Long) {
            postToMain {
                // 同节点列表：别处删了规则先提交撤销窗口，免得 undo 按旧位置插回越界或插回幽灵行
                mutateList {
                    val index = ruleList.indexOfFirst { it.id == ruleId }
                    if (index != -1) {
                        ruleList.removeAt(index)
                        host?.refresh()
                    }
                }
                notifyNeedReload()
            }
        }

        override suspend fun onCleared() {
            postToMain {
                // 「重置路由」清空了全部规则：窗口里待删的规则已不在库里，撤销只会插回幽灵行
                mutateList {
                    ruleList.clear()
                    host?.refresh()
                }
                notifyNeedReload()
            }
        }
    }

    // 与原来的 View.post 一样排到主 looper 末尾，不阻塞调用方
    private fun postToMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) { block() }
    }

    // 放在 listener 之后：属性初始化按书写顺序，init 里要用到它
    init {
        RuleRepository.addListener(listener)
        reload()
    }
}
