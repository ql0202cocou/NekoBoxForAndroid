package io.nekohasekai.sagernet.uitest

import android.os.Parcel
import android.os.Parcelable
import io.nekohasekai.sagernet.database.BackupIntegrity
import io.nekohasekai.sagernet.database.BackupIntegrityException
import io.nekohasekai.sagernet.database.BackupRestore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
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
    fun restoringLostParametersKeepsTheMarkerInsteadOfInventingAPath() {
        val result = decode(listOf(profile(1) { path = "" }))
        assertEquals(0, result.skipped)
        assertTrue((result.profiles!!.single().requireBean() as StandardV2RayBean).xhttpParametersLost())
    }
}
