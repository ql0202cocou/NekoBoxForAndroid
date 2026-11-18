package io.nekohasekai.sagernet.uitest

import android.os.Parcel
import android.os.Parcelable
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.BackupRepository
import io.nekohasekai.sagernet.database.BackupRestore
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.ui.profile.SSHSettingsActivity
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 真实编辑器、Room、分享与备份入口；凭据全为虚构标记，不连接 SSH 服务。 */
class SSHAuthSwitchTest : UiTestBase() {
    @Test fun passwordToKey() = verifySwitch(PASSWORD, PRIVATE_KEY)
    @Test fun keyToPassword() = verifySwitch(PRIVATE_KEY, PASSWORD)
    @Test fun passwordToNone() = verifySwitch(PASSWORD, NONE)
    @Test fun keyToNone() = verifySwitch(PRIVATE_KEY, NONE)
    @Test fun noneToPassword() = verifySwitch(NONE, PASSWORD)
    @Test fun noneToKey() = verifySwitch(NONE, PRIVATE_KEY)

    private fun verifySwitch(from: Int, to: Int) {
        val original = install(from)
        val editor = ui.launchEditor<SSHSettingsActivity>(original.id)
        val page = editor.get()
        assertCredentials(page.proxyEntity!!.sshBean!!, from, old = true)
        page.selectAuth(to)
        page.fillCredentials(to)
        val updates = mutableListOf<ProxyEntity>()
        val listener = object : ProfileManager.Listener {
            override suspend fun onAdd(profile: ProxyEntity) {}
            override suspend fun onUpdated(data: TrafficData) {}
            override suspend fun onUpdated(profile: ProxyEntity) { updates += profile }
            override suspend fun onRemoved(groupId: Long, profileId: Long) {}
        }
        ProfileManager.addListener(listener)
        try {
            saveEditor(page)
            assertTrue(page.isFinishing)
            val saved = ui.bg { ProfileRepository.getProfile(original.id)!! }
            assertEquals(original.id, saved.id)
            assertEquals(original.groupId, saved.groupId)
            assertCredentials(saved.sshBean!!, to)
            assertCredentials(updates.single().sshBean!!, to)
            assertStoredCredentials(saved.id, to)

            // 保存用的临时对象允许带旧字段；所有对外入口必须按当前认证方式裁剪。
            // 特别是密码切到 NONE：底层 SSH 生成器的默认分支不能从临时对象带入旧密码。
            val inMemory = page.proxyEntity!!
            for (entity in listOf(inMemory, saved)) {
                assertCredentials(entity.requireBean().clone() as SSHBean, to)
                assertCredentials(parseUniversal(standardLink(entity.requireBean())) { false } as SSHBean, to)
                assertBuiltCredentials(entity, to)
            }
            assertBackupCredentials(saved.id, to)
        } finally {
            ProfileManager.removeListener(listener)
            ui.destroyNow(editor)
        }

        val reopened = ui.launchEditor<SSHSettingsActivity>(original.id)
        assertEquals(to, EditorCache.serverAuthType)
        assertEquals(if (to == PASSWORD) NEW_PASSWORD else "", EditorCache.serverPassword)
        assertEquals(if (to == PRIVATE_KEY) NEW_KEY else "", EditorCache.serverPrivateKey)
        assertEquals(if (to == PRIVATE_KEY) NEW_PASSPHRASE else "", EditorCache.serverPassword1)
        assertCredentials(reopened.get().proxyEntity!!.sshBean!!, to)
        ui.destroyNow(reopened)
    }

    @Test
    fun `同一草稿切回原认证保留输入但放弃修改后重开只读已保存凭据`() {
        val original = install(PASSWORD)
        val before = storedBytes(original.id)
        val editor = ui.launchEditor<SSHSettingsActivity>(original.id)
        editor.get().selectAuth(PRIVATE_KEY)
        editor.get().fillCredentials(PRIVATE_KEY)
        editor.get().selectAuth(PASSWORD)
        assertEquals(OLD_PASSWORD, EditorCache.serverPassword)
        assertEquals(NEW_KEY, EditorCache.serverPrivateKey)
        assertEquals(NEW_PASSPHRASE, EditorCache.serverPassword1)
        // 仅关闭页面，不经过保存；下一次顶层编辑会话会清空上一份内存草稿。
        ui.destroyNow(editor)
        assertArrayEquals(before, storedBytes(original.id))
        val reopened = ui.launchEditor<SSHSettingsActivity>(original.id)
        assertCredentials(reopened.get().proxyEntity!!.sshBean!!, PASSWORD, old = true)
        assertEquals(OLD_PASSWORD, EditorCache.serverPassword)
        assertEquals("", EditorCache.serverPrivateKey)
        assertEquals("", EditorCache.serverPassword1)
        ui.destroyNow(reopened)
    }

