package io.nekohasekai.sagernet.fmt.v2ray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// XHTTP extra / mode 校验器：白名单与规范化、拒绝清单、headers、数值上限、报错信息不带值
class XhttpExtraTest {

    private fun canon(raw: String?) = canonicalXhttpExtra(raw)

    private fun reject(raw: String): InvalidXhttpExtraException =
        assertThrows(InvalidXhttpExtraException::class.java) { canonicalXhttpExtra(raw) }

    // ---- 空值

    @Test
    fun `空值与空对象返回空串`() {
        assertEquals("", canon(null))
        assertEquals("", canon(""))
        assertEquals("", canon("   \n"))
        assertEquals("", canon("{}"))
        assertEquals("", canon(" { } "))
    }

    // ---- 白名单、规范键名与顺序

    @Test
    fun `键名按 ASCII 大小写不敏感匹配，输出规范键名`() {
        assertEquals(
            """{"xPaddingBytes":"100-1000","noSSEHeader":true}""",
            canon("""{"XPADDINGBYTES":"100-1000","nosseheader":true}"""),
        )
        assertEquals(
            """{"scMaxEachPostBytes":500000}""",
            canon("""{"ScMaxEachPostBytes":500000}"""),
        )
    }

    @Test
    fun `输出顺序按白名单表，与输入顺序无关`() {
        val a = canon("""{"noSSEHeader":true,"xPaddingBytes":"100-1000","headers":{"X-A":"1"}}""")
        val b = canon("""{"headers":{"X-A":"1"},"xPaddingBytes":"100-1000","noSSEHeader":true}""")
        assertEquals(a, b)
        assertEquals("""{"headers":{"X-A":"1"},"xPaddingBytes":"100-1000","noSSEHeader":true}""", a)
    }

    @Test
    fun `白名单里的每个键都能通过`() {
        val extra = """{"headers":{"X-A":"1"},"xPaddingBytes":"100-1000","xPaddingObfsMode":true,"xPaddingKey":"x_padding",
            |"xPaddingHeader":"X-Padding","xPaddingPlacement":"queryInHeader","xPaddingMethod":"repeat-x","uplinkHTTPMethod":"POST",
            |"sessionIDPlacement":"path","sessionIDKey":"x_session","sessionIDTable":"abc","sessionIDLength":"8-16","seqPlacement":"path",
            |"seqKey":"x_seq","uplinkDataPlacement":"auto","uplinkDataKey":"X-Data","uplinkChunkSize":"1000-2000","noGRPCHeader":false,
            |"noSSEHeader":false,"scMaxEachPostBytes":1000000,"scMinPostsIntervalMs":30,"scMaxBufferedPosts":30,
            |"scStreamUpServerSecs":"20-80","serverMaxHeaderBytes":8192,
            |"xmux":{"maxConcurrency":"16-32","cMaxReuseTimes":0,"hMaxRequestTimes":"600-900","hMaxReusableSecs":"1800-3000","hKeepAlivePeriod":-1}}"""
            .trimMargin().replace("\n", "")
        val out = canon(extra)
        // 已经是规范形式：再规范一次不变
        assertEquals(out, canon(out))
        assertTrue(out.contains(""""xmux":{"maxConcurrency":"16-32","cMaxReuseTimes":0,"hMaxRequestTimes":"600-900","hMaxReusableSecs":"1800-3000","hKeepAlivePeriod":-1}"""))
        assertTrue(out.startsWith("""{"headers":{"X-A":"1"},"xPaddingBytes":"100-1000","""))
    }

    @Test
    fun `范围的两种写法归一，两端相等写整数，颠倒的两端对调`() {
        assertEquals("""{"xPaddingBytes":100}""", canon("""{"xPaddingBytes":"100-100"}"""))
        assertEquals("""{"xPaddingBytes":100}""", canon("""{"xPaddingBytes":"100"}"""))
        assertEquals("""{"xPaddingBytes":100}""", canon("""{"xPaddingBytes":100}"""))
        assertEquals("""{"xPaddingBytes":"100-1000"}""", canon("""{"xPaddingBytes":"1000-100"}"""))
        assertEquals("""{"xPaddingBytes":0}""", canon("""{"xPaddingBytes":""}"""))
    }

