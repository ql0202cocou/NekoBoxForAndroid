package io.nekohasekai.sagernet.uitest

import android.os.Parcel
import android.os.Parcelable
import io.nekohasekai.sagernet.database.BackupIntegrity
import io.nekohasekai.sagernet.database.BackupIntegrityException
import io.nekohasekai.sagernet.database.BackupRestore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.putBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.xhttpParametersLost
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// 备份页的真实 Parcel / Base64 解码入口；只验证写库前的校验，不调用提交、服务停止或进程重启。
class BackupXhttpImportTest : UiTestBase() {
    private fun profile(id: Long, bean: StandardV2RayBean = VMessBean(), edit: StandardV2RayBean.() -> Unit = {}) =
        ProxyEntity(id = id, groupId = 1).putBean(bean.apply {
            type = "xhttp"
            path = "/xh"
            serverAddress = "example.com"
            serverPort = 443
            initializeDefaultValues()
            edit()
        })

    private fun encoded(value: Parcelable): String {
        val parcel = Parcel.obtain()
        return try {
            value.writeToParcel(parcel, 0)
            Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    private fun decode(profiles: List<ProxyEntity>, group: ProxyGroup = ProxyGroup(id = 1)) =
        BackupRestore.decode(
            JSONObject()
                .put("profiles", JSONArray(profiles.map(::encoded)))
                .put("groups", JSONArray(listOf(encoded(group)))),
            profile = true, rule = false, setting = false,
        )

    @Test
    fun importingOldTestErrorsKeepsSuccessfulMeasurements() {
        val result = decode(listOf(
            profile(1).apply { status = 3; ping = 56; error = "historic diagnostic" },
            profile(2).apply { status = 1; ping = 42; error = "stale diagnostic" },
        )).profiles!!
        assertEquals(listOf(null, null), result.map { it.error })
        assertEquals(listOf(0, 1), result.map { it.status })
        assertEquals(listOf(0, 42), result.map { it.ping })
    }

    @Test
    fun invalidRoutePortsRejectTheSelectedRulesBeforeRestore() {
        fun restore(port: String, source: String) = BackupRestore.decode(
            JSONObject().put("rules", JSONArray(listOf(
                encoded(RuleEntity(id = 1, port = "443")),
                encoded(RuleEntity(id = 2, port = port, sourcePort = source)),
            ))), false, true, false,
        )
        for (bad in listOf("65536", "-1", ", ,", "443:80", "1:2:3", ":abc", "80,443x")) {
            assertThrows(IllegalArgumentException::class.java) { restore(bad, "") }
            assertThrows(IllegalArgumentException::class.java) { restore("", bad) }
        }
        val ports = "0, 80, 00100:200, :1024, 65535:, :"
        assertEquals(ports, restore(ports, "443\n8080").rules!!.last().port)
    }

    @Test
    fun backupNormalizesXhttpBeforeReturningProfiles() {
        val profiles = listOf(VMessBean(), VMessBean().apply { alterId = -1 }, TrojanBean()).mapIndexed { index, bean ->
            profile(index + 1L, bean) {
                xhttpMode = " STREAM-UP "
                xhttpExtra = """{"noSSEHeader":true,"XPADDINGBYTES":"500-100"}"""
            }.apply { core = ProxyEntity.CORE_XRAY }
        }
        val result = decode(profiles)
        assertEquals(0, result.skipped)
        assertEquals(listOf(1L, 2L, 3L), result.profiles!!.map { it.id })
        for (entity in result.profiles) {
            assertEquals(ProxyEntity.CORE_XRAY, entity.core)
            val bean = entity.requireBean() as StandardV2RayBean
            assertEquals("stream-up", bean.xhttpMode)
            assertEquals("""{"xPaddingBytes":"100-500","noSSEHeader":true}""", bean.xhttpExtra)
        }
    }

    @Test
    fun invalidXhttpRecordsAreSkippedWithoutLosingValidRecords() {
        val result = decode(listOf(
            profile(1),
            profile(2) { xhttpExtra = """{"downloadSettings":{"address":"test-secret"}}""" },
            profile(3, TrojanBean()) { xhttpMode = "invalid-mode" },
            profile(4) { xhttpExtra = """{"unknownKey":true}""" },
            profile(5) { xhttpExtra = "not-json" },
        ))
        assertEquals(4, result.skipped)
        assertEquals(listOf(1L), result.profiles!!.map { it.id })
    }

    @Test
    fun invalidXhttpCannotLeaveBrokenReferencesOrAnEmptyRestore() {
        val invalid = profile(2) { xhttpExtra = """{"downloadSettings":{}}""" }
        assertThrows(IllegalArgumentException::class.java) { decode(listOf(invalid)) }
        val error = assertThrows(BackupIntegrityException::class.java) {
            decode(listOf(profile(1), invalid), ProxyGroup(id = 1, frontProxy = 2))
        }
        assertEquals(BackupIntegrity.Kind.GROUP_FRONT_PROXY, error.breaks.single().kind)
        assertEquals(1, error.breaks.single().count)
    }

    @Test
    fun skippedProfileOnlyBreaksReferencesToItsOwnIdWhenKnown() {
        val invalid = profile(2) { xhttpMode = "invalid-mode" }
        fun restore(target: Long, unknown: Boolean = false): BackupRestore.Decoded {
            val chain = ProxyEntity(id = 3, groupId = 1).putBean(ChainBean().apply {
                initializeDefaultValues()
                proxies = listOf(1L, target)
            })
            val nodes = JSONArray(listOf(profile(1), invalid, chain).map(::encoded))
            if (unknown) nodes.put(JSONObject.NULL)
            val backup = JSONObject()
                .put("profiles", nodes)
                .put("groups", JSONArray(listOf(encoded(ProxyGroup(id = 1)))))
                .put("rules", JSONArray(listOf(encoded(RuleEntity(id = 1, outbound = target)))))
            return BackupRestore.decode(backup, true, true, false)
        }
        val decoded = restore(99)
        assertEquals(1, decoded.skipped)
        assertEquals(listOf(1L, 99L), decoded.profiles!!.last().chainBean!!.proxies)
        assertEquals(99L, decoded.rules!!.single().outbound)
        for (attempt in listOf({ restore(2) }, { restore(99, unknown = true) })) {
            val error = assertThrows(BackupIntegrityException::class.java) { attempt() }
            assertEquals(listOf(BackupIntegrity.Kind.CHAIN_MEMBER, BackupIntegrity.Kind.RULE_OUTBOUND), error.breaks.map { it.kind })
        }
    }

    @Test
    fun restoringLostParametersKeepsTheMarkerInsteadOfInventingAPath() {
        val result = decode(listOf(profile(1) { path = "" }))
        assertEquals(0, result.skipped)
        assertTrue((result.profiles!!.single().requireBean() as StandardV2RayBean).xhttpParametersLost())
    }
}
