package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import androidx.core.content.IntentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity

class ProfileSelectActivity : ThemedActivity(R.layout.layout_empty),
    ConfigurationFragment.SelectCallback {

    companion object {
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_PROFILE_ID = "id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 进程恢复时系统已按 arguments 重建了 Fragment，再 replace 会丢掉分组页状态
        if (savedInstanceState != null) return
        val selected = IntentCompat.getParcelableExtra(intent, EXTRA_SELECTED, ProxyEntity::class.java)

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment.newSelectInstance(selected, R.string.select_profile)
            )
            .commitAllowingStateLoss()
    }

    override fun returnProfile(profileId: Long) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_PROFILE_ID, profileId)
        })
        finish()
    }

}