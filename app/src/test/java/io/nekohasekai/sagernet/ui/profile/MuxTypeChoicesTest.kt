package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_MIHOMO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.fmt.CoreDecision
import io.nekohasekai.sagernet.fmt.CoreTestNodes
import io.nekohasekai.sagernet.fmt.CoreTestNodes.reality
import io.nekohasekai.sagernet.fmt.CoreTestNodes.vless
import io.nekohasekai.sagernet.fmt.CoreTestNodes.vmess
import io.nekohasekai.sagernet.fmt.DialCore
import io.nekohasekai.sagernet.fmt.MuxFamily
import io.nekohasekai.sagernet.fmt.decideCore
import io.nekohasekai.sagernet.fmt.v2ray.MUX_COOL
import io.nekohasekai.sagernet.fmt.v2ray.MUX_H2MUX
import io.nekohasekai.sagernet.fmt.v2ray.MUX_SMUX
import io.nekohasekai.sagernet.fmt.v2ray.MUX_YAMUX
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// 编辑器 mux 协议按核心显示（D15，方案 E1）的纯函数
class MuxTypeChoicesTest {

    private fun appFile(path: String): File =
        listOf(File(path), File("app/$path")).firstOrNull { it.isFile } ?: error("找不到 app/$path")

    private val arrayNames: Map<Int, String> = R.array::class.java.fields.associate { it.getInt(null) to it.name }

