package io.nekohasekai.sagernet.bg

/**
 * 第三次被强杀就停止守护；慢循环不能因时间窗口过期而无限拉起。
 * 例外：一次强杀前已稳定运行 [HEALTHY_UPTIME_MS]，说明不是循环（例如长时间运行中被系统回收），从这次重新计数。
 * 每个守护独占。
 */
internal class ProcessRestartBudget {
    private var kills = 0

    fun allowRestart(killed: Boolean, uptimeMillis: Long): Boolean {
        if (killed) {
            if (uptimeMillis >= HEALTHY_UPTIME_MS && kills < MAX_KILLS) kills = 0
            kills++
        }
        return kills < MAX_KILLS
    }

    companion object {
        const val MAX_KILLS = 3
        const val HEALTHY_UPTIME_MS = 30 * 60 * 1000L
    }
}