    @Test
    fun `枚举取值区分大小写，空串视为默认`() {
        assertEquals("""{"xPaddingPlacement":"cookie"}""", canon("""{"xPaddingPlacement":"cookie"}"""))
        assertEquals("""{"xPaddingPlacement":""}""", canon("""{"xPaddingPlacement":""}"""))
        val e = reject("""{"xPaddingPlacement":"Cookie"}""")
        assertEquals(listOf("xPaddingPlacement"), e.path)
        reject("""{"sessionIDPlacement":"body"}""")
        reject("""{"uplinkDataPlacement":"query"}""")
        reject("""{"xPaddingMethod":"random"}""")
    }

    // ---- 拒绝清单

    @Test
    fun `拒绝 downloadSettings、嵌套 extra 与外层的 host、path、mode`() {
        for (key in listOf("downloadSettings", "DownloadSettings", "extra", "EXTRA", "host", "Host", "path", "mode", "MODE")) {
            val e = reject("""{"$key":"x"}""")
            assertEquals(listOf(key), e.path)
        }
        val download = reject("""{"downloadSettings":{"address":"203.0.113.1","port":443}}""")
        assertTrue(download.message!!.contains("downloadSettings is not allowed"))
        reject("""{"extra":{"headers":{}}}""")
    }

    @Test
    fun `拒绝未知键与非 ASCII 键`() {
        reject("""{"unknownKey":1}""")
        reject("""{"xPaddingBytesX":1}""")
        // Go 的 encoding/json 会把 U+212A（开尔文符号）折叠成 k，所以必须按非 ASCII 拒绝
        reject("{\"Key\":1}")
        reject("""{"xPaddıngBytes":1}""")
        reject("""{"":1}""")
        // xmux 里的键同样走白名单
        reject("""{"xmux":{"maxConcurrencyy":1}}""")
        // downloadSettings 之类的键只在外层有说明；xmux 里就是未知键
        reject("""{"xmux":{"extra":1}}""")
    }

    @Test
    fun `同一个键的两种大小写写法同时出现算重复`() {
        val e = reject("""{"xPaddingBytes":100,"XPADDINGBYTES":200}""")
        assertTrue(e.message!!.contains("duplicate key"))
        reject("""{"noSSEHeader":true,"noSSEHeader":false}""")
    }

    // ---- 取值类型

    @Test
    fun `取值类型不对即拒绝`() {
        reject("""{"noSSEHeader":"true"}""")
        reject("""{"xPaddingBytes":true}""")
        reject("""{"xPaddingBytes":[100]}""")
        reject("""{"xPaddingBytes":1.5}""")
        reject("""{"xPaddingBytes":1e2}""")
        reject("""{"xPaddingBytes":"a-b"}""")
        reject("""{"xPaddingBytes":"-5"}""")
        reject("""{"xPaddingKey":123}""")
        reject("""{"xPaddingKey":"has space"}""")
        reject("""{"xPaddingKey":"${"k".repeat(129)}"}""")
        reject("""{"uplinkHTTPMethod":"PO ST"}""")
        reject("""{"sessionIDTable":"é"}""")
        reject("""{"scMaxBufferedPosts":"30"}""")
        reject("""{"xmux":[1]}""")
        reject("""{"headers":"X-A: 1"}""")
        reject("""{"headers":null}""")
    }

    @Test
    fun `不是 JSON 对象或带尾随内容即拒绝，消息不转述解析器`() {
        for (raw in listOf("[]", "null", "1", "\"x\"", "{", "{\"noSSEHeader\":", "{} {}", "{} x", "not json")) {
            val e = reject(raw)
            assertEquals("xhttp extra rejected: not a valid JSON object", e.message)
        }
        val tooLong = reject("{\"headers\":{\"X-A\":\"" + "a".repeat(XHTTP_EXTRA_MAX_LENGTH) + "\"}}")
        assertEquals("xhttp extra rejected: extra is too long", tooLong.message)
    }

    // ---- headers

