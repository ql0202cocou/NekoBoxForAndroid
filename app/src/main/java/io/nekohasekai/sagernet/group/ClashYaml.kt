package io.nekohasekai.sagernet.group

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.NodeId
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import java.util.Collections
import java.util.IdentityHashMap

fun clashYaml(): Yaml {
    // Preserve credential text (0123, true, dates, etc.). Field parsers own
    // numeric/boolean conversion. Keep null and merge tags and all load limits.
    val loader = LoaderOptions().apply { codePointLimit = 32 * 1024 * 1024 }
    val dumper = DumperOptions()
    val resolver = object : Resolver() {
        override fun resolve(kind: NodeId, value: String?, implicit: Boolean): Tag {
            val tag = super.resolve(kind, value, implicit)
            return if (kind == NodeId.scalar && (tag == Tag.INT || tag == Tag.FLOAT || tag == Tag.BOOL || tag == Tag.TIMESTAMP)) {
                Tag.STR
            } else tag
        }
    }
    return Yaml(SafeConstructor(loader), Representer(dumper), dumper, loader, resolver)
}

// 不带别名的文档，展开后的内容量不会超过原文长度太多；合法订阅用别名 / 合并键（<<: *common）
// 复用公共字段，放大也有限。超过「原文 4 倍 + 800 万」只能是刻意构造的别名炸弹
private const val CLASH_YAML_EXPANSION_FACTOR = 4L
private const val CLASH_YAML_EXPANSION_SLACK = 8_000_000L

// 载入后检查对象图，两种结构按无效 YAML 拒绝（YAMLException）：
// - 环：别名指回自己的祖先（name: [*node]），toString / hashCode 无限递归抛 StackOverflowError，
//   它是 Error，漏过各导入入口的 Exception 捕获
// - 展开量过大：SnakeYAML 只限制指向集合的别名个数（50），指向字符串的不限；层层嵌套或反复引用
//   长字符串，展开后可达上亿字符，对它 toString 就会 OutOfMemoryError
// 展开量按字符串长度计（容器和其它标量各计 1），近似 toString 的输出长度。按对象身份记录：
// 容器的 hashCode / equals 会遍历内容，遇环同样递归。用显式栈遍历，深层结构不会让检查本身栈溢出；
// 算完的容器记下展开量直接复用，耗时与原文长度成正比
fun checkClashYamlGraph(root: Any?, textLength: Int) {
    val limit = CLASH_YAML_EXPANSION_FACTOR * textLength + CLASH_YAML_EXPANSION_SLACK
    val done = IdentityHashMap<Any, Long>()
    val onPath = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    class Frame(val node: Any, val children: Iterator<Any?>) {
        var size = 1L
    }

    val stack = ArrayList<Frame>()
    fun add(frame: Frame, size: Long) {
        frame.size += size
        if (frame.size > limit) throw YAMLException("Excessive aliasing in the Clash YAML")
    }

    // 标量直接返回展开量；容器算过的返回记下的值，没算过的入栈、返回 null
    fun enter(node: Any?): Long? {
        if (node == null) return 1
        val children = when (node) {
            is Map<*, *> -> node.entries.asSequence().flatMap { sequenceOf(it.key, it.value) }.iterator()
            is Collection<*> -> node.iterator()
            is Array<*> -> node.iterator()
            is String -> return maxOf(node.length, 1).toLong()
            else -> return 1
        }
        done[node]?.let { return it }
        if (!onPath.add(node)) throw YAMLException("Recursive structure in the Clash YAML")
        stack += Frame(node, children)
        return null
    }

    enter(root)
    while (stack.isNotEmpty()) {
        val top = stack.last()
        if (top.children.hasNext()) {
            enter(top.children.next())?.let { add(top, it) }
            continue
        }
        stack.removeAt(stack.lastIndex)
        onPath.remove(top.node)
        done[top.node] = top.size
        stack.lastOrNull()?.let { add(it, top.size) }
    }
}

// Retain YAML 1.1 boolean spellings, but only in fields that require booleans.
fun Any?.clashBoolean(): Boolean = when (this?.toString()?.lowercase()) {
    "true", "yes", "on" -> true
    else -> false
}
