package io.nekohasekai.sagernet.widget.glass

/**
 * 玻璃材质的三个档位（ui-plan 第 8 节）。纯数据，不依赖 Android 框架。
 *
 * - [SOLID]：MD3 实色，不透明表面 + 系统 elevation，与没有玻璃时完全一致，零额外开销；
 * - [FROST]：静态磨砂，不捕获下层内容；半透明底色 + 环境色渐变 + 高光 + 自绘阴影；
 * - [BLUR]：完整柔光，在 [FROST] 之上加真实背景模糊（API 31+ 的 RenderEffect + 下层内容抓取）。
 */
enum class GlassTier { SOLID, FROST, BLUR }

/** 用玻璃的三个悬浮层（U5a 定的范围）；页面里的其它表面不玻璃化 */
enum class GlassTarget { DOCK, STATUS_CARD, CONNECT_BUTTON }

/**
 * 设置项「玻璃材质」（Key.GLASS_MATERIAL）的取值，[stored] 是写进 DataStore 的整数，
 * 与 `arrays.xml` 的 `int_array_3` 顺序一致（0 自动、1 静态磨砂、2 实色）。
 */
enum class GlassChoice(val stored: Int) {
    AUTO(0),
    FROSTED(1),
    SOLID(2);

    companion object {
        /** 未知值（例如以后删掉某档后留下的旧值）一律按「自动」处理 */
        fun fromStored(value: Int): GlassChoice = entries.firstOrNull { it.stored == value } ?: AUTO
    }
}
