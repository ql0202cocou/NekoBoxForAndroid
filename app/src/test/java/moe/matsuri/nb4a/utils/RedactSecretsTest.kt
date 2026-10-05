package moe.matsuri.nb4a.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

// redactSecrets 对外核配置与日志里凭据的遮蔽。YAML 输入都由 snakeyaml 按 MihomoConfig 的方式
// （Yaml().dump，默认选项）实际生成，不手写；JSON 输入由 JavaUtil.gson 生成
class RedactSecretsTest {

    private val defaultUser = "nb4a-3f9c2e7d1a6b4c8e"
    private val defaultPass = "9d2c7e1f4b8a6c3e5f7a9b1d2c4e6f8a"

    // 与 mihomo 配置同形状：listeners 可带 users，proxies 是只含标量的映射（snakeyaml 输出成行内写法）
    private fun mihomo(
        users: Boolean,
        longServer: Boolean,
        user: String = defaultUser,
        pass: String = defaultPass,
    ): String {
        val config = LinkedHashMap<String, Any?>()
        config["log-level"] = "warning"
        config["mode"] = "rule"
        config["external-controller"] = "127.0.0.1:39001"
        config["secret"] = "ctrl-secret-123"
        config["listeners"] = (0..1).map { i ->
            LinkedHashMap<String, Any?>().apply {
                put("name", "in-$i")
                put("type", "socks")
                put("listen", "127.0.0.1")
                put("port", 44829 + i)
                put("udp", true)
                put("proxy", "out-$i")
                if (users) put("users", listOf(linkedMapOf("username" to user, "password" to pass)))
            }
        }
        config["proxies"] = (0..1).map { i ->
            LinkedHashMap<String, Any?>().apply {
                put("name", "out-$i")
                put("type", "anytls")
                put("server", if (longServer && i == 0) "anytls-node-35-chars-01.example.com" else "127.0.0.1")
                put("port", 36239 + i)
                put("password", "golden-pass-anytls")
                put("udp", true)
                put(
                    "sni",
                    if (longServer && i == 1) "a-rather-long-server-name-for-folding.anytls.example.com"
                    else "anytls.example.com"
                )
            }
        }
        config["rules"] = listOf("MATCH,REJECT")
        return Yaml().dump(config)
    }

    @Test
    fun `mihomo 行内映射里的 password 被遮蔽，其它键保持可读`() {
        val input = mihomo(users = false, longServer = false)
        // 先确认 snakeyaml 输出的确是行内写法，password 不在行首
        assertEquals(
            """
            log-level: warning
            mode: rule
            external-controller: 127.0.0.1:39001
            secret: ctrl-secret-123
            listeners:
            - {name: in-0, type: socks, listen: 127.0.0.1, port: 44829, udp: true, proxy: out-0}
            - {name: in-1, type: socks, listen: 127.0.0.1, port: 44830, udp: true, proxy: out-1}
            proxies:
            - {name: out-0, type: anytls, server: 127.0.0.1, port: 36239, password: golden-pass-anytls,
              udp: true, sni: anytls.example.com}
            - {name: out-1, type: anytls, server: 127.0.0.1, port: 36240, password: golden-pass-anytls,
              udp: true, sni: anytls.example.com}
            rules: ['MATCH,REJECT']

            """.trimIndent(),
            input
        )
        assertEquals(
            """
            log-level: warning
            mode: rule
            external-controller: 127.0.0.1:39001
            secret: ***
            listeners:
            - {name: in-0, type: socks, listen: 127.0.0.1, port: 44829, udp: true, proxy: out-0}
            - {name: in-1, type: socks, listen: 127.0.0.1, port: 44830, udp: true, proxy: out-1}
            proxies:
            - {name: out-0, type: anytls, server: 127.0.0.1, port: 36239, password: ***,
              udp: true, sni: anytls.example.com}
            - {name: out-1, type: anytls, server: 127.0.0.1, port: 36240, password: ***,
              udp: true, sni: anytls.example.com}
            rules: ['MATCH,REJECT']

            """.trimIndent(),
            Util.redactSecrets(input)
        )
    }

