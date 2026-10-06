package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.CoreConflict
import io.nekohasekai.sagernet.fmt.CoreDecision
import io.nekohasekai.sagernet.fmt.CoreTestNodes
import io.nekohasekai.sagernet.fmt.CoreTestNodes.PIN
import io.nekohasekai.sagernet.fmt.CoreTestNodes.anytls
import io.nekohasekai.sagernet.fmt.CoreTestNodes.reality
import io.nekohasekai.sagernet.fmt.CoreTestNodes.trojan
import io.nekohasekai.sagernet.fmt.CoreTestNodes.vless
import io.nekohasekai.sagernet.fmt.CoreTestNodes.vmess
import io.nekohasekai.sagernet.fmt.DialCore
import io.nekohasekai.sagernet.fmt.ProfileField
import io.nekohasekai.sagernet.fmt.decideCore
import io.nekohasekai.sagernet.fmt.http.HttpBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// 选核被拒时编辑器对话框的文案映射（D14）：每个冲突标识都有原因、每个字段都有标题，分组与首句按判定结果
class CoreConflictTextTest {

    private fun appFile(path: String): File =
        listOf(File(path), File("app/$path")).firstOrNull { it.isFile } ?: error("找不到 app/$path")

    // strings.xml 里的 name → 是否 translatable="false"
    private fun strings(dir: String): Map<String, Boolean> =
        Regex("""<string name="([^"]+)"([^>]*)>""").findAll(appFile("src/main/res/$dir/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].contains("""translatable="false"""") }

    // R.string 的常量值 → 资源名（JVM 单测里不能 getString，只能按常量反查名字）
    private val stringNames: Map<Int, String> =
        R.string::class.java.fields.associate { it.getInt(null) to it.name }

    private fun name(id: Int): String = stringNames[id] ?: error("$id 不是 R.string 的常量")

    private val beans: List<AbstractBean> = listOf(
        vmess(), vless(), trojan(), anytls(), HttpBean().apply { initializeDefaultValues() },
    ) + listOf("tcp", "http", "ws", "grpc", "httpupgrade", "quic").map { network -> vmess { type = network } }

    @Test
    fun `每个冲突标识都有中英文原因`() {
        val en = strings("values")
        val zh = strings("values-zh-rCN")
        val seen = HashSet<Int>()
        for (id in CoreConflict.entries) {
            val reason = coreConflictReason(id)
            assertTrue("$id 与别的冲突共用原因", seen.add(reason))
            // 资源名与标识一一对应，防止映射串行
            assertEquals("core_conflict_${id.name.lowercase()}", name(reason))
            assertTrue("$id 缺英文", name(reason) in en)
            assertTrue("$id 缺中文", name(reason) in zh)
        }
    }

    @Test
    fun `每个字段都有编辑器标题`() {
        val en = strings("values")
        val zh = strings("values-zh-rCN")
        for (bean in beans) for (field in ProfileField.entries) {
            val title = name(profileFieldTitle(field, bean))
            assertTrue("$field → $title", title in en)
        }
        // 这次新增的字段名两种语言都有（不翻译的专名除外）
        for (title in listOf(R.string.core_conflict_field_global_allow_insecure, R.string.enable_ech)) {
            assertTrue(name(title), name(title) in zh)
        }
        for (title in listOf(R.string.reality_public_key, R.string.reality_mldsa65_verify)) {
            assertEquals(true, en[name(title)])
        }
        for (lead in listOf(
            R.string.core_rejected_manual, R.string.core_rejected_auto, R.string.core_rejected_fixed,
            R.string.core_conflict_line, R.string.core_conflict_field_value,
        )) {
            assertTrue(name(lead), name(lead) in en && name(lead) in zh)
        }
    }

    @Test
    fun `字段标题随协议与传输方式变化`() {
        assertEquals(R.string.xtls_flow, profileFieldTitle(ProfileField.ENCRYPTION, vless()))
        assertEquals(R.string.encryption, profileFieldTitle(ProfileField.ENCRYPTION, vmess()))
        assertEquals(R.string.ws_host, profileFieldTitle(ProfileField.HOST, vmess { type = "ws" }))
        assertEquals(R.string.http_upgrade_path, profileFieldTitle(ProfileField.PATH, vmess { type = "httpupgrade" }))
        assertEquals(R.string.grpc_service_name, profileFieldTitle(ProfileField.PATH, vmess { type = "grpc" }))
        assertEquals(R.string.http_host, profileFieldTitle(ProfileField.HOST, vmess { type = "http" }))
        assertEquals(R.string.certificate_fingerprint, profileFieldTitle(ProfileField.CERTIFICATE_FINGERPRINT, anytls()))
    }

    private fun rejected(bean: AbstractBean, core: Int = CORE_AUTO, global: Boolean = false): CoreDecision.Rejected {
        val node = CoreTestNodes.node(bean, core, global)
        return decideCore(node.type, node.core, node.bean, node.global) as CoreDecision.Rejected
    }

    @Test
    fun `自动选核被拒按核心分组`() {
        // REALITY + mldsa65Verify 走 Xray 优先；ws 上 Xray 不能跑 REALITY，sing-box 不能校验 mldsa65Verify
        val bean = vless { reality(mldsa = true); type = "ws" }
        val decision = rejected(bean)
        assertEquals(R.string.core_rejected_auto, coreRejectionLead(decision))
        assertEquals(
            listOf(
                CoreConflictGroup(
                    DialCore.XRAY,
                    listOf(
                        CoreConflictLine(
                            listOf(R.string.network, R.string.reality_public_key), null,
                            R.string.core_conflict_xray_reality_transport,
                        ),
                    ),
                ),
                CoreConflictGroup(
                    DialCore.SING_BOX,
                    listOf(
                        CoreConflictLine(
                            listOf(R.string.reality_mldsa65_verify), null, R.string.core_conflict_sing_box_mldsa65_verify,
                        ),
                    ),
                ),
            ),
            coreConflictGroups(decision, bean),
        )
    }

    @Test
    fun `手动选核被拒不分组`() {
        val bean = vless { reality(mldsa = true); utlsFingerprint = "netscape" }
        val decision = rejected(bean, CORE_SING_BOX)
        assertEquals(R.string.core_rejected_manual, coreRejectionLead(decision))
        val groups = coreConflictGroups(decision, bean)
        assertEquals(1, groups.size)
        assertEquals(null, groups.single().core)
        assertEquals(
            setOf(
                CoreConflictLine(listOf(R.string.reality_mldsa65_verify), null, R.string.core_conflict_sing_box_mldsa65_verify),
                // 取值不在名单里的冲突带上取值
                CoreConflictLine(listOf(R.string.utls_fingerprint), "netscape", R.string.core_conflict_sing_box_utls_fingerprint),
            ),
            groups.single().lines.toSet(),
        )
    }

    @Test
    fun `全局允许不安全单独成一个字段名`() {
        // Xray 手动选核、节点没开 allowInsecure、全局开着
        val bean = vmess()
        val decision = rejected(bean, CORE_XRAY, global = true)
        assertEquals(
            listOf(
                CoreConflictLine(
                    listOf(R.string.allow_insecure, R.string.core_conflict_field_global_allow_insecure), null,
                    R.string.core_conflict_xray_allow_insecure,
                ),
            ),
            coreConflictGroups(decision, bean).single().lines,
        )
    }

    @Test
    fun `不能选核的协议用固定说法`() {
        val bean = HttpBean().apply {
            initializeDefaultValues()
            security = "tls"
            certificateFingerprint = PIN
        }
        val decision = rejected(bean)
        assertEquals(R.string.core_rejected_fixed, coreRejectionLead(decision))
        assertEquals(
            listOf(
                CoreConflictGroup(
                    null,
                    listOf(
                        CoreConflictLine(
                            listOf(R.string.certificate_fingerprint), null, R.string.core_conflict_protocol_certificate_pin,
                        ),
                    ),
                ),
            ),
            coreConflictGroups(decision, bean),
        )
    }
}
