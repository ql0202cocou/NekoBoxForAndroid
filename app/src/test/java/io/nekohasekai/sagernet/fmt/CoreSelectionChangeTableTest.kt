package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.fmt.CoreTestNodes.Node
import io.nekohasekai.sagernet.fmt.LegacyCoreSelection.Carrier
import io.nekohasekai.sagernet.fmt.LegacyCoreSelection.Outcome
import io.nekohasekai.sagernet.fmt.LegacyCoreSelection.Rejection
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.effectiveUtlsFingerprint
import io.nekohasekai.sagernet.fmt.v2ray.resolveWsEarlyData
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

// K1 选核变更对照表（plan.md K1 的交付物，发布说明照它写）与差分测试。
//
// 「之前」：冻结的 1.8.0-a3 规则（LegacyCoreSelection.outcome）的结果，含旧代码报错的情形；核心自己加载配置时才报的错
// （sing-box 加载期校验、Xray run -test）冻结规则看不到，记作由该核心承载，在「之前」一栏的文字里写明。
// 「之后」：先做存量升级标注（upgradeLegacyProfile），再用能力表判定（decideCore）。
//
// 差分测试用固定种子随机生成大量存量节点，比较两者。每个样本按顺序找第一条命中的行（只看节点本身的字段与冻结规则
// 的承载）：命中某行的样本，之前、之后的结果都必须落在该行写明的取值里；结果不同的样本必须命中某一行；每行至少有
// 一个结果不同的样本命中。统计与 Markdown 写到 build/reports/core-selection/
class CoreSelectionChangeTableTest {

    enum class Result { SING_BOX, XRAY, MIHOMO, REJECTED }

    // 一个样本：原始存量节点、冻结规则的结果、标注与判定之后的结果
    class Sample(val node: Node, val legacy: Outcome, val upgraded: LegacyProfileUpgrade, val decision: CoreDecision) {
        val bean get() = node.bean
        val before: Result = when (legacy) {
            is Outcome.Rejected -> Result.REJECTED
            is Outcome.Carried -> when (legacy.carrier) {
                Carrier.SING_BOX -> Result.SING_BOX
                Carrier.XRAY -> Result.XRAY
                Carrier.MIHOMO -> Result.MIHOMO
                Carrier.PLUGIN -> error("能选核的协议不会走插件")
            }
        }
        val after: Result = when (decision) {
            is CoreDecision.Selected -> when (decision.core) {
                DialCore.SING_BOX -> Result.SING_BOX
                DialCore.XRAY -> Result.XRAY
                DialCore.MIHOMO -> Result.MIHOMO
            }

            is CoreDecision.Rejected -> Result.REJECTED
            CoreDecision.Fixed -> error("能选核的协议不会是 Fixed")
        }
        val changed get() = before != after
        val carriedBy: Carrier? get() = (legacy as? Outcome.Carried)?.carrier
    }

    class Row(
        val name: String,
        val condition: String,
        val beforeText: String,
        val afterText: String,
        val effects: String,
        val before: Set<Result>,
        val after: Set<Result>,
        val matches: (Sample) -> Boolean,
    )

    // ---- 节点特征（只看存量节点的字段与冻结规则的承载）

    private val Sample.standard get() = bean as? StandardV2RayBean
    private val Sample.isVless get() = (bean as? VMessBean)?.isVLESS == true
    private val Sample.isVmess get() = bean is VMessBean && !isVless
    private val Sample.isTrojan get() = bean is TrojanBean
    private val Sample.isAnyTls get() = bean is AnyTLSBean
    private val Sample.auto get() = node.core == CORE_AUTO
    private val Sample.tlsOn get() = standard?.security == "tls"
    private val Sample.reality get() = tlsOn && !standard!!.realityPubKey.isNullOrBlank()
    private val Sample.pin get() = tlsOn && !standard!!.certificateFingerprint.isNullOrBlank()
    private val Sample.mldsa get() = reality && !standard!!.realityMldsa65Verify.isNullOrBlank()
    private val Sample.transport get() = standard?.type
    private val Sample.insecure get() = standard?.allowInsecure == true || node.global
    private val Sample.utls: String?
        get() = when (val b = bean) {
            is StandardV2RayBean -> if (tlsOn) b.effectiveUtlsFingerprint()?.takeIf { it.isNotBlank() } else null
            is AnyTLSBean -> b.utlsFingerprint?.takeIf { it.isNotBlank() }
            else -> null
        }
    private val Sample.flow: String?
        get() = if (isVless) standard!!.encryption?.takeIf { it.isNotBlank() && it != "auto" } else null

