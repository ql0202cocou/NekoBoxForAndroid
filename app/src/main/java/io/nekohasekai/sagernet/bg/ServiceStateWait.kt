package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// 服务已收尾，可以动它依赖的数据：Stopped 是 stopRunner 走完的终态（killProcesses 里的最终流量落库在
// 报 Stopped 之前完成，见 BaseService.stopRunner）；Idle 是主进程没连上 :bg 或 :bg 正在退出
//（没有在跑的服务，也就没有会写库的收尾）。Connecting / Connected / Stopping 都还没收尾
val BaseService.State.isSettled get() = this == BaseService.State.Stopped || this == BaseService.State.Idle

// 挂起到状态收尾为止；超时返回 false。条件是状态本身，不是固定等待：状态已收尾时立即返回
suspend fun StateFlow<BaseService.State>.awaitSettled(timeoutMs: Long): Boolean =
    value.isSettled || withTimeoutOrNull(timeoutMs) { first { it.isSettled } } != null
