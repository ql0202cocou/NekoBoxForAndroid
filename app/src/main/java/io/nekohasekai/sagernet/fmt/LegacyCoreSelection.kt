package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_MIHOMO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_ANYTLS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_HYSTERIA
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_MIERU
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NAIVE
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NEKO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN_GO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean

// K1 之前「一个节点实际由哪个核心承载」的规则：1.8.0-a3（b17f0c96）时的冻结副本。
//
// 照当时的 ProxyEntity.resolvedCore、coreForType、needExternal、certificatePinUnsupported、
// mldsa65VerifyUnsupported 与 buildXrayOutbound 开头的两条报错逐条复制，连同它们依赖的 xrayLacksTransport、
// xrayLacksAllowInsecure、canUseSingBox、tlsFields 里的证书指纹取法，全部写成本文件的私有副本，不引用会被 K1 删改的
// 函数。冻结的是当时的实际行为，包括已知的误判：xrayLacksAllowInsecure 只看 security == "tls"，REALITY 节点开了
// allowInsecure（节点或全局）也会被当成 Xray 不支持。
//
// 用途只有两个：存量数据的一次性升级标注（LegacyProfileUpgrade.kt）与 K1 选核变更的差分测试。
// 不随能力表修改；能力表、生成器或选核以后怎么变，这里都不动。
object LegacyCoreSelection {

    // 节点在 1.8.0-a3 上由谁承载
    enum class Carrier {
        SING_BOX,
        XRAY,
        MIHOMO,

        // 插件 app 提供的核心（Trojan-Go、Mieru、Naive、hysteria 1 的 faketcp / wechat-video），以及 Neko 插件节点
        PLUGIN,
    }

    // 1.8.0-a3 在构建（运行、测速、导出、成员检查）时会报的四种选核相关错误，按当时的检查顺序排列
    enum class Rejection {
        // requireBuildableHop：this core cannot pin certificates; ...
        CERTIFICATE_PIN,

        // requireBuildableHop：REALITY mldsa65Verify only works on the Xray core ...
        MLDSA65_VERIFY,

        // buildXrayOutbound：xray-core no longer supports the <type> transport ...
        XRAY_TRANSPORT,

        // buildXrayOutbound：xray-core no longer supports allowInsecure ...
        XRAY_ALLOW_INSECURE,
    }

    // 当时一次构建对这个节点的结果：由某个核心承载，或报错。只覆盖上面这些选核相关的检查，
    // 核心自己加载配置时的报错（Xray run -test、sing-box 加载期校验）不在内
    sealed class Outcome {
        data class Carried(val carrier: Carrier) : Outcome()
        data class Rejected(val rejection: Rejection) : Outcome()
    }

    // 原 ProxyEntity.resolvedCore：手动值原样返回（不检查它对协议有没有意义），自动时按协议选
    fun resolvedCore(type: Int, core: Int, bean: AbstractBean, globalAllowInsecure: Boolean): Int {
        if (core != CORE_AUTO) return core
        return coreForType(type, bean, globalAllowInsecure)
    }

    // 原 ProxyEntity.coreForType：只有 VMess / VLESS 与 AnyTLS 选核
    fun coreForType(type: Int, bean: AbstractBean, globalAllowInsecure: Boolean): Int {
        return when (type) {
            TYPE_VMESS -> (bean as VMessBean).let {
                val preferXray = it.isVLESS || it.certificateFingerprint.isNotBlank()
                if (preferXray && !xrayLacksTransport(it) && !xrayLacksAllowInsecure(it, globalAllowInsecure)) CORE_XRAY
                else CORE_SING_BOX
            }

            TYPE_ANYTLS -> CORE_MIHOMO
            else -> CORE_SING_BOX
        }
    }

    // 原 ProxyEntity.needExternal：VMess 只在算出 Xray、AnyTLS 只在算出 mihomo 时用外核，Trojan 一律不用
    fun needExternal(type: Int, core: Int, bean: AbstractBean, globalAllowInsecure: Boolean): Boolean {
        return when (type) {
            TYPE_TROJAN_GO -> true
            TYPE_MIERU -> true
            TYPE_NAIVE -> true
            TYPE_VMESS -> resolvedCore(type, core, bean, globalAllowInsecure) == CORE_XRAY
            TYPE_HYSTERIA -> !canUseSingBox(bean as HysteriaBean)
            TYPE_ANYTLS -> resolvedCore(type, core, bean, globalAllowInsecure) == CORE_MIHOMO
            TYPE_NEKO -> true
            else -> false
        }
    }

