package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.CoreTestNodes.Node
import io.nekohasekai.sagernet.fmt.CoreTestNodes.reality
import io.nekohasekai.sagernet.fmt.LegacyCoreSelection.Carrier
import io.nekohasekai.sagernet.fmt.LegacyCoreSelection.Outcome
import io.nekohasekai.sagernet.fmt.LegacyCoreSelection.Rejection
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Random

// 冻结副本（LegacyCoreSelection）的真值表。
//
// K1 接线之前，本测试逐项对照冻结副本与当时的现行实现（ProxyEntity.resolvedCore、coreForType、needExternal、
// certificatePinUnsupported、mldsa65VerifyUnsupported，以及 buildXrayOutbound 开头的两条报错），全部相同。
// 接线删掉了这些函数，对照改为钉住当时对照通过的结果：下面两组样本（穷举、固定种子的随机样本）逐个节点算出冻结
// 副本的全部判定，按行拼起来取 SHA-256，并记下结果分布。摘要与分布是在接线之前的代码上（接线提交的父提交，原函数
// 还在、原对照测试通过）算出的，以后冻结副本被误改，摘要就对不上。
//
// 摘要也取决于样本的生成（CoreTestNodes 的穷举取值与 randomSelectable / randomOther）：改动那里时，先在改动前的
// 代码上确认本测试通过，再按新样本重算摘要，不能拿冻结副本改过之后的结果回填
class LegacyCoreSelectionTest {

    // 一个节点在冻结副本下的全部判定，一行
    private fun row(node: Node): String {
        val type = node.entity().type
        val core = node.core
        val bean = node.bean
        val global = node.global
        return listOf(
            type, core, global, bean.javaClass.simpleName,
            LegacyCoreSelection.resolvedCore(type, core, bean, global),
            LegacyCoreSelection.coreForType(type, bean, global),
            LegacyCoreSelection.needExternal(type, core, bean, global),
            LegacyCoreSelection.certificatePinUnsupported(type, core, bean, global),
            LegacyCoreSelection.mldsa65VerifyUnsupported(type, core, bean, global),
            LegacyCoreSelection.carrier(type, core, bean, global),
            LegacyCoreSelection.outcome(type, core, bean, global),
        ).joinToString("|")
    }

    private fun sha256(rows: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(rows.joinToString("\n").toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun distribution(nodes: List<Node>): Map<String, Int> = nodes
        .groupingBy { LegacyCoreSelection.outcome(it.type, it.core, it.bean, it.global).toString() }
        .eachCount().toSortedMap()

    // VMess / VLESS 的决定因素逐个穷举：协议、证书指纹、传输、安全层（含 REALITY）、节点与全局 allowInsecure、全部 core 取值
    private fun exhaustiveNodes(): List<Node> {
        val nodes = ArrayList<Node>()
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
                    nodes += CoreTestNodes.node(bean.clone(), core, global)
                }
        return nodes
    }

    private fun randomNodes(): List<Node> {
        val random = Random(20261006)
        return List(20_000) { CoreTestNodes.randomSelectable(random) } + List(5_000) { CoreTestNodes.randomOther(random) }
    }

    @Test
    fun `VMess 与 VLESS 的决定因素穷举`() {
        val nodes = exhaustiveNodes()
        assertEquals(2 * 2 * 6 * 3 * 2 * 2 * 2 * CoreTestNodes.CORE_SAMPLES.size, nodes.size)
        assertEquals(EXHAUSTIVE_DISTRIBUTION, distribution(nodes))
        assertEquals(EXHAUSTIVE_SHA256, sha256(nodes.map(::row)))
    }

    @Test
    fun `随机节点的判定不变`() {
        val nodes = randomNodes()
        val seen = distribution(nodes)
        assertEquals(RANDOM_DISTRIBUTION, seen)
        assertEquals(RANDOM_SHA256, sha256(nodes.map(::row)))
        // 每一种结果都要被随机样本覆盖到，钉住的摘要才有意义
        val expected = Carrier.entries.map { Outcome.Carried(it) } + Rejection.entries.map { Outcome.Rejected(it) }
        assertEquals(expected.map { it.toString() }.toSortedSet(), seen.keys)
    }

    @Test
    fun `Neko 节点一律走插件`() {
        // NekoBean 不能在 JVM 上构造运行，Neko 分支也不读 bean
        val dummy = SOCKSBean().apply { initializeDefaultValues() }
        assertTrue(LegacyCoreSelection.needExternal(ProxyEntity.TYPE_NEKO, 0, dummy, false))
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

    companion object {
        // 以下取值在接线之前的代码上算出（见文件头）
        const val EXHAUSTIVE_SHA256 = "82dca969c480bfe2290ead7eec80cd1724d5a6c2395ec5c3adfb163a5aff3d1d"
        val EXHAUSTIVE_DISTRIBUTION: Map<String, Int> = sortedMapOf(
            "Carried(carrier=SING_BOX)" to 1548,
            "Carried(carrier=XRAY)" to 584,
            "Rejected(rejection=CERTIFICATE_PIN)" to 832,
            "Rejected(rejection=MLDSA65_VERIFY)" to 236,
            "Rejected(rejection=XRAY_ALLOW_INSECURE)" to 96,
            "Rejected(rejection=XRAY_TRANSPORT)" to 160,
        )
        const val RANDOM_SHA256 = "d869155e98c51eea4a5917e0ac631eefd465122e44bc458331d871a64f2a6655"
        val RANDOM_DISTRIBUTION: Map<String, Int> = sortedMapOf(
            "Carried(carrier=MIHOMO)" to 1649,
            "Carried(carrier=PLUGIN)" to 1672,
            "Carried(carrier=SING_BOX)" to 13692,
            "Carried(carrier=XRAY)" to 1444,
            "Rejected(rejection=CERTIFICATE_PIN)" to 4671,
            "Rejected(rejection=MLDSA65_VERIFY)" to 1066,
            "Rejected(rejection=XRAY_ALLOW_INSECURE)" to 304,
            "Rejected(rejection=XRAY_TRANSPORT)" to 502,
        )
    }
}
