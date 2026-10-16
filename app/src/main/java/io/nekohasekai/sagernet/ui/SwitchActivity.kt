package io.nekohasekai.sagernet.ui

import android.os.Bundle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class SwitchActivity : ThemedActivity(R.layout.layout_empty),
    ConfigurationFragment.SelectCallback {

    override val isDialog = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 同 ProfileSelectActivity：进程恢复时 Fragment 已按 arguments 重建
        if (savedInstanceState != null) return
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment.newSelectInstance(null, R.string.action_switch)
            )
            .commitAllowingStateLoss()
    }

    override fun returnProfile(profileId: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = profileId
        // postUpdate(id) 要重读这一行，放到后台；监听器本来就在后台回调（同 MainActivity.cbSelectorUpdate）
        runOnDefaultDispatcher {
            ProfileRepository.postUpdate(old)
            ProfileRepository.postUpdate(profileId)
        }
        SagerNet.reloadService()
        finish()
    }
}