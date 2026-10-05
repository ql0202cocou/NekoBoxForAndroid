package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.CoreTestNodes.Node
import io.nekohasekai.sagernet.fmt.CoreTestNodes.reality
import io.nekohasekai.sagernet.fmt.LegacyCoreSelection.Carrier
import io.nekohasekai.sagernet.fmt.LegacyCoreSelection.Outcome
import io.nekohasekai.sagernet.fmt.LegacyCoreSelection.Rejection
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildXrayOutbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

// 冻结副本（LegacyCoreSelection）与 1.8.0-a3 现行实现逐项相同：resolvedCore、coreForType、needExternal、
// certificatePinUnsupported、mldsa65VerifyUnsupported，以及 buildXrayOutbound 开头的两条报错。
//
// 注意：这条对照只在原函数还在时成立。K1 接线时删掉或改写 ProxyEntity.resolvedCore / coreForType / needExternal /
// certificatePinUnsupported / mldsa65VerifyUnsupported、或放宽 buildXrayOutbound 的那个代理，要把本测试改成钉住真值表
// （把下面穷举的结果固定成预期），不能随原函数一起删掉
class LegacyCoreSelectionTest {

    private val settings = { global: Boolean -> ExternalCoreSettings(logLevel = 0, ipv6Mode = 0, globalAllowInsecure = global) }

    // 现行实现给出的结果，按冻结副本的 Outcome 表示
    private fun currentOutcome(node: Node): Outcome {
        val entity = node.entity()
        if (entity.certificatePinUnsupported(node.global)) return Outcome.Rejected(Rejection.CERTIFICATE_PIN)
        if (entity.mldsa65VerifyUnsupported(node.global) != null) return Outcome.Rejected(Rejection.MLDSA65_VERIFY)
        if (!entity.needExternal(node.global)) return Outcome.Carried(Carrier.SING_BOX)
        return when (entity.type) {
            ProxyEntity.TYPE_VMESS -> {
                val error = runCatching {
                    buildXrayOutbound(node.bean as VMessBean, "127.0.0.1", 20000, settings(node.global))
                }.exceptionOrNull()
                when {
                    error == null -> Outcome.Carried(Carrier.XRAY)
                    error.message!!.startsWith("xray-core no longer supports the ") ->
                        Outcome.Rejected(Rejection.XRAY_TRANSPORT)

                    error.message!!.startsWith("xray-core no longer supports allowInsecure") ->
                        Outcome.Rejected(Rejection.XRAY_ALLOW_INSECURE)

                    else -> throw AssertionError("buildXrayOutbound 报了选核以外的错：${error.message}", error)
                }
            }

            ProxyEntity.TYPE_ANYTLS -> Outcome.Carried(Carrier.MIHOMO)
            else -> Outcome.Carried(Carrier.PLUGIN)
        }
    }

    private fun assertSame(node: Node) {
        val entity = node.entity()
        val label = "type=${node.type} core=${node.core} global=${node.global} bean=${node.bean.javaClass.simpleName}"
        val type = entity.type
        assertEquals(label, entity.resolvedCore(node.global), LegacyCoreSelection.resolvedCore(type, node.core, node.bean, node.global))
        assertEquals(label, entity.coreForType(node.global), LegacyCoreSelection.coreForType(type, node.bean, node.global))
        assertEquals(label, entity.needExternal(node.global), LegacyCoreSelection.needExternal(type, node.core, node.bean, node.global))
        assertEquals(
            label, entity.certificatePinUnsupported(node.global),
            LegacyCoreSelection.certificatePinUnsupported(type, node.core, node.bean, node.global),
        )
        assertEquals(
            label, entity.mldsa65VerifyUnsupported(node.global) != null,
            LegacyCoreSelection.mldsa65VerifyUnsupported(type, node.core, node.bean, node.global),
        )
        assertEquals(label, currentOutcome(node), LegacyCoreSelection.outcome(type, node.core, node.bean, node.global))
    }

    // VMess / VLESS 的决定因素逐个穷举：协议、证书指纹、传输、安全层（含 REALITY）、节点与全局 allowInsecure、全部 core 取值
    @Test
    fun `VMess 与 VLESS 的决定因素穷举`() {
        var count = 0
        for (vless in listOf(false, true)) for (pin in listOf(false, true)) for (transport in CoreTestNodes.TRANSPORT_SAMPLES)
            for (security in listOf("none", "tls", "reality")) for (insecure in listOf(false, true))
                for (mldsa in listOf(false, true)) for (global in listOf(false, true)) for (core in CoreTestNodes.CORE_SAMPLES) {
                    val build: VMessBean.() -> Unit = {
                        type = transport
                        when (security) {
                            "none" -> this.security = "none"
                            "tls" -> this.security = "tls"
                            else -> reality(mldsa)
                        }
                        if (pin) certificateFingerprint = CoreTestNodes.PIN
                        allowInsecure = insecure
                    }
                    val bean = if (vless) CoreTestNodes.vless(build) else CoreTestNodes.vmess(build)
                    assertSame(CoreTestNodes.node(bean.clone(), core, global))
                    count++
                }
        assertEquals(2 * 2 * 6 * 3 * 2 * 2 * 2 * CoreTestNodes.CORE_SAMPLES.size, count)
    }

    @Test
    fun `随机节点逐项相同`() {
        val random = Random(20261006)
        val seen = HashMap<Outcome, Int>()
        val nodes = List(20_000) { CoreTestNodes.randomSelectable(random) } + List(5_000) { CoreTestNodes.randomOther(random) }
        for (node in nodes) {
            assertSame(node)
            seen.merge(LegacyCoreSelection.outcome(node.type, node.core, node.bean, node.global), 1, Int::plus)
        }
        // 每一种结果都要被随机样本覆盖到，对照才有意义
        val expected = Carrier.entries.map { Outcome.Carried(it) } + Rejection.entries.map { Outcome.Rejected(it) }
        assertEquals(expected.toSet(), seen.keys)
    }

    @Test
    fun `Neko 节点一律走插件`() {
        // NekoBean 不能在 JVM 上构造运行，Neko 分支也不读 bean
        val entity = ProxyEntity(type = ProxyEntity.TYPE_NEKO)
        val dummy = SOCKSBean().apply { initializeDefaultValues() }
        assertEquals(entity.needExternal(false), LegacyCoreSelection.needExternal(ProxyEntity.TYPE_NEKO, 0, dummy, false))
        assertEquals(Carrier.PLUGIN, LegacyCoreSelection.carrier(ProxyEntity.TYPE_NEKO, 0, dummy, false))
    }

    // 冻结的误判：REALITY 节点开了 allowInsecure 也被当成 Xray 不支持
    @Test
    fun `REALITY 节点的 allowInsecure 误判照旧`() {
        val bean = CoreTestNodes.vless { reality() }
        val type = ProxyEntity.TYPE_VMESS
        assertEquals(Carrier.XRAY, LegacyCoreSelection.carrier(type, 0, bean, false))
        assertEquals(Carrier.SING_BOX, LegacyCoreSelection.carrier(type, 0, bean, true))
        assertEquals(
            Outcome.Rejected(Rejection.XRAY_ALLOW_INSECURE),
            LegacyCoreSelection.outcome(type, ProxyEntity.CORE_XRAY, bean, true),
        )
        assertTrue(LegacyCoreSelection.outcome(type, 0, bean, true) == Outcome.Carried(Carrier.SING_BOX))
    }
}
