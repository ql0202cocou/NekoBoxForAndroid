package moe.matsuri.nb4a.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.zip.DataFormatException

// 只覆盖不依赖 Android 框架的纯函数；b64Decode / b64EncodeUrlSafe 走
// android.util.Base64，在本地 JVM 单测里不可用，不在此测试
class UtilTest {

    @Test
    fun `redactSecrets 打码 JSON 里的密码字段`() {
        assertEquals(
            """{"password":"***"}""",
            Util.redactSecrets("""{"password":"hunter2"}""")
        )
    }

    @Test
    fun `redactSecrets 打码 YAML 里的密码行`() {
        assertEquals(
            "password: ***",
            Util.redactSecrets("password: hunter2")
        )
    }

    @Test
    fun `redactSecrets 打码 URL userinfo 里的密码`() {
        assertEquals(
            "https://user:***@example.com:443",
            Util.redactSecrets("https://user:hunter2@example.com:443")
        )
    }

    @Test
    fun `redactSecrets 打码整个 userinfo 都是凭据的 URL`() {
        assertEquals(
            "trojan://***@example.com:443",
            Util.redactSecrets("trojan://some-uuid@example.com:443")
        )
    }

    @Test
    fun `redactSecrets 打码 query 里的 secret 参数`() {
        assertEquals(
            "https://example.com/?password=***&key=***",
            Util.redactSecrets("https://example.com/?password=hunter2&key=hunter2")
        )
    }

    @Test
    fun `redactSecrets 普通文本保持不变`() {
        val text = "server example.com port 443"
        assertEquals(text, Util.redactSecrets(text))
    }

    @Test
    fun `redactUrlPath 只保留 scheme 和 host`() {
        assertEquals(
            "https://dns.example.com/***",
            Util.redactUrlPath("https://dns.example.com/per-user-id/query")
        )
    }

    @Test
    fun `zlib 压缩解压往返一致`() {
        val raw = "一段要被压缩的文本 text to compress".toByteArray()
        assertArrayEquals(raw, Util.zlibDecompress(Util.zlibCompress(raw, 9)))
    }

    @Test
    fun `zlibDecompress 损坏数据抛 DataFormatException`() {
        assertThrows(DataFormatException::class.java) {
            Util.zlibDecompress(byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun `mergeMap 嵌套 map 深合并`() {
        val dst = mutableMapOf<String, Any?>("a" to mapOf("x" to 1, "y" to 2))
        Util.mergeMap(dst, mapOf("a" to mapOf("y" to 3, "z" to 4)))
        assertEquals(mapOf("x" to 1, "y" to 3, "z" to 4), dst["a"])
    }

    @Test
    fun `mergeMap 加号前缀的 key 前置列表`() {
        val dst = mutableMapOf<String, Any?>("outbounds" to listOf("b"))
        Util.mergeMap(dst, mapOf("+outbounds" to listOf("a")))
        assertEquals(listOf("a", "b"), dst["outbounds"])
    }

    @Test
    fun `mergeMap 加号后缀的 key 追加列表`() {
        val dst = mutableMapOf<String, Any?>("outbounds" to listOf("a"))
        Util.mergeMap(dst, mapOf("outbounds+" to listOf("b")))
        assertEquals(listOf("a", "b"), dst["outbounds"])
    }

    @Test
    fun `mergeMap 普通 key 直接覆盖`() {
        val dst = mutableMapOf<String, Any?>("k" to 1)
        Util.mergeMap(dst, mapOf("k" to 2))
        assertEquals(2, dst["k"])
    }

    @Test
    fun `mergeMap null 值可以覆盖已有 key`() {
        val dst = mutableMapOf<String, Any?>("k" to 1)
        Util.mergeMap(dst, mapOf("k" to null))
        assertEquals(null, dst["k"])
    }
}
