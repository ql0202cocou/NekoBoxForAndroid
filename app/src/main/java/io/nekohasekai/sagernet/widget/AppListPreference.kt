package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.database.EditorCache

class AppListPreference : Preference {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context, attrs, defStyle
    )

    constructor(
        context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    // 包缓存异步填充；缓存未就绪时 getSummary 先返占位，等就绪后刷新。
    // 主线程跑 awaitLoadSync 会 runBlocking 等全量包扫描，冷启动窗口内可致 ANR
    @Volatile
    private var waitingForCache = false

    override fun getSummary(): CharSequence {
        if (!PackageCache.isLoaded) {
            if (!waitingForCache) {
                waitingForCache = true
                runOnDefaultDispatcher {
                    PackageCache.awaitLoadSync()
                    waitingForCache = false
                    // 加载失败（如包读取权限被拒）时不再刷新，下次绑定自然重试，
                    // 避免「刷新-重试」自激循环
                    if (PackageCache.isLoaded) runOnMainDispatcher { notifyChanged() }
                }
            }
            return ""
        }
        // loadLabel() memoizes; loading each label from the PackageManager again
        // would repeat that work on every rebind of this preference
        val packages = EditorCache.routePackages.split("\n").filter { it.isNotBlank() }
            .map { PackageCache.loadLabel(it) }
        if (packages.isEmpty()) {
            return context.getString(androidx.preference.R.string.not_set)
        }
        val count = packages.size
        if (count <= 5) return packages.joinToString("\n")
        return context.getString(R.string.apps_message, count)
    }

    fun postUpdate() {
        notifyChanged()
    }

}