    @Test
    fun `headers 是字符串到字符串的对象，不含 Host`() {
        assertEquals(
            """{"headers":{"X-Token":"fake-token","Accept-Language":"zh-CN"}}""",
            canon("""{"headers":{"X-Token":"fake-token","Accept-Language":"zh-CN"}}"""),
        )
        assertEquals("""{"headers":{}}""", canon("""{"headers":{}}"""))
        for (name in listOf("Host", "host", "HOST")) {
            val e = reject("""{"headers":{"$name":"example.com"}}""")
            assertEquals(listOf("headers", name), e.path)
            assertTrue(e.message!!.contains("headers must not contain host"))
        }
        // 不是字符串的值
        reject("""{"headers":{"X-A":1}}""")
        reject("""{"headers":{"X-A":["1"]}}""")
        reject("""{"headers":{"X-A":null}}""")
        // 名字必须是 token，伪头部与带空白的名字都不行
        reject("""{"headers":{":authority":"x"}}""")
        reject("""{"headers":{"X A":"x"}}""")
        reject("""{"headers":{"":"x"}}""")
        // 同名（大小写不敏感）只许一个
        reject("""{"headers":{"X-A":"1","x-a":"2"}}""")
        // 值里的控制字符（头部注入）
        reject("{\"headers\":{\"X-A\":\"a\\r\\nHost: evil\"}}")
        reject("{\"headers\":{\"X-A\":\"a\\u0000b\"}}")
        // 制表符是合法的头部值字符
        assertEquals("""{"headers":{"X-A":"a\tb"}}""", canon("{\"headers\":{\"X-A\":\"a\\tb\"}}"))
    }

    @Test
    fun `headers 的条数与长度有上限`() {
        val many = (1..33).joinToString(",") { "\"X-H$it\":\"v\"" }
        assertTrue(reject("""{"headers":{$many}}""").message!!.contains("too many headers"))
        val ok = (1..32).joinToString(",") { "\"X-H$it\":\"v\"" }
        assertNotNull(canon("""{"headers":{$ok}}"""))
        reject("""{"headers":{"X-A":"${"v".repeat(4097)}"}}""")
        assertNotNull(canon("""{"headers":{"X-A":"${"v".repeat(4096)}"}}"""))
    }

    // ---- 数值上限

    @Test
    fun `数值上限含端点，超出即拒绝`() {
        val caps = mapOf(
            "xPaddingBytes" to 65_536L,
            "sessionIDLength" to 256L,
            "uplinkChunkSize" to 16L * 1024 * 1024,
            "scMaxEachPostBytes" to 16L * 1024 * 1024,
            "scMinPostsIntervalMs" to 60_000L,
            "scStreamUpServerSecs" to 3600L,
            "scMaxBufferedPosts" to 4096L,
            "serverMaxHeaderBytes" to 1024L * 1024,
        )
        for ((key, cap) in caps) {
            assertEquals("""{"$key":$cap}""", canon("""{"$key":$cap}"""))
            val e = reject("""{"$key":${cap + 1}}""")
            assertEquals(listOf(key), e.path)
            assertTrue(e.message!!.contains("out of range"))
            reject("""{"$key":-1}""")
        }
        // 范围的任一端超出都拒绝
        reject("""{"xPaddingBytes":"100-65537"}""")
        reject("""{"xPaddingBytes":"99999999999999999999"}""")
    }

