package io.nekohasekai.sagernet.uitest

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * U4c c1 撤销入口：撤销窗口内别处改列表（走统一入口 mutate）后再点撤销不崩、内容一致；
 * 连删几行后撤销顺序不变；列表变短时撤销的插入下标被夹住。不截图，所有平台都跑。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class UndoEntryTest(private val name: String, private val run: (UndoScenarios) -> Check) : UiTestBase() {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf<Pair<String, (UndoScenarios) -> Check>>(
            "route-undo-after-add" to { it.routeUndoAfterAdd() },
            "route-undo-after-update" to { it.routeUndoAfterUpdate() },
            "route-undo-several-deletions" to { it.routeUndoSeveralDeletions() },
            "route-undo-into-shorter-list" to { it.routeUndoIntoShorterList() },
            "group-undo-after-add" to { it.groupUndoAfterAdd() },
            "group-undo-several-deletions" to { it.groupUndoSeveralDeletions() },
            "group-undo-into-shorter-list" to { it.groupUndoIntoShorterList() },
        ).map { arrayOf(it.first, it.second) }
    }

    @Test
    fun undo() {
        val check = run(UndoScenarios(ui))
        assertTrue("$name：$check", check.ok)
    }
}
