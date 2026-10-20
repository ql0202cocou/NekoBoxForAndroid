package io.nekohasekai.sagernet.uitest

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * 编辑器页面级状态在 ViewModel 里（U4d d5）：旋转后不重读库。每个场景都是「载入之后在库里改掉对应的行，再旋转，
 * 编辑器里看到的仍是载入时的那一份」；链式代理编辑器的待处理替换位置另验证进程重建后经 SavedStateHandle 带回。
 * 不截图，所有平台都跑。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class EditorRotationTest(private val case: UiCase) : UiBehaviorBase() {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            UiCase("profile-keeps-loaded-entity") { it.profileEditorRotationKeepsLoadedEntity() },
            UiCase("profile-keeps-move-menu") { it.profileEditorRotationKeepsMoveMenu() },
            UiCase("group-rotation-before-commit-does-not-reload") { it.groupEditorRotationBeforeCommitDoesNotReload() },
            UiCase("route-rotation-before-commit-does-not-reload") { it.routeEditorRotationBeforeCommitDoesNotReload() },
            UiCase("chain-keeps-members") { it.chainEditorRotationKeepsMembers() },
            UiCase("chain-replacing-survives-process-recreation") { it.chainEditorReplacingSurvivesProcessRecreation() },
        ).map { arrayOf(it) }
    }

    @Test
    fun behaves() = assertSatisfied(case)
}