    // sing-box 加载配置时报错，或每次拨号都失败的取值
    private val Sample.singBoxCannotRun: Boolean
        get() {
            utls?.let { if (!SING_BOX_UTLS_FINGERPRINTS.accepts(it)) return true }
            if (isAnyTls) return false
            flow?.let { if (!SING_BOX_VLESS_FLOWS.accepts(it)) return true }
            if (reality && standard!!.enableECH == true) return true
            if (transport == "quic" && (!tlsOn || reality || utls != null)) return true
            return false
        }

    // Xray 启动前的配置校验（run -test）会拒绝的取值
    private val Sample.xrayCannotRun: Boolean
        get() {
            if (reality && transport in setOf("ws", "httpupgrade")) return true
            utls?.let {
                if (!XRAY_UTLS_FINGERPRINTS.accepts(it)) return true
                if (reality && XRAY_REALITY_FORBIDDEN_FINGERPRINTS.accepts(it)) return true
            }
            flow?.let { if (!XRAY_VLESS_FLOWS.accepts(it)) return true }
            return false
        }

    // mihomo 不认识、会静默改用 Go 标准 TLS 的指纹
    private val Sample.mihomoDegrades get() = isAnyTls && utls?.let { !MIHOMO_UTLS_FINGERPRINTS.accepts(it) } == true

    // 1.8.0-a3 的 Xray 生成器静默丢掉的字段：packetaddr、没填配置的 ECH、非 Sec-WebSocket-Protocol 的 early data 头名
    private val Sample.xrayDropsField: Boolean
        get() {
            val b = standard ?: return false
            if (!isTrojan && b.packetEncoding == 1) return true
            if (tlsOn && !reality && b.enableECH == true && b.echConfig.isNullOrBlank()) return true
            if (transport == "ws") {
                val ed = b.resolveWsEarlyData()
                if (ed.maxEarlyData != null && ed.headerName != null && !XRAY_WS_EARLY_DATA_HEADERS.accepts(ed.headerName)) {
                    return true
                }
            }
            return false
        }

    private val Sample.anyTlsPlain get() = isAnyTls && (bean as AnyTLSBean).let { it.certificateFingerprint.isNullOrBlank() && it.certificates.isNullOrBlank() }

    // ---- 对照表（按顺序匹配，第一条命中的行生效）

