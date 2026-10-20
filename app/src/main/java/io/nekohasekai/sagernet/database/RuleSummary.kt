package io.nekohasekai.sagernet.database

import androidx.annotation.StringRes
import io.nekohasekai.sagernet.R

/**
 * 路由规则卡片上的匹配摘要：纯数据，不碰 View / Context，可 JVM 测试。
 * 标签用字符串资源 id（绑定时才解析成文字），值保持各字段原有的写法。
 * [fields] 最多 [MAX_FIELDS] 项，其余的只记个数 [overflow]。
 */
data class RuleSummary(
    val fields: List<Field>,
    val overflow: Int,
) {

    // more：值是列表时被截掉的条目数（0 = 没有截断），由绑定处渲染成「+N」
    data class Field(@StringRes val label: Int, val value: String, val more: Int = 0)

    companion object {
        const val MAX_FIELDS = 3
        const val MAX_ENTRIES = 3

        fun of(rule: RuleEntity): RuleSummary {
            val all = ArrayList<Field>()
            // 自定义配置没有可展示的值，只显示标签
            if (rule.config.isNotBlank()) all += Field(R.string.custom_config, "")
            add(all, R.string.route_field_domain, rule.domains)
            add(all, R.string.route_field_dst_ip, rule.ip)
            add(all, R.string.route_field_src_ip, rule.source)
            add(all, R.string.route_field_src_port, rule.sourcePort)
            add(all, R.string.route_field_dst_port, rule.port)
            add(all, R.string.network, rule.network)
            add(all, R.string.protocol, rule.protocol)
            // 值只放个数，「N 个应用」的单位由标签「应用」承担
            if (rule.packages.isNotEmpty()) all += Field(R.string.apps, rule.packages.size.toString())
            return RuleSummary(all.take(MAX_FIELDS), (all.size - MAX_FIELDS).coerceAtLeast(0))
        }

        private fun add(out: MutableList<Field>, @StringRes label: Int, raw: String) {
            if (raw.isBlank()) return
            // 多行的值（域名、IP 列表按行存）只取前 MAX_ENTRIES 条压成一行，余下的只记个数，
            // 导入的长名单不会把卡片撑得很高
            val entries = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
            out += Field(label, entries.take(MAX_ENTRIES).joinToString(", "), (entries.size - MAX_ENTRIES).coerceAtLeast(0))
        }
    }
}
