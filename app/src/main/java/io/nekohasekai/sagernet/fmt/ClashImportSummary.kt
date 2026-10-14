package io.nekohasekai.sagernet.fmt

// 一次 Clash 导入的汇总：从各条目的结果算出计数，生成一段英文日志。纯数据，不碰界面
// （界面只显示订阅更新的节点变化，不显示导入汇总）。
// 各条目的结果按四类计数：完整导入、有损导入、类型不支持、解析失败（后三类分组计数，见下）。
// 字段在本应用里本来就没有对应行为的（无影响忽略）只单列，不算「有损」，也不让汇总变成非空。
// 统计只说明订阅里出现了哪些没保留的字段，不代表用户需要它们
class ClashImportSummary private constructor(
    val total: Int,
    val complete: Int,
    val lossy: Int,
    val unknownType: Int,
    val failed: Int,
    // 分组计数，键已按显示规则处理：type；「type: 原因」；「type.字段路径: 原因」
    val unknownTypes: Map<String, Int>,
    val failures: Map<String, Int>,
    val lossyFields: Map<String, Int>,
    val noEffectFields: Map<String, Int>,
) {
    // 没有被跳过或有损导入的节点时为真：只有无影响的忽略不用提示
    val isEmpty: Boolean get() = unknownType == 0 && failed == 0 && lossy == 0

    // 日志：英文，只有计数、type、字段路径与原因，不含节点名与任何字段值以外的内容
    fun logText(): String {
        if (isEmpty) return ""
        return buildString {
            append("Clash import: $total entries, $complete complete, $lossy lossy, ")
            append("$unknownType unsupported type, $failed failed")
            appendGroup("unsupported types", unknownTypes)
            appendGroup("failures", failures)
            appendGroup("lossy fields", lossyFields)
            appendGroup("no-effect fields", noEffectFields)
        }
    }

    private fun StringBuilder.appendGroup(title: String, counts: Map<String, Int>) {
        if (counts.isEmpty()) return
        val sorted = counts.sorted()
        append("\n  ").append(title).append(": ")
        append(sorted.take(LOG_ITEMS).joinToString("; ") { (key, count) -> "$key ($count)" })
        if (sorted.size > LOG_ITEMS) append("; and ${sorted.size - LOG_ITEMS} more")
    }

    // 一个节点都没导入时的异常消息：英文，只有计数
    fun failureMessage(): String =
        "No proxies imported from the Clash subscription: $total entries, " +
                "$unknownType unsupported type, $failed failed to parse"

    companion object {
        const val LOG_ITEMS = 20

        private fun Map<String, Int>.sorted() =
            entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key to it.value }

        fun of(nodes: List<ClashNodeResult>): ClashImportSummary {
            var complete = 0
            var lossy = 0
            var unknownType = 0
            var failed = 0
            val unknownTypes = LinkedHashMap<String, Int>()
            val failures = LinkedHashMap<String, Int>()
            val lossyFields = LinkedHashMap<String, Int>()
            val noEffectFields = LinkedHashMap<String, Int>()
            fun MutableMap<String, Int>.count(key: String) = merge(key, 1, Int::plus)

            for (node in nodes) when (node) {
                is ClashNodeResult.UnknownType -> {
                    unknownType++
                    unknownTypes.count(node.type)
                }

                is ClashNodeResult.Failed -> {
                    failed++
                    failures.count("${node.type}: ${node.description}")
                }

                is ClashNodeResult.Imported -> {
                    // 同一节点的同一组只计一次
                    val lossyRecords = node.fields.filter { it.lossy }
                    lossyRecords.map { "${node.type}.${it.path}: ${it.reason?.text}" }.toSet().forEach { lossyFields.count(it) }
                    node.fields.filter { it.result == ClashFieldResult.IGNORED && !it.lossy }
                        .map { "${node.type}.${it.path}: ${it.reason?.text}" }.toSet().forEach { noEffectFields.count(it) }
                    if (lossyRecords.isEmpty()) complete++ else lossy++
                }
            }
            return ClashImportSummary(
                nodes.size, complete, lossy, unknownType, failed,
                unknownTypes, failures, lossyFields, noEffectFields,
            )
        }
    }
}

// Clash 订阅认准了，但一个节点都没导入（全部类型不支持或解析失败，或 proxies 为空）：带着汇总穿过
// parseRaw 的各层（不当成「这一策略没认出东西」吞掉）与 ImportBatch，交给调用方。
// 消息只有计数，订阅更新失败时由 snackbar 显示
class ClashImportException(val summary: ClashImportSummary) : IllegalStateException(summary.failureMessage())
