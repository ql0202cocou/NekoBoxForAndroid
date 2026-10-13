package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.database.EditorCache

class NaiveSettingsActivity : ProfileSettingsActivity<NaiveBean>() {

    override fun createEntity() = NaiveBean()

    override fun NaiveBean.init() {
        EditorCache.profileName = name
        EditorCache.serverAddress = serverAddress
        EditorCache.serverPort = serverPort
        EditorCache.serverUsername = username
        EditorCache.serverPassword = password
        EditorCache.serverProtocol = proto
        EditorCache.serverSNI = sni
        EditorCache.serverCertificates = certificates
        EditorCache.serverHeaders = extraHeaders
        EditorCache.serverInsecureConcurrency = insecureConcurrency
        EditorCache.profileCacheStore.putBoolean("sUoT", sUoT)
    }

    override fun NaiveBean.serialize() {
        name = EditorCache.profileName
        serverAddress = EditorCache.serverAddress
        serverPort = EditorCache.serverPort
        username = EditorCache.serverUsername
        password = EditorCache.serverPassword
        proto = EditorCache.serverProtocol
        sni = EditorCache.serverSNI
        certificates = EditorCache.serverCertificates
        extraHeaders = EditorCache.serverHeaders.replace("\r\n", "\n")
        insecureConcurrency = EditorCache.serverInsecureConcurrency
        sUoT = EditorCache.profileCacheStore.getBoolean("sUoT")
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.naive_preferences)
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.bindPasswordPreference()
        findPreference<EditTextPreference>(Key.SERVER_INSECURE_CONCURRENCY)!!.bindIntegerPreference()
    }

    // 只在保存（save 走到 finish）时切换彩蛋；放弃修改、校验不过、会话已失效都不切换
    private var saving = false

    override suspend fun save() {
        saving = true
        try {
            super.save()
        } finally {
            saving = false
        }
    }

    override fun finish() {
        if (saving) {
            if (EditorCache.profileName == "喵要打开隐藏功能") {
                DataStore.isExpert = true
            } else if (EditorCache.profileName == "喵要关闭隐藏功能") {
                DataStore.isExpert = false
            }
        }
        super.finish()
    }

}