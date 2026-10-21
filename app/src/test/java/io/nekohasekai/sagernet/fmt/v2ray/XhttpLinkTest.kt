package io.nekohasekai.sagernet.fmt.v2ray

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.SilencedLogs
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// XHTTP 的分享链接：导入（标准链接、v2rayN JSON）的字段读取与拒绝、导出的字段与格式。
// 整条链路的导出再解析逐字段比较见 ProtocolLinkTest（变体在 ProtocolLinkCases.kt）
class XhttpLinkTest {

    // 解析失败的回退链会写日志，JVM 里没有 libcore
    @get:Rule
    val logs = SilencedLogs()

    private val uuid = "00000000-0000-4000-8000-000000000001"

    private fun import(link: String) = runBlocking { parseProxies(link) { false } }.single() as StandardV2RayBean

    private fun importAll(text: String) = runBlocking { parseProxies(text) { false } }

    private fun vlessUrl(query: String) = "vless://$uuid@xhttp.example.com:443?$query"

    // ---- 导入：真实客户端（v2rayN / v2rayNG）的写法

    @Test
    fun `v2rayNG 风格的 VLESS xhttp 链接读出 host path mode extra`() {
        val extra = """{"xPaddingBytes":"100-1000","noGRPCHeader":false}"""
        val bean = import(
            "vless://$uuid@xhttp.example.com:443?encryption=none&security=tls&sni=sni.example.com&fp=chrome&alpn=h2" +
                "&type=xhttp&host=cdn.example.com&path=%2Fxh&mode=stream-up&extra=" + java.net.URLEncoder.encode(extra, "UTF-8") +
                "#xhttp-node",
        )
        assertEquals("xhttp", bean.type)
        assertEquals("cdn.example.com", bean.host)
        assertEquals("/xh", bean.path)
        assertEquals("stream-up", bean.xhttpMode)
        assertEquals("""{"xPaddingBytes":"100-1000","noGRPCHeader":false}""", bean.xhttpExtra)
        assertEquals("sni.example.com", bean.sni)
        assertEquals("tls", bean.security)
    }

    @Test
    fun `extra 用 percent 编码的紧凑 JSON 时读出规范形式`() {
        val bean = import(vlessUrl("type=xhttp&path=%2Fxh&mode=auto&extra=%7B%22xmux%22%3A%7B%22maxConcurrency%22%3A%2216-32%22%7D%2C%22headers%22%3A%7B%22X-A%22%3A%22b%22%7D%7D"))
        // 键序按白名单表的顺序（headers 在 xmux 之前）
        assertEquals("""{"headers":{"X-A":"b"},"xmux":{"maxConcurrency":"16-32"}}""", bean.xhttpExtra)
    }

    @Test
    fun `缺省值：没有 mode 是 auto，没有 path 是斜杠，没有 host 与 extra 是空`() {
        val bean = import(vlessUrl("type=xhttp"))
        assertEquals("xhttp", bean.type)
        assertEquals("auto", bean.xhttpMode)
        assertEquals("/", bean.path)
        assertEquals("", bean.host)
        assertEquals("", bean.xhttpExtra)
        // 空值与没写一样
        val blank = import(vlessUrl("type=xhttp&path=&mode=&host=&extra="))
        assertEquals("/", blank.path)
        assertEquals("auto", blank.xhttpMode)
        assertEquals("", blank.xhttpExtra)
    }

    @Test
    fun `splithttp 归一为 xhttp，mode 大小写不敏感，URL 路径部分也能当 path`() {
        val bean = import("vless://$uuid@xhttp.example.com:443/old/path?type=splithttp&mode=Packet-Up")
        assertEquals("xhttp", bean.type)
        assertEquals("packet-up", bean.xhttpMode)
        assertEquals("/old/path", bean.path)
        // 查询参数里的 path 优先
        assertEquals("/q", import(vlessUrl("type=splithttp&path=%2Fq")).path)
    }

    @Test
    fun `headerType=http 不会把 xhttp 改成 http`() {
        val bean = import(vlessUrl("type=xhttp&headerType=http&path=%2Fx"))
        assertEquals("xhttp", bean.type)
    }

    @Test
    fun `host 原样收下，IPv6 方括号不动，TLS 下 sni 缺省沿用 host`() {
        val bean = import(vlessUrl("type=xhttp&host=%5B2001%3Adb8%3A%3A1%5D&path=%2F"))
        assertEquals("[2001:db8::1]", bean.host)
        val tls = import(vlessUrl("type=xhttp&security=tls&host=cdn.example.com&path=%2F"))
        assertEquals("cdn.example.com", tls.sni)
        assertEquals("cdn.example.com", tls.host)
    }

