package io.nekohasekai.sagernet.uitest

import org.junit.Assert.assertTrue
import org.junit.Rule

/** 参数化用例的一个场景：[name] 做用例名，[run] 在自己的用例里跑场景并返回判定 */
class UiCase(val name: String, val run: (UiScenarios) -> Check) {
    override fun toString() = name
}

/** 行为用例的公共基类：[scenarios] 绑定本用例的 [UiTestRule] */
abstract class UiBehaviorBase : UiTestBase() {
    protected val scenarios by lazy { UiScenarios(ui) }

    protected fun assertSatisfied(case: UiCase) {
        val check = case.run(scenarios)
        assertTrue("${case.name}：$check", check.ok)
    }
}
