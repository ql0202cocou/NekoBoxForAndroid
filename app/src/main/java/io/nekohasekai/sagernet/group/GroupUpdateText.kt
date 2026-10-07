package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.ClashImportSummary

// 订阅更新「Diff」对话框的正文：新增 / 更新 / 删除 / 重复四类名单，末尾接 Clash 导入汇总的文本块。
// 纯函数，文案由 getString 取（运行时即 Context.getString(id, arg)）。没有任何内容时返回空串。
// 每类名单只列前 GROUP_DIFF_LINES 条，超出的折成一行总数：数万节点的订阅首更全量拼进单个对话框
// 会让主线程渲染卡死。先 take 再格式化，不为只显示几十条而把全量名单都格式化一遍
internal const val GROUP_DIFF_LINES = 50

internal fun groupUpdateDialogText(
    added: List<String>,
    updated: Map<String, String>,
    deleted: List<String>,
    duplicate: List<String>,
    importSummary: ClashImportSummary?,
    getString: (resId: Int, arg: Any) -> String,
): String {
    fun joinNames(total: Int, shown: List<String>): String {
        val lines = if (total > shown.size) {
            shown + getString(R.string.group_diff_more, total)
        } else shown
        return lines.joinToString("\n", postfix = "\n\n")
    }

    var status = ""
    if (added.isNotEmpty()) {
        status += getString(R.string.group_added, joinNames(added.size, added.take(GROUP_DIFF_LINES)))
    }
    if (updated.isNotEmpty()) {
        status += getString(R.string.group_changed,
            joinNames(updated.size, updated.entries.take(GROUP_DIFF_LINES).map {
                if (it.key == it.value) it.key else "${it.key} => ${it.value}"
            }))
    }
    if (deleted.isNotEmpty()) {
        status += getString(R.string.group_deleted, joinNames(deleted.size, deleted.take(GROUP_DIFF_LINES)))
    }
    if (duplicate.isNotEmpty()) {
        status += getString(R.string.group_duplicate, joinNames(duplicate.size, duplicate.take(GROUP_DIFF_LINES)))
    }
    if (importSummary != null) status += importSummary.uiText(getString)
    return status.trim()
}
