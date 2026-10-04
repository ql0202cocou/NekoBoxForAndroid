package io.nekohasekai.sagernet.golden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldenCompareDocumentTest {

    private fun json(e: String, a: String, eDyn: GoldenDynamic = GoldenDynamic.NONE, aDyn: GoldenDynamic = GoldenDynamic.NONE) =
        GoldenCompare.compareDocuments(e, a, GoldenFormat.JSON, eDyn, aDyn)

    private fun yaml(e: String, a: String, eDyn: GoldenDynamic = GoldenDynamic.NONE, aDyn: GoldenDynamic = GoldenDynamic.NONE) =
        GoldenCompare.compareDocuments(e, a, GoldenFormat.YAML, eDyn, aDyn)

    private fun assertSame(r: GoldenCompareResult) = assertTrue(r.diffs.joinToString("\n"), r.same)

    private fun assertDiffer(r: GoldenCompareResult, vararg contains: String) {
        assertFalse("应当有差异", r.same)
        val text = r.diffs.joinToString("\n")
        contains.forEach { assertTrue("差异里没有「$it」：\n$text", it in text) }
    }

    private fun ports(vararg p: Long) = GoldenDynamic(ports = p.toList())

    // ---- 键序、空白、数组

    @Test
    fun `对象键序与空白不计`() {
        assertSame(json("""{"a":1,"b":{"x":[1,2],"y":"s"}}""", "{\n  \"b\": {\n    \"y\": \"s\",\n    \"x\": [ 1, 2 ]\n  },\n  \"a\": 1\n}\n"))
    }

    @Test
    fun `数组顺序严格`() {
        assertDiffer(json("""{"a":[1,2]}""", """{"a":[2,1]}"""), "$.a[0]", "预期 1，实际 2")
    }

    @Test
    fun `数组长度不同`() {
        assertDiffer(json("""{"a":[1,2]}""", """{"a":[1,2,3]}"""), "数组长度不同：预期 2，实际 3", "$.a[2]：预期缺失，实际 3")
        assertDiffer(json("""[1]""", """[]"""), "数组长度不同")
    }

    // ---- 标量

    @Test
    fun `整数与小数写法不同即不相等`() {
        assertDiffer(json("""{"p":443}""", """{"p":443.0}"""), "$.p：预期 443，实际 443.0")
        assertDiffer(json("""{"p":100}""", """{"p":1e2}"""), "实际 1e2")
    }

    @Test
    fun `整数之间与小数之间按数值比较`() {
        assertSame(json("""{"p":1.5,"q":0.10,"r":-0}""", """{"p":1.50,"q":0.1,"r":0}"""))
        assertSame(json("""{"big":123456789012345678901234567890}""", """{"big":123456789012345678901234567890}"""))
        assertDiffer(json("""{"p":1.5}""", """{"p":1.25}"""))
        assertDiffer(json("""{"p":443}""", """{"p":444}"""))
    }

    @Test
    fun `字符串逐字符相等且与数字和布尔不混同`() {
        assertSame(json("""{"s":"aé"}""", """{"s":"aé"}"""))
        assertDiffer(json("""{"s":"a"}""", """{"s":"a "}"""))
        assertDiffer(json("""{"s":"443"}""", """{"s":443}"""), "预期 \"443\"，实际 443")
        assertDiffer(json("""{"s":"true"}""", """{"s":true}"""))
        assertDiffer(json("""{"s":true}""", """{"s":false}"""))
    }

    @Test
    fun `键缺失与值为 null 不相等`() {
        assertDiffer(json("""{"a":null}""", """{}"""), "$.a：预期 null，实际缺失")
        assertDiffer(json("""{}""", """{"a":null}"""), "$.a：预期缺失，实际 null")
        assertDiffer(json("""{"a":null}""", """{"a":0}"""))
        assertSame(json("""{"a":null}""", """{"a":null}"""))
    }

    // ---- 报错

    @Test
    fun `JSON 重复键报错`() {
        val doc = """{"a":1,"b":{"x":1,"x":1}}"""
        assertDiffer(json(doc, doc), "重复的对象键 \"x\"")
    }

    @Test
    fun `JSON 解析不了就报错`() {
        for (bad in listOf("", "{", """{"a":1} {}""", """{'a':1}""", """{"a":NaN}""", """{"a":1,}""", "// c\n{}", """{"a":0443}""")) {
            assertDiffer(json(bad, bad), "解析失败")
        }
    }

    @Test
    fun `顶层要求对象时不接受数组`() {
        val r = GoldenCompare.compareDocuments("[]", "[]", GoldenFormat.JSON_OBJECT)
        assertDiffer(r, "顶层不是 JSON 对象")
    }

    // ---- YAML

    @Test
    fun `YAML 键序与写法不计`() {
        assertSame(yaml("a: 1\nb:\n  - x\n  - 'y'\n", "b: [x, \"y\"]\na: 1\n"))
    }

    @Test
    fun `YAML 区分字符串与数字、整数与小数`() {
        assertDiffer(yaml("port: 443\n", "port: \"443\"\n"), "预期 443，实际 \"443\"")
        assertDiffer(yaml("port: 443\n", "port: 443.0\n"))
        assertSame(yaml("port: 443\nr: 1.50\n", "port: +443\nr: 1.5\n"))
    }

    @Test
    fun `YAML 不把 yes 和十六进制当成同值`() {
        assertDiffer(yaml("a: true\n", "a: yes\n"))
        assertDiffer(yaml("a: 443\n", "a: 0x1BB\n"))
        assertSame(yaml("a: yes\nb: ~\n", "a: yes\nb: null\n"))
    }

    @Test
    fun `YAML 重复键与解析失败报错`() {
        val dup = "a: 1\na: 2\n"
        assertDiffer(yaml(dup, dup), "重复的对象键 \"a\"")
        val bad = "a: [1, 2\n"
        assertDiffer(yaml(bad, bad), "解析失败")
        assertDiffer(yaml("", ""), "YAML 文档为空")
        val multi = "a: 1\n---\na: 2\n"
        assertDiffer(yaml(multi, multi), "解析失败")
    }

    @Test
    fun `先 JSON 后 YAML 的文档两种都不是就报错`() {
        val r = GoldenCompare.compareDocuments("a: [", "a: [", GoldenFormat.JSON_OR_YAML)
        assertDiffer(r, "既不是 JSON", "也不是 YAML")
        assertSame(GoldenCompare.compareDocuments("a: 1\n", "{\"a\": 1}", GoldenFormat.JSON_OR_YAML))
    }

    // ---- 动态值

    @Test
    fun `动态端口在数字与字符串里换成占位符`() {
        val p = GoldenPlaceholders(ports(41001))
        assertEquals(GoldenValue.Ref("PORT#1"), p.substitute(GoldenParser.parseJson("41001")))
        assertEquals(
            GoldenValue.Str(listOf(StrPart.Text("socks://127.0.0.1:"), StrPart.Ref("PORT#1"))),
            p.substitute(GoldenParser.parseJson("\"socks://127.0.0.1:41001\"")),
        )
        // 只匹配完整的数字串
        assertEquals(GoldenValue.str("141001"), p.substitute(GoldenValue.str("141001")))
        assertEquals(GoldenValue.str("410012"), p.substitute(GoldenValue.str("410012")))
        assertEquals(GoldenValue.Integer(41002.toBigInteger()), p.substitute(GoldenParser.parseJson("41002")))
        // 小数不是端口
        assertEquals(GoldenParser.parseJson("41001.0"), p.substitute(GoldenParser.parseJson("41001.0")))
    }

    @Test
    fun `端口号不同但对应关系相同时相等`() {
        val e = """{"inbounds":[{"listen_port":41002}],"outbounds":[{"server":"127.0.0.1","server_port":41001,"detour":"127.0.0.1:41002"}]}"""
        val a = """{"inbounds":[{"listen_port":52010}],"outbounds":[{"server":"127.0.0.1","server_port":52007,"detour":"127.0.0.1:52010"}]}"""
        assertSame(json(e, a, ports(41001, 41002), ports(52007, 52010)))
    }

    @Test
    fun `本该相同的两处端口变得不同能发现`() {
        val e = """{"in":41002,"out":"127.0.0.1:41002"}"""
        val a = """{"in":52002,"out":"127.0.0.1:52003"}"""
        assertDiffer(json(e, a, ports(41002), ports(52002, 52003)), "$.out：预期 \"127.0.0.1:{PORT#1}\"，实际 \"127.0.0.1:{PORT#2}\"")
    }

    @Test
    fun `跨文档的端口对应被打乱能发现`() {
        fun docs(singBox: String, ext: String) = listOf(
            GoldenDocument("sing-box.json", singBox, GoldenFormat.JSON),
            GoldenDocument("ext-0.xray-plugin.json", ext, GoldenFormat.JSON),
        )
        // sing-box 拨外核的 socks 端口，外核出站拨回 sing-box 的映射入站
        val e = docs("""{"a_socks":41001,"b_mapping":41002}""", """{"in":41001,"out":41002}""")
        val good = docs("""{"a_socks":52001,"b_mapping":52002}""", """{"in":52001,"out":52002}""")
        val swapped = docs("""{"a_socks":52001,"b_mapping":52002}""", """{"in":52002,"out":52001}""")
        val eDyn = ports(41001, 41002)
        val aDyn = ports(52001, 52002)
        assertSame(GoldenCompare.compareDocumentSets(e, eDyn, good, aDyn))
        assertDiffer(
            GoldenCompare.compareDocumentSets(e, eDyn, swapped, aDyn),
            "ext-0.xray-plugin.json $.in：预期 PORT#1，实际 PORT#2",
        )
    }

    @Test
    fun `动态端口不能被删掉也不能变成字符串`() {
        assertDiffer(json("""{"p":41001}""", """{}""", ports(41001), ports(52001)), "$.p：预期 PORT#1，实际缺失")
        assertDiffer(json("""{"p":41001}""", """{"p":"52001"}""", ports(41001), ports(52001)), "预期 PORT#1，实际 \"{PORT#1}\"")
        // 一侧不再把它当动态值：占位符对上的是字面值，算差异
        assertDiffer(json("""{"p":41001}""", """{"p":41001}""", ports(41001), GoldenDynamic.NONE), "预期 PORT#1，实际 41001")
    }

    @Test
    fun `路径与 secret 按子串替换`() {
        val e = """{"ca":"/data/user/0/moe.nb4a/cache/hy_111.ca","url":"file:///data/user/0/moe.nb4a/cache/hy_111.ca","secret":"abcd1234","h":"Bearer abcd1234"}"""
        val a = """{"ca":"/data/user/0/moe.nb4a/cache/hy_999.ca","url":"file:///data/user/0/moe.nb4a/cache/hy_999.ca","secret":"zz9","h":"Bearer zz9"}"""
        val eDyn = GoldenDynamic(paths = listOf("/data/user/0/moe.nb4a/cache/hy_111.ca"), secrets = listOf("abcd1234"))
        val aDyn = GoldenDynamic(paths = listOf("/data/user/0/moe.nb4a/cache/hy_999.ca"), secrets = listOf("zz9"))
        assertSame(json(e, a, eDyn, aDyn))
        // secret 两处本该相同，一处变了
        val broken = a.replace("Bearer zz9", "Bearer zz8")
        assertDiffer(json(e, broken, eDyn, aDyn), "$.h：预期 \"Bearer {SECRET#1}\"，实际 \"Bearer zz8\"")
    }

    @Test
    fun `路径里的数字不被当成端口`() {
        val p = GoldenPlaceholders(GoldenDynamic(ports = listOf(41001), paths = listOf("/cache/41001.ca")))
        assertEquals(
            GoldenValue.Str(listOf(StrPart.Ref("PATH#1"), StrPart.Text(" "), StrPart.Ref("PORT#1"))),
            p.substitute(GoldenValue.str("/cache/41001.ca 41001")),
        )
    }

    @Test
    fun `占位符按遍历次序编号，对象按键名排序`() {
        val p = GoldenPlaceholders(ports(41001, 41002))
        val v = p.substitute(GoldenParser.parseJson("""{"z":41001,"a":[41002,41001]}"""))
        assertEquals("""{"a":[PORT#1,PORT#2],"z":PORT#2}""", GoldenCompare.render(v))
    }

    @Test
    fun `列了但没出现的动态值给出警告`() {
        val r = json("""{"p":41001}""", """{"p":52001}""", GoldenDynamic(ports = listOf(41001, 41009), secrets = listOf("gone")), ports(52001))
        assertSame(r)
        val text = r.warnings.joinToString("\n")
        assertTrue(text, "[预期]：dynamic.ports 里的 41009 没有在任何文档里出现" in text)
        assertTrue(text, "dynamic.secrets 里的 gone" in text)
        assertEquals(2, r.warnings.size)
    }

    // ---- 报告格式

    @Test
    fun `差异给出文档内路径`() {
        val e = """{"outbounds":[{},{},{"server_port":1}],"a.b":{"k":1}}"""
        val a = """{"outbounds":[{},{},{"server_port":2}],"a.b":{"k":2}}"""
        assertDiffer(json(e, a), "document $.outbounds[2].server_port：预期 1，实际 2", "$[\"a.b\"].k")
    }
}
