package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// AnyTLS 兼容测试的两份指纹名单两边一致：libcore/anytlscompat/fingerprint.go 的 echFingerprints，与 fingerprints 的键
// 加上 fingerprint() 单独处理的 random、none，必须正好是 Kotlin 的 ANYTLS_COMPAT_ECH_FINGERPRINTS 与
// ANYTLS_COMPAT_UTLS_FINGERPRINTS（都区分大小写）。编辑器保存只按 Kotlin 的名单判定、不试建，Go 改了名单而 Kotlin
// 没跟上时，保存放行的节点要到试建才被拒，或者保存拒绝了 Go 能跑的节点，这里先红
class AnyTLSCompatFingerprintWireTest {

    // 单测的工作目录是 app/，从仓库根目录跑时也能找到
    private fun repoFile(path: String): File =
        listOf(File("../$path"), File(path)).firstOrNull { it.isFile } ?: error("找不到 $path（工作目录 ${File(".").absolutePath}）")

    private val goSource by lazy { repoFile("libcore/anytlscompat/fingerprint.go").readText() }

    private fun stringLiterals(text: String) = Regex(""""([^"]*)"""").findAll(text).map { it.groupValues[1] }.toList()

    @Test
    fun `ECH 指纹名单等于 Go 的 echFingerprints`() {
        val literal = Regex("""var echFingerprints = \[]string\{([^}]*)}""").find(goSource)?.groupValues?.get(1)
            ?: error("fingerprint.go 里找不到 echFingerprints")
        val names = stringLiterals(literal)
        assertEquals("Go 的 echFingerprints 里有重复：$names", names.size, names.toSet().size)
        assertEquals(names.toSet(), ANYTLS_COMPAT_ECH_FINGERPRINTS.names)
        assertFalse(ANYTLS_COMPAT_ECH_FINGERPRINTS.ignoreCase)
    }

    @Test
    fun `uTLS 指纹名单等于 Go 的 fingerprints 加上 random 与 none`() {
        val body = Regex("""var fingerprints = map\[string]utls\.ClientHelloID\{(.*?)\n}""", RegexOption.DOT_MATCHES_ALL)
            .find(goSource)?.groupValues?.get(1) ?: error("fingerprint.go 里找不到 fingerprints")
        val keys = Regex(""""([^"]+)"\s*:""").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals("Go 的 fingerprints 里有重复：$keys", keys.size, keys.toSet().size)
        // random 与 none 不在映射里，由 fingerprint() 在查映射之前单独处理（空串与 none 都是 Go 标准 TLS）
        val function = goSource.substringAfter("func fingerprint(", "").substringBefore("\n}\n")
        assertTrue("fingerprint.go 里找不到 fingerprint()", function.isNotEmpty())
        for (special in listOf("random", "none")) assertTrue(special, "\"$special\"" in function)
        assertEquals(keys.toSet() + "random" + "none", ANYTLS_COMPAT_UTLS_FINGERPRINTS.names)
        assertFalse(ANYTLS_COMPAT_UTLS_FINGERPRINTS.ignoreCase)
    }
}
