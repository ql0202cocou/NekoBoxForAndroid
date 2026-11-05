package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean

// v4 已有独立证书策略，却还没有覆写目标标记；手动 mihomo 的 JSON 当时作用于本机 SOCKS 跳。
// 必须在改 core 前保留此语义。v3 及更早的数据仍交给原迁移判断，尤其不能把兼容测试的内部出站误标成 SOCKS。
fun preserveRemovedMihomoAnyTLSOverride(bean: AnyTLSBean, core: Int): Boolean {
    if (core != ProxyEntity.CORE_MIHOMO || bean.certificatePolicy == AnyTLSBean.CERTIFICATE_POLICY_LEGACY ||
        bean.customOutboundJson.isNullOrBlank() || bean.localSocksOutboundJson
    ) return false
    bean.localSocksOutboundJson = true
    return true
}
