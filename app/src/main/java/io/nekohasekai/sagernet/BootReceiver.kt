package io.nekohasekai.sagernet

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RestoreJournal
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class BootReceiver : BroadcastReceiver() {
    companion object {
        private val componentName by lazy { ComponentName(app, BootReceiver::class.java) }
        var enabled: Boolean
            get() = app.packageManager.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            set(value) = app.packageManager.setComponentEnabledSetting(
                componentName, if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
    }

    override fun onReceive(context: Context, intent: Intent) {
        // exported receiver: ignore anything outside the declared intent-filter
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit

            else -> return
        }

        // Keep the process alive until WorkManager acknowledges scheduling,
        // rather than finishing immediately after submitting the Binder call.
        val pendingResult = goAsync()
        runOnDefaultDispatcher {
            try {
                SubscriptionUpdater.reconfigureUpdater()
            } finally {
                pendingResult.finish()
            }
        }

        if (!DataStore.persistAcrossReboot) {   // sanity check
            enabled = false
            return
        }

        val doStart = when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> false // DataStore.directBootAware
            else -> SagerNet.user.isUserUnlocked
        } && DataStore.selectedProxy > 0 &&
            // an interrupted restore that could not be replayed yet: the data is half-old
            !RestoreJournal.default.isPending()

        if (doStart) SagerNet.startService()
    }
}