    @Test
    fun `mihomo 行内映射折行后落到行首的 password 只遮值，同一行后面的键保留`() {
        val input = mihomo(users = false, longServer = true)
        assertTrue(input.contains(",\n  password: golden-pass-anytls, udp: true, sni: anytls.example.com}\n"))
        assertEquals(
            """
            log-level: warning
            mode: rule
            external-controller: 127.0.0.1:39001
            secret: ***
            listeners:
            - {name: in-0, type: socks, listen: 127.0.0.1, port: 44829, udp: true, proxy: out-0}
            - {name: in-1, type: socks, listen: 127.0.0.1, port: 44830, udp: true, proxy: out-1}
            proxies:
            - {name: out-0, type: anytls, server: anytls-node-35-chars-01.example.com, port: 36239,
              password: ***, udp: true, sni: anytls.example.com}
            - {name: out-1, type: anytls, server: 127.0.0.1, port: 36240, password: ***,
              udp: true, sni: a-rather-long-server-name-for-folding.anytls.example.com}
            rules: ['MATCH,REJECT']

            """.trimIndent(),
            Util.redactSecrets(input)
        )
    }

    @Test
    fun `mihomo 入站 users 里的 username 与 password 被遮蔽`() {
        val input = mihomo(users = true, longServer = false)
        assertTrue(input.contains("\n  users:\n  - {username: $defaultUser, password: $defaultPass}\n"))
        assertEquals(
            """
            log-level: warning
            mode: rule
            external-controller: 127.0.0.1:39001
            secret: ***
            listeners:
            - name: in-0
              type: socks
              listen: 127.0.0.1
              port: 44829
              udp: true
              proxy: out-0
              users:
              - {username: ***, password: ***}
            - name: in-1
              type: socks
              listen: 127.0.0.1
              port: 44830
              udp: true
              proxy: out-1
              users:
              - {username: ***, password: ***}
            proxies:
            - {name: out-0, type: anytls, server: 127.0.0.1, port: 36239, password: ***,
              udp: true, sni: anytls.example.com}
            - {name: out-1, type: anytls, server: 127.0.0.1, port: 36240, password: ***,
              udp: true, sni: anytls.example.com}
            rules: ['MATCH,REJECT']

            """.trimIndent(),
            Util.redactSecrets(input)
        )
    }

    @Test
    fun `mihomo 入站 users 凭据很长时同样被遮蔽`() {
        val user = "nb4a-" + "a".repeat(40)
        val pass = "b".repeat(64)
        val input = mihomo(users = true, longServer = false, user = user, pass = pass)
        val redacted = Util.redactSecrets(input)
        assertFalse(redacted.contains(user))
        assertFalse(redacted.contains(pass))
        assertFalse(redacted.contains("golden-pass-anytls"))
        assertEquals(2, Regex("""- \{username: \*\*\*, password: \*\*\*}""").findAll(redacted).count())
        assertTrue(redacted.contains("  proxy: out-1\n"))
        assertTrue(redacted.contains("udp: true, sni: anytls.example.com}"))
    }

    // 刁钻的密码：snakeyaml 会按内容选普通 / 单引号 / 双引号 / 块字面量写法，长值还会折行
    private val trickyPasswords = listOf(
        "pw with some spaces",
        "lorem ipsum dolor sit amet consectetur adipiscing elit ".repeat(4).trim(),
        "a,b", "x, y,z", "k: v", "a#b", "a #comment", "it's", "q''q", "say \"hi\"", "back\\slash\\",
        "{brace}", "[bracket]", "}, evil: injected", " lead and trail ", "-dash", "*star", "&anchor", "!tag",
        "12345678", "yes", "null", "true", "0o17", "中文密码 🎉 emoji", "", "tab\there", "line1\n\nline3",
    )

    // server 长度各不相同，password 会落在行内不同的列，或折行后落到续行行首，
    // 含空格的密码自身也会在不同位置被折开
    private val servers = (0 until 12).map { "n".repeat(it * 5) + "s.example.org" }