    private fun stringArray(id: Int): List<String> {
        val name = arrayNames[id] ?: error("$id 不是 R.array 的常量")
        val xml = appFile("src/main/res/values/arrays.xml").readText()
        val body = Regex("""<string-array name="$name"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: error("arrays.xml 里没有 $name")
        return Regex("""<item>(.*?)</item>""").findAll(body).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `各核心的可选项`() {
        assertEquals(listOf(MUX_H2MUX, MUX_SMUX, MUX_YAMUX), muxTypeChoices(CORE_SING_BOX).values)
        assertEquals(listOf(MUX_COOL), muxTypeChoices(CORE_XRAY).values)
        assertEquals(listOf(MUX_H2MUX, MUX_SMUX, MUX_YAMUX, MUX_COOL), muxTypeChoices(CORE_AUTO).values)
        // 编辑器不提供的取值（残留的 mihomo、非法值）列全，由保存检查报冲突
        assertEquals(MuxTypeChoices.ALL, muxTypeChoices(CORE_MIHOMO))
        assertEquals(MuxTypeChoices.ALL, muxTypeChoices(-1))
    }

    @Test
    fun `下拉框资源与可选项一致`() {
        for (choices in MuxTypeChoices.entries) {
            assertEquals("$choices", choices.values.map { it.toString() }, stringArray(choices.valuesRes))
            assertEquals("$choices", choices.values.size, stringArray(choices.entriesRes).size)
        }
        assertEquals(listOf("h2mux", "smux", "yamux"), stringArray(MuxTypeChoices.SING_BOX.entriesRes))
        assertEquals(listOf("Mux.Cool"), stringArray(MuxTypeChoices.XRAY.entriesRes))
        assertEquals(
            listOf("h2mux (sing-box)", "smux (sing-box)", "yamux (sing-box)", "Mux.Cool (Xray)"),
            stringArray(MuxTypeChoices.ALL.entriesRes),
        )
    }

    @Test
    fun `协议族`() {
        assertEquals(MuxFamily.SING_MUX, muxFamilyOf(MUX_H2MUX))
        assertEquals(MuxFamily.SING_MUX, muxFamilyOf(MUX_YAMUX))
        assertEquals(MuxFamily.MUX_COOL, muxFamilyOf(MUX_COOL))
        assertEquals(MuxFamily.UNKNOWN, muxFamilyOf(7))
        assertEquals(MUX_H2MUX, defaultMuxType(DialCore.SING_BOX))
        assertEquals(MUX_COOL, defaultMuxType(DialCore.XRAY))
        assertEquals(null, defaultMuxType(DialCore.MIHOMO))
    }

    @Test
    fun `切换核心`() {
        // 在新列表里就不动
        assertEquals(MUX_SMUX, muxTypeForCore(CORE_SING_BOX, MUX_SMUX))
        assertEquals(MUX_COOL, muxTypeForCore(CORE_XRAY, MUX_COOL))
        assertEquals(MUX_YAMUX, muxTypeForCore(CORE_AUTO, MUX_YAMUX))
        assertEquals(MUX_COOL, muxTypeForCore(CORE_AUTO, MUX_COOL))
        // 不在就换成该核心的第一项
        assertEquals(MUX_COOL, muxTypeForCore(CORE_XRAY, MUX_SMUX))
        assertEquals(MUX_H2MUX, muxTypeForCore(CORE_SING_BOX, MUX_COOL))
        assertEquals(MUX_H2MUX, muxTypeForCore(CORE_SING_BOX, 7))
        assertEquals(MUX_H2MUX, muxTypeForCore(CORE_AUTO, 7))
    }

    @Test
    fun `打开 mux 时的协议族`() {
        // 自动：按不开 mux 时会选的核心
        assertEquals(MUX_COOL, muxTypeOnEnable(CORE_AUTO, MUX_H2MUX, DialCore.XRAY))
        assertEquals(MUX_COOL, muxTypeOnEnable(CORE_AUTO, MUX_COOL, DialCore.XRAY))
        assertEquals(MUX_H2MUX, muxTypeOnEnable(CORE_AUTO, MUX_COOL, DialCore.SING_BOX))
        assertEquals(MUX_SMUX, muxTypeOnEnable(CORE_AUTO, MUX_SMUX, DialCore.SING_BOX))
        assertEquals(MUX_H2MUX, muxTypeOnEnable(CORE_AUTO, 7, DialCore.SING_BOX))
        // 不开 mux 也被拒（或不能选核）时不改
        assertEquals(MUX_SMUX, muxTypeOnEnable(CORE_AUTO, MUX_SMUX, null))
        assertEquals(MUX_SMUX, muxTypeOnEnable(CORE_AUTO, MUX_SMUX, DialCore.MIHOMO))
        // 手动：只保证取值在列表里
        assertEquals(MUX_COOL, muxTypeOnEnable(CORE_XRAY, MUX_H2MUX, null))
        assertEquals(MUX_YAMUX, muxTypeOnEnable(CORE_SING_BOX, MUX_YAMUX, null))
        assertEquals(MUX_H2MUX, muxTypeOnEnable(CORE_SING_BOX, MUX_COOL, null))
    }

    // 编辑器的做法：临时 bean 关掉 mux 算自动选核，再按结果挑协议族；挑完后打开 mux 仍选同一个核心
    private fun enableMuxLikeEditor(bean: StandardV2RayBean): Pair<DialCore?, Int> {
        val node = CoreTestNodes.node(bean)
        bean.enableMux = false
        val withoutMux = (decideCore(node.type, CORE_AUTO, bean, false) as? CoreDecision.Selected)?.core
        val muxType = muxTypeOnEnable(CORE_AUTO, bean.muxType, withoutMux)
        bean.enableMux = true
        bean.muxType = muxType
        val withMux = (decideCore(node.type, CORE_AUTO, bean, false) as? CoreDecision.Selected)?.core
        assertEquals(withoutMux, withMux)
        return withMux to muxType
    }

    @Test
    fun `打开 mux 不换核心`() {
        // 带 REALITY 的 VLESS 走 Xray：默认的 h2mux 换成 Mux.Cool
        assertEquals(DialCore.XRAY to MUX_COOL, enableMuxLikeEditor(vless { reality(); muxType = MUX_H2MUX }))
        // 普通 VMess 走 sing-box：残留的 Mux.Cool 换成 h2mux，已选的 smux 不动
        assertEquals(DialCore.SING_BOX to MUX_H2MUX, enableMuxLikeEditor(vmess { muxType = MUX_COOL }))
        assertEquals(DialCore.SING_BOX to MUX_SMUX, enableMuxLikeEditor(vmess { muxType = MUX_SMUX }))
    }

    @Test
    fun `padding 只给 sing-mux`() {
        assertTrue(muxPaddingVisible(CORE_AUTO, MUX_H2MUX))
        assertTrue(muxPaddingVisible(CORE_SING_BOX, MUX_YAMUX))
        assertFalse(muxPaddingVisible(CORE_AUTO, MUX_COOL))
        assertFalse(muxPaddingVisible(CORE_XRAY, MUX_COOL))
        // 手动 Xray 下即使残留 sing-mux 取值也不显示
        assertFalse(muxPaddingVisible(CORE_XRAY, MUX_H2MUX))
    }
}