    private val rows = listOf(
        Row(
            "sing-box 本来就跑不起来的取值",
            "冻结规则走 sing-box，且节点带 sing-box 加载期报错或每次拨号都失败的取值：sing-box 不认的 uTLS 指纹" +
                "（例如 randomizednoalpn、hellochrome_131、大写的 Chrome、mihomo 专有的 chrome120 / none）、sing-box 不认的" +
                " VLESS flow（xtls-rprx-vision-udp443 等）、REALITY + ECH、quic 不带 TLS、quic + uTLS 指纹或 REALITY",
            "sing-box（冻结规则的结果；sing-box 加载配置时报错或拨号失败，实际跑不起来）",
            "自动选核且 Xray 能完整承载时改走 Xray；否则（手动 sing-box，或 Xray 也承载不了）启动前拒绝，报出冲突字段",
            "原来的启动失败或连不上，变成可用（走 Xray）或启动前的明确报错",
            setOf(Result.SING_BOX), setOf(Result.XRAY, Result.REJECTED),
        ) { it.carriedBy == Carrier.SING_BOX && it.singBoxCannotRun },
        Row(
            "Xray 本来就跑不起来的取值",
            "冻结规则走 Xray，且节点带 Xray run -test 会拒绝的取值：REALITY + ws / httpupgrade、Xray 不认的 uTLS 指纹" +
                "（chrome120、chrome_psk、none 等）、REALITY + unsafe、Xray 不认的 VLESS flow",
            "Xray（冻结规则的结果；启动前的核心校验拒绝，实际跑不起来）",
            "自动选核且 sing-box 能完整承载时改走 sing-box；否则启动前拒绝，报出冲突字段",
            "原来的启动失败变成可用（走 sing-box）或更早、更具体的报错",
            setOf(Result.XRAY), setOf(Result.SING_BOX, Result.REJECTED),
        ) { it.carriedBy == Carrier.XRAY && it.xrayCannotRun },
        Row(
            "mihomo 静默降级的 uTLS 指纹",
            "AnyTLS 冻结规则走 mihomo，uTLS 指纹 mihomo 不认识（例如 netscape、大写的 Chrome、randomizednoalpn）",
            "mihomo（只打 warning，静默改用 Go 标准 TLS）",
            "拒绝（sing-box 也不认这些名字），报出指纹字段",
            "原来悄悄丢掉的 TLS 指纹伪装，改为启动前报错",
            setOf(Result.MIHOMO), setOf(Result.REJECTED, Result.SING_BOX),
        ) { it.carriedBy == Carrier.MIHOMO && it.mihomoDegrades },
        Row(
            "Xray 静默丢掉的字段",
            "冻结规则走 Xray，且节点带 1.8.0-a3 的 Xray 生成器静默丢掉的字段：packetaddr（VMess / VLESS）、开了 ECH 但没填配置" +
                "（非 REALITY）、非 Sec-WebSocket-Protocol 的 early data 头名",
            "Xray（这些字段不生效）",
            "自动选核且 sing-box 能完整承载时改走 sing-box（字段生效）；否则（手动 Xray、带证书指纹、mux 为 Mux.Cool、" +
                "只有 Xray 认的指纹或 flow）启动前拒绝，报出冲突字段",
            "packetaddr、ECH 自动查询、自定义 early data 头名在 sing-box 上生效；不能完整承载的节点不再静默降级",
            setOf(Result.XRAY), setOf(Result.SING_BOX, Result.REJECTED),
        ) { it.carriedBy == Carrier.XRAY && it.xrayDropsField },
        Row(
            "手动 Xray 的 REALITY 节点开了 allowInsecure",
            "VMess / VLESS 手动选 Xray，REALITY，节点或全局「允许不安全」打开，没有证书指纹",
            "报错：xray-core no longer supports allowInsecure（REALITY 节点被误判）",
            "Xray（REALITY 下 allowInsecure 本来就不起作用）；Xray 另有冲突时仍拒绝",
            "修复误判，节点能运行",
            setOf(Result.REJECTED), setOf(Result.XRAY, Result.REJECTED),
        ) { it.node.core == CORE_XRAY && it.reality && !it.pin && it.insecure && it.legacy == Outcome.Rejected(Rejection.XRAY_ALLOW_INSECURE) },
        Row(
            "Trojan 带证书指纹",
            "Trojan，开了 TLS（含 REALITY），填了证书指纹",
            "报错：this core cannot pin certificates",
            "Xray（pinnedPeerCertSha256；REALITY 下指纹不适用）；Xray 承载不了时（h2 / quic、mux 为 sing-mux、ECH 自动查询、" +
                "early data 没有头名等）仍拒绝",
            "D10：Trojan 节点第一次能用证书固定；Trojan 的 mux 变成 Mux.Cool 需要显式选择（存量 sing-mux 留在 sing-box 一侧）",
            setOf(Result.REJECTED), setOf(Result.XRAY, Result.REJECTED),
        ) { it.isTrojan && it.pin },
        Row(
            "mldsa65Verify 落在 sing-box 上",
            "REALITY + mldsa65Verify，冻结规则没有走 Xray：Trojan、不带证书指纹的 VMess、全局或节点开了 allowInsecure 的 VLESS（自动选核）",
            "报错：REALITY mldsa65Verify only works on the Xray core ...",
            "Xray；Xray 承载不了时（REALITY + ws / httpupgrade / h2 / quic、mux 为 sing-mux、packetaddr 等）仍拒绝",
            "节点能运行，mldsa65Verify 真正生效",
            setOf(Result.REJECTED), setOf(Result.XRAY, Result.REJECTED),
        ) { it.mldsa && it.legacy == Outcome.Rejected(Rejection.MLDSA65_VERIFY) },
        Row(
            "VLESS 不带 REALITY",
            "VLESS 自动选核，不带 REALITY，冻结规则走 Xray（不是 h2 / quic，且没有生效的 allowInsecure 或带证书指纹）",
            "Xray",
            "sing-box；sing-box 不能完整承载时留在 Xray：带证书指纹、开了 mux（标注为 Mux.Cool）、只有 Xray 认的指纹或 flow",
            "少一个 Xray 进程；packetaddr、xudp 由 sing-box 处理；没填 uTLS 指纹的存量节点经标注补写 firefox（Xray 上没填时是 chrome，" +
                "sing-box 上没填是 Go 标准 TLS）；" +
                "certificates 从「追加到系统根证书」变成「只信任它」；UDP 不再默认走 XUDP；early data 经标注仍走 " +
                "Sec-WebSocket-Protocol 头；自定义出站 JSON 从本机 socks 出站改为作用到 VLESS 出站；导出从 profiles.txt 变成 .json；" +
                "不带 TLS 的 http 伪装传输（tcp 伪 HTTP 头）改由 sing-box 的 http 传输承载，与 Xray 服务端的互通未实测" +
                "（VMess 一直这样用）；迁移按当时的全局「允许不安全」标注 Mux.Cool，之后打开全局开关，开了 mux 的非 REALITY " +
                "VLESS 会被拒绝（sing-box 不跑 Mux.Cool、Xray 不接受 allowInsecure），拒绝信息列出字段；1.8.0-a3 会改走 " +
                "sing-box 并换成 sing-mux",
            setOf(Result.XRAY), setOf(Result.SING_BOX, Result.XRAY),
        ) { it.isVless && it.auto && !it.reality && it.carriedBy == Carrier.XRAY },
        Row(
            "VMess 关了 TLS 仍残留证书指纹",
            "VMess 自动选核，没开 TLS，bean 里残留证书指纹（1.8.0-a3 按残留的指纹选了 Xray）",
            "Xray",
            "sing-box（不开 TLS 时证书指纹不算要求）；开了 mux（标注为 Mux.Cool）的留在 Xray",
            "与 VLESS 一行相同的换核影响（不涉及 TLS 的几项除外）",
            setOf(Result.XRAY), setOf(Result.SING_BOX, Result.XRAY),
        ) { it.isVmess && it.auto && !it.tlsOn && it.carriedBy == Carrier.XRAY },
        Row(
            "VMess + REALITY",
            "VMess 自动选核，REALITY，冻结规则走 sing-box（没有证书指纹）",
            "sing-box",
            "Xray；Xray 承载不了时留在 sing-box：REALITY + ws / httpupgrade / h2、mux 为 sing-mux、packetaddr、只有 sing-box 认的指纹",
            "REALITY 客户端版本按 Xray 26.9.30 上报、带上 X25519MLKEM768；alpn 不再写进 ClientHello；UDP 默认走 XUDP；" +
                "多一个 Xray 进程；自定义出站 JSON 改为作用到本机 socks 出站；导出变成 profiles.txt",
            setOf(Result.SING_BOX), setOf(Result.XRAY, Result.SING_BOX),
        ) { it.isVmess && it.auto && it.reality && it.carriedBy == Carrier.SING_BOX },
        Row(
            "VLESS + REALITY 开了 allowInsecure",
            "VLESS 自动选核，REALITY，节点或全局「允许不安全」打开，没有证书指纹（1.8.0-a3 的误判把它赶到 sing-box）",
            "sing-box",
            "Xray；Xray 承载不了时留在 sing-box（同上一行）",
            "同 VMess + REALITY；全局「允许不安全」不再影响 REALITY 节点的选核",
            setOf(Result.SING_BOX), setOf(Result.XRAY, Result.SING_BOX),
        ) { it.isVless && it.auto && it.reality && it.carriedBy == Carrier.SING_BOX },
        Row(
            "Trojan + REALITY",
            "Trojan，REALITY，没有证书指纹与 mldsa65Verify（任何 core 值，规范化后为自动）",
            "sing-box",
            "Xray（D10）；Xray 承载不了时留在 sing-box：REALITY + ws / httpupgrade / h2、mux 为 sing-mux（存量 Trojan 的 mux 都是 sing-mux）",
            "同 VMess + REALITY；Trojan 的 Xray 出站随 D10 补上",
            setOf(Result.SING_BOX), setOf(Result.XRAY, Result.SING_BOX),
        ) { it.isTrojan && it.reality && it.carriedBy == Carrier.SING_BOX },
        Row(
            "AnyTLS 不带证书指纹与 certificates",
            "AnyTLS 自动选核，没有证书指纹，也没有 certificates",
            "mihomo",
            "sing-box；只有 mihomo 认的指纹（chrome120、firefox120、safari16、none）留在 mihomo",
            "测速改用 sing-box 的 URL 测试（不再有翻倍的超时，测的是热连接往返）；ECH 自动查询改经 sing-box 的 DNS；" +
                "少一个 mihomo 进程；自定义出站 JSON 改为作用到 AnyTLS 出站；导出从 profiles.txt 变成 .json",
            setOf(Result.MIHOMO), setOf(Result.SING_BOX, Result.MIHOMO),
        ) { it.isAnyTls && it.auto && it.anyTlsPlain && it.carriedBy == Carrier.MIHOMO },
    )

