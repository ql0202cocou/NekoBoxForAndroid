package io.nekohasekai.sagernet.group

import androidx.core.content.FileProvider
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ClashImportException
import io.nekohasekai.sagernet.uitest.UiTestBase
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class SubscriptionUpdateTest : UiTestBase() {
    @Before
    @After
    fun resetFileProviderRoots() {
        // Robolectric 每例换缓存目录，FileProvider 的静态路径缓存却随沙箱复用。
        (FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>).clear()
    }

    @Test
    fun queuedUpdateSkipsDeletedOrConvertedGroup() = ui.bg {
        val fixture = SubscriptionUpdateFixture("yaml", "unique", 1)
        fixture.prepare()
        val previous = GroupManager.userInterface
        try {
            GroupManager.userInterface = UpdateReport()
            fixture.file.writeText(fixture.text())
            val queued = SagerDatabase.groupDao.getById(fixture.groupId)!!
            SagerDatabase.groupDao.updateGroup(queued.copy(type = GroupType.BASIC))
            assertFalse(GroupUpdater.executeUpdate(queued, byUser = true))
            assertEquals(0L, SagerDatabase.proxyDao.countByGroup(fixture.groupId))
            SagerDatabase.groupDao.deleteById(fixture.groupId)
            assertFalse(GroupUpdater.executeUpdate(queued, byUser = true))
        } finally {
            GroupManager.userInterface = previous
            fixture.cleanup()
        }
    }

    @Test
    fun updatePreservesManualDnsChangedDuringDownload() = ui.bg {
        val fixture = SubscriptionUpdateFixture("yaml", "unique", 1)
        fixture.prepare()
        try {
            val before = SagerDatabase.groupDao.getById(fixture.groupId)!!
            val edited = SagerDatabase.groupDao.getById(fixture.groupId)!!
            edited.proxyServerNameserver = "192.0.2.53"
            SagerDatabase.groupDao.updateGroup(edited)
            fixture.file.writeText("dns:\n  proxy-server-nameserver: [192.0.2.54]\n" + fixture.text())
            RawUpdater.doUpdate(before, before.subscription!!, UpdateReport(), false, SagerDatabase.dataGeneration())
            val after = SagerDatabase.groupDao.getById(fixture.groupId)!!
            assertEquals("192.0.2.53", after.proxyServerNameserver)
            assertEquals(false, after.subscription!!.nameserverFromSubscription)
            fixture.verify(443)
            fixture.update()
            val next = SagerDatabase.groupDao.getById(fixture.groupId)!!
            assertEquals("192.0.2.54", next.proxyServerNameserver)
            assertEquals(true, next.subscription!!.nameserverFromSubscription)
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun `WireGuard 导入验证接口和每个 peer 的有效范围`() {
        val key = "A".repeat(43) + "="
        val iface = "[Interface]\nAddress = 192.0.2.5/32\nPrivateKey = $key\n"
        val peer = "[Peer]\nPublicKey = $key\nEndpoint = 192.0.2.2:51820\n"
        val restricted = peer + "AllowedIPs = 192.0.2.0/24\nAllowedIPs = 2001:db8::/32\n"

        for (bad in listOf(iface.replace("/32", "/99"), iface.replace(key, "invalid"))) {
            assertThrows(IllegalArgumentException::class.java) { parseWireGuard(bad + peer) }
        }
        for (bad in listOf(
            peer.replace(key, "invalid"), peer + "PresharedKey = invalid\n",
            peer + "AllowedIPs = 192.0.2.0/24, invalid\n", peer + "AllowedIPs = ,\n",
        )) {
            assertTrue(runCatching { parseWireGuard(iface + bad) }.isFailure)
            val imported = parseWireGuard(iface + bad + restricted).single()
            assertEquals("192.0.2.0/24,2001:db8::/32", imported.peerAllowedIps)
        }
        assertEquals("", parseWireGuard(iface + peer).single().peerAllowedIps)
    }

    @Test
    fun `YAML 和链接订阅的首次导入重复更新及批量改动保持节点与本地状态`() = ui.bg {
        for (format in listOf("yaml", "links")) for (pattern in listOf("unique", "same-name", "duplicates")) {
            val fixture = SubscriptionUpdateFixture(format, pattern, 100)
            fixture.prepare()
            try {
                fixture.file.writeText(fixture.text())
                val first = fixture.update()
                assertEquals(fixture.expectedCount, first.added)
                assertEquals(fixture.expectedCount, first.changed)
                assertEquals(if (pattern == "duplicates") 50 else 0, first.duplicates)
                fixture.verify(443)
                val ids = SagerDatabase.proxyDao.getIdsByGroup(fixture.groupId).sorted()
                ids.forEach {
                    SagerDatabase.proxyDao.updateTraffic(it, 1234, 5678)
                    SagerDatabase.proxyDao.updateStatus(it, 1, 42, "old test error")
                }
                DataStore.selectedProxy = ids.last()
                val unchanged = fixture.update()
                assertEquals(0, unchanged.changed)
                SagerDatabase.proxyDao.getByGroup(fixture.groupId).forEach {
                    assertEquals(1, it.status)
                    assertEquals(42, it.ping)
                    assertEquals("old test error", it.error)
                }
                fixture.file.writeText(fixture.text(8443))
                val changed = fixture.update()
                assertEquals(fixture.expectedCount, changed.updated)
                assertEquals(fixture.expectedCount, changed.changed)
                assertEquals(0, changed.added)
                assertEquals(0, changed.deleted)
                fixture.verify(8443)
                assertEquals(ids, SagerDatabase.proxyDao.getIdsByGroup(fixture.groupId).sorted())
                assertEquals(ids.last(), DataStore.selectedProxy)
                SagerDatabase.proxyDao.getByGroup(fixture.groupId).forEach {
                    assertEquals(1234L, it.tx)
                    assertEquals(5678L, it.rx)
                    assertEquals(0, it.status)
                    assertEquals(0, it.ping)
                    assertNull(it.error)
                }
            } finally {
                fixture.cleanup()
            }
        }
    }

    @Test
    fun `解析失败不会改变已有节点分组及选中状态`() = ui.bg {
        val fixture = SubscriptionUpdateFixture("yaml", "unique", 100)
        fixture.prepare()
        try {
            fixture.file.writeText(fixture.text())
            fixture.update()
            DataStore.selectedProxy = SagerDatabase.proxyDao.getByGroup(fixture.groupId).last().id
            val before = fixture.snapshot()
            for (text in listOf(
                "[Interface]\nAddress = 192.0.2.5/99\nPrivateKey = invalid\n[Peer]\nPublicKey = invalid\nEndpoint = 192.0.2.2:51820",
                "proxies:\n  - {name: broken, type: trojan, server: test.example.com, port: invalid, password: fictional}\n",
                "This file contains no proxy links.",
            )) {
                fixture.file.writeText(text)
                val failure = runCatching { fixture.update() }.exceptionOrNull()
                assertNotNull(failure)
                if (text.startsWith("proxies:")) assertTrue(failure.toString(), failure is ClashImportException)
                assertEquals(before, fixture.snapshot())
            }
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun `写入中途失败回滚已插入和已更新节点及订阅时间`() = ui.bg {
        val fixture = SubscriptionUpdateFixture("yaml", "unique", 100)
        fixture.prepare()
        val db = SagerDatabase.instance.openHelper.writableDatabase
        try {
            fixture.file.writeText(fixture.text())
            fixture.update()
            val rows = SagerDatabase.proxyDao.getByGroup(fixture.groupId)
            DataStore.selectedProxy = rows.last().id
            val before = fixture.snapshot()
            val firstBlob = db.query("SELECT hex(trojanBean) FROM proxy_entities WHERE id = ${rows.first().id}").use {
                check(it.moveToFirst())
                it.getString(0)
            }
            // 新节点先插入，随后更新到中间行才报错；触发器同时确认之前的写入确实发生了。
            val added = "  - {name: extra, type: trojan, server: extra.example.com, port: 443, password: fictional}\n"
            fixture.file.writeText(fixture.text(8443).lineSequence()
                .filterNot { "name: 'node-99'" in it }.joinToString("\n") + added)
            db.execSQL("""
                CREATE TRIGGER fail_subscription_update BEFORE UPDATE OF trojanBean ON proxy_entities
                WHEN OLD.id = ${rows[50].id}
                BEGIN
                    SELECT CASE WHEN (SELECT COUNT(*) FROM proxy_entities) = 101
                        AND (SELECT hex(trojanBean) FROM proxy_entities WHERE id = ${rows.first().id}) != '$firstBlob'
                        THEN RAISE(ABORT, 'injected subscription write failure')
                        ELSE RAISE(ABORT, 'unexpected injection state') END;
                END
            """.trimIndent())
            val failure = runCatching { fixture.update() }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure.toString(), failure!!.message.orEmpty().contains("injected subscription write failure"))
            assertEquals(before, fixture.snapshot())
            db.execSQL("DROP TRIGGER fail_subscription_update")
            // 故障解除后同一份订阅可以重试成功。
            val report = fixture.update()
            assertEquals(1, report.added)
            assertEquals(1, report.deleted)
            assertEquals(99, report.updated)
            assertEquals(100L, SagerDatabase.proxyDao.countByGroup(fixture.groupId))
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS fail_subscription_update")
            fixture.cleanup()
        }
    }
}