    @Test
    fun `Trojan 链接同样读出 xhttp 参数`() {
        val bean = TrojanBean().apply {
            parseDuckSoft("https://pw@trojan.example.com:443?type=xhttp&host=h.example.com&path=%2Ft&mode=stream-one".toHttpUrl())
            initializeDefaultValues()
        }
        assertEquals("xhttp", bean.type)
        assertEquals("h.example.com", bean.host)
        assertEquals("/t", bean.path)
        assertEquals("stream-one", bean.xhttpMode)
    }

    @Test
    fun `其它传输方式忽略 mode 与 extra`() {
        val bean = import(vlessUrl("type=ws&path=%2Fws&mode=stream-one&extra=%7B%22downloadSettings%22%3A%7B%7D%7D"))
        assertEquals("ws", bean.type)
        assertEquals("auto", bean.xhttpMode)
        assertEquals("", bean.xhttpExtra)
    }

    // ---- 导入：拒绝

    private fun extraQuery(extra: String) = "type=xhttp&path=%2Fx&extra=" + java.net.URLEncoder.encode(extra, "UTF-8")

    @Test
    fun `extra 带 downloadSettings 的链接被拒，消息不带值`() {
        val secret = "secret-host.invalid"
        val e = assertThrows(InvalidXhttpExtraException::class.java) {
            import(vlessUrl(extraQuery("""{"downloadSettings":{"address":"$secret","port":443}}""")))
        }
        assertTrue(e is UnsupportedTransportException)
        assertEquals("xhttp extra rejected at downloadSettings: downloadSettings is not allowed", e.message)
        assertFalse(e.message!!.contains(secret))
    }

    @Test
    fun `extra 不合格时的消息只带键名，不带 headers 的值`() {
        val secret = "Bearer-TOP-SECRET-zq71"
        // 未知键
        val unknown = assertThrows(InvalidXhttpExtraException::class.java) {
            import(vlessUrl(extraQuery("""{"headers":{"Authorization":"$secret"},"unknownKey":1}""")))
        }
        assertEquals("xhttp extra rejected at unknownKey: unknown key", unknown.message)
        // header 值本身不合格（控制字符）：路径带的是 header 名，不是值
        val badValue = assertThrows(InvalidXhttpExtraException::class.java) {
            import(vlessUrl(extraQuery("""{"headers":{"X-Token":"$secret\u0001"}}""")))
        }
        assertFalse(badValue.message!!.contains(secret))
        assertTrue(badValue.message!!.contains("X-Token"))
        // 不是 JSON
        val notJson = assertThrows(InvalidXhttpExtraException::class.java) { import(vlessUrl(extraQuery("not-json-$secret"))) }
        assertFalse(notJson.message!!.contains(secret))
    }

    @Test
    fun `mode 不认识的链接被拒，值不进消息`() {
        val e = assertThrows(InvalidXhttpExtraException::class.java) {
            import(vlessUrl("type=xhttp&path=%2Fx&mode=zq71-fast"))
        }
        assertEquals("xhttp mode rejected: unsupported mode", e.message)
    }

    @Test
    fun `批量导入时 xhttp 参数不合格的一条被跳过，其它条照常`() {
        val text = listOf(
            vlessUrl("type=xhttp&path=%2Fa&mode=auto") + "#a",
            vlessUrl(extraQuery("""{"downloadSettings":{}}""")) + "#b",
            vlessUrl("type=xhttp&path=%2Fc&mode=bogus") + "#c",
            vlessUrl("type=xhttp&path=%2Fd&mode=stream-one") + "#d",
        ).joinToString("\n")
        val result = importAll(text)
        assertEquals(listOf("a", "d"), result.map { it.name })
        // 一条都没有时报出第一条被拒原因，是 UnsupportedTransportException 的子类
        val e = assertThrows(UnsupportedTransportException::class.java) {
            importAll(vlessUrl(extraQuery("""{"downloadSettings":{}}""")))
        }
        assertTrue(e is InvalidXhttpExtraException)
    }

    // ---- v2rayN JSON（VMess）

    private fun vmessJson(extraFields: String) =
        """{"v":"2","ps":"n","add":"example.com","port":"443","id":"$uuid","aid":"0","net":"xhttp"$extraFields}"""

