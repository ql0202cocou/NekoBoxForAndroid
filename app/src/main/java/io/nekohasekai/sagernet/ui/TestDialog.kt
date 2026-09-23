package io.nekohasekai.sagernet.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TestDialog(private val fragment: ConfigurationFragment) {
    val binding = LayoutProgressListBinding.inflate(fragment.layoutInflater)
    val builder = MaterialAlertDialogBuilder(fragment.requireContext()).setView(binding.root)
        .setPositiveButton(R.string.minimize) { _, _ ->
            minimize()
        }
        .setNegativeButton(android.R.string.cancel) { _, _ ->
            cancel()
        }
        .setCancelable(false)

    lateinit var cancel: () -> Unit
    lateinit var minimize: () -> Unit

    val dialogStatus = AtomicInteger(0) // 1: hidden 2: cancelled
    var notification: ConnectionTestNotification? = null

    val results: MutableSet<ProxyEntity> = ConcurrentHashMap.newKeySet()
    var proxyN = 0
    val finishedN = AtomicInteger(0)

    fun update(profile: ProxyEntity) {
        if (dialogStatus.get() != 2) {
            results.add(profile)
        }
        runOnMainDispatcher {
            val context = fragment.context ?: return@runOnMainDispatcher
            val progress = finishedN.addAndGet(1)
            val status = dialogStatus.get()
            notification?.updateNotification(
                progress,
                proxyN,
                progress >= proxyN || status == 2
            )
            if (status >= 1) return@runOnMainDispatcher
            if (!fragment.isAdded) return@runOnMainDispatcher

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

            binding.nowTesting.text = text
            binding.progress.text = "$progress / $proxyN"
        }
    }

}

// 测试失败（status 3）的状态文字：能归类的错误给友好提示，其余一律「不可用」
fun Context.unavailableText(error: String?): String {
    val err = error ?: ""
    val msg = Protocols.genFriendlyMsg(err)
    return if (msg != err) msg else getString(R.string.unavailable)
}
