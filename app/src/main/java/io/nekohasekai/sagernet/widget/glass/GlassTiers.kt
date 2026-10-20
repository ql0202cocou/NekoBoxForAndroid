package io.nekohasekai.sagernet.widget.glass

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.database.DataStore

/**
 * [GlassPolicy] 的 Android 一侧：收集设备条件（API、低内存设备、省电模式）与用户选择，给每个表面一个档位。
 * 一个 MainActivity 一个，只在主线程使用。
 *
 * 条件变化时（设置项被改、省电模式开关）由宿主调 [refresh]，档位变了就通知 [Listener]；
 * 不重建 Activity。省电模式的广播只在界面可见期间（[start] 到 [stop]）监听，回到前台时重算一次。
 * 「画布是否硬件加速」是每次绘制才知道的，所以由绘制端经 [tier] 的参数传入，不进缓存。
 */
class GlassTiers(private val context: Context) {

    fun interface Listener {
        /** 至少一个表面的档位变了；绘制端据此重建材质并重绘 */
        fun onGlassTiersChanged()
    }

    private class Snapshot(val choice: GlassChoice, val lowRam: Boolean, val powerSave: Boolean)

    private val listeners = ArrayList<Listener>()
    private var snapshot: Snapshot? = null
    private var receiver: BroadcastReceiver? = null

    fun addListener(listener: Listener) {
        if (listeners.none { it === listener }) listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners.removeAll { it === listener }
    }

    /** [target] 现在该用的档位。UI 测试可经 [testOverride] 整体压成固定档位 */
    fun tier(target: GlassTarget, hardwareAccelerated: Boolean = true): GlassTier {
        testOverride?.let { return it }
        val s = snapshot ?: read().also { snapshot = it }
        return GlassPolicy.resolve(
            s.choice, target, Build.VERSION.SDK_INT, hardwareAccelerated, s.lowRam, s.powerSave,
        )
    }

    /** 重新读取用户选择与设备条件；任一表面的档位因此改变（硬件加速按可用算）则通知监听者 */
    fun refresh() {
        val before = GlassTarget.entries.map { tier(it) }
        snapshot = read()
        val after = GlassTarget.entries.map { tier(it) }
        if (before != after) listeners.toList().forEach { it.onGlassTiersChanged() }
    }

    /** 界面可见时调用：开始监听省电模式，并重算一次（停止期间条件可能已变） */
    fun start() {
        if (receiver == null) {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = refresh()
            }.also {
                ContextCompat.registerReceiver(
                    context, it, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
        }
        refresh()
    }

    fun stop() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    private fun read(): Snapshot {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return Snapshot(
            choice = GlassChoice.fromStored(DataStore.glassMaterial),
            lowRam = activity?.isLowRamDevice == true,
            powerSave = power?.isPowerSaveMode == true,
        )
    }

    companion object {
        /**
         * 测试注入点：非空时所有表面都取这个档位，不看用户选择与设备条件。
         * UI 测试（UiTestRule）默认压成 [GlassTier.SOLID]，截图基线因此不受材质影响；
         * 要测真实策略的用例自己置 null。不用于产品代码。
         */
        @VisibleForTesting
        @Volatile
        var testOverride: GlassTier? = null
    }
}
