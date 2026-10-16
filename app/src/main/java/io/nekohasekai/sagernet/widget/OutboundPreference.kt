package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import io.nekohasekai.sagernet.database.EditorCache

class OutboundPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = androidx.preference.R.attr.dropdownPreferenceStyle
) : SimpleMenuPreference(context, attrs, defStyle, 0) {

    init {
        setEntries(R.array.outbound_entry)
        setEntryValues(R.array.outbound_value)
    }

    // 只在主线程读写：上次在后台查到的节点（id 与名字，节点已不存在时名字为 null），以及正在查的节点 id
    private var loadedName: Pair<Long, String?>? = null
    private var loadingId = 0L

    // getSummary 在主线程调用，不查库：节点名用后台查好的结果，没查过就先显示选项本身的摘要，查完刷新这一行
    override fun getSummary(): CharSequence? {
        val profileId = if (value == "3") {
            EditorCache.profileCacheStore.getLong(key + "Long") ?: 0
        } else 0L
        when (val summary = outboundSummary(value, profileId, loadedName)) {
            is OutboundSummary.Name -> return summary.name
            is OutboundSummary.Load -> loadName(summary.profileId)
            OutboundSummary.Default -> Unit
        }
        return super.getSummary()
    }

    private fun loadName(profileId: Long) {
        if (loadingId == profileId) return
        loadingId = profileId
        runOnDefaultDispatcher {
            val name = ProfileRepository.getProfile(profileId)?.displayName()
            onMainDispatcher {
                // 查库期间又选了别的节点：这次结果作废，由新的那次查询刷新
                if (loadingId != profileId) return@onMainDispatcher
                loadingId = 0L
                loadedName = profileId to name
                notifyChanged()
            }
        }
    }

}

// OutboundPreference 摘要的取法，抽成纯函数便于测试
internal sealed interface OutboundSummary {
    /** 显示选项本身的摘要：不是「指定配置」（值 3）、没选节点，或选的节点已不存在 */
    data object Default : OutboundSummary

    /** 显示节点名 */
    data class Name(val name: String) : OutboundSummary

    /** 还没查过这个节点：先显示选项本身的摘要，同时在后台查它的名字 */
    data class Load(val profileId: Long) : OutboundSummary
}

// value 是选项的值，profileId 是缓存里「指定配置」选中的节点 id，loaded 是上次在后台查到的节点 id 与名字
internal fun outboundSummary(value: String?, profileId: Long, loaded: Pair<Long, String?>?): OutboundSummary =
    when {
        value != "3" || profileId <= 0L -> OutboundSummary.Default
        loaded?.first == profileId -> loaded.second?.let { OutboundSummary.Name(it) } ?: OutboundSummary.Default
        else -> OutboundSummary.Load(profileId)
    }
