package io.nekohasekai.sagernet.golden

import io.nekohasekai.sagernet.fmt.AbstractBean
import moe.matsuri.nb4a.utils.Util
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

// 日志脱敏的对账：基线里每个场景 run / test 两种模式的 sing-box 配置与外核配置，按写日志的方式脱敏
// （sing-box 配置走 Util.redactConfig、外核配置走 Util.redactSecrets，同 ConfigBuildResult.loggableConfig /
// loggableExternalConfig；本机 socks 凭据由 redactLocalAuth 按值遮蔽，不在这里查）之后，场景里任何节点的凭据
// 字段值都不能再出现。SENSITIVE_KEYS 按输出的键名手工维护，生成器写出一个名单里没有的装凭据的键
// （REALITY 的 short_id / shortId、mieru 的 user.name、naive 的 proxy URL 里的用户名都漏过）时这里失败。
class GoldenLogRedactionTest {

    // bean 里装凭据的字符串字段（按字段名，含父类的）
    private val credentialFields = setOf(
        "password", "uuid", "username", "authPayload", "obfuscation", "token",
        "privateKey", "privateKeyPassphrase", "peerPreSharedKey", "realityShortId",
    )

    // 名字像凭据、其实是公开值的字段：SSH 主机公钥、WireGuard 对端公钥、REALITY 公钥
    private val publicFields = setOf("publicKey", "peerPublicKey", "realityPubKey")

    // 新协议加了名字像凭据的字段、两个集合都没收时测试失败，先归类再说
    private val credentialLike = Regex("pass|key|secret|token|uuid|user|auth|psk|short|obfs|obfuscation", RegexOption.IGNORE_CASE)

    @Test
    fun `基线配置按写日志的方式脱敏后不留任何节点凭据`() {
        val baseline = GoldenBaseline.load()
        val leaks = ArrayList<String>()
        val unclassified = LinkedHashSet<String>()
        // 每个凭据字段在脱敏前的配置里出现过几次：证明对账不是空过
        val seen = sortedMapOf<String, Int>()
        var documents = 0
        for (id in baseline.scenarioIds) {
            val input = baseline.input(id)
            val secrets = LinkedHashMap<String, String>()
            for (profileId in input.profileIds) {
                val bean = input.newBean(profileId)
                for ((field, value) in stringFields(bean)) {
                    when {
                        field in credentialFields -> if (value.isNotBlank()) secrets[value] = field
                        field in publicFields -> Unit
                        credentialLike.containsMatchIn(field) -> unclassified += "${bean.javaClass.simpleName}.$field"
                    }
                }
            }
            for (mode in GoldenBoxMode.entries) {
                val modeDir = File(baseline.root, "scenarios/$id/${mode.dir}")
                val singBox = File(modeDir, "sing-box.json")
                // (文件名, 原文, 脱敏后)
                val docs = buildList {
                    if (singBox.isFile) singBox.readText().let { add(Triple("sing-box.json", it, Util.redactConfig(it))) }
                    for (group in baseline.externalGroups(id, mode)) {
                        val text = File(modeDir, group.file).readText()
                        add(Triple(group.file, text, Util.redactSecrets(text)))
                    }
                }
                for ((file, raw, redacted) in docs) {
                    documents++
                    for ((value, field) in secrets) {
                        if (!raw.contains(value)) continue
                        seen.merge(field, 1, Int::plus)
                        if (redacted.contains(value)) leaks += "$id / $mode $file：$field 的值脱敏后仍在"
                    }
                }
            }
        }
        println("日志脱敏对账：$documents 份配置；脱敏前各凭据字段出现次数 $seen")
        assertTrue(unclassified.joinToString("\n", "名字像凭据、未归类的 bean 字段：\n"), unclassified.isEmpty())
        assertTrue(leaks.take(40).joinToString("\n", "脱敏后仍有凭据 ${leaks.size} 处：\n"), leaks.isEmpty())
        // 每个凭据字段都要在基线的配置里出现过：缺了说明夹具或读法变了，对账覆盖面在缩小
        assertEquals(credentialFields, seen.keys)
    }

    // bean 的全部 String 字段（含父类），值为 null 的跳过
    private fun stringFields(bean: AbstractBean): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var c: Class<*>? = bean.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.type != String::class.java || Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                (f.get(bean) as String?)?.let { out += f.name to it }
            }
            c = c.superclass
        }
        return out
    }
}
