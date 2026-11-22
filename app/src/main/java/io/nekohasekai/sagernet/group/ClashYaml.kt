package io.nekohasekai.sagernet.group

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.composer.Composer
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.Mark
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.events.CollectionEndEvent
import org.yaml.snakeyaml.events.CollectionStartEvent
import org.yaml.snakeyaml.events.Event
import org.yaml.snakeyaml.events.MappingStartEvent
import org.yaml.snakeyaml.events.NodeEvent
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.events.SequenceStartEvent
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeId
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.parser.Parser
import org.yaml.snakeyaml.parser.ParserImpl
import org.yaml.snakeyaml.reader.StreamReader
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import java.util.Collections
import java.util.IdentityHashMap

// Mark 原本会保留整个扫描窗口。错误诊断只需行列，也避免保留带凭据的输入片段。
private val emptyMarkBuffer = IntArray(0)

private fun parserFor(text: String, options: LoaderOptions): Parser {
    val reader = object : StreamReader(text) {
        override fun getMark() = Mark("subscription", index, line, column, emptyMarkBuffer, 0)
    }
    return ParserImpl(reader, options)
}

// 常见的大订阅由根映射和若干列表组成。逐项构造列表，及时释放每项的语法树，同时保留
// DNS 等其它顶层字段。需要完整文档语义的结构仍由原构造器处理。
private object WholeDocumentRequired : RuntimeException(null, null, false, false)

private fun loadLargeMapping(text: String, options: LoaderOptions, resolver: Resolver): Map<String, Any?>? {
    if (text.length < 1024 * 1024) return null
    val source = parserFor(text, options)
    val parser = object : Parser by source {
        var depth = 0
        override fun getEvent(): Event = source.event.also { event ->
            // 锚点可跨项共享。深度接近整文档上限时也转回，让 Composer 精确执行原限制。
            if (event is NodeEvent && event.anchor != null) throw WholeDocumentRequired
            if (event is CollectionStartEvent && ++depth >= options.nestingDepthLimit) throw WholeDocumentRequired
            if (event is CollectionEndEvent) depth--
        }
    }
    try {
        if (parser.event.eventId != Event.ID.StreamStart || parser.event.eventId != Event.ID.DocumentStart) return null
        val root = parser.event as? MappingStartEvent ?: return null
        if (root.tag != null) return null
        val composer = object : Composer(parser, resolver, options) {
            fun value(): Node = composeValueNode(null)
        }
        val builder = object : SafeConstructor(options) {
            fun next(): Any? = constructDocument(composer.value())
        }
        val result = LinkedHashMap<String, Any?>()
        while (!parser.checkEvent(Event.ID.MappingEnd)) {
            val key = parser.event as? ScalarEvent ?: return null
            if (key.tag != null || resolver.resolve(NodeId.scalar, key.value, key.implicit.canOmitTagInPlainScalar()) != Tag.STR ||
                result.containsKey(key.value)) return null
            val sequence = parser.peekEvent() as? SequenceStartEvent
            result[key.value] = if (sequence != null && sequence.tag == null) {
                parser.event
                val values = ArrayList<Any?>()
                while (!parser.checkEvent(Event.ID.SequenceEnd)) values += builder.next()
                parser.event
                values
            } else builder.next()
        }
        parser.event
        if (parser.event.eventId != Event.ID.DocumentEnd || !parser.checkEvent(Event.ID.StreamEnd)) return null
        return result
    } catch (_: WholeDocumentRequired) {
        return null
    }
}

fun clashYaml(): Yaml {
    // 数字、布尔和日期形状的凭据保留原文，由具体字段转换；保留 null、合并标签及加载上限。
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
    return object : Yaml(SafeConstructor(loader), Representer(dumper), dumper, loader, resolver) {
        @Suppress("UNCHECKED_CAST")
        override fun <T> load(yaml: String): T {
            loadLargeMapping(yaml, loader, resolver)?.let { return it as T }
            constructor.setComposer(Composer(parserFor(yaml, loader), resolver, loader))
            return constructor.getSingleData(Any::class.java) as T
        }
    }
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
