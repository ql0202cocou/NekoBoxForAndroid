package io.nekohasekai.sagernet.uitest

/**
 * 已知的、现在就不满足验收的 UI 行为。每一项都写明出处（doc/agent/open-issues.md 的条目）和验收内容，
 * [probe] 就是满足验收时才返回 ok 的场景判定；KnownUiGapsTest 断言它们「仍然不满足」。
 *
 * 规则（与 golden 基线的 RELAXATIONS 同理：名单上的放宽没用到也失败）：
 * - 修好一项后，对应的探针开始满足验收，KnownUiGapsTest 随之失败并提示把这一项从这里删掉，场景改挂到
 *   相应的 *Test 里成为普通用例；
 * - 不许为了让测试变绿而新增条目：新增必须有 open-issues 出处或写明实测发现的依据；
 * - U4d 结束时名单应为空，或逐项写明留下的理由。
 */
class KnownUiGap(
    val id: String,
    /** open-issues.md 里的出处（条目标题或位置） */
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
            id = "enter-animation-replays-on-rotation",
            source = "open-issues.md「U2 批 7 的边界与实现时发现」：M5 入场只在冷启动与用户切分组时播；旋转后视图重建会再播一次" +
                "（enterTransition 不随重建保留）",
            acceptance = "旋转后配置页的节点列表不再播入场动画",
        ) { it.rotationDoesNotReplayEnterAnimation() },

        KnownUiGap(
            id = "config-list-position-lost-on-recreate",
            source = "Robolectric 实测发现（open-issues.md 没有记录）：配置页滚到中间后重建（recreate），重建后第一个可见项回到 0。" +
                "只在 Robolectric 里复现，没有在真机 / 模拟器上核对；若真机上位置能恢复，说明是节点异步载入与 " +
                "LayoutManager 状态恢复的时序在 Robolectric 里不同，这一项应删掉",
            acceptance = "配置页节点列表滚到中间后重建，第一个可见项不变",
        ) { it.configListPositionSurvives(Change.RECREATE) },

        KnownUiGap(
            id = "config-list-position-lost-on-rotation",
            source = "Robolectric 实测发现（open-issues.md 没有记录），与重建同一原因，见上一项",
            acceptance = "配置页节点列表滚到中间后旋转，第一个可见项不变",
        ) { it.configListPositionSurvives(Change.ROTATE) },

        KnownUiGap(
            id = "child-editor-process-recreation-overwrites-saved-content",
            source = "open-issues.md「2026-10-05 审计发现」：ConfigEditActivity.kt:197-207 子编辑器已保存内容在" +
                "「子编辑器前台时进程死亡 → 父编辑器重建」场景被静默覆盖（QUALITY-008 也提到子编辑器的进程重建没被覆盖）",
            acceptance = "子编辑器开着时进程重建：缓存里子编辑器已保存的内容仍在，子编辑器重建后显示的也是它",
        ) { it.childEditorProcessRecreationKeepsSavedContent() },
    )
}
