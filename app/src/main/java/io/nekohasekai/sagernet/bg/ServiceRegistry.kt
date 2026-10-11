package io.nekohasekai.sagernet.bg

// Per-process handles on the proxy service, kept out of DataStore (the
// settings store). state caches the last known service state in whichever
// process reads it (:bg writes it from changeState, the main process from the
// binder callback); the two instance references only ever exist in :bg.
//
// 属主规则（:bg 里的三份）：两个服务实例可以短暂并存——VpnService 在 Stopping 拆卸时，用户已切到别的模式，
// 新起的 ProxyService 看到的是自己的 Stopped 状态，会正常启动（Data 每个实例一份）。旧实例随后的清理
// 若无条件地把注册表置空、把状态写成 Stopped，就会把新实例刚登记的服务和 Connected 状态覆盖掉
// （后果：NativeInterface 的选择器回调整段跳过、webui / Clash API 的选择被静默丢弃、
// 仅代理模式下 strictSocks5 的判断失效）。所以 baseService / vpnService 只能由属主登记与清理，
// 清理只在引用仍是属主自己时生效；state 在别的实例登记着的时候不接受属主以外的写入。
// 主进程里的 state 由 MainActivity / SagerConnection 按 binder 回调直接写，不经过属主入口。
object ServiceRegistry {

    @Volatile
    var state = BaseService.State.Idle

    @Volatile
    var vpnService: VpnService? = null
        private set

    @Volatile
    var baseService: BaseService.Interface? = null
        private set

    // onStartCommand 的接管：新的启动请求永远以请求到达的那个实例为准
    @Synchronized
    fun claimBase(owner: BaseService.Interface) {
        baseService = owner
    }

    // 实例开始停止或销毁时登记退出；已被别的实例接管的，不动
    @Synchronized
    fun releaseBase(owner: BaseService.Interface) {
        baseService = releasedBy(baseService, owner)
    }

    @Synchronized
    fun claimVpn(owner: VpnService) {
        vpnService = owner
    }

    @Synchronized
    fun releaseVpn(owner: Any) {
        vpnService = releasedBy(vpnService, owner)
    }

    // 实例的状态变化写进注册表。停止时实例先 releaseBase（baseService 为空）再报 Stopping / Stopped，
    // 那时仍要写；已被别的实例接管的，旧实例的状态不能覆盖新实例的
    @Synchronized
    fun setState(owner: BaseService.Interface, s: BaseService.State) {
        if (mayWriteState(baseService, owner)) state = s
    }

    // destroyRunner 后台关闭完成后的收尾：没有任何实例接管、状态仍停在 Stopping 才置 Stopped。
    // binder 已关，只更新进程内状态；关闭期间新服务若已起来（onStartCommand 先 claimBase），不覆盖它的状态
    @Synchronized
    fun settleStoppedIfUnclaimed() {
        if (baseService == null && state == BaseService.State.Stopping) {
            state = BaseService.State.Stopped
        }
    }
}

// 当前登记的引用是属主自己才清空，否则原样保留（引用已换成别的实例，或本来就是空）
internal fun <T : Any> releasedBy(current: T?, owner: Any): T? = if (current === owner) null else current

// 状态可写：没有实例登记着（停止中的实例已退出登记），或登记着的就是属主自己
internal fun mayWriteState(registered: Any?, owner: Any): Boolean = registered == null || registered === owner
