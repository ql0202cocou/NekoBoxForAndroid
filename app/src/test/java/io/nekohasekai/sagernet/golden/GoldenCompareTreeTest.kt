package io.nekohasekai.sagernet.golden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GoldenCompareTreeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // 一次采集的动态取值：两次采集只有这些不同
    private class Dyn(
        val socks: Int,
        val mapping: Int,
        val socks2: Int,
        val controller: Int,
        val ca: String,
        val secret: String,
    )

    private val first = Dyn(41001, 41002, 41003, 41004, "/data/user/0/moe.nb4a.debug/cache/hysteria_111.ca", "c0ffee11")
    private val second = Dyn(52011, 52012, 52013, 52014, "/data/user/0/moe.nb4a.debug/cache/hysteria_999.ca", "beef9922")

    private fun singBox(d: Dyn) = """
        {
          "inbounds": [
            {"type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": 2080},
            {"type": "direct", "tag": "mapping-${d.mapping}", "listen": "127.0.0.1", "listen_port": ${d.mapping}, "override_address": "example.com", "override_port": 443}
          ],
          "outbounds": [
            {"type": "socks", "tag": "g-3", "server": "127.0.0.1", "server_port": ${d.socks}, "version": "5"},
            {"type": "socks", "tag": "g-4", "server": "127.0.0.1", "server_port": ${d.socks2}},
            {"type": "direct", "tag": "direct"}
          ]
        }
    """.trimIndent()

    private fun xray(d: Dyn) = """
        {
          "inbounds": [{"listen": "127.0.0.1", "port": ${d.socks}, "protocol": "socks"}],
          "outbounds": [{"protocol": "vless", "settings": {"vnext": [{"address": "127.0.0.1", "port": ${d.mapping}}]}, "streamSettings": {"tlsSettings": {"certificate": "${d.ca}"}}}]
        }
    """.trimIndent()

    private fun mihomo(d: Dyn, controller: Boolean) = buildString {
        append("mixed-port: ${d.socks2}\n")
        append("proxies:\n- name: anytls\n  type: anytls\n  server: example.org\n  port: 8443\n  password: fake-password\n  udp: true\n")
        if (controller) append("external-controller: 127.0.0.1:${d.controller}\nsecret: ${d.secret}\n")
    }

    private fun external(d: Dyn, controller: Boolean) = """
        [
          {"file": "ext-0.xray-plugin.json", "pluginId": "xray-plugin", "controller": null, "tempFiles": ["${d.ca}"],
           "hops": [{"index": 0, "chainIndex": 0, "profileId": 3, "port": ${d.socks}, "finalAddress": "127.0.0.1",
                     "finalPort": ${d.mapping}, "inboundTag": "in-0", "outboundTag": "out-0"}]},
          {"file": "ext-1.mihomo-plugin.yaml", "pluginId": "mihomo-plugin",
           "controller": ${if (controller) """{"port": ${d.controller}, "secret": "${d.secret}"}""" else "null"}, "tempFiles": [],
           "hops": [{"index": 1, "chainIndex": 1, "profileId": 4, "port": ${d.socks2}, "finalAddress": "example.org",
                     "finalPort": 8443, "inboundTag": "in-1", "outboundTag": "out-1"}]}
        ]
    """.trimIndent()

    private fun okResult(d: Dyn, controller: Boolean): String {
        val ports = listOfNotNull(d.socks, d.mapping, d.socks2, d.controller.takeIf { controller })
        val secrets = if (controller) """["${d.secret}"]""" else "[]"
        return """
            {
              "status": "ok",
              "build": {"mainEntId": 1, "selectorGroupId": -1, "profileTagMap": {"3": "g-3", "4": "g-4"},
                        "trafficMap": {"g-3": [3], "g-4": [4]}, "boxIndexNames": {}, "boxTagNames": {}},
              "external": ${external(d, controller)},
              "dynamic": {"ports": $ports, "paths": ["${d.ca}"], "secrets": $secrets}
            }
        """.trimIndent()
    }

    private fun manifest(commit: String, dirty: String = """"dirty": {"count": 0, "top": [], "codeCount": 0, "codeTop": []}""") = """
        {"formatVersion": 2, "commit": "$commit", $dirty,
         "app": {"versionName": "1.8.0-a1", "versionCode": 525},
         "cores": {"sing-box": {"version": "1.14.2-neko-1"}, "xray": {"version": "v26.3.27"}, "mihomo": {"version": "v1.19.31"}},
         "system": {"sdkInt": 37, "release": "17"}, "collectedAt": "2026-10-04T14:11:54Z"}
    """.trimIndent()

    // 一棵完整的采集产物；键是相对路径
    private fun sample(d: Dyn, commit: String = "abc1234"): MutableMap<String, String> = linkedMapOf(
        "manifest.json" to manifest(commit),
        "scenarios/mixed-chain/input.json" to """{"settings": {"mixedPort": 2080}, "profiles": [{"id": 3, "type": "vless"}, {"id": 4, "type": "anytls"}]}""",
        "scenarios/mixed-chain/run/sing-box.json" to singBox(d),
        "scenarios/mixed-chain/run/ext-0.xray-plugin.json" to xray(d),
        "scenarios/mixed-chain/run/ext-1.mihomo-plugin.yaml" to mihomo(d, false),
        "scenarios/mixed-chain/run/result.json" to okResult(d, false),
        "scenarios/mixed-chain/test/sing-box.json" to singBox(d),
        "scenarios/mixed-chain/test/ext-0.xray-plugin.json" to xray(d),
        "scenarios/mixed-chain/test/ext-1.mihomo-plugin.yaml" to mihomo(d, true),
        "scenarios/mixed-chain/test/result.json" to okResult(d, true),
        "scenarios/mixed-chain/export/export.txt" to singBox(d) + "\n\n" + xray(d) + "\n\n" + mihomo(d, false),
        "scenarios/mixed-chain/export/result.json" to
            """{"status": "ok", "exportName": "profiles.txt", "dynamic": {"ports": [${d.socks}, ${d.mapping}, ${d.socks2}], "paths": ["${d.ca}"], "secrets": []}}""",
        "scenarios/broken/input.json" to """{"profiles": [{"id": 9, "type": "vless", "uuid": "bad"}]}""",
        "scenarios/broken/run/result.json" to errorResult("invalid uuid"),
        "address/corpus.json" to """[{"input": "1.1.1.1", "numeric": true}, {"input": "fe80::1%wlan0", "numeric": true}]""",
    )

    private fun errorResult(message: String, cause: String = "bad length") = """
        {"status": "error", "error": {"class": "io.nekohasekai.sagernet.fmt.ProfileBuildException", "message": "$message",
         "causes": [{"class": "java.lang.IllegalArgumentException", "message": "$cause"}]},
         "dynamic": {"ports": [], "paths": [], "secrets": []}}
    """.trimIndent()

    private fun write(files: Map<String, String>): File {
        val root = tmp.newFolder()
        for ((rel, text) in files) File(root, rel).apply { parentFile!!.mkdirs() }.writeText(text)
        return root
    }

    private fun compare(e: Map<String, String>, a: Map<String, String>) =
        GoldenCompareTree.compare(write(e), write(a))

    private fun assertSame(r: GoldenTreeReport) = assertTrue(r.render(), r.same)

    // 报告按文件分组，单条差异的「文件：消息」形式另外拼上，两种写法都能断言
    private fun assertDiffer(r: GoldenTreeReport, vararg contains: String) {
        val text = r.render() + r.diffs.joinToString("\n")
        assertFalse("应当有差异：\n$text", r.same)
        contains.forEach { assertTrue("报告里没有「$it」：\n$text", it in text) }
    }

    // ---- 相等

    @Test
    fun `动态值不同但结构相同时一致`() {
        val r = compare(sample(first), sample(second, commit = "def5678"))
        assertSame(r)
        assertTrue(r.render(), r.warnings.isEmpty())
        val text = r.render()
        assertTrue(text, "预期：提交 abc1234，工作区干净，应用 1.8.0-a1，sing-box 1.14.2-neko-1，Xray v26.3.27，mihomo v1.19.31，系统 API 37（Android 17），采集于 2026-10-04T14:11:54Z\n" in text)
        assertTrue(text, "实际：提交 def5678，" in text)
        assertTrue(text, text.endsWith("结论：一致\n"))
    }

    @Test
    fun `manifest 里没有认得的字段就原样打印`() {
        val e = sample(first).apply { put("manifest.json", """{"collectedBy": "x"}""") }
        val text = compare(e, sample(second)).render()
        assertTrue(text, "预期：{\"collectedBy\": \"x\"}" in text)
    }

    private fun manifestOf(json: String): String {
        val e = sample(first).apply { put("manifest.json", json) }
        return compare(e, sample(second)).manifest[0]
    }

    @Test
    fun `manifest 打印工作区改动数与代码改动数`() {
        val line = manifestOf(manifest("abc1234", """"dirty": {"count": 3012, "top": ["a"], "codeCount": 2, "codeTop": ["x", "y"]}"""))
        assertTrue(line, "提交 abc1234，工作区有 3012 处改动，其中可能影响配置输出的代码 2 处，应用 1.8.0-a1" in line)
    }

    @Test
    fun `旧格式 manifest 的 dirtyPaths 也能打印`() {
        val old = """{"formatVersion": 1, "commit": "abc1234", "dirtyPaths": ["a", "b"], "app": {"versionName": "1.7.8-a1"}}"""
        assertEquals("预期：提交 abc1234，工作区有 2 处改动（旧格式，未区分代码改动），应用 1.7.8-a1", manifestOf(old))
        assertEquals("提交 abc1234，工作区干净", manifestOf("""{"commit": "abc1234", "dirtyPaths": []}""").removePrefix("预期："))
    }

    @Test
    fun `manifest 字段缺失时只打印有的`() {
        assertEquals("应用 1.0，系统 API 36", manifestOf("""{"app": {"versionName": "1.0"}, "system": {"sdkInt": 36}}""").removePrefix("预期："))
        assertEquals("sing-box 1.14", manifestOf("""{"cores": {"sing-box": {"version": "1.14"}, "xray": 5}, "app": []}""").removePrefix("预期："))
        // 类型不对的字段当作缺失
        assertEquals("应用 1.0", manifestOf("""{"commit": 5, "dirty": {"count": "x"}, "app": {"versionName": "1.0"}}""").removePrefix("预期："))
    }

    @Test
    fun `manifest 解析不了时原样打印`() {
        val line = manifestOf("{not json")
        assertTrue(line, "解析失败" in line && "{not json" in line)
        assertEquals("[1, 2]", manifestOf("[1, 2]").removePrefix("预期："))
    }

    // ---- 结构差异

    @Test
    fun `映射入站与外核拨号端口对不上能发现`() {
        // 外核出站拨的不是 sing-box 的映射入站端口，而是另一个动态端口
        val a = sample(second).apply {
            put("scenarios/mixed-chain/run/ext-0.xray-plugin.json", xray(second).replace("\"port\": ${second.mapping}", "\"port\": ${second.controller}"))
            put("scenarios/mixed-chain/run/result.json", okResult(second, false).replace("\"ports\": [", "\"ports\": [${second.controller}, "))
        }
        assertDiffer(
            compare(sample(first), a),
            "scenarios/mixed-chain/run/ext-0.xray-plugin.json（1 处）",
            "$.outbounds[0].settings.vnext[0].port：预期 PORT#1，实际 PORT#4",
        )
    }

    @Test
    fun `外核配置里的普通字段变化能发现`() {
        val a = sample(second).apply {
            put("scenarios/mixed-chain/test/ext-1.mihomo-plugin.yaml", mihomo(second, true).replace("port: 8443", "port: \"8443\""))
        }
        assertDiffer(compare(sample(first), a), "$.proxies[0].port：预期 8443，实际 \"8443\"")
    }

    @Test
    fun `input 变化能发现`() {
        val a = sample(second).apply { put("scenarios/broken/input.json", """{"profiles": [{"id": 9, "type": "vless", "uuid": "bad2"}]}""") }
        assertDiffer(compare(sample(first), a), "scenarios/broken/input.json", "$.profiles[0].uuid：预期 \"bad\"，实际 \"bad2\"")
    }

    @Test
    fun `地址语料严格比较`() {
        val a = sample(second).apply { put("address/corpus.json", get("address/corpus.json")!!.replace("\"numeric\": true}]", "\"numeric\": false}]")) }
        assertDiffer(compare(sample(first), a), "address/corpus.json", "$[1].numeric：预期 true，实际 false")
    }

    // ---- 只在一侧

    @Test
    fun `单侧缺文件、模式或场景算差异`() {
        assertDiffer(
            compare(sample(first), sample(second).apply { remove("scenarios/mixed-chain/run/ext-1.mihomo-plugin.yaml") }),
            "scenarios/mixed-chain/run/ext-1.mihomo-plugin.yaml：只在预期一侧存在",
        )
        assertDiffer(
            compare(sample(first), sample(second).apply { keys.removeAll { it.startsWith("scenarios/mixed-chain/test/") } }),
            "scenarios/mixed-chain/test/：整个目录只在预期一侧存在",
        )
        assertDiffer(
            compare(sample(first), sample(second).apply { put("scenarios/extra/input.json", "{}") }),
            "scenarios/extra/：整个目录只在实际一侧存在",
        )
        assertDiffer(
            compare(sample(first), sample(second).apply { remove("address/corpus.json") }),
            "address/corpus.json：只在预期一侧存在",
        )
        assertDiffer(
            compare(sample(first).apply { remove("scenarios/broken/input.json") }, sample(second).apply { remove("scenarios/broken/input.json") }),
            "scenarios/broken/input.json：两侧都缺少这个文件",
        )
    }

    @Test
    fun `两侧都没有场景不算一致`() {
        assertDiffer(compare(mapOf("manifest.json" to "{}"), mapOf("manifest.json" to "{}")), "两侧都没有任何场景")
    }

    @Test
    fun `目录不存在不算一致`() {
        val e = write(sample(first))
        assertFalse(GoldenCompareTree.compare(e, File(tmp.root, "missing")).same)
    }

    @Test
    fun `格式之外的文件按字节比较`() {
        val same = compare(sample(first).apply { put("scenarios/broken/run/note.txt", "x") }, sample(second).apply { put("scenarios/broken/run/note.txt", "x") })
        assertSame(same)
        assertTrue(same.warnings.single().contains("scenarios/broken/run/note.txt 不在产物格式内"))
        assertDiffer(
            compare(sample(first).apply { put("scenarios/broken/run/note.txt", "x") }, sample(second).apply { put("scenarios/broken/run/note.txt", "y") }),
            "两侧字节不同",
        )
        assertDiffer(compare(sample(first), sample(second).apply { put("extra.bin", "z") }), "extra.bin：格式之外的文件，只在实际一侧存在")
    }

    @Test
    fun `根目录的 README 不参与比较`() {
        val readme = "README.md" to "说明文档"
        // 只在预期一侧、只在实际一侧、两侧内容不同：都不算差异也不给警告
        for ((e, a) in listOf(
            sample(first).apply { put(readme.first, readme.second) } to sample(second),
            sample(first) to sample(second).apply { put(readme.first, readme.second) },
            sample(first).apply { put(readme.first, "甲") } to sample(second).apply { put(readme.first, "乙") },
        )) {
            val r = compare(e, a)
            assertSame(r)
            assertTrue(r.render(), r.warnings.isEmpty())
        }
        // 只豁免根目录那一份
        assertDiffer(
            compare(sample(first).apply { put("sub/README.md", "x") }, sample(second)),
            "sub/README.md：格式之外的文件，只在预期一侧存在",
        )
    }

    // ---- export.txt

    @Test
    fun `export 按空行拆段并逐段比较`() {
        val a = sample(second).apply {
            put("scenarios/mixed-chain/export/export.txt", singBox(second) + "\n\n" + xray(second) + "\n\n" + mihomo(second, false).replace("udp: true", "udp: false"))
        }
        assertDiffer(compare(sample(first), a), "scenarios/mixed-chain/export/export.txt#2（1 处）", "$.proxies[0].udp：预期 true，实际 false")
    }

    @Test
    fun `export 段数不同算差异`() {
        val a = sample(second).apply { put("scenarios/mixed-chain/export/export.txt", singBox(second) + "\n\n" + xray(second)) }
        assertDiffer(compare(sample(first), a), "export.txt#2：只在预期一侧存在")
    }

    @Test
    fun `export 解析不了的段报错`() {
        val bad = singBox(first) + "\n\n" + "a: [1\n"
        assertDiffer(compare(sample(first).apply { put("scenarios/mixed-chain/export/export.txt", bad) }, sample(second)), "export.txt#1：预期一侧解析失败")
        // 第 0 段必须是 JSON 对象
        val yamlFirst = "a: 1\n\n" + xray(first)
        assertDiffer(compare(sample(first), sample(second).apply { put("scenarios/mixed-chain/export/export.txt", yamlFirst) }), "export.txt#0：实际一侧解析失败")
        // 多出的空段
        val empty = singBox(first) + "\n\n\n\n" + xray(first)
        assertDiffer(compare(sample(first), sample(second).apply { put("scenarios/mixed-chain/export/export.txt", empty) }), "第 1 段为空")
    }

    // ---- result.json

    @Test
    fun `status 与 error 差异能发现`() {
        assertDiffer(
            compare(sample(first), sample(second).apply { put("scenarios/mixed-chain/run/result.json", errorResult("x")) }),
            "scenarios/mixed-chain/run/result.json", "$.status：预期 \"ok\"，实际 \"error\"",
        )
        assertDiffer(
            compare(sample(first), sample(second).apply { put("scenarios/broken/run/result.json", errorResult("invalid uuid!")) }),
            "$.error.message：预期 \"invalid uuid\"，实际 \"invalid uuid!\"",
        )
        assertDiffer(
            compare(sample(first), sample(second).apply { put("scenarios/broken/run/result.json", errorResult("invalid uuid", "bad len")) }),
            "$.error.causes[0].message",
        )
        assertDiffer(
            compare(sample(first), sample(second).apply { put("scenarios/broken/run/result.json", errorResult("invalid uuid").replace("ProfileBuildException", "IllegalStateException")) }),
            "$.error.class",
        )
    }

    @Test
    fun `build、external、exportName 差异能发现`() {
        val run = "scenarios/mixed-chain/run/result.json"
        assertDiffer(compare(sample(first), sample(second).apply { put(run, get(run)!!.replace("\"mainEntId\": 1", "\"mainEntId\": 2")) }), "$.build.mainEntId")
        assertDiffer(compare(sample(first), sample(second).apply { put(run, get(run)!!.replace("\"chainIndex\": 1", "\"chainIndex\": 0")) }), "$.external[1].hops[0].chainIndex")
        // 映射端口写回 finalPort 的对应关系
        assertDiffer(
            compare(sample(first), sample(second).apply { put(run, get(run)!!.replace("\"finalPort\": ${second.mapping}", "\"finalPort\": ${second.socks}")) }),
            "$.external[0].hops[0].finalPort：预期 PORT#1，实际 PORT#2",
        )
        val export = "scenarios/mixed-chain/export/result.json"
        assertDiffer(compare(sample(first), sample(second).apply { put(export, get(export)!!.replace("profiles.txt", "a.json")) }), "$.exportName")
    }

    @Test
    fun `dynamic 只比较个数，临时文件路径按动态路径处理`() {
        // tempFiles 的路径两侧不同但对应同一个 PATH#1，已在一致用例里覆盖；这里看个数变化
        val run = "scenarios/mixed-chain/run/result.json"
        val a = sample(second).apply { put(run, get(run)!!.replace("\"secrets\": []", "\"secrets\": [\"never-used\"]")) }
        val r = compare(sample(first), a)
        assertDiffer(r, "$.dynamic.secrets：预期 0，实际 1")
        assertTrue(r.render(), r.warnings.any { "[实际]：dynamic.secrets 里的 never-used 没有在任何文档里出现" in it })
    }

    @Test
    fun `dynamic 格式不对或缺失报错`() {
        val run = "scenarios/mixed-chain/run/result.json"
        assertDiffer(
            compare(sample(first), sample(second).apply { put(run, get(run)!!.replace("\"ports\": [", "\"ports\": [\"x\", ")) }),
            "实际一侧 dynamic.ports[0] 不是整数",
        )
        assertDiffer(
            compare(sample(first), sample(second).apply { put("scenarios/broken/run/result.json", """{"status": "error"}""") }),
            "实际一侧 缺少 dynamic",
        )
    }

    @Test
    fun `result 缺失或解析失败报错`() {
        assertDiffer(
            compare(sample(first).apply { remove("scenarios/mixed-chain/test/result.json") }, sample(second).apply { remove("scenarios/mixed-chain/test/result.json") }),
            "两侧都缺少 result.json",
        )
        assertDiffer(
            compare(sample(first), sample(second).apply { put("scenarios/broken/run/result.json", "{\"status\": \"ok\", \"status\": \"ok\"}") }),
            "result.json：实际一侧解析失败",
        )
    }

    @Test
    fun `重复键与非 UTF-8 报错`() {
        val e = sample(first).apply { put("scenarios/mixed-chain/run/sing-box.json", singBox(first).replace("\"version\": \"5\"", "\"version\": \"5\", \"version\": \"5\"")) }
        val a = sample(second).apply { put("scenarios/mixed-chain/run/sing-box.json", singBox(second).replace("\"version\": \"5\"", "\"version\": \"5\", \"version\": \"5\"")) }
        assertDiffer(compare(e, a), "重复的对象键 \"version\"")

        val eRoot = write(sample(first))
        val aRoot = write(sample(second))
        File(aRoot, "scenarios/broken/input.json").writeBytes(byteArrayOf('{'.code.toByte(), 0xC3.toByte(), '}'.code.toByte()))
        val r = GoldenCompareTree.compare(eRoot, aRoot)
        assertDiffer(r, "scenarios/broken/input.json：实际一侧解析失败：不是有效的 UTF-8")
    }

    // ---- 只比 sing-box 一侧

    private fun compareSingBox(e: Map<String, String>, a: Map<String, String>) =
        GoldenCompareTree.compare(write(e), write(a), GoldenCompareScope.SING_BOX)

    // 外核一侧换成另一种格式：合并后的配置、external 换了结构、export 少一段，产物格式版本号也变了
    private fun reformatted(d: Dyn, commit: String = "abc1234"): MutableMap<String, String> = sample(d, commit).apply {
        val merged = xray(d).replace("\"outbounds\"", "\"routing\": {\"rules\": []}, \"outbounds\"")
        for (mode in listOf("run", "test")) {
            remove("scenarios/mixed-chain/$mode/ext-1.mihomo-plugin.yaml")
            put("scenarios/mixed-chain/$mode/ext-0.xray-plugin.json", merged)
            put("scenarios/mixed-chain/$mode/result.json", get("scenarios/mixed-chain/$mode/result.json")!!
                .replace("\"external\": [", "\"external\": [{\"file\": \"ext-0.xray-plugin.json\", \"groups\": 1}, "))
        }
        put("scenarios/mixed-chain/export/export.txt", singBox(d) + "\n\n" + merged)
        for (input in keys.filter { it.endsWith("/input.json") }) put(input, get(input)!!.replaceFirst("{", "{\"formatVersion\": 2, "))
        put("address/corpus.json", """{"formatVersion": 2, "entries": []}""")
    }

    @Test
    fun `只比 sing-box 一侧时外核一侧与格式版本的变化都不算差异`() {
        val r = compareSingBox(sample(first), reformatted(second, commit = "def5678"))
        assertSame(r)
        // 外核一侧的动态值（测速控制端口、CA 路径、secret）本来就不比较，不给「没出现」的警告
        assertTrue(r.render(), r.warnings.isEmpty())
        assertTrue(r.render(), "比较范围：只比 sing-box 一侧\n" in r.render())
        // 同样的两棵树做完整比较就不一致
        assertDiffer(
            GoldenCompareTree.compare(write(sample(first)), write(reformatted(second))),
            "ext-1.mihomo-plugin.yaml：只在预期一侧存在", "export.txt#2：只在预期一侧存在", "address/corpus.json",
        )
    }

    @Test
    fun `只比 sing-box 一侧时 sing-box 配置、export 第 0 段、result 与 input 的变化仍能发现`() {
        val base = reformatted(second)
        assertDiffer(
            compareSingBox(sample(first), base.apply {
                put("scenarios/mixed-chain/run/sing-box.json", singBox(second).replace("\"version\": \"5\"", "\"version\": \"4\""))
            }),
            "scenarios/mixed-chain/run/sing-box.json", "$.outbounds[0].version：预期 \"5\"，实际 \"4\"",
        )
        assertDiffer(
            compareSingBox(sample(first), reformatted(second).apply {
                put("scenarios/mixed-chain/export/export.txt", singBox(second).replace("2080", "2081") + "\n\n" + xray(second))
            }),
            "export.txt#0", "listen_port：预期 2080，实际 2081",
        )
        val run = "scenarios/mixed-chain/run/result.json"
        assertDiffer(
            compareSingBox(sample(first), reformatted(second).apply { put(run, get(run)!!.replace("\"g-4\": [4]", "\"g-4\": [5]")) }),
            "$.build.trafficMap.g-4[0]：预期 4，实际 5",
        )
        assertDiffer(
            compareSingBox(sample(first), reformatted(second).apply { put(run, errorResult("x")) }),
            "$.status：预期 \"ok\"，实际 \"error\"",
        )
        assertDiffer(
            compareSingBox(sample(first), reformatted(second).apply {
                put("scenarios/broken/input.json", get("scenarios/broken/input.json")!!.replace("\"bad\"", "\"bad2\""))
            }),
            "$.profiles[0].uuid：预期 \"bad\"，实际 \"bad2\"",
        )
        // 动态值的个数照常比较
        assertDiffer(
            compareSingBox(sample(first), reformatted(second).apply { put(run, get(run)!!.replace("\"secrets\": []", "\"secrets\": [\"x\"]")) }),
            "$.dynamic.secrets：预期 0，实际 1",
        )
    }

    @Test
    fun `完整比较的报告写明比较范围`() {
        assertTrue(compare(sample(first), sample(second)).render().contains("比较范围：全部\n"))
    }

    // ---- 忽略本机认证

    private class Auth(val user: String, val pass: String)

    private val auth1 = Auth("u00112233445566aa", "p00112233445566778899aabbccddee01")
    private val auth2 = Auth("u99887766554433bb", "pffeeddccbbaa99887766554433221102")

    private fun creds(auth: Auth?) = if (auth == null) "" else """, "username": "${auth.user}", "password": "${auth.pass}""""

    // g-3 接 Xray、g-4 接 mihomo（都是本机 socks 出站）；g-5 是用户自己的 socks 节点，凭据是夹具里固定的
    private fun ilaSingBox(d: Dyn, auth: Auth?) = """
        {
          "inbounds": [
            {"type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": 2080},
            {"type": "direct", "tag": "c-0-mapping-3", "listen": "127.0.0.1", "listen_port": ${d.mapping}, "override_address": "example.com", "override_port": 443}
          ],
          "outbounds": [
            {"type": "socks", "tag": "g-3", "server": "127.0.0.1", "server_port": ${d.socks}${creds(auth)}},
            {"type": "socks", "tag": "g-4", "server": "127.0.0.1", "server_port": ${d.socks2}${creds(auth)}},
            {"type": "socks", "tag": "g-5", "server": "198.51.100.7", "server_port": 1080, "username": "node-user", "password": "node-pass"},
            {"type": "direct", "tag": "direct"}
          ]
        }
    """.trimIndent()

    private fun ilaXray(d: Dyn, auth: Auth?): String {
        val access = if (auth == null) "" else """, "access": "none""""
        val settings = if (auth == null) "" else
            """"auth": "password", "accounts": [{"user": "${auth.user}", "pass": "${auth.pass}"}], """
        return """
            {
              "log": {"loglevel": "warning"$access},
              "inbounds": [{"tag": "in-0", "listen": "127.0.0.1", "port": ${d.socks}, "protocol": "socks", "settings": {$settings"udp": true}}],
              "outbounds": [
                {"tag": "block", "protocol": "blackhole"},
                {"tag": "out-0", "protocol": "vless", "settings": {"vnext": [{"address": "127.0.0.1", "port": ${d.mapping}, "users": [{"id": "fake-uuid"}]}]}}
              ],
              "routing": {"domainStrategy": "AsIs", "rules": [{"type": "field", "inboundTag": ["in-0"], "outboundTag": "out-0"}]}
            }
        """.trimIndent()
    }

    private fun ilaMihomo(d: Dyn, auth: Auth?, controller: Boolean) = buildString {
        append("log-level: warning\nmode: rule\n")
        if (controller) append("external-controller: 127.0.0.1:${d.controller}\nsecret: ${d.secret}\n")
        append("listeners:\n- name: in-1\n  type: socks\n  listen: 127.0.0.1\n  port: ${d.socks2}\n  udp: true\n  proxy: out-1\n")
        if (auth != null) append("  users:\n  - {username: ${auth.user}, password: ${auth.pass}}\n")
        append("proxies:\n- {name: out-1, type: anytls, server: example.org, port: 8443, password: fake-password, udp: true}\n")
        append("rules: ['MATCH,REJECT']\n")
    }

    // 格式 3 起每个跳实例都有 localAuth；旧格式没有这个键
    private fun ilaResult(d: Dyn, auth: Auth?, version: Int, controller: Boolean): String {
        fun local() = when {
            version < 3 -> ""
            auth == null -> """, "localAuth": null"""
            else -> """, "localAuth": {"username": "${auth.user}", "password": "${auth.pass}"}"""
        }
        val ports = listOfNotNull(d.socks, d.mapping, d.socks2, d.controller.takeIf { controller })
        val secrets = listOfNotNull(d.secret.takeIf { controller }, auth?.user, auth?.pass).joinToString(", ") { "\"$it\"" }
        return """
            {
              "status": "ok",
              "build": {"mainEntId": 1, "selectorGroupId": -1, "profileTagMap": {"3": "g-3"}, "trafficMap": {"g-3": [3]}, "boxIndexNames": {}, "boxTagNames": {}},
              "external": [
                {"file": "ext-0.xray-plugin.json", "pluginId": "xray-plugin", "controller": null, "tempFiles": [],
                 "hops": [{"index": 0, "chainIndex": 0, "profileId": 3, "port": ${d.socks}, "finalAddress": "127.0.0.1",
                           "finalPort": ${d.mapping}, "inboundTag": "in-0", "outboundTag": "out-0"${local()}}]},
                {"file": "ext-1.mihomo-plugin.yaml", "pluginId": "mihomo-plugin",
                 "controller": ${if (controller) """{"port": ${d.controller}, "secret": "${d.secret}"}""" else "null"}, "tempFiles": [],
                 "hops": [{"index": 1, "chainIndex": 1, "profileId": 4, "port": ${d.socks2}, "finalAddress": "example.org",
                           "finalPort": 8443, "inboundTag": "in-1", "outboundTag": "out-1"${local()}}]}
              ],
              "dynamic": {"ports": $ports, "paths": [], "secrets": [$secrets]}
            }
        """.trimIndent()
    }

    // 一棵产物树：auth 为 null 时是加认证之前（格式 2）的样子
    private fun ilaTree(d: Dyn, auth: Auth?, version: Int = if (auth == null) 2 else 3): MutableMap<String, String> {
        val s = "scenarios/mixed-chain"
        val exportSecrets = listOfNotNull(auth?.user, auth?.pass).joinToString(", ") { "\"$it\"" }
        return linkedMapOf(
            "manifest.json" to manifest("abc1234"),
            "$s/input.json" to """{"formatVersion": $version, "profiles": [{"id": 3, "type": "vless"}, {"id": 4, "type": "anytls"}]}""",
            "$s/run/sing-box.json" to ilaSingBox(d, auth),
            "$s/run/ext-0.xray-plugin.json" to ilaXray(d, auth),
            "$s/run/ext-1.mihomo-plugin.yaml" to ilaMihomo(d, auth, false),
            "$s/run/result.json" to ilaResult(d, auth, version, false),
            "$s/test/sing-box.json" to ilaSingBox(d, auth),
            "$s/test/ext-0.xray-plugin.json" to ilaXray(d, auth),
            "$s/test/ext-1.mihomo-plugin.yaml" to ilaMihomo(d, auth, true),
            "$s/test/result.json" to ilaResult(d, auth, version, true),
            "$s/export/export.txt" to ilaSingBox(d, auth) + "\n\n" + ilaXray(d, auth) + "\n\n" + ilaMihomo(d, auth, false),
            "$s/export/result.json" to """{"status": "ok", "exportName": "profiles.txt", "dynamic": {"ports": [${d.socks}, ${d.mapping}, ${d.socks2}], "paths": [], "secrets": [$exportSecrets]}}""",
            "address/corpus.json" to """{"formatVersion": $version, "entries": [{"input": "1.1.1.1", "numeric": true}]}""",
        )
    }

    private fun compareIgnoringAuth(e: Map<String, String>, a: Map<String, String>) =
        GoldenCompareTree.compare(write(e), write(a), GoldenCompareScope.IGNORE_LOCAL_AUTH)

    @Test
    fun `忽略本机认证时只多出认证的新产物与旧基线一致`() {
        val r = compareIgnoringAuth(ilaTree(first, null), ilaTree(second, auth2))
        assertSame(r)
        assertTrue(r.render(), r.warnings.isEmpty())
        assertTrue(r.render(), "比较范围：忽略本机认证\n" in r.render())
        // 完整比较就不一致：认证本身、格式版本号都算差异
        assertDiffer(
            compare(ilaTree(first, null), ilaTree(second, auth2)),
            "$.outbounds[0].username", "$.inbounds[0].settings.auth", "$.log.access", "$.listeners[0].users",
            "$.external[0].hops[0].localAuth", "$.dynamic.secrets", "input.json", "address/corpus.json",
        )
        // 两次加了认证的采集：凭据按 dynamic.secrets 换成占位符，完整比较一致
        val both = compare(ilaTree(first, auth1), ilaTree(second, auth2))
        assertSame(both)
        assertTrue(both.render(), both.warnings.isEmpty())
    }

    @Test
    fun `忽略本机认证只去掉约定的位置，别处同名的键照常比较`() {
        val base = ilaTree(first, null)
        fun changed(path: String, transform: (String) -> String) = ilaTree(second, auth2).apply { put(path, transform(get(path)!!)) }
        val run = "scenarios/mixed-chain/run"
        // 用户自己的 socks 节点（不指向本机）的凭据
        assertDiffer(
            compareIgnoringAuth(base, changed("$run/sing-box.json") { it.replace("node-user", "node-user2") }),
            "$.outbounds[2].username",
        )
        // 本机 socks 出站的其它字段
        assertDiffer(
            compareIgnoringAuth(base, changed("$run/sing-box.json") { it.replace("\"tag\": \"g-3\",", "\"tag\": \"g-3\", \"version\": \"4\",") }),
            "$.outbounds[0].version",
        )
        // Xray：入站本身（不在 settings 里）的同名键、settings 的其它键、出站里的同名键
        assertDiffer(
            compareIgnoringAuth(base, changed("$run/ext-0.xray-plugin.json") { it.replace("\"tag\": \"in-0\",", "\"tag\": \"in-0\", \"auth\": \"password\",") }),
            "$.inbounds[0].auth",
        )
        assertDiffer(
            compareIgnoringAuth(base, changed("$run/ext-0.xray-plugin.json") { it.replace("\"udp\": true", "\"udp\": false") }),
            "$.inbounds[0].settings.udp",
        )
        assertDiffer(
            compareIgnoringAuth(base, changed("$run/ext-0.xray-plugin.json") { it.replace("\"protocol\": \"vless\", \"settings\": {", "\"protocol\": \"vless\", \"settings\": {\"accounts\": [], ") }),
            "$.outbounds[1].settings.accounts",
        )
        assertDiffer(
            compareIgnoringAuth(base, changed("$run/ext-0.xray-plugin.json") { it.replace("\"loglevel\": \"warning\"", "\"loglevel\": \"info\"") }),
            "$.log.loglevel",
        )
        // mihomo：代理里的同名键、listener 的其它键
        assertDiffer(
            compareIgnoringAuth(base, changed("$run/ext-1.mihomo-plugin.yaml") { it.replace("udp: true}", "udp: true, users: []}") }),
            "$.proxies[0].users",
        )
        assertDiffer(
            compareIgnoringAuth(base, changed("$run/ext-1.mihomo-plugin.yaml") { it.replace("  proxy: out-1\n", "  proxy: out-0\n") }),
            "$.listeners[0].proxy",
        )
        // result.json：组上（不在跳实例里）的同名键、多出来的别的 secret
        assertDiffer(
            compareIgnoringAuth(base, changed("$run/result.json") { it.replace("\"tempFiles\": [],\n", "\"tempFiles\": [], \"localAuth\": null,\n") }),
            "$.external[0].localAuth",
        )
        assertDiffer(
            compareIgnoringAuth(base, changed("$run/result.json") { it.replace("\"secrets\": [", "\"secrets\": [\"other\", ") }),
            "$.dynamic.secrets：预期 0，实际 1",
        )
        // 凭据出现在约定之外的位置
        assertDiffer(
            compareIgnoringAuth(base, changed("$run/sing-box.json") { it.replace("\"tag\": \"direct\"", "\"tag\": \"direct\", \"note\": \"${auth2.user}\"") }),
            "$.outbounds[3].note",
        )
        // export.txt 的外核段同样只去掉约定的位置
        assertDiffer(
            compareIgnoringAuth(base, changed("scenarios/mixed-chain/export/export.txt") { it.replace("udp: true}", "udp: false}") }),
            "export.txt#2", "$.proxies[0].udp",
        )
    }

    @Test
    fun `忽略本机认证容忍格式版本号，别的差异照常报告`() {
        val base = ilaTree(first, null)
        assertSame(compareIgnoringAuth(base, ilaTree(second, auth2, version = 7)))
        assertDiffer(
            compareIgnoringAuth(base, ilaTree(second, auth2).apply { put("address/corpus.json", get("address/corpus.json")!!.replace("true", "false")) }),
            "$.entries[0].numeric",
        )
        val input = "scenarios/mixed-chain/input.json"
        assertDiffer(
            compareIgnoringAuth(base, ilaTree(second, auth2).apply { put(input, get(input)!!.replace("\"vless\"", "\"vmess\"")) }),
            "$.profiles[0].type",
        )
        // 方向反过来（基线带认证、新采集不带）同样只差认证
        assertSame(compareIgnoringAuth(ilaTree(first, auth1), ilaTree(second, null)))
    }

    // ---- 报告

    @Test
    fun `差异很多时每个文件只列前若干条`() {
        val many = (1..30).joinToString(",", "{", "}") { "\"k$it\": $it" }
        val e = sample(first).apply { put("scenarios/broken/input.json", many) }
        val a = sample(second).apply { put("scenarios/broken/input.json", many.replace(Regex(": (\\d+)"), ": \"$1\"")) }
        val r = compare(e, a)
        val text = r.render(maxPerFile = 5)
        assertEquals(30, r.diffs.size)
        assertTrue(text, "scenarios/broken/input.json（30 处）" in text)
        assertTrue(text, "…另有 25 处未列出" in text)
        assertTrue(text, "结论：不一致（30 处差异）" in text)
    }
}
