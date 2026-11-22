package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// Stopped 表示服务报告已收尾，Idle 也可能是主进程尚未绑定 :bg。
// 备份写库还须独占 ServiceRestoreLock，以排除缓存状态滞后和紧随其后的启动。
val BaseService.State.isSettled get() = this == BaseService.State.Stopped || this == BaseService.State.Idle

// 挂起到状态收尾为止；超时返回 false。条件是状态本身，不是固定等待：状态已收尾时立即返回
suspend fun StateFlow<BaseService.State>.awaitSettled(timeoutMs: Long): Boolean =
    value.isSettled || withTimeoutOrNull(timeoutMs) { first { it.isSettled } } != null
