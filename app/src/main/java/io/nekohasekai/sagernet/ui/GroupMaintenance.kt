package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.displayType
import io.nekohasekai.sagernet.ktx.confirm
import io.nekohasekai.sagernet.ktx.displayName
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import moe.matsuri.nb4a.Protocols

/**
 * 配置页菜单里对当前分组的维护操作：清除流量、清除测试结果、删除不可用、去重
 * （从 ConfigurationFragment 拆出，逻辑与协程作用域不变）。
 * 由 ConfigurationFragment 的字段持有，只存 fragment 引用，生命周期与原先这些代码写在 fragment 里一致；
 * 各操作跑在 appScope，读库期间 fragment 可能已销毁，涉及界面的部分先判断 isAdded
 */
class GroupMaintenance(private val fragment: ConfigurationFragment) {

    fun clearTraffic() {
        runOnDefaultDispatcher {
            val toClear = ProfileRepository.getProfilesByGroup(GroupRepository.currentGroupId())
                .filter { it.tx != 0L || it.rx != 0L }
            if (toClear.isNotEmpty()) {
                ProfileRepository.clearTraffic(toClear)
                // :bg 自己也有一份计数；不通知它的话，运行中的 looper 会把清除前的总量又写回库里
                SagerNet.clearTrafficStatistics(toClear.map { it.id }.toLongArray())
                // 单列写库不会发通知，这里手动刷新列表
                for (profile in toClear) ProfileRepository.postUpdate(profile)
            }
        }
    }

    fun clearTestResults() {
        runOnDefaultDispatcher {
            val toClear = ProfileRepository.getProfilesByGroup(GroupRepository.currentGroupId())
                .filter { it.status != 0 }
            ProfileRepository.clearTestResults(toClear)
            // 单列写库不会发通知，这里手动刷新列表
            for (profile in toClear) ProfileRepository.postUpdate(profile)
        }
    }

    fun deleteUnavailable() {
        runOnDefaultDispatcher {
            val toClear = ProfileRepository.getProfilesByGroup(GroupRepository.currentGroupId())
                .filter { it.status != 0 && it.status != 1 }
            if (toClear.isNotEmpty()) {
                onMainDispatcher {
                    // 读库期间 fragment 可能已销毁，requireContext 会在 appScope 上抛异常崩溃
                    if (!fragment.isAdded) return@onMainDispatcher
                    fragment.requireContext().confirm(R.string.delete_confirm_prompt) {
                        runOnDefaultDispatcher {
                            ProfileRepository.deleteProfiles(toClear)
                        }
                    }
                }
            }
        }
    }

    fun removeDuplicates() {
        runOnDefaultDispatcher {
            val profiles = ProfileRepository.getProfilesByGroup(GroupRepository.currentGroupId())
            val toClear = mutableListOf<ProxyEntity>()
            val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
            for (pf in profiles) {
                val proxy = Protocols.Deduplication(pf.requireBean(), pf.displayType())
                if (!uniqueProxies.add(proxy)) {
                    toClear += pf
                }
            }
            if (toClear.isNotEmpty()) {
                onMainDispatcher {
                    if (!fragment.isAdded) return@onMainDispatcher
                    // 最多列出 20 个，再多的用省略号代替
                    val names = toClear.take(21).mapIndexed { index, proxyEntity ->
                        if (index < 20) proxyEntity.displayName() else "......"
                    }
                    fragment.requireContext().confirm(
                        fragment.getString(R.string.delete_confirm_prompt) + "\n" + names.joinToString("\n")
                    ) {
                        runOnDefaultDispatcher {
                            ProfileRepository.deleteProfiles(toClear)
                        }
                    }
                }
            }
        }
    }
}
