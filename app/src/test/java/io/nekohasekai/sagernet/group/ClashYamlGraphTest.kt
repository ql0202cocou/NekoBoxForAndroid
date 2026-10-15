package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.yaml.snakeyaml.error.YAMLException

// loadClashYaml 的对象图检查：成环与别名展开过大的结构在载入时拒绝，SnakeYAML 自身的限制都放行它们
class ClashYamlGraphTest {

    private fun assertRejected(yaml: String) {
        // 先确认 SnakeYAML 本身照常载入，拒绝来自 checkClashYamlGraph
        clashYaml().load<Any?>(yaml)
        assertThrows(YAMLException::class.java) { loadClashYaml(yaml) }
    }

    @Test
    fun `别名指回祖先形成的环被拒绝`() {
        // 审计复现样例：name 在单节点 try 外取值，server 在 clashKey 里取值，两处都会栈溢出
        assertRejected("proxies:\n  - &node {name: [*node], type: socks5, server: 192.0.2.1, port: 1080}\n")
        assertRejected("proxies:\n  - &node {name: cycle, type: socks5, server: [*node], port: 1080}\n")
        // proxies 以外的段（dns 由 doUpdate 读取）同样拒绝
        assertRejected(
            "dns: &d {nameserver: [*d]}\nproxies:\n  - {name: a, type: socks5, server: 192.0.2.1, port: 1080}\n"
        )
    }

    @Test
    fun `指向字符串的别名不受 SnakeYAML 限制，反复引用长字符串被拒绝`() {
        val refs = List(1000) { "*s" }.joinToString(", ")
        assertRejected("s: &s ${"x".repeat(10_000)}\njunk: [$refs]\nproxies: []\n")
    }

    @Test
    fun `50 个集合别名以内层层嵌套，展开量过大被拒绝`() {
        // 15 层、每层引用上一层 3 次，共 45 个集合别名，展开后约 4300 万个元素
        val levels = buildString {
            append("l0: &l0 [x, x, x]\n")
            for (i in 1..15) append("l$i: &l$i [*l${i - 1}, *l${i - 1}, *l${i - 1}]\n")
        }
        assertRejected(levels + "proxies: []\n")
    }

    @Test
    fun `正常的共享别名与合并键照常导入`() {
        val result = parseClash(
            loadClashYaml(
                """
                common: &common {type: vmess, port: 443, uuid: 00000000-0000-0000-0000-000000000004, network: ws}
                ws: &ws {path: /shared}
                proxies:
                  - {<<: *common, name: a, server: a.example.com, ws-opts: *ws}
                  - {<<: *common, name: b, server: b.example.com, ws-opts: *ws}
                """.trimIndent()
            )
        )
        assertEquals(listOf("a", "b"), result.beans.map { it.name })
        assertEquals(listOf("/shared", "/shared"), result.beans.map { (it as VMessBean).path })
    }

    @Test
    fun `不用别名的大文档不受展开量限制`() {
        val yaml = buildString {
            append("proxies:\n")
            repeat(20_000) {
                append("  - {name: n$it, type: socks5, server: 192.0.2.1, port: 1080, username: u, password: p}\n")
            }
        }
        assertEquals(20_000, (loadClashYaml(yaml)["proxies"] as List<*>).size)
    }
}
