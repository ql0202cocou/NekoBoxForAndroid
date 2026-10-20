package io.nekohasekai.sagernet.uitest

import org.junit.Test

/**
 * M5 入场动画：只有这个测试打开动画（其余用例动画时长缩放为 0）。冷启动播入场；
 * 旋转后不重播由 ConfigPageStateTest 覆盖（d2 起满足）。不截图，所有平台都跑。
 */
class EnterAnimationTest : UiBehaviorBase() {

    @Test
    fun coldStartPlaysEnterAnimation() = assertSatisfied(UiCase("cold-start") { it.coldStartPlaysEnterAnimation() })
}