    @Test
    fun `新节点填过密码和私钥后选择无认证不会保存或通知这些凭据`() {
        val existing = install(PASSWORD)
        val editor = ui.launchEditor<SSHSettingsActivity>(0)
        val page = editor.get()
        page.child!!.findPreference<EditTextPreference>(Key.SERVER_ADDRESS)!!.text = "ssh.example.com"
        page.selectAuth(PASSWORD)
        page.fillCredentials(PASSWORD)
        page.selectAuth(PRIVATE_KEY)
        page.fillCredentials(PRIVATE_KEY)
        page.selectAuth(NONE)
        val added = mutableListOf<ProxyEntity>()
        val listener = object : ProfileManager.Listener {
            override suspend fun onAdd(profile: ProxyEntity) { added += profile }
            override suspend fun onUpdated(data: TrafficData) {}
            override suspend fun onUpdated(profile: ProxyEntity) {}
            override suspend fun onRemoved(groupId: Long, profileId: Long) {}
        }
        ProfileManager.addListener(listener)
        try {
            saveEditor(page)
            assertTrue(page.isFinishing)
            val saved = ui.bg { ProfileRepository.getProfilesByGroup(existing.groupId).single { it.id != existing.id } }
            for (bean in listOf(saved.sshBean!!, added.single().sshBean!!)) {
                assertEquals(NONE, bean.authType)
                assertEquals("", bean.password)
                assertEquals("", bean.privateKey)
                assertEquals("", bean.privateKeyPassphrase)
            }
            assertStoredCredentials(saved.id, NONE)
        } finally {
            ProfileManager.removeListener(listener)
            ui.destroyNow(editor)
        }
    }

    private fun install(auth: Int): ProxyEntity = ui.bg {
        val group = GroupRepository.createGroup(ProxyGroup(name = "SSH test"))
        DataStore.selectedGroup = group.id
        DataStore.rulesFirstCreate = true
        ProfileRepository.createProfile(group.id, SSHBean().apply {
            name = "SSH fixture"
            serverAddress = "ssh.example.com"
            serverPort = 2222
            username = "fixture-user"
            authType = auth
            if (auth == PASSWORD) password = OLD_PASSWORD
            if (auth == PRIVATE_KEY) {
                privateKey = OLD_KEY
                privateKeyPassphrase = OLD_PASSPHRASE
            }
            publicKey = HOST_KEY
        })
    }

    private fun SSHSettingsActivity.selectAuth(auth: Int) {
        val pref = child!!.findPreference<ListPreference>(Key.SERVER_AUTH_TYPE)!!
        assertTrue(pref.callChangeListener(auth.toString()))
        pref.value = auth.toString()
        UiSettle.settle()
        assertEquals(auth, EditorCache.serverAuthType)
        assertEquals(auth == PASSWORD, child!!.findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.isVisible)
        assertEquals(auth == PRIVATE_KEY, child!!.findPreference<EditTextPreference>(Key.SERVER_PRIVATE_KEY)!!.isVisible)
        assertEquals(auth == PRIVATE_KEY, child!!.findPreference<EditTextPreference>(Key.SERVER_PASSWORD1)!!.isVisible)
    }

    private fun SSHSettingsActivity.fillCredentials(auth: Int) {
        if (auth == PASSWORD) child!!.findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.text = NEW_PASSWORD
        if (auth == PRIVATE_KEY) {
            child!!.findPreference<EditTextPreference>(Key.SERVER_PRIVATE_KEY)!!.text = NEW_KEY
            child!!.findPreference<EditTextPreference>(Key.SERVER_PASSWORD1)!!.text = NEW_PASSPHRASE
        }
        UiSettle.settle()
    }

    private fun assertCredentials(bean: SSHBean, auth: Int, old: Boolean = false) {
        assertEquals(auth, bean.authType)
        assertEquals(if (auth == PASSWORD) (if (old) OLD_PASSWORD else NEW_PASSWORD) else "", bean.password)
        assertEquals(if (auth == PRIVATE_KEY) (if (old) OLD_KEY else NEW_KEY) else "", bean.privateKey)
        assertEquals(if (auth == PRIVATE_KEY) (if (old) OLD_PASSPHRASE else NEW_PASSPHRASE) else "", bean.privateKeyPassphrase)
        // 服务端主机密钥校验与客户端认证独立，切换时必须保留。
        assertEquals(HOST_KEY, bean.publicKey)
        assertEquals("SSH fixture", bean.name)
        assertEquals("ssh.example.com", bean.serverAddress)
        assertEquals(2222, bean.serverPort)
        assertEquals("fixture-user", bean.username)
    }

