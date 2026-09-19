package io.nekohasekai.sagernet.bg

// Per-process handles on the proxy service, kept out of DataStore (the
// settings store). state caches the last known service state in whichever
// process reads it (:bg writes it from changeState, the main process from the
// binder callback); the two instance references only ever exist in :bg.
object ServiceRegistry {

    @Volatile
    var state = BaseService.State.Idle

    @Volatile
    var vpnService: VpnService? = null

    @Volatile
    var baseService: BaseService.Interface? = null
}
