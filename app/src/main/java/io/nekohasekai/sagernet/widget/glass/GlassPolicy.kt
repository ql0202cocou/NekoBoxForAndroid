package io.nekohasekai.sagernet.widget.glass

/**
 * 档位策略：（用户选择、设备条件、表面）→ 档位。纯函数，不读 DataStore / 系统服务（JVM 测试：`GlassPolicyTest`），
 * 设备条件由 [GlassTiers] 在 Android 一侧收集后传进来。
 *
 * | 用户选择 | 结果 |
 * | --- | --- |
 * | 实色 | 所有表面 [GlassTier.SOLID] |
 * | 静态磨砂 | 所有表面 [GlassTier.FROST] |
 * | 自动 | API ≥ 31 且画布硬件加速、非低内存设备、非省电模式 → [GlassTier.BLUR]，否则 [GlassTier.FROST] |
 *
 * 连接按钮永远不是 [GlassTier.BLUR]（u5-plan 第 6 节第 4 条：BLUR 档不对按钮做下层捕获，用 FROST 配方）。
 * 「自动」没有降到 [GlassTier.SOLID] 的路：SOLID 只来自用户选择。
 */
object GlassPolicy {

    /** 真实背景模糊（RenderEffect）的最低 API */
    const val BLUR_MIN_SDK = 31

    fun resolve(
        choice: GlassChoice,
        target: GlassTarget,
        sdkInt: Int,
        hardwareAccelerated: Boolean,
        lowRamDevice: Boolean,
        powerSave: Boolean,
    ): GlassTier = when (choice) {
        GlassChoice.SOLID -> GlassTier.SOLID
        GlassChoice.FROSTED -> GlassTier.FROST
        GlassChoice.AUTO -> when {
            target == GlassTarget.CONNECT_BUTTON -> GlassTier.FROST
            sdkInt >= BLUR_MIN_SDK && hardwareAccelerated && !lowRamDevice && !powerSave -> GlassTier.BLUR
            else -> GlassTier.FROST
        }
    }
}
