package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean

// 冻结 1.9.0-a9 普通 AnyTLS 的选核规则，仅供旧数据库、备份和通用链接迁移。
// 必须在 core 改为自动前调用；不能使用会随新版本变化的能力表反推旧证书含义。返回 bean 是否被改写。
fun preserveLegacyAnyTLSCertificatePolicy(bean: AnyTLSBean, core: Int): Boolean {
    if (bean.certificatePolicy != AnyTLSBean.CERTIFICATE_POLICY_LEGACY) return false
    val trust = when {
        bean.compatibilityTest == true -> false
        core == ProxyEntity.CORE_SING_BOX -> true
        core != ProxyEntity.CORE_AUTO -> false
        !bean.certificateFingerprint.isNullOrBlank() -> false
        !bean.certificates.isNullOrBlank() &&
            (bean.utlsFingerprint.isNullOrBlank() || bean.utlsFingerprint in LEGACY_MIHOMO_FINGERPRINTS) -> false
        else -> bean.utlsFingerprint.isNullOrBlank() || bean.utlsFingerprint in LEGACY_SING_BOX_FINGERPRINTS
    }
    bean.localSocksOutboundJson = bean.compatibilityTest != true && !bean.customOutboundJson.isNullOrBlank() &&
        (core == ProxyEntity.CORE_MIHOMO || core == ProxyEntity.CORE_AUTO && !trust)
    bean.certificatePolicySource = AnyTLSBean.POLICY_MIGRATED
    bean.certificatePolicy = if (trust) AnyTLSBean.CERTIFICATE_POLICY_TRUST else AnyTLSBean.CERTIFICATE_POLICY_PIN
    return true
}

private val LEGACY_MIHOMO_FINGERPRINTS = setOf(
    "none", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random",
    "chrome120", "firefox120", "safari16", "chrome_psk_shuffle", "chrome_pq",
)
private val LEGACY_SING_BOX_FINGERPRINTS = setOf(
    "chrome", "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle", "chrome_pq", "chrome_pq_psk",
    "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized",
)
