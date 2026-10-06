package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

// AGENTS.md「Security」：选核被拒的冲突只带字段 key、原因与少数几类取值（uTLS 指纹、VLESS flow、传输、early data
// 头名、mux 名、packet encoding / mux 类型 / 核心编号），凭据字段（uuid、password、密钥、shortId）永远不能作为
// Conflict 的值。ProfileField.UUID 只为已知差异而设（K1b），这里钉住它不会混进冲突
class ConflictCredentialTest {

    @Test
    fun `冲突与能力表的行都不以 UUID 为字段`() {
        for (conflict in CoreConflict.entries) assertFalse("$conflict", ProfileField.UUID in conflict.fields)
        // Conflict 的取值只来自 RequirementItem（能力表的行）与选核本身的核心编号，行的字段同样不含 UUID
        for (requirement in Requirement.entries) assertFalse("$requirement", ProfileField.UUID in requirement.fields)
    }

    @Test
    fun `随机节点被拒时冲突的取值与说明都不含凭据`() {
        val uuid = "uuid-marker-7c1e9b"
        val password = "password-marker-4d2a"
        val shortId = "5eed5eed"
        val secrets = listOf(uuid, password, shortId, CoreTestNodes.REALITY_KEY)
        val random = Random(20261006)
        var rejected = 0
        repeat(20_000) {
            val node = CoreTestNodes.randomSelectable(random)
            when (val bean = node.bean) {
                is VMessBean -> bean.uuid = uuid
                is TrojanBean -> bean.password = password
                is AnyTLSBean -> bean.password = password
            }
            (node.bean as? StandardV2RayBean)?.let { if (!it.realityShortId.isNullOrBlank()) it.realityShortId = shortId }
            for (core in CoreTestNodes.CORE_SAMPLES) {
                val decision = decideCore(node.type, core, node.bean, node.global) as? CoreDecision.Rejected ?: continue
                rejected++
                val texts = decision.conflicts.mapNotNull { it.value } + decision.conflicts.map { it.message() } +
                    decision.message(core)
                for (text in texts) for (secret in secrets) {
                    assertFalse("凭据出现在冲突里：$text", secret in text)
                }
            }
        }
        // 样本里确实有被拒的节点，断言不是空转
        assertTrue(rejected > 1000)
    }
}
