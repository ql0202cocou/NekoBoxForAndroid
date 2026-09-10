package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.utils.PackageCache

class AppListPreference : Preference {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context, attrs, defStyle
    )

    constructor(
        context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    override fun getSummary(): CharSequence {
        // the cache fills asynchronously at app start; a restored route editor
        // can bind this preference before it is there
        PackageCache.awaitLoadSync()
        // loadLabel() memoizes; loading each label from the PackageManager again
        // would repeat that work on every rebind of this preference
        val packages = DataStore.routePackages.split("\n").filter { it.isNotBlank() }
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