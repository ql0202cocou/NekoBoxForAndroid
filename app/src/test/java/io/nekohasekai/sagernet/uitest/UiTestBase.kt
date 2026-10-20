package io.nekohasekai.sagernet.uitest

import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 所有 UI 测试的基类：Robolectric 跑 SDK 35（JDK 17 即可，不换环境），原生绘图（截图要用），
 * en-US、亮色、411dp × xhdpi 的默认设备。别的档位（宽度、字号、夜间）由用例在启动前改配置。
 * go.Seq / libcore.Libcore 的原生库在 JVM 上没有，由影子类顶替，并把这两个包交给 Robolectric 插桩。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [35],
    qualifiers = "en-rUS-w411dp-h914dp-port-notnight-xhdpi",
    shadows = [ShadowGoSeq::class, ShadowLibcore::class, ShadowNoBindContextImpl::class],
    instrumentedPackages = ["go", "libcore"],
)
abstract class UiTestBase {
    @get:Rule
    val ui = UiTestRule()
}
