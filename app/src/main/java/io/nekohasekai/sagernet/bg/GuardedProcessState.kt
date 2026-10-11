package io.nekohasekai.sagernet.bg

/**
 * 守护下一个外核进程的状态，只读视图。启动就绪等待（awaitLocalPorts）据它区分「进程退出且守护已放弃」与
 * 「退出过但守护已重启 / 正在重启」：后者的监听很快会回来，不能当作启动失败。
 */
interface ProcessState {
    /** 进程最近一次退出的退出码（含随后被守护重启的），从未退出为 null。只有 [running] 为 false 时才说明当前进程已退出 */
    val exitCode: Int?

    /** 当前这个子进程是否在运行；退出后到守护重启之间的间隙为 false */
    val running: Boolean

    /** 守护已放弃：looper 因进程退得太快、重启失败而结束，或池已关闭。不会再重启 */
    val abandoned: Boolean

    /** 已退出的次数（代次）。探测前后不同，说明探测跨过了一次进程退出 */
    val exits: Int
}

/**
 * [ProcessState] 的可写实现，由 GuardedProcessPool 的 Guard 持有并驱动：进程每次启动、退出各调用一次，
 * looper 结束时放弃。状态变迁是单写者（新进程只在旧进程退出后才启动），读者在任意线程。
 * 可见性顺序：先写退出码与代次，最后写 running，读到 running 为 false 就一定能读到对应的退出码。
 */
class GuardedProcessState : ProcessState {
    @Volatile
    override var exitCode: Int? = null
        private set

    // 状态对象在进程刚启动时创建
    @Volatile
    override var running: Boolean = true
        private set

    @Volatile
    override var abandoned: Boolean = false
        private set

    @Volatile
    override var exits: Int = 0
        private set

    fun onExited(code: Int) {
        exitCode = code
        exits += 1
        running = false
    }

    /** 守护重启了进程。退出码与代次保留，是否仍可见由 [running] 决定 */
    fun onStarted() {
        running = true
    }

    fun onAbandoned() {
        abandoned = true
    }
}
