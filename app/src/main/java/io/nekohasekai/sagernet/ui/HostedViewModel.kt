package io.nekohasekai.sagernet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 有页面宿主的 ViewModel：宿主 [H]（页面里的适配器）在视图创建时 [attach]、销毁时 [detach]；
 * 没有宿主（旋转的空档）时只改状态，新宿主接上时直接取现成的列表。子类用 [onAttached] / [onDetached] 补自己的收尾。
 * [host] 只在主线程读写。
 */
abstract class HostedViewModel<H : Any> : ViewModel() {

    protected var host: H? = null
        private set

    fun attach(host: H) {
        this.host = host
        onAttached(host)
    }

    /** 视图销毁时摘掉 [host]；不是当前宿主（已被新页面换掉）就不动 */
    fun detach(host: H) {
        if (this.host !== host) return
        this.host = null
        onDetached()
    }

    fun isAttached(host: H) = this.host === host

    protected open fun onAttached(host: H) = Unit

    protected open fun onDetached() = Unit

    /**
     * 仓库回调跑在调用方线程：改列表一律投回主线程。用 Dispatchers.Main（不是 immediate），
     * 与原先的 view.post 一样总是排到主线程消息队列里，不会在回调线程就是主线程时插队
     */
    protected fun postToMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) { block() }
    }
}

/** 列表页的宿主：列表变更的统一入口 [mutate] 先提交撤销窗口（UndoSnackbarManager.mutate），再执行块 */
interface ListHost {
    fun mutate(block: () -> Unit)
}

/** 列表页的 ViewModel：改列表经 [mutateList]，有宿主时先提交撤销窗口，没有宿主时直接改 */
abstract class ListViewModel<H : ListHost> : HostedViewModel<H>() {

    protected fun mutateList(block: () -> Unit) {
        val host = host
        if (host == null) block() else host.mutate(block)
    }
}
