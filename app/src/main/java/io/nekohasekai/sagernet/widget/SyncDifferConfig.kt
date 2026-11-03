package io.nekohasekai.sagernet.widget

import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import java.util.concurrent.Executor

// 差量计算与结果回主线程都在调用线程上同步完成
private val SYNC_EXECUTOR = Executor { it.run() }

/**
 * 主线程同步比对的 ListAdapter 配置：submitList 返回时 RecyclerView 已收到全部通知，拖动 / 撤销 / 删除的就地改动
 * 与整表提交才不会撞上一次还没落地的差量。submitList 只在主线程调用；列表在几十到几千行的量级，主线程算得过来
 */
fun <T : Any> syncDifferConfig(callback: DiffUtil.ItemCallback<T>): AsyncDifferConfig<T> =
    AsyncDifferConfig.Builder(callback)
        .setBackgroundThreadExecutor(SYNC_EXECUTOR)
        .setMainThreadExecutor(SYNC_EXECUTOR)
        .build()
