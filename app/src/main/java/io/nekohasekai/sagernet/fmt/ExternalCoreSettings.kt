package io.nekohasekai.sagernet.fmt

// 外核配置生成器（Xray、mihomo、Trojan-Go、Mieru、Naive）与 ExternalCore.launch 读到的全部设置。
// 生成器只从这里取设置、不碰 DataStore，测试可传固定值；生产实现见 ProtocolHandlers.kt 的
// ExternalCoreSettings.fromDataStore()。只放确实被读到的设置，不预留字段
data class ExternalCoreSettings(
    // DataStore.logLevel 原值，档位同 ConfigBuilder：0 panic、1 warn、2 info、3 debug、4 trace
    val logLevel: Int,
    // IPv6Mode 常量
    val ipv6Mode: Int,
    // 全局「允许不安全」，叠加在节点自己的开关之上（effectiveAllowInsecure）
    val globalAllowInsecure: Boolean,
) {
    companion object
}
