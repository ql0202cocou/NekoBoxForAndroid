package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_MIHOMO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_ANYTLS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.kryo.KryoSamples
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.MUX_COOL
import io.nekohasekai.sagernet.fmt.v2ray.MUX_H2MUX
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 数据库 8 → 9 迁移的读写流程（migrateLegacyRows）：用假的表与游标核对查询的列序、空值处理、写回与计数日志。
// 假游标按查询语句里列出的列名取值、按类型严格取（id 是 Long，type / core 是 Int），NULL 上调 getBlob 直接报错，
// 列序或空值处理写错都会失败。逐行判断本身见 fmt/LegacyRowUpgradeTest
class LegacyRowMigrationTest {

    private class Write(val id: Long, val core: Int, val column: String?, val bytes: ByteArray?)

    private class FakeStore(val table: List<Map<String, Any?>>) : LegacyRowStore {
        val writes = ArrayList<Write>()
        val queries = ArrayList<String>()
        private var open = false

        override fun query(sql: String, block: (LegacyRowCursor) -> Unit) {
            queries += sql
            val columns = Regex("SELECT (.+) FROM `proxy_entities`").matchEntire(sql)!!.groupValues[1]
                .split(",").map { it.trim().removeSurrounding("`") }
            open = true
            try {
                block(Cursor(columns))
            } finally {
                open = false
            }
        }

        private inner class Cursor(val columns: List<String>) : LegacyRowCursor {
            private var row = -1
            private fun value(index: Int): Any? = table[row].getValue(columns[index])
            override fun moveToNext(): Boolean = ++row < table.size
            override fun getLong(index: Int): Long = value(index) as Long
            override fun getInt(index: Int): Int = value(index) as Int
            override fun isNull(index: Int): Boolean = value(index) == null
            override fun getBlob(index: Int): ByteArray = value(index) as ByteArray? ?: error("NULL 列 ${columns[index]} 上调了 getBlob")
        }

        private fun write(write: Write) {
            check(!open) { "游标关闭之前就写回了" }
            writes += write
        }

        override fun updateCore(id: Long, core: Int) = write(Write(id, core, null, null))

        override fun updateCoreAndVmessBean(id: Long, core: Int, vmessBean: ByteArray) =
            write(Write(id, core, "vmessBean", vmessBean))

        override fun updateCoreAndTrojanBean(id: Long, core: Int, trojanBean: ByteArray) =
            write(Write(id, core, "trojanBean", trojanBean))
    }

    private fun sample(id: String) = KryoSamples.samples.single { it.id == id }.bytes

    // 表里的行带着全部 bean 列与别的列，迁移只读查询里列出的那几列
    private fun row(id: Long, type: Int, core: Int, vmessBean: ByteArray? = null, trojanBean: ByteArray? = null) = mapOf(
        "id" to id, "type" to type, "core" to core, "vmessBean" to vmessBean, "trojanBean" to trojanBean,
        "name" to "node-$id", "groupId" to 1L,
    )

    private class Run(val store: FakeStore, val warnings: List<String>, val infos: List<String>, val globalReads: Int)

    private fun migrate(vararg rows: Map<String, Any?>, global: Boolean = false): Run {
        val store = FakeStore(rows.toList())
        val warnings = ArrayList<String>()
        val infos = ArrayList<String>()
        var reads = 0
        migrateLegacyRows(store, { reads++; global }, { warnings += it }, { infos += it })
        return Run(store, warnings, infos, reads)
    }

    private fun decode(bytes: ByteArray) = KryoConverters.deserialize(VMessBean(), bytes)

