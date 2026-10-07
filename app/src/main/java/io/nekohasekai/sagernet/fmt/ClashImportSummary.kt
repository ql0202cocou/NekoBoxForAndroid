package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.R

// 一次 Clash 导入的汇总：从各条目的结果算出计数，生成一段英文日志与给界面的文本块。纯数据，
// 不碰界面；文案由调用方传入的 getString 取（与 configBuildNotices 同一做法）。
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
    // 给界面的逐条明细，每类只留前 UI_LINES 条；带节点名，不进日志
    private val unknownTypeLines: List<String>,
    private val failedLines: List<String>,
    private val lossyLines: List<String>,
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

    // 界面文本块：分节，每节最多 UI_LINES 行，超出的折成一行总数（同订阅更新的 Diff 对话框）。
    // getString 按资源 id 取带一个参数的字符串（运行时即 Context.getString(id, arg)）
    fun uiText(getString: (resId: Int, arg: Any) -> String): String {
        if (isEmpty) return ""
        fun section(resId: Int, total: Int, lines: List<String>): String {
            if (total == 0) return ""
            val shown = if (total > lines.size) lines + getString(R.string.group_diff_more, total) else lines
            return getString(resId, shown.joinToString("\n", postfix = "\n\n"))
        }
        val noEffect = noEffectFields.sorted()
        return section(R.string.clash_import_unsupported_type, unknownType, unknownTypeLines) +
                section(R.string.clash_import_failed, failed, failedLines) +
                section(R.string.clash_import_lossy, lossy, lossyLines) +
                section(
                    R.string.clash_import_no_effect, noEffect.size,
                    noEffect.take(UI_LINES).map { (key, count) -> "$key ($count)" },
                )
    }

    companion object {
        const val LOG_ITEMS = 20
        const val UI_LINES = 50
        const val UI_FIELDS = 5

        private fun Map<String, Int>.sorted() =
            entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key to it.value }

        private fun ClashFieldRecord.describe() = buildString {
            append(path)
            if (shownValue != null) append(" = ").append(shownValue)
            reason?.let { append(": ").append(it.text) }
        }

        private fun ClashNodeResult.label() = "${name?.takeIf { it.isNotBlank() } ?: "#${index + 1}"} ($type)"

        fun of(nodes: List<ClashNodeResult>): ClashImportSummary {
            var complete = 0
            var lossy = 0
            var unknownType = 0
            var failed = 0
            val unknownTypes = LinkedHashMap<String, Int>()
            val failures = LinkedHashMap<String, Int>()
            val lossyFields = LinkedHashMap<String, Int>()
            val noEffectFields = LinkedHashMap<String, Int>()
            val unknownTypeLines = ArrayList<String>()
            val failedLines = ArrayList<String>()
            val lossyLines = ArrayList<String>()
            fun MutableMap<String, Int>.count(key: String) = merge(key, 1, Int::plus)

            for (node in nodes) when (node) {
                is ClashNodeResult.UnknownType -> {
                    unknownType++
                    unknownTypes.count(node.type)
                    if (unknownTypeLines.size < UI_LINES) unknownTypeLines += node.label()
                }

                is ClashNodeResult.Failed -> {
                    failed++
                    failures.count("${node.type}: ${node.description}")
                    if (failedLines.size < UI_LINES) failedLines += "${node.label()}: ${node.description}"
                }

                is ClashNodeResult.Imported -> {
                    // 同一节点的同一组只计一次
                    val lossyRecords = node.fields.filter { it.lossy }
                    lossyRecords.map { "${node.type}.${it.path}: ${it.reason?.text}" }.toSet().forEach { lossyFields.count(it) }
                    node.fields.filter { it.result == ClashFieldResult.IGNORED && !it.lossy }
                        .map { "${node.type}.${it.path}: ${it.reason?.text}" }.toSet().forEach { noEffectFields.count(it) }
                    if (lossyRecords.isEmpty()) {
                        complete++
                    } else {
                        lossy++
                        if (lossyLines.size < UI_LINES) {
                            // 每个节点最多列 UI_FIELDS 个字段，其余折成一个计数（英文，与行内的原因一致），单行不会无限长
                            val more = lossyRecords.size - UI_FIELDS
                            lossyLines += "${node.label()}: " +
                                    lossyRecords.take(UI_FIELDS).joinToString("; ") { it.describe() } +
                                    if (more > 0) "; … and $more more" else ""
                        }
                    }
                }
            }
            return ClashImportSummary(
                nodes.size, complete, lossy, unknownType, failed,
                unknownTypes, failures, lossyFields, noEffectFields,
                unknownTypeLines, failedLines, lossyLines,
            )
        }
    }
}

// Clash 订阅认准了，但一个节点都没导入（全部类型不支持或解析失败，或 proxies 为空）：带着汇总穿过
// parseRaw 的各层（不当成「这一策略没认出东西」吞掉）与 ImportBatch，交给更新失败的提示
class ClashImportException(val summary: ClashImportSummary) : IllegalStateException(summary.failureMessage())
