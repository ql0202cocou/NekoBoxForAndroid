package io.nekohasekai.sagernet.ui

import android.net.Uri
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.IMPORT_PREVIEW_LINK_MAX
import io.nekohasekai.sagernet.group.IMPORT_PREVIEW_NAME_MAX
import io.nekohasekai.sagernet.group.SubscriptionPreset
import io.nekohasekai.sagernet.group.subscriptionPresets
import io.nekohasekai.sagernet.group.truncateForDisplay
import io.nekohasekai.sagernet.group.isHttpSubscriptionLink
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import moe.matsuri.nb4a.utils.Util

/**
 * 深链与应用内导入的订阅 / 节点确认流程（从 MainActivity 拆出，逻辑与调用顺序不变）。
 * 由 MainActivity 的字段持有宿主，随它一起销毁；跑在 appScope 的协程捕获的仍是同一个 Activity，
 * 与这些方法原先直接写在 MainActivity 里时一致
 */
class MainImporter(private val activity: MainActivity) {

    // 导入链跑在 appScope（深链、配置页的剪贴板 / 文件导入都会进来），解析期间
    // 旋转或退出后 Activity 已销毁：再弹窗会 BadTokenException，提交 Fragment 会抛
    // "Activity has been destroyed"。这时放弃界面部分，已写库的导入不受影响
    private suspend fun onLiveActivity(block: () -> Unit) = onMainDispatcher {
        if (!activity.isFinishing && !activity.isDestroyed) block()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // 明文格式
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onLiveActivity {
                    activity.alert(e.readableMessage).show()
                }
                return
            }
        }

        // 两种形态（?url= 明文与编码的分组）汇合后统一校验：深链由外部网页 / 应用
        // 发起，订阅地址只接受 http(s)。content:// 等本地来源只供用户在应用内自己
        // 选择的文件使用，不能由外部链接指定
        val link = group.subscription?.link
        if (!link.isNullOrBlank()) {
            if (!isHttpSubscriptionLink(link)) {
                onLiveActivity {
                    activity.alert(activity.getString(R.string.invalid_subscription_url)).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        group.name = group.name.takeIf { !it.isNullOrBlank() }
            ?: ("Subscription #" + System.currentTimeMillis())

        onLiveActivity {

            activity.displayFragmentWithId(R.id.nav_group)

            // 深链内容来自外部且可能极长：确认框里的名称、链接与预设值先截断再显示，
            // 导入的仍是完整值
            val message = activity.getString(
                R.string.subscription_import_message, truncateForDisplay(name, IMPORT_PREVIEW_NAME_MAX)
            )
            val presets = subscriptionPresets(
                group.proxyServerNameserver, group.subscription?.forceResolve, group.subscription?.customUserAgent
            ).joinToString("\n") { (field, value) ->
                val label = when (field) {
                    SubscriptionPreset.PROXY_SERVER_NAMESERVER -> activity.getString(R.string.proxy_server_nameserver)
                    SubscriptionPreset.FORCE_RESOLVE -> activity.getString(R.string.force_resolve)
                    SubscriptionPreset.CUSTOM_USER_AGENT -> activity.getString(R.string.subscription_user_agent)
                }
                "$label: $value"
            }
            val shownLink = link?.takeIf { it.isNotBlank() }?.let { truncateForDisplay(it, IMPORT_PREVIEW_LINK_MAX) }
            val details = listOfNotNull(
                shownLink,
                presets.takeIf { it.isNotEmpty() }?.let { activity.getString(R.string.subscription_import_presets) + "\n" + it },
            ).joinToString("\n\n")

            MaterialAlertDialogBuilder(activity).setTitle(R.string.subscription_import)
                .setMessage(if (details.isNotEmpty()) "$message\n\n$details" else message)
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(activity.getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onLiveActivity {
                activity.alert(e.readableMessage).show()
            }
            return
        }

        onLiveActivity {
            MaterialAlertDialogBuilder(activity).setTitle(R.string.profile_import)
                .setMessage(activity.getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = GroupManager.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onLiveActivity {
            activity.displayFragmentWithId(R.id.nav_configuration)

            activity.snackbar(activity.resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }
}