    @Test
    fun `逐行读、读完再写回，写回与计数日志`() {
        val broken = sample("VMessBean/dd8f56d3/vmess-tcp-tls").let { it.copyOf(it.size / 2) }
        val run = migrate(
            // 当时走 Xray、开了 smux 的 VMess：改 bean（标为 Mux.Cool），core 不变
            row(1001, TYPE_VMESS, CORE_AUTO, vmessBean = sample("VMessBean/dd8f56d3/vmess-tcp-tls")),
            // vmessBean 为 NULL：只规范 core，不调 getBlob
            row(1002, TYPE_VMESS, CORE_MIHOMO),
            // bean 不用改、core 为 mihomo：只写 core
            row(1003, TYPE_VMESS, CORE_MIHOMO, vmessBean = sample("VMessBean/dd8f56d3/default")),
            // 不能选核的手动值改回自动
            row(1004, TYPE_TROJAN, CORE_XRAY),
            // 合法的手动值不动
            row(1005, TYPE_ANYTLS, CORE_MIHOMO),
            // 读不出：记一条警告，不写
            row(1006, TYPE_VMESS, CORE_XRAY, vmessBean = broken),
            // 当时走 sing-box，什么都不改
            row(1007, TYPE_VMESS, CORE_AUTO, vmessBean = sample("VMessBean/329572d1/vless-tcp-tls")),
        )
        assertEquals(listOf(LEGACY_ROW_QUERY), run.store.queries)
        val writes = run.store.writes
        assertEquals(listOf(1001L, 1002L, 1003L, 1004L), writes.map { it.id })
        assertEquals(listOf(CORE_AUTO, CORE_SING_BOX, CORE_SING_BOX, CORE_AUTO), writes.map { it.core })
        assertEquals(listOf("vmessBean", null, null, null), writes.map { it.column })
        decode(writes[0].bytes!!).let {
            assertEquals(MUX_COOL, it.muxType)
            assertArrayEquals(KryoConverters.serialize(it), writes[0].bytes)
        }
        assertEquals(1, run.warnings.size)
        assertTrue(run.warnings[0], Regex("""database 8 -> 9: profile #1006 skipped, bean unreadable \(\w+\)""").matches(run.warnings[0]))
        assertEquals(listOf("database 8 -> 9: 4 of 7 profiles upgraded, 1 unreadable"), run.infos)
        // 全局「允许不安全」只取一次
        assertEquals(1, run.globalReads)
    }

    // Trojan 行读 trojanBean 列、改过的 bean 写回 trojanBean；读不出的行仍规范 core（只写 core）
    @Test
    fun `Trojan 行的 bean 列与读不出时的 core`() {
        val sampleTrojan = sample("TrojanBean/aa275d5e/trojan-tcp-tls-ech")
        // 被旧版读错位又写回的取值：开了 mux、muxType 越界
        val oddTrojan = KryoConverters.serialize(KryoConverters.deserialize(TrojanBean(), sampleTrojan).apply { muxType = 7 })
        val brokenVmess = sample("VMessBean/dd8f56d3/vmess-tcp-tls").let { it.copyOf(it.size / 2) }
        val brokenTrojan = sampleTrojan.copyOf(sampleTrojan.size / 2)
        val run = migrate(
            // Trojan 的 bean 在 trojanBean 列；vmessBean 列里放着别的字节，不该读
            row(2001, TYPE_TROJAN, CORE_XRAY, vmessBean = byteArrayOf(1, 2, 3), trojanBean = oddTrojan),
            // 没越界的 Trojan：读了 bean，不改
            row(2002, TYPE_TROJAN, CORE_AUTO, trojanBean = sampleTrojan),
            // 读不出 + core 非法：只写 core
            row(2003, TYPE_VMESS, CORE_MIHOMO, vmessBean = brokenVmess),
            row(2004, TYPE_TROJAN, CORE_XRAY, trojanBean = brokenTrojan),
            // 读不出 + core 合法：不写
            row(2005, TYPE_TROJAN, CORE_AUTO, trojanBean = brokenTrojan),
        )
        val writes = run.store.writes
        assertEquals(listOf(2001L, 2003L, 2004L), writes.map { it.id })
        assertEquals(listOf(CORE_AUTO, CORE_SING_BOX, CORE_AUTO), writes.map { it.core })
        assertEquals(listOf("trojanBean", null, null), writes.map { it.column })
        KryoConverters.deserialize(TrojanBean(), writes[0].bytes!!).let { assertEquals(MUX_H2MUX, it.muxType) }
        assertEquals(listOf(2003L, 2004L, 2005L), run.warnings.map { Regex("#(\\d+)").find(it)!!.groupValues[1].toLong() })
        assertEquals(listOf("database 8 -> 9: 3 of 5 profiles upgraded, 3 unreadable"), run.infos)
        assertEquals(1, run.globalReads)
    }

    @Test
    fun `没有要读的 bean 时不取全局设置`() {
        val run = migrate(
            row(1, TYPE_VMESS, CORE_AUTO),
            row(2, TYPE_TROJAN, CORE_AUTO),
            // 不能选核的协议不读 bean 列
            row(3, TYPE_ANYTLS, CORE_AUTO, vmessBean = byteArrayOf(1), trojanBean = byteArrayOf(1)),
        )
        assertEquals(0, run.globalReads)
        assertTrue(run.store.writes.isEmpty())
        assertEquals(listOf("database 8 -> 9: 0 of 3 profiles upgraded, 0 unreadable"), run.infos)
    }

    @Test
    fun `空表`() {
        val run = migrate()
        assertTrue(run.store.writes.isEmpty())
        assertEquals(listOf("database 8 -> 9: 0 of 0 profiles upgraded, 0 unreadable"), run.infos)
    }
}