    @Test
    fun `xmux 的数值上限与互斥`() {
        val caps = mapOf(
            "maxConcurrency" to 1024L,
            "maxConnections" to 256L,
            "cMaxReuseTimes" to 1_000_000L,
            "hMaxRequestTimes" to 1_000_000L,
            "hMaxReusableSecs" to 86_400L,
        )
        for ((key, cap) in caps) {
            assertEquals("""{"xmux":{"$key":$cap}}""", canon("""{"xmux":{"$key":$cap}}"""))
            val e = reject("""{"xmux":{"$key":${cap + 1}}}""")
            assertEquals(listOf("xmux", key), e.path)
        }
        assertEquals("""{"xmux":{"hKeepAlivePeriod":3600}}""", canon("""{"xmux":{"hKeepAlivePeriod":3600}}"""))
        assertEquals("""{"xmux":{"hKeepAlivePeriod":-1}}""", canon("""{"xmux":{"hKeepAlivePeriod":-1}}"""))
        reject("""{"xmux":{"hKeepAlivePeriod":3601}}""")
        reject("""{"xmux":{"hKeepAlivePeriod":-2}}""")
        reject("""{"xmux":{"hKeepAlivePeriod":"60"}}""")
        // Xray 构建配置时拒绝两者同时指定：导入时就报
        val both = reject("""{"xmux":{"maxConcurrency":"16-32","maxConnections":2}}""")
        assertTrue(both.message!!.contains("can not both be set"))
        assertEquals("""{"xmux":{"maxConcurrency":0,"maxConnections":2}}""", canon("""{"xmux":{"maxConnections":2,"maxConcurrency":0}}"""))
        assertEquals("""{"xmux":{}}""", canon("""{"xmux":{}}"""))
    }

    // ---- 报错信息

    @Test
    fun `异常是 UnsupportedTransportException，transport 是 xhttp`() {
        val e = reject("""{"unknownKey":1}""")
        assertTrue((e as UnsupportedTransportException).transport == "xhttp")
        assertEquals("""xhttp extra rejected at unknownKey: unknown key""", e.message)
    }

    @Test
    fun `报错只带形态合格的键名，不带值`() {
        // 值（可能是凭据）不进消息
        val secret = "fake-secret-value-zq81"
        for (raw in listOf(
            """{"headers":{"Authorization":1}}""",
            """{"headers":{"Host":"$secret"}}""",
            """{"xPaddingKey":"$secret $secret"}""",
            """{"noSSEHeader":"$secret"}""",
            """{"xPaddingBytes":"$secret"}""",
            """{"unknownKey":"$secret"}""",
        )) {
            val e = reject(raw)
            assertFalse(raw, e.message!!.contains(secret))
        }
        // 键名形态不合格（含空格、非 ASCII、超长）一律写 (key not shown)
        val weird = reject("{\"has space-zq82\":1}")
        assertEquals("xhttp extra rejected at (key not shown): unknown key", weird.message)
        assertFalse(weird.message!!.contains("zq82"))
        val nonAscii = reject("{\"键名-zq83\":1}")
        assertEquals("xhttp extra rejected at (key not shown): unknown key", nonAscii.message)
        val long = reject("{\"" + "k".repeat(65) + "\":1}")
        assertEquals("xhttp extra rejected at (key not shown): unknown key", long.message)
        // headers 的名字同理
        val header = reject("{\"headers\":{\"X A-zq84\":\"v\"}}")
        assertEquals("xhttp extra rejected at headers.(key not shown): invalid header name", header.message)
        // 形态合格的 header 名可以回显
        val host = reject("""{"headers":{"Host":"x"}}""")
        assertEquals("xhttp extra rejected at headers.Host: headers must not contain host", host.message)
    }

    // ---- mode

    @Test
    fun `mode 空值是 auto，四个合法值原样返回`() {
        assertEquals("auto", requireXhttpMode(null))
        assertEquals("auto", requireXhttpMode(""))
        assertEquals("auto", requireXhttpMode("  "))
        for (m in listOf("auto", "packet-up", "stream-up", "stream-one")) assertEquals(m, requireXhttpMode(m))
        assertEquals(listOf("auto", "packet-up", "stream-up", "stream-one"), XHTTP_MODES)
    }

    @Test
    fun `mode 大小写不敏感，返回规范小写名`() {
        assertEquals("packet-up", requireXhttpMode("Packet-Up"))
        assertEquals("stream-one", requireXhttpMode(" STREAM-ONE "))
    }

    @Test
    fun `未知 mode 拒绝，值不进消息`() {
        for (bad in listOf("stream", "packet_up", "multi", "stream-up-zq85")) {
            val e = assertThrows(InvalidXhttpExtraException::class.java) { requireXhttpMode(bad) }
            assertEquals("xhttp mode rejected: unsupported mode", e.message)
            assertEquals("xhttp", e.transport)
        }
    }
}
