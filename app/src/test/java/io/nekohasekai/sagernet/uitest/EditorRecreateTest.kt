package io.nekohasekai.sagernet.uitest

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * 节点编辑器的重建与会话：改字段后重建 / 旋转不丢；模拟进程重建（存实例状态 → 销毁 → 清空内存里的编辑缓存 →
 * 用实例状态新建）按 EditorSession 的规则表现——缓存丢了就从实体重新初始化并重新认领，被别的编辑器接管就关闭。
 * 子编辑器开着时的进程重建目前不满足验收，见 [KnownUiGaps]。不截图，所有平台都跑。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class EditorRecreateTest(private val case: UiCase) : UiBehaviorBase() {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = buildList<UiCase> {
            for (how in Change.entries) {
                add(UiCase("field-edit-${how.name.lowercase()}") { it.editorFieldEditSurvives(how) })
            }
            add(UiCase("process-recreation-reinitializes") { it.editorProcessRecreationReinitializes() })
            add(UiCase("process-recreation-taken-over") { it.editorProcessRecreationTakenOver() })
        }.map { arrayOf(it) }
    }

    @Test
    fun behaves() = assertSatisfied(case)
}
