package io.nekohasekai.sagernet.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.fmt.displayType
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// 测试任务跑在 appScope 上，可能比界面活得久（旋转、退出）：这里对 Fragment、
// 对话框和其中的控件都只弱引用，旧 Activity 能及时回收，任务照常跑完并写回结果
class TestDialog(fragment: ConfigurationFragment) {
    private val fragmentRef = WeakReference(fragment)
    private val nowTestingRef: WeakReference<TextView>
    private val progressRef: WeakReference<TextView>
    private val dialogRef: WeakReference<AlertDialog>

    lateinit var cancel: () -> Unit
    lateinit var minimize: () -> Unit

    val dialogStatus = AtomicInteger(0) // 1: hidden 2: cancelled
    var notification: ConnectionTestNotification? = null

    val results: MutableSet<ProxyEntity> = ConcurrentHashMap.newKeySet()
    var proxyN = 0
    val finishedN = AtomicInteger(0)

    init {
        val binding = LayoutProgressListBinding.inflate(fragment.layoutInflater)
        nowTestingRef = WeakReference(binding.nowTesting)
        progressRef = WeakReference(binding.progress)
        dialogRef = WeakReference(
            MaterialAlertDialogBuilder(fragment.requireContext()).setView(binding.root)
                .setPositiveButton(R.string.minimize) { _, _ ->
                    minimize()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    cancel()
                }
                .setCancelable(false)
                .show()
        )
    }

    // Activity 已销毁时 dismiss() 会抛 "not attached to window manager"
    fun dismiss() {
        runCatching { dialogRef.get()?.dismiss() }
    }

    fun hide() {
        dialogRef.get()?.hide()
    }

    fun update(profile: ProxyEntity) {
        if (dialogStatus.get() != 2) {
            results.add(profile)
        }
        runOnMainDispatcher {
            val progress = finishedN.addAndGet(1)
            val status = dialogStatus.get()
            notification?.updateNotification(
                progress,
                proxyN,
                progress >= proxyN || status == 2
            )
            if (status >= 1) return@runOnMainDispatcher
            val fragment = fragmentRef.get() ?: return@runOnMainDispatcher
            if (!fragment.isAdded) return@runOnMainDispatcher
            val context = fragment.requireContext()

            // refresh dialog

            var profileStatusText: String? = null
            var profileStatusColor = 0

            when (profile.status) {
                -1 -> {
                    profileStatusText = profile.error
                    profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                }

                0 -> {
                    profileStatusText = fragment.getString(R.string.connection_test_testing)
                    profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                }

                1 -> {
                    profileStatusText = fragment.getString(R.string.available, profile.ping)
                    profileStatusColor = context.getColour(R.color.material_green_500)
                }

                2 -> {
                    profileStatusText = profile.error
                    profileStatusColor = context.getColour(R.color.material_red_500)
                }

                3 -> {
                    profileStatusText = context.unavailableText(profile.error)
                    profileStatusColor = context.getColour(R.color.material_red_500)
                }
            }

            val text = SpannableStringBuilder().apply {
                append("\n" + profile.displayName())
                append("\n")
                append(
                    profile.displayType(),
                    ForegroundColorSpan(context.getProtocolColor(profile.type)),
                    SPAN_EXCLUSIVE_EXCLUSIVE
                )
                append(" ")
                append(
                    profileStatusText,
                    ForegroundColorSpan(profileStatusColor),
                    SPAN_EXCLUSIVE_EXCLUSIVE
                )
                append("\n")
            }

            nowTestingRef.get()?.text = text
            progressRef.get()?.text = "$progress / $proxyN"
        }
    }

}

// 测试失败（status 3）的状态文字：能归类的错误给友好提示，其余一律「不可用」
fun Context.unavailableText(error: String?): String {
    val err = error ?: ""
    val msg = Protocols.genFriendlyMsg(err)
    return if (msg != err) msg else getString(R.string.unavailable)
}
