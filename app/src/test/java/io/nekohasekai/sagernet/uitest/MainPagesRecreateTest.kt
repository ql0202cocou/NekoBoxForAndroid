package io.nekohasekai.sagernet.uitest

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * 主界面四页的重建 / 旋转：页面类型、Dock 选中项、标题不变；配置页的选中分组与列表位置不变；
 * 设置分类页重建 / 旋转后仍停在该页；分类页返回后一级页的滚动位置恢复。
 * 不截图，所有平台都跑。目前不满足验收的项（配置页列表位置、旋转后的设置一级页滚动）登记在 [KnownUiGaps]，不在这里。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class MainPagesRecreateTest(private val case: UiCase) : UiBehaviorBase() {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = buildList<UiCase> {
            for (page in Page.entries) for (how in Change.entries) {
                add(UiCase("${page.name.lowercase()}-${how.name.lowercase()}") { it.mainPageSurvives(page, how) })
            }
            for (how in Change.entries) {
                add(UiCase("config-selected-group-${how.name.lowercase()}") { it.configSelectedGroupSurvives(how) })
                add(UiCase("settings-section-${how.name.lowercase()}") { it.settingsSectionSurvives(how) })
            }
            add(UiCase("settings-hub-scroll-kept-after-section") { it.settingsHubScrollKept(rotateInside = false) })
        }.map { arrayOf(it) }
    }

    @Test
    fun survives() = assertSatisfied(case)
}
