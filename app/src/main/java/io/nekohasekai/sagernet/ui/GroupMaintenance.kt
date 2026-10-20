package io.nekohasekai.sagernet.ui

import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.displayType
import io.nekohasekai.sagernet.ktx.confirm
import io.nekohasekai.sagernet.ktx.displayName
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.Protocols

/**
 * 配置页菜单里对当前分组的维护操作：清除流量、清除测试结果、删除不可用、去重
 * （从 ConfigurationFragment 拆出，逻辑与协程作用域不变）。
 * 由 ConfigurationFragment 的字段持有，只存 fragment 引用，生命周期与原先这些代码写在 fragment 里一致。
 * 两类协程：清除流量 / 清除测试结果 / 确认后的删除是必须做完的写入，留在 appScope（不碰界面）；
 * 删除不可用 / 去重的「读库后弹确认框」是页面内的读取，跑在 fragment 的视图作用域里，
 * 页面销毁就取消，不会在页面离开后弹框
 */
class GroupMaintenance(private val fragment: ConfigurationFragment) {

    fun clearTraffic() {
        // 进程级：清除流量要写完并通知 :bg，页面离开也不能中断，不碰界面
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
        // 进程级：清除测试结果落库，页面离开也要写完，不碰界面
        runOnDefaultDispatcher {
            val toClear = ProfileRepository.getProfilesByGroup(GroupRepository.currentGroupId())
                .filter { it.status != 0 }
            ProfileRepository.clearTestResults(toClear)
            // 单列写库不会发通知，这里手动刷新列表
            for (profile in toClear) ProfileRepository.postUpdate(profile)
        }
    }

    fun deleteUnavailable() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val toClear = withContext(Dispatchers.IO) {
                ProfileRepository.getProfilesByGroup(GroupRepository.currentGroupId())
                    .filter { it.status != 0 && it.status != 1 }
            }
            if (toClear.isNotEmpty()) {
                fragment.requireContext().confirm(R.string.delete_confirm_prompt) {
                    // 确认后的删除必须做完，不随页面取消
                    runOnDefaultDispatcher {
                        ProfileRepository.deleteProfiles(toClear)
                    }
                }
            }
        }
    }

    fun removeDuplicates() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val toClear = withContext(Dispatchers.IO) {
                val profiles = ProfileRepository.getProfilesByGroup(GroupRepository.currentGroupId())
                val duplicates = mutableListOf<ProxyEntity>()
                val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
                for (pf in profiles) {
                    val proxy = Protocols.Deduplication(pf.requireBean(), pf.displayType())
                    if (!uniqueProxies.add(proxy)) {
                        duplicates += pf
                    }
                }
                duplicates
            }
            if (toClear.isNotEmpty()) {
                // 最多列出 20 个，再多的用省略号代替
                val names = toClear.take(21).mapIndexed { index, proxyEntity ->
                    if (index < 20) proxyEntity.displayName() else "......"
                }
                fragment.requireContext().confirm(
                    fragment.getString(R.string.delete_confirm_prompt) + "\n" + names.joinToString("\n")
                ) {
                    // 确认后的删除必须做完，不随页面取消
                    runOnDefaultDispatcher {
                        ProfileRepository.deleteProfiles(toClear)
                    }
                }
            }
        }
    }
}