    @Test
    fun `VMess JSON 的 xhttp 读出 host path type 里的 mode 与字符串 extra`() {
        val bean = parseV2RayNJson(
            vmessJson(""","host":"cdn.example.com","path":"/xh","type":"stream-up","extra":"{\"xPaddingBytes\":\"100-500\"}","tls":"tls","sni":"s.example.com""""),
        )
        assertEquals("xhttp", bean.type)
        assertEquals("cdn.example.com", bean.host)
        assertEquals("/xh", bean.path)
        assertEquals("stream-up", bean.xhttpMode)
        assertEquals("""{"xPaddingBytes":"100-500"}""", bean.xhttpExtra)
    }

    @Test
    fun `VMess JSON 的 extra 写成对象也能读，与字符串写法得到同一个结果`() {
        val asString = parseV2RayNJson(vmessJson(""","path":"/x","extra":"{\"xmux\":{\"maxConcurrency\":\"16-32\"},\"headers\":{\"A\":\"b\"}}""""))
        val asObject = parseV2RayNJson(vmessJson(""","path":"/x","extra":{"xmux":{"maxConcurrency":"16-32"},"headers":{"A":"b"}}"""))
        assertEquals(asString.xhttpExtra, asObject.xhttpExtra)
        assertEquals("""{"headers":{"A":"b"},"xmux":{"maxConcurrency":"16-32"}}""", asObject.xhttpExtra)
    }

    @Test
    fun `VMess JSON 的缺省值：type 缺省或 none 是 auto，没有 path 是斜杠`() {
        for (fields in listOf("", ",\"type\":\"none\"", ",\"type\":\"\"", ",\"type\":null")) {
            val bean = parseV2RayNJson(vmessJson(fields))
            assertEquals(fields, "auto", bean.xhttpMode)
            assertEquals(fields, "/", bean.path)
            assertEquals(fields, "", bean.xhttpExtra)
        }
        assertEquals("xhttp", parseV2RayNJson(vmessJson("").replace("\"xhttp\"", "\"splithttp\"")).type)
    }

    @Test
    fun `VMess JSON 的 mode 与 extra 不合格时被拒，且不影响其它传输方式的 type 字段`() {
        assertThrows(InvalidXhttpExtraException::class.java) { parseV2RayNJson(vmessJson(""","type":"fast"""")) }
        assertThrows(InvalidXhttpExtraException::class.java) {
            parseV2RayNJson(vmessJson(""","extra":"{\"downloadSettings\":{}}""""))
        }
        assertThrows(InvalidXhttpExtraException::class.java) {
            parseV2RayNJson(vmessJson(""","extra":{"downloadSettings":{}}"""))
        }
        // extra 是数组、数字
        assertThrows(InvalidXhttpExtraException::class.java) { parseV2RayNJson(vmessJson(""","extra":[1]""")) }
        assertThrows(InvalidXhttpExtraException::class.java) { parseV2RayNJson(vmessJson(""","extra":5""")) }
        // 其它传输方式的 type 字段含义不变，extra 不看
        val tcp = parseV2RayNJson(vmessJson("").replace("\"xhttp\"", "\"tcp\"").replace("}", ""","type":"http","extra":"junk"}"""))
        assertEquals("http", tcp.type)
        assertTrue(tcp.xhttpExtra.isNullOrEmpty())
    }

    // ---- 老格式仍然拒绝 xhttp（见 V2RayTransportTest）；这里只确认入口不会因为新解析而放行
    @Test
    fun `老 vmess 标准链接仍拒绝 xhttp`() {
        assertThrows(UnsupportedTransportException::class.java) { parseV2Ray("vmess://xhttp:$uuid-0@example.com:443?a=b") }
    }

    // ---- 导出

    private fun vless(block: VMessBean.() -> Unit = {}) = VMessBean().apply {
        name = "n"
        serverAddress = "xhttp.example.com"
        serverPort = 443
        uuid = this@XhttpLinkTest.uuid
        alterId = -1
        encryption = ""
        type = "xhttp"
        path = "/xh"
        block()
        initializeDefaultValues()
    }

    private fun query(link: String): Map<String, String> {
        val url = link.replaceFirst("vless://", "https://").replaceFirst("trojan://", "https://").toHttpUrl()
        return url.queryParameterNames.associateWith { url.queryParameter(it)!! }
    }

    @Test
    fun `VLESS 导出 type host path mode，mode 默认写 auto，没填 host 不写`() {
        val link = vless().toUriVMessVLESSTrojan(false)
        assertTrue(link, link.startsWith("vless://"))
        val q = query(link)
        assertEquals("xhttp", q["type"])
        assertEquals("/xh", q["path"])
        assertEquals("auto", q["mode"])
        assertNull("没填 host 时不写，生成器的兜底不进链接", q["host"])
        assertNull(q["extra"])
        assertEquals("cdn.example.com", query(vless { host = "cdn.example.com" }.toUriVMessVLESSTrojan(false))["host"])
    }

    @Test
    fun `VLESS 导出 extra 是紧凑规范 JSON 的 URL 编码，从不写 splithttp`() {
        val bean = vless { type = "splithttp"; xhttpMode = "stream-one"; xhttpExtra = """{"headers":{"A":"b"}}""" }
        val link = bean.toUriVMessVLESSTrojan(false)
        assertFalse(link, link.contains("splithttp"))
        val q = query(link)
        assertEquals("xhttp", q["type"])
        assertEquals("stream-one", q["mode"])
        assertEquals("""{"headers":{"A":"b"}}""", q["extra"])
        // 原始链接里花括号与引号已编码
        assertFalse(link.substringAfter("extra=").substringBefore("#").contains("{"))
        // 存的是未规范的写法时，导出的仍是规范形式
        val loose = vless { xhttpExtra = """{ "XPADDINGBYTES" : 300 , "headers" : { } }""" }
        assertEquals("""{"headers":{},"xPaddingBytes":300}""", query(loose.toUriVMessVLESSTrojan(false))["extra"])
    }

    @Test
    fun `Trojan 导出同样带 xhttp 参数`() {
        val bean = TrojanBean().apply {
            serverAddress = "trojan.example.com"; serverPort = 443; password = "pw"
            type = "xhttp"; path = "/t"; xhttpMode = "packet-up"; security = "none"
            initializeDefaultValues()
        }
        val q = query(bean.toUriVMessVLESSTrojan(true))
        assertEquals("xhttp", q["type"])
        assertEquals("packet-up", q["mode"])
        assertEquals("/t", q["path"])
    }

    @Test
    fun `VMess JSON 导出 net=xhttp，mode 在 type，extra 是字符串，host 只写用户填的`() {
        val bean = VMessBean().apply {
            name = "n"; serverAddress = "xhttp.example.com"; serverPort = 443; uuid = this@XhttpLinkTest.uuid
            type = "splithttp"; path = "/xh"; xhttpMode = "packet-up"
            xhttpExtra = """{"xPaddingBytes":"100-500"}"""
            initializeDefaultValues()
        }
        val json = JsonParser.parseString(bean.toV2rayNJson()).asJsonObject
        assertEquals("xhttp", json["net"].asString)
        assertEquals("packet-up", json["type"].asString)
        assertEquals("/xh", json["path"].asString)
        assertEquals("", json["host"].asString)
        assertTrue(json["extra"].asJsonPrimitive.isString)
        assertEquals("""{"xPaddingBytes":"100-500"}""", json["extra"].asString)
        assertFalse(bean.toV2rayNJson().contains("splithttp"))
        // 没有 extra 时不写这个键
        assertFalse(JsonParser.parseString(bean.apply { xhttpExtra = "" }.toV2rayNJson()).asJsonObject.has("extra"))
    }

    @Test
    fun `残缺的旧节点与不合格的存量值导出时报错，不导出会被修好的链接`() {
        val lost = vless { path = "" }
        assertThrows(XhttpParametersLostException::class.java) { lost.toUriVMessVLESSTrojan(false) }
        assertThrows(XhttpParametersLostException::class.java) { VMessBean().apply { type = "xhttp"; initializeDefaultValues() }.toV2rayNJson() }
        assertThrows(InvalidXhttpExtraException::class.java) {
            vless { xhttpExtra = """{"downloadSettings":{}}""" }.toUriVMessVLESSTrojan(false)
        }
        assertThrows(InvalidXhttpExtraException::class.java) { vless { xhttpMode = "fast" }.toUriVMessVLESSTrojan(false) }
    }

    @Test
    fun `非 xhttp 节点的链接不带 mode 与 extra`() {
        val link = vless { type = "ws"; host = "h"; path = "/w"; xhttpMode = "stream-up"; xhttpExtra = """{"noSSEHeader":true}""" }
            .toUriVMessVLESSTrojan(false)
        val q = query(link)
        assertNull(q["mode"])
        assertNull(q["extra"])
        val json = JsonParser.parseString(
            VMessBean().apply {
                serverAddress = "a.example.com"; type = "ws"; xhttpExtra = """{"noSSEHeader":true}"""; initializeDefaultValues()
            }.toV2rayNJson(),
        ).asJsonObject
        assertFalse(json.has("extra"))
    }
}