    private fun sample(node: Node): Sample {
        val legacy = LegacyCoreSelection.outcome(node.type, node.core, node.bean, node.global)
        val bean = node.bean.clone()
        val upgraded = upgradeLegacyProfile(node.type, node.core, bean, node.global)
        return Sample(node, legacy, upgraded, decideCore(node.type, upgraded.core, bean, node.global))
    }

    @Test
    fun `差分测试：每个变化都归到对照表的一行`() {
        val random = Random(20261006)
        val total = 200_000
        val hits = rows.associateWith { 0 }.toMutableMap()
        val changedHits = rows.associateWith { 0 }.toMutableMap()
        val transitions = HashMap<String, Int>()
        var changed = 0
        val unexplained = ArrayList<String>()
        repeat(total) {
            val sample = sample(CoreTestNodes.randomSelectable(random))
            val row = rows.firstOrNull { it.matches(sample) }
            if (sample.changed) {
                changed++
                transitions.merge("${sample.before} → ${sample.after}", 1, Int::plus)
            }
            if (row == null) {
                if (sample.changed && unexplained.size < 20) unexplained += describe(sample)
                return@repeat
            }
            hits.merge(row, 1, Int::plus)
            if (sample.changed) changedHits.merge(row, 1, Int::plus)
            assertTrue(
                "「${row.name}」之前应是 ${row.before}：${describe(sample)}", sample.before in row.before,
            )
            assertTrue(
                "「${row.name}」之后应是 ${row.after}：${describe(sample)}", sample.after in row.after,
            )
        }
        writeReport(total, changed, hits, changedHits, transitions)
        assertEquals("归不进对照表的变化：\n" + unexplained.joinToString("\n"), emptyList<String>(), unexplained)
        for (row in rows) assertTrue("「${row.name}」没有样本命中", changedHits.getValue(row) > 0)
    }