    private fun trickyConfig(pw: String): LinkedHashMap<String, Any?> {
        val config = LinkedHashMap<String, Any?>()
        config["mode"] = "rule"
        config["secret"] = pw
        config["listeners"] = listOf(
            LinkedHashMap<String, Any?>().apply {
                put("name", "in-0")
                put("type", "socks")
                put("listen", "127.0.0.1")
                put("port", 44829)
                put("proxy", "out-0")
                put("users", listOf(linkedMapOf("username" to pw, "password" to pw)))
            }
        )
        val proxies = ArrayList<Map<String, Any?>>()
        servers.forEachIndexed { i, server ->
            proxies += linkedMapOf(
                "name" to "out-$i", "type" to "anytls", "server" to server,
                "port" to 36239 + i, "password" to pw, "sni" to "sni-$i.example.org",
            )
        }
        // 带列表的映射是块写法：password 在行首
        proxies += linkedMapOf(
            "name" to "out-block", "type" to "anytls", "server" to "block.example.org",
            "port" to 443, "password" to pw, "alpn" to listOf("h2"), "sni" to "sni-block.example.org",
        )
        // 以 password 开头的块写法映射：序列项首键「- password:」
        proxies += linkedMapOf(
            "password" to pw, "name" to "out-first", "type" to "anytls", "server" to "first.example.org",
            "port" to 444, "alpn" to listOf("h2"), "sni" to "sni-first.example.org",
        )
        // 块写法里嵌套的行内映射
        proxies += linkedMapOf(
            "name" to "out-plugin", "type" to "ss", "server" to "plugin.example.org", "port" to 445,
            "plugin-opts" to linkedMapOf("mode" to "tls", "password" to pw, "host" to "host.example.org"),
            "sni" to "sni-plugin.example.org",
        )
        config["proxies"] = proxies
        return config
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `mihomo 刁钻密码在各种写法与折行位置下都被遮蔽`() {
        for (pw in trickyPasswords) {
            val config = trickyConfig(pw)
            val input = Yaml().dump(config)
            val redacted = Util.redactSecrets(input)
            val context = "密码 ${JavaUtil.gson.toJson(pw)}\n--- 输入\n$input\n--- 输出\n$redacted"

            if (pw.isNotEmpty()) assertFalse(context, redacted.contains(pw))
            for (piece in pw.split(Regex("""\s+"""))) {
                if (piece.length >= 4) assertFalse("片段 $piece\n$context", redacted.contains(piece))
            }

            // 遮蔽后的文本仍是合法 YAML（*** 换成普通词后），凭据都成了占位符，其它键原样可读
            val parsed = Yaml().load<Map<String, Any?>>(redacted.replace("***", "REDACTED"))
            assertEquals(context, "REDACTED", parsed["secret"])
            val listener = (parsed["listeners"] as List<Map<String, Any?>>).single()
            assertEquals(context, "in-0", listener["name"])
            assertEquals(context, "out-0", listener["proxy"])
            val user = (listener["users"] as List<Map<String, Any?>>).single()
            assertEquals(context, mapOf("username" to "REDACTED", "password" to "REDACTED"), user)

            val expected = config["proxies"] as List<Map<String, Any?>>
            val actual = parsed["proxies"] as List<Map<String, Any?>>
            assertEquals(context, expected.size, actual.size)
            for ((want, got) in expected.zip(actual)) {
                for ((k, v) in want) {
                    when (k) {
                        "password" -> assertEquals(context, "REDACTED", got[k])
                        "plugin-opts" -> assertEquals(
                            context,
                            (v as Map<String, Any?>) + ("password" to "REDACTED"),
                            got[k]
                        )
                        else -> assertEquals(context, v, got[k])
                    }
                }
                assertTrue(context, redacted.contains("name: ${want["name"]}"))
                assertTrue(context, redacted.contains("server: ${want["server"]}"))
                assertTrue(context, redacted.contains("sni: ${want["sni"]}"))
            }
        }
    }

    @Test
    fun `含空格的密码确实在输入里被折行`() {
        // 上面的用例要覆盖折行：含空格的密码在若干位置被 snakeyaml 拆到两行
        for (pw in trickyPasswords.filter { it.trim().contains(' ') && !it.contains('\n') }.take(2)) {
            val input = Yaml().dump(trickyConfig(pw))
            val places = 3 + servers.size + 3
            assertTrue(pw, Regex(Regex.escape(pw)).findAll(input).count() < places)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `块写法里上一行以逗号结尾时，含逗号的密码整段被遮蔽`() {
        // 块写法的普通标量可以以逗号结尾、也可以含「, 」，形状上与行内映射的折行相同
        val pw = "first part, second part"
        val input = Yaml().dump(
            linkedMapOf(
                "proxies" to listOf(
                    linkedMapOf(
                        "name" to "node,", "password" to pw, "alpn" to listOf("h2"), "sni" to "sni.example.org",
                    )
                )
            )
        )
        assertTrue(input, input.contains("- name: node,\n  password: $pw\n"))
        val redacted = Util.redactSecrets(input)
        assertFalse(redacted.contains("second"))
        assertFalse(redacted.contains("first"))
        val proxy = (Yaml().load<Map<String, Any?>>(redacted.replace("***", "REDACTED"))["proxies"]
            as List<Map<String, Any?>>).single()
        assertEquals(
            mapOf("name" to "node,", "password" to "REDACTED", "alpn" to listOf("h2"), "sni" to "sni.example.org"),
            proxy
        )
    }

    @Test
    fun `JSON 里 Xray accounts 的 user 与 pass 被遮蔽`() {
        val xray = linkedMapOf<String, Any?>(
            "inbounds" to listOf(
                linkedMapOf(
                    "tag" to "in-0", "listen" to "127.0.0.1", "port" to 36359, "protocol" to "socks",
                    "settings" to linkedMapOf(
                        "auth" to "password",
                        "accounts" to listOf(linkedMapOf("user" to defaultUser, "pass" to defaultPass)),
                        "udp" to true,
                    ),
                )
            ),
        )
        val redacted = Util.redactSecrets(JavaUtil.gson.toJson(xray))
        assertFalse(redacted.contains(defaultUser))
        assertFalse(redacted.contains(defaultPass))
        assertTrue(redacted.contains("\"user\": \"***\""))
        assertTrue(redacted.contains("\"pass\": \"***\""))
        assertTrue(redacted.contains("\"tag\": \"in-0\""))
        assertTrue(redacted.contains("\"port\": 36359"))
    }

    @Test
    fun `JSON 里 sing-box socks 出站的 username 与 password 被遮蔽`() {
        val input = JavaUtil.gson.toJson(
            linkedMapOf(
                "server" to "127.0.0.1", "server_port" to 36359,
                "username" to defaultUser, "password" to defaultPass, "type" to "socks",
            )
        )
        assertEquals(
            """
            {
              "server": "127.0.0.1",
              "server_port": 36359,
              "username": "***",
              "password": "***",
              "type": "socks"
            }
            """.trimIndent(),
            Util.redactSecrets(input)
        )
    }

    @Test
    fun `JSON 里名字相近的键与非字符串的值不受影响`() {
        val input = JavaUtil.gson.toJson(
            linkedMapOf(
                "users" to listOf(linkedMapOf("name" to "alice", "level" to 0)),
                "userLevel" to 0,
                "user_level" to "gold",
                "passphrase" to "keep-me",
                "user" to 7,
                "pass" to linkedMapOf("mode" to "plain"),
            )
        )
        assertEquals(input, Util.redactSecrets(input))
    }

    @Test
    fun `YAML 里名字相近的键不受影响`() {
        val input = Yaml().dump(
            linkedMapOf(
                "users-count" to 1, "userLevel" to "gold", "passphrase" to "keep-me",
                "list" to listOf(linkedMapOf("users" to "x", "pass-through" to "y", "superuser" to "z")),
            )
        )
        assertEquals(input, Util.redactSecrets(input))
    }

    @Test
    fun `普通日志里出现的键值文字只影响所在的那一行`() {
        val input = """
            2026/10/05 12:00:00 [Info] [Test] login failed, password: hunter2
            2026/10/05 12:00:01 [Info] [Test] next line stays: intact, really
            java.lang.IllegalStateException: boom
            ${'\t'}at moe.Foo.bar(Foo.kt:12)
            ${'\t'}at moe.Foo.baz(Foo.kt:34)
            2026/10/05 12:00:02 [Debug] [Test] payload {user: alice, count: 3}
            2026/10/05 12:00:03 [Debug] [Test] free text, user: bob and more words
            2026/10/05 12:00:04 [Debug] [Test] another line, token: abc, def
            password: hunter3
            2026/10/05 12:00:05 [Info] [Test] after the block, port: 443, sni: x.example.com
            2026/10/05 12:00:06 [Info] [Test] quote opened, secret: 'never closed
            2026/10/05 12:00:07 [Info] [Test] the end, {name: n}
        """.trimIndent()
        assertEquals(
            """
            2026/10/05 12:00:00 [Info] [Test] login failed, password: ***
            2026/10/05 12:00:01 [Info] [Test] next line stays: intact, really
            java.lang.IllegalStateException: boom
            ${'\t'}at moe.Foo.bar(Foo.kt:12)
            ${'\t'}at moe.Foo.baz(Foo.kt:34)
            2026/10/05 12:00:02 [Debug] [Test] payload {user: ***, count: 3}
            2026/10/05 12:00:03 [Debug] [Test] free text, user: ***
            2026/10/05 12:00:04 [Debug] [Test] another line, token: ***
            password: ***
            2026/10/05 12:00:05 [Info] [Test] after the block, port: 443, sni: x.example.com
            2026/10/05 12:00:06 [Info] [Test] quote opened, secret: ***
            2026/10/05 12:00:07 [Info] [Test] the end, {name: n}
            """.trimIndent(),
            Util.redactSecrets(input)
        )
    }

    @Test
    fun `写日志时脱敏过的配置在分享日志时再脱敏一遍不变`() {
        for (pw in trickyPasswords) {
            val once = Util.redactSecrets(Yaml().dump(trickyConfig(pw)))
            assertEquals(once, Util.redactSecrets(once))
        }
    }

    @Test
    fun `几 MB 的日志在限定时间内脱敏完`() {
        val sb = StringBuilder()
        val xray = JavaUtil.gson.toJson(
            linkedMapOf(
                "inbounds" to listOf(
                    linkedMapOf(
                        "tag" to "in-0", "protocol" to "socks",
                        "settings" to linkedMapOf("accounts" to listOf(linkedMapOf("user" to "u", "pass" to "p"))),
                    )
                )
            )
        )
        val yamls = trickyPasswords.map { Yaml().dump(trickyConfig(it)) }
        var round = 0
        while (sb.length < 2_000_000) {
            sb.append(yamls[round % yamls.size])
            sb.append(xray).append('\n')
            repeat(20) {
                sb.append("2026/10/05 12:00:").append(it).append(" [Info] [Test] connection ").append(round)
                    .append(", user: someone, password: secret-$it\n")
            }
            sb.append("java.lang.RuntimeException: x\n")
            repeat(10) { sb.append("\tat moe.Foo.bar(Foo.kt:").append(it).append(")\n") }
            // 没闭合的引号后面跟一长串缩进行；一整行重复很多次的「, user:」
            sb.append("x, password: '").append("q".repeat(2000)).append('\n')
            repeat(50) { sb.append("  ").append("y".repeat(100)).append('\n') }
            sb.append("{").append(", user: a".repeat(5000)).append("}\n")
            round++
        }
        val input = sb.toString()
        val started = System.nanoTime()
        val redacted = Util.redactSecrets(input)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("耗时 $elapsedMs ms", elapsedMs < 10_000)
        assertFalse(redacted.contains("secret-1"))
        assertFalse(redacted.contains("lorem ipsum"))
    }
}
