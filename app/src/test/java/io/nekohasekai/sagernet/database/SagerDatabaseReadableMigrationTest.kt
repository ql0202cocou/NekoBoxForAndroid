package io.nekohasekai.sagernet.database

import androidx.room.migration.Migration
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_MIHOMO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.LegacyV6Bytes
import io.nekohasekai.sagernet.fmt.kryo.KryoSamples
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.uitest.UiTestBase
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

// Robolectric 提供真实的 DataStore / configuration.db；节点库仍用共用的 SQLite 设施，执行生产 MIGRATION_8_9。
// 不把全局设置换成测试 lambda，也不直接调用 upgradeLegacyRow 来生成期望值。
class SagerDatabaseReadableMigrationTest : UiTestBase() {

    private val manual by lazy {
        SagerDatabase::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("MIGRATION_") }
            .map { it.apply { isAccessible = true }.get(null) as Migration }
    }
    private val auto by lazy { SagerDatabase_Impl().createAutoMigrations(emptyMap()) }

    private fun SqliteMemoryDb.migrateFrom(version: Int) {
        val migration = (manual + auto).single { it.startVersion == version }
        if (migration in manual) migration.migrate(supportDatabase()) else migration.migrate(connection())
        assertSchema(migration.endVersion)
    }

    private fun decode(type: Int, bytes: ByteArray): StandardV2RayBean =
        if (type == TYPE_VMESS) KryoConverters.deserialize(VMessBean(), bytes)
        else KryoConverters.deserialize(TrojanBean(), bytes)

    // 历史样本先对照已审查的字段期望，避免把 reader 的变化误当成迁移前的正确状态。
    private fun historical(id: String): ByteArray {
        val sample = KryoSamples.samples.single { it.id == id }
        assertEquals(emptyList<String>(), KryoSamples.diff(id, KryoSamples.read(sample), sample.expected))
        return sample.bytes
    }

    private class Case(
        val type: Int,
        val core: Int,
        val bytes: ByteArray,
        val expectedCore: Int = core,
        val changes: Map<String, Any> = emptyMap(),
    ) {
        val column = if (type == TYPE_VMESS) "vmessBean" else "trojanBean"
    }

    @Test
    fun `8 到 9 未设置全局允许不安全时按默认关闭迁移`() = verifyMigration(null)

    @Test
    fun `8 到 9 全局允许不安全关闭时迁移可读 bean`() = verifyMigration(false)

    @Test
    fun `8 到 9 全局允许不安全开启时保留原承载语义`() = verifyMigration(true)

    private fun verifyMigration(globalAllowInsecure: Boolean?) {
        // UiTestRule 已清空设置库；缺值与显式 false 都必须从生产 DataStore 读取。
        if (globalAllowInsecure == null) DataStore.configurationStore.remove(Key.GLOBAL_ALLOW_INSECURE)
        else DataStore.globalAllowInsecure = globalAllowInsecure
        val settingBefore = DataStore.configurationStore.getBoolean(Key.GLOBAL_ALLOW_INSECURE)
        assertEquals(globalAllowInsecure, settingBefore)

        val vmess = historical("VMessBean/dd8f56d3/vmess-tcp-tls")
        val vlessInsecure = historical("VMessBean/329572d1/vless-tcp-tls")
        val trojan = historical("TrojanBean/aa275d5e/trojan-tcp-tls-ech")
        // 历史样本没有越界取值：只为覆盖 Trojan 的 bean 写回分支构造该输入，不冒充历史原始字节。
        val oddTrojan = KryoConverters.serialize(decode(TYPE_TROJAN, trojan).apply {
            packetEncoding = 5
            muxType = 7
        })
        val tls = LegacyV6Bytes.vlessTlsTcp
        val ws = LegacyV6Bytes.vlessWsEarlyData
        val mux = LegacyV6Bytes.vlessMux
        for (bytes in listOf(tls, ws, mux)) {
            val bean = decode(TYPE_VMESS, bytes)
            assertEquals("", bean.utlsFingerprint)
            assertEquals(false, bean.allowInsecure)
        }
        val onXray = globalAllowInsecure != true
        val cases = listOf(
            // 证书固定压过全局开关，手动 mihomo 则按旧行为规范为 sing-box、保留 bean 原字节。
            Case(TYPE_VMESS, CORE_AUTO, vmess, changes = mapOf("muxType" to 3)),
            Case(TYPE_VMESS, CORE_MIHOMO, vmess, CORE_SING_BOX),
            Case(TYPE_VMESS, CORE_AUTO, vlessInsecure),
            Case(TYPE_VMESS, CORE_XRAY, vlessInsecure, changes = mapOf("muxType" to 3)),
            Case(TYPE_TROJAN, CORE_AUTO, trojan),
            Case(TYPE_TROJAN, CORE_XRAY, trojan, CORE_AUTO),
            Case(TYPE_TROJAN, CORE_XRAY, oddTrojan, CORE_AUTO, mapOf("muxType" to 0, "packetEncoding" to 0)),
            // 这三份旧字节的标注依赖全局开关；开关开启时原本由 sing-box 承载，无需改写。
            Case(TYPE_VMESS, CORE_AUTO, tls, changes = if (onXray) mapOf("utlsFingerprint" to "firefox") else emptyMap()),
            Case(TYPE_VMESS, CORE_AUTO, ws, changes = if (onXray) mapOf(
                "earlyDataHeaderName" to "Sec-WebSocket-Protocol", "utlsFingerprint" to "firefox",
            ) else emptyMap()),
            Case(TYPE_VMESS, CORE_AUTO, mux, changes = if (onXray) mapOf("muxType" to 3) else emptyMap()),
            Case(TYPE_VMESS, CORE_XRAY, tls),
        )
        val schema = Schema.load(8)
        val rows = cases.mapIndexed { index, case ->
            // id 超过 Int 范围；另一种 bean 列也填有效样本，以捕获列选错、WHERE 参数错位和误写整行。
            val id = 0x1_0000_0000L + index
            schema.row("proxy_entities", "id" to id, "groupId" to 7, "userOrder" to index,
                "type" to case.type, "core" to case.core, "tx" to id * 10, "rx" to id * 20,
                "status" to 1, "ping" to 42, "uuid" to "migration-$id", "error" to "test error $id",
                "vmessBean" to vmess.toBlob(), "trojanBean" to trojan.toBlob(), "sshBean" to blobOf(1, 2, index),
            ) + (case.column to case.bytes.toBlob())
        }
        val group = schema.row("proxy_groups", "id" to 7, "name" to "migration group", "isSelector" to 1,
            "frontProxy" to rows.first().getValue("id"), "landingProxy" to rows.last().getValue("id"))
        val rule = schema.row("rules", "id" to 3, "name" to "migration rule", "outbound" to rows.first().getValue("id"))

        SqliteMemoryDb().use { db ->
            db.createSchema(schema)
            rows.forEach { db.insert("proxy_entities", it) }
            db.insert("proxy_groups", group)
            db.insert("rules", rule)
            assertEquals(rows, db.table("proxy_entities"))

            db.migrateFrom(8)

            val migrated = db.table("proxy_entities")
            assertEquals(rows.size, migrated.size)
            for ((index, case) in cases.withIndex()) {
                val before = rows[index]
                val after = migrated[index]
                val where = "id=${before["id"]}, global=$globalAllowInsecure"
                assertEquals(where, before - "core" - case.column, after - "core" - case.column)
                assertEquals(where, case.expectedCore.toLong(), after["core"])
                val blob = after[case.column] as Blob
                if (case.changes.isEmpty()) {
                    assertEquals("$where 不改 bean 就保留原字节", case.bytes.toBlob(), blob)
                } else {
                    assertNotEquals("$where 必须写回 bean", case.bytes.toBlob(), blob)
                    val bytes = blob.hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val bean = decode(case.type, bytes)
                    assertFalse("$where 已完成标注", bean.legacyUnlabeled)
                    assertEquals(where, KryoSamples.fieldsOf(decode(case.type, case.bytes)) + case.changes,
                        KryoSamples.fieldsOf(bean))
                }
            }
            assertEquals(listOf(group), db.table("proxy_groups"))
            assertEquals(listOf(rule), db.table("rules"))
            assertEquals("迁移不得改写全局设置", settingBefore,
                DataStore.configurationStore.getBoolean(Key.GLOBAL_ALLOW_INSECURE))

            // 后续各级保留历史行，13 版新增的流量清零版本从零开始。
            for (version in 9 until Schema.latestVersion()) {
                db.migrateFrom(version)
                val expected = if (version >= 12) migrated.map { it + ("trafficEpoch" to 0L) } else migrated
                assertEquals("$version → ${version + 1}", expected, db.table("proxy_entities"))
                assertEquals(listOf(group), db.table("proxy_groups"))
                assertEquals(listOf(rule), db.table("rules"))
            }
        }
    }
}