    private fun describe(sample: Sample): String {
        val node = sample.node
        val b = node.bean
        val fields = when (b) {
            is StandardV2RayBean -> "type=${b.type} security=${b.security} reality=${!b.realityPubKey.isNullOrBlank()} " +
                "mldsa=${!b.realityMldsa65Verify.isNullOrBlank()} pin=${!b.certificateFingerprint.isNullOrBlank()} " +
                "insecure=${b.allowInsecure} utls=${b.utlsFingerprint} ech=${b.enableECH}/${!b.echConfig.isNullOrBlank()} " +
                "mux=${b.enableMux}/${b.muxType} pe=${b.packetEncoding} enc=${b.encryption} " +
                "ws=${b.wsMaxEarlyData}/${b.earlyDataHeaderName}/${b.path}"

            is AnyTLSBean -> "pin=${!b.certificateFingerprint.isNullOrBlank()} cert=${!b.certificates.isNullOrBlank()} " +
                "utls=${b.utlsFingerprint} insecure=${b.allowInsecure} ech=${b.enableECH}/${!b.echConfig.isNullOrBlank()}"

            else -> ""
        }
        val conflicts = (sample.decision as? CoreDecision.Rejected)?.conflicts?.joinToString { "${it.core}:${it.id}" }
        return "${b.javaClass.simpleName}${if (b is VMessBean && b.isVLESS) "(VLESS)" else ""} core=${node.core} " +
            "global=${node.global} ${sample.before} → ${sample.after} [$fields] conflicts=$conflicts"
    }

