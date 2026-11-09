package io.nekohasekai.sagernet.uitest

/**
 * 已知的、现在就不满足验收的 UI 行为。每一项就地写明审计或实测依据和验收内容，
 * [probe] 就是满足验收时才返回 ok 的场景判定；KnownUiGapsTest 断言它们「仍然不满足」。
 *
 * 规则（与 golden 基线的 RELAXATIONS 同理：名单上的放宽没用到也失败）：
 * - 修好一项后，对应的探针开始满足验收，KnownUiGapsTest 随之失败并提示把这一项从这里删掉，场景改挂到
 *   相应的 *Test 里成为普通用例；
 * - 不许为了让测试变绿而新增条目：新增必须写明审计或实测发现的依据；
 * - 留下的条目须逐项写明理由。
 */
class KnownUiGap(
    val id: String,
    /** 审计或实测依据，以及保留该边界的理由 */
    val source: String,
    /** 验收内容：修好后应当成立的事 */
    val acceptance: String,
    val probe: (UiScenarios) -> Check,
) {
    override fun toString() = id
}

object KnownUiGaps {

    val all: List<KnownUiGap> = listOf(
        KnownUiGap(
            id = "child-editor-process-recreation-overwrites-saved-content",
            source = "2026-10-05 审计发现：ConfigEditActivity 在「子编辑器前台时进程死亡 → 父编辑器重建」" +
                "场景会覆盖子编辑器已保存的内容。2026-10-20 决定不落盘含凭据的编辑缓存，保留该已知边界。",
            acceptance = "子编辑器开着时进程重建：缓存里子编辑器已保存的内容仍在，子编辑器重建后显示的也是它",
        ) { it.childEditorProcessRecreationKeepsSavedContent() },
    )
}