    private fun storedBytes(id: Long) = ui.bg {
        SagerDatabase.instance.openHelper.readableDatabase.query(
            "SELECT sshBean FROM proxy_entities WHERE id = ?", arrayOf<Any>(id)
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getBlob(0) }
    }

    private fun assertStoredCredentials(id: Long, auth: Int) {
        // Kryo ASCII 字符串的末字节带结束位；检查其余完整前缀，并以当前凭据存在作正对照。
        val bytes = storedBytes(id).toString(Charsets.ISO_8859_1)
        val active = when (auth) {
            PASSWORD -> setOf(NEW_PASSWORD)
            PRIVATE_KEY -> setOf(NEW_KEY, NEW_PASSPHRASE)
            else -> emptySet()
        }
        for (marker in listOf(OLD_PASSWORD, OLD_KEY, OLD_PASSPHRASE, NEW_PASSWORD, NEW_KEY, NEW_PASSPHRASE)) {
            assertEquals(marker, marker in active, bytes.contains(marker.dropLast(1)))
        }
    }

    private fun assertBuiltCredentials(entity: ProxyEntity, auth: Int) {
        for (mode in ConfigBuildMode.entries) {
            val result = ui.bg {
                val input = captureConfigInput(entity, mode)
                assertCredentials(input.main.newEntity().sshBean!!, auth)
                // 使用真实 Android 快照和配置构建，只替换需要 JNI 的核心试建；不声称验证 SSH 握手。
                var trials = 0
                val platform = object : ConfigPlatform by input.platform {
                    override fun trialSingBoxOutbound(kind: SingBoxTrialKind, json: String): SingBoxTrialFailure? {
                        trials++
                        assertOutbound(JsonParser.parseString(json).asJsonObject, auth)
                        return null
                    }
                }
                buildConfig(ConfigInput(mode, input.main, input.settings, input.data, input.packageUids, platform)).also {
                    assertEquals("每种模式都应检查实际交给核心试建的 SSH 出站", 1, trials)
                }
            }
            val outbound = JsonParser.parseString(result.config).asJsonObject.getAsJsonArray("outbounds")
                .map { it.asJsonObject }.single { it["type"].asString == "ssh" }
            assertOutbound(outbound, auth)
        }
    }

    private fun assertOutbound(outbound: JsonObject, auth: Int) {
        assertEquals(if (auth == PASSWORD) NEW_PASSWORD else "", outbound["password"]?.asString.orEmpty())
        assertEquals(if (auth == PRIVATE_KEY) NEW_KEY else "", outbound["private_key"]?.asString.orEmpty())
        assertEquals(if (auth == PRIVATE_KEY) NEW_PASSPHRASE else "", outbound["private_key_passphrase"]?.asString.orEmpty())
        assertEquals(listOf(HOST_KEY), outbound.getAsJsonArray("host_key").map { it.asString })
    }

    private fun assertBackupCredentials(id: Long, auth: Int) {
        val snapshot = ui.bg { BackupRepository.readSnapshot(profile = true, rule = false)!! }
        val content = JSONObject()
            .put("profiles", JSONArray(snapshot.profiles!!.map(::encoded)))
            .put("groups", JSONArray(snapshot.groups!!.map(::encoded)))
        val restored = BackupRestore.decode(content, profile = true, rule = false, setting = false)
        assertEquals(0, restored.skipped)
        assertCredentials(restored.profiles!!.single { it.id == id }.sshBean!!, auth)
    }

    private fun encoded(value: Parcelable): String {
        val parcel = Parcel.obtain()
        return try {
            value.writeToParcel(parcel, 0)
            Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    private companion object {
        const val NONE = SSHBean.AUTH_TYPE_NONE
        const val PASSWORD = SSHBean.AUTH_TYPE_PASSWORD
        const val PRIVATE_KEY = SSHBean.AUTH_TYPE_PRIVATE_KEY
        const val OLD_PASSWORD = "fixture-old-password-Z"
        const val OLD_KEY = "fixture-old-private-key-Z"
        const val OLD_PASSPHRASE = "fixture-old-key-passphrase-Z"
        const val NEW_PASSWORD = "fixture-new-password-Z"
        const val NEW_KEY = "fixture-new-private-key-Z"
        const val NEW_PASSPHRASE = "fixture-new-key-passphrase-Z"
        const val HOST_KEY = "ssh-ed25519 fixture-server-host-key"
    }
}