    private fun writeReport(
        total: Int,
        changed: Int,
        hits: Map<Row, Int>,
        changedHits: Map<Row, Int>,
        transitions: Map<String, Int>,
    ) {
        val dir = File("build/reports/core-selection").apply { mkdirs() }
        val md = StringBuilder()
        md.appendLine("# K1 选核变更对照表")
        md.appendLine()
        md.appendLine("「之前」是冻结的 1.8.0-a3 规则（LegacyCoreSelection）的结果；「之后」是先做存量升级标注、再用能力表判定的结果。")
        md.appendLine("行按顺序匹配，一个节点只归第一条命中的行。由 CoreSelectionChangeTableTest 生成。")
        md.appendLine()
        md.appendLine("| # | 类别 | 判定条件 | K1 之前 | K1 之后 | 连带的行为变化 |")
        md.appendLine("| --- | --- | --- | --- | --- | --- |")
        rows.forEachIndexed { i, row ->
            md.appendLine("| ${i + 1} | ${row.name} | ${row.condition} | ${row.beforeText} | ${row.afterText} | ${row.effects} |")
        }
        md.appendLine()
        md.appendLine("## 差分测试")
        md.appendLine()
        md.appendLine("固定种子 20261006，样本 $total 个，结果不同 $changed 个。")
        md.appendLine()
        md.appendLine("| # | 类别 | 命中样本 | 其中结果不同 |")
        md.appendLine("| --- | --- | --- | --- |")
        rows.forEachIndexed { i, row ->
            md.appendLine("| ${i + 1} | ${row.name} | ${hits.getValue(row)} | ${changedHits.getValue(row)} |")
        }
        md.appendLine()
        md.appendLine("| 之前 → 之后 | 样本数 |")
        md.appendLine("| --- | --- |")
        transitions.entries.sortedByDescending { it.value }.forEach { md.appendLine("| ${it.key} | ${it.value} |") }
        File(dir, "change-table.md").writeText(md.toString())
    }
}
