package io.nekohasekai.sagernet.uitest

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * U4b b2「页面内的读取与界面刷新跟着页面作用域」的验收：行内协程、整表重读、读库后弹框在页面销毁时随之取消，
 * 销毁之后不再回填 View；没有分组的列表页走到 onResume 不崩。不截图，所有平台都跑。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class PageScopeTest(private val case: UiCase) : UiBehaviorBase() {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            UiCase("config-row-load-cancelled-on-destroy") { it.configRowLoadCancelledOnDestroy() },
            UiCase("group-row-count-cancelled-on-destroy") { it.groupRowCountCancelledOnDestroy() },
            UiCase("route-reload-cancelled-on-destroy") { it.routeReloadCancelledOnDestroy() },
            UiCase("pager-reload-after-destroy") { it.pagerReloadAfterDestroyIsHarmless() },
            UiCase("profile-list-resumes-without-group") { it.profileListResumesWithoutGroup() },
            UiCase("delete-unavailable-confirm-shown") { it.deleteUnavailableConfirm(destroyFirst = false) },
            UiCase("delete-unavailable-confirm-skipped-after-destroy") { it.deleteUnavailableConfirm(destroyFirst = true) },
            UiCase("group-test-entry-resets-on-destroy") { it.groupTestEntryResetsOnDestroy() },
        ).map { arrayOf(it) }
    }

    @Test
    fun scoped() = assertSatisfied(case)
}
