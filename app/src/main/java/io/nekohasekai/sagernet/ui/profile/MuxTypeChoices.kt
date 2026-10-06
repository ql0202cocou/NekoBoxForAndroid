package io.nekohasekai.sagernet.ui.profile

import androidx.annotation.ArrayRes
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.fmt.DialCore
import io.nekohasekai.sagernet.fmt.MuxFamily
import io.nekohasekai.sagernet.fmt.v2ray.MUX_COOL
import io.nekohasekai.sagernet.fmt.v2ray.MUX_H2MUX
import io.nekohasekai.sagernet.fmt.v2ray.MUX_SMUX
import io.nekohasekai.sagernet.fmt.v2ray.MUX_YAMUX

// 编辑器里 mux 协议（muxType）的可选项按核心显示（D15，方案 E1）。纯函数：输入核心取值与当前 muxType，
// 输出可选项、要改成的值和 padding 是否显示；StandardV2RaySettingsActivity 只负责调用。能不能保存仍由保存时的
// 选核判定决定（D14），这里只让下拉框不给出注定冲突的选项

enum class MuxTypeChoices(
    val values: List<Int>,
    @param:ArrayRes val entriesRes: Int,
    @param:ArrayRes val valuesRes: Int,
) {
    // 手动 sing-box：只有 sing-mux
    SING_BOX(listOf(MUX_H2MUX, MUX_SMUX, MUX_YAMUX), R.array.mux_type_sing_box_entry, R.array.int_array_3),

    // 手动 Xray：只有 Mux.Cool
    XRAY(listOf(MUX_COOL), R.array.mux_type_xray_entry, R.array.mux_type_xray_value),

    // 自动：四项都列，名称后注明所属核心。编辑器不提供的核心取值（例如残留的 mihomo）也列全，由保存检查报冲突
    ALL(listOf(MUX_H2MUX, MUX_SMUX, MUX_YAMUX, MUX_COOL), R.array.mux_type_auto_entry, R.array.int_array_4),
}

// core 是实体的 core 列（编辑器里 profileCore 的取值）
fun muxTypeChoices(core: Int): MuxTypeChoices = when (core) {
    CORE_SING_BOX -> MuxTypeChoices.SING_BOX
    CORE_XRAY -> MuxTypeChoices.XRAY
    else -> MuxTypeChoices.ALL
}

// muxType 属于哪个协议族，与能力表（CoreRequirements）的划分相同
fun muxFamilyOf(muxType: Int): MuxFamily = when (muxType) {
    MUX_H2MUX, MUX_SMUX, MUX_YAMUX -> MuxFamily.SING_MUX
    MUX_COOL -> MuxFamily.MUX_COOL
    else -> MuxFamily.UNKNOWN
}

// 核心的默认 mux 协议族；mihomo 不承载 V2Ray 系协议，没有
fun defaultMuxType(core: DialCore): Int? = when (core) {
    DialCore.SING_BOX -> MUX_H2MUX
    DialCore.XRAY -> MUX_COOL
    DialCore.MIHOMO -> null
}

// 切换核心后的 muxType：当前值在新列表里就不动，否则改成新列表的第一项（sing-box 为 h2mux，Xray 为 Mux.Cool）
fun muxTypeForCore(core: Int, muxType: Int): Int {
    val values = muxTypeChoices(core).values
    return if (muxType in values) muxType else values.first()
}

// 把 enableMux 从关切到开时的 muxType。
// 自动选核：coreWithoutMux 是不开 mux 时自动选核会选的核心（被拒或不能选核时为 null，不改）；当前协议族与它
// 不匹配就改成它的默认族，免得只因打开 mux 就把节点换到另一个核心（例如带 REALITY 的 VLESS 被默认的 h2mux
// 拉到 sing-box）。手动选核：与切换核心相同，只保证取值在列表里
fun muxTypeOnEnable(core: Int, muxType: Int, coreWithoutMux: DialCore?): Int {
    if (core != CORE_AUTO) return muxTypeForCore(core, muxType)
    val target = coreWithoutMux ?: return muxType
    val default = defaultMuxType(target) ?: return muxType
    return if (muxFamilyOf(muxType) == muxFamilyOf(default)) muxType else default
}

// padding 是 sing-mux 的选项：手动 Xray 或选了 Mux.Cool 时隐藏
fun muxPaddingVisible(core: Int, muxType: Int): Boolean = core != CORE_XRAY && muxType != MUX_COOL