    // needExternal 与当时 externalCore(bean) 的组合：外核时 VMessBean 是 Xray、AnyTLSBean 是 mihomo，其余是插件
    fun carrier(type: Int, core: Int, bean: AbstractBean, globalAllowInsecure: Boolean): Carrier {
        if (!needExternal(type, core, bean, globalAllowInsecure)) return Carrier.SING_BOX
        return when (type) {
            TYPE_VMESS -> Carrier.XRAY
            TYPE_ANYTLS -> Carrier.MIHOMO
            else -> Carrier.PLUGIN
        }
    }

    // 原 ProxyEntity.certificatePinUnsupported
    fun certificatePinUnsupported(type: Int, core: Int, bean: AbstractBean, globalAllowInsecure: Boolean): Boolean {
        if (certificatePin(bean).isNullOrBlank()) return false
        return when (type) {
            TYPE_VMESS -> resolvedCore(type, core, bean, globalAllowInsecure) != CORE_XRAY
            TYPE_ANYTLS -> resolvedCore(type, core, bean, globalAllowInsecure) != CORE_MIHOMO
            else -> true
        }
    }

    // 原 mldsa65VerifyUnsupported 的判定部分（报错文本不冻结）：REALITY 真正生效且配了 mldsa65Verify，
    // VMess / VLESS 最终核心不是 Xray，或者是别的协议
    fun mldsa65VerifyUnsupported(type: Int, core: Int, bean: AbstractBean, globalAllowInsecure: Boolean): Boolean {
        if (bean !is StandardV2RayBean) return false
        if (!(bean.security == "tls" && !bean.realityPubKey.isNullOrBlank() && !bean.realityMldsa65Verify.isNullOrBlank())) {
            return false
        }
        return when (type) {
            TYPE_VMESS -> resolvedCore(type, core, bean, globalAllowInsecure) != CORE_XRAY
            else -> true
        }
    }

    // 当时一次构建的结果。顺序照 requireBuildableHop（证书固定、mldsa65Verify）再到 buildXrayOutbound 开头的两条
    fun outcome(type: Int, core: Int, bean: AbstractBean, globalAllowInsecure: Boolean): Outcome {
        if (certificatePinUnsupported(type, core, bean, globalAllowInsecure)) {
            return Outcome.Rejected(Rejection.CERTIFICATE_PIN)
        }
        if (mldsa65VerifyUnsupported(type, core, bean, globalAllowInsecure)) {
            return Outcome.Rejected(Rejection.MLDSA65_VERIFY)
        }
        val carrier = carrier(type, core, bean, globalAllowInsecure)
        if (carrier == Carrier.XRAY) {
            val vmess = bean as VMessBean
            if (xrayLacksTransport(vmess)) return Outcome.Rejected(Rejection.XRAY_TRANSPORT)
            if (xrayLacksAllowInsecure(vmess, globalAllowInsecure)) {
                return Outcome.Rejected(Rejection.XRAY_ALLOW_INSECURE)
            }
        }
        return Outcome.Carried(carrier)
    }

    // 原 VMessBean.xrayLacksTransport（XrayConfig.kt）：quic，或 http 带 TLS（h2）
    private fun xrayLacksTransport(bean: VMessBean): Boolean =
        bean.type == "quic" || (bean.type == "http" && bean.security == "tls")

    // 原 VMessBean.xrayLacksAllowInsecure（XrayConfig.kt）：只看 security == "tls"，REALITY 节点同样算
    private fun xrayLacksAllowInsecure(bean: VMessBean, globalAllowInsecure: Boolean): Boolean =
        bean.security == "tls" && bean.certificateFingerprint.isBlank() &&
            (bean.allowInsecure == true || globalAllowInsecure)

    // 原 HysteriaBean.canUseSingBox（HysteriaFmt.kt）
    private fun canUseSingBox(bean: HysteriaBean): Boolean = bean.protocol == HysteriaBean.PROTOCOL_UDP

    // 原 tlsFields(bean)?.certificateFingerprint（ProtocolHandlers.kt）：StandardV2RayBean 只在开了 TLS 时算
    private fun certificatePin(bean: AbstractBean): String? = when (bean) {
        is StandardV2RayBean -> if (bean.security == "tls") bean.certificateFingerprint else null
        is HysteriaBean -> bean.certificateFingerprint
        is TuicBean -> bean.certificateFingerprint
        is AnyTLSBean -> bean.certificateFingerprint
        else -> null
    }
}
