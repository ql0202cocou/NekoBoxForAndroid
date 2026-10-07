package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.DialogFragment
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.utils.Theme

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }
        Theme.applyNightTheme()

        if (!isDialog) {
            // 状态栏压在顶部栏上，图标颜色按顶部栏的实际底色（appBarColor）判断。顶部栏现在是
            // surface，结果等于跟随日夜；保留按底色判断的写法，以后换顶部栏颜色不用改这里。
            // 导航栏压在主题的 surface 上，日夜默认值就是对的
            val appBarLuminance = ColorUtils.calculateLuminance(getColorAttr(R.attr.appBarColor))
            val statusBarStyle = if (appBarLuminance < 0.5) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                // 第二个参数（深色遮罩）只在 API 23 以下生效，minSdk 24 用不到，给透明即可
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            }
            enableEdgeToEdge(statusBarStyle = statusBarStyle)
        }
        super.onCreate(savedInstanceState)
        // 任务可能由任一界面起头（如快捷方式直达扫码页），开着设置时每次建界面都补一次。
        // 这里不写 false：关闭只由设置页的开关回调处理；恢复备份、重置设置经
        // ProcessPhoenix 以 NEW_TASK | CLEAR_TASK 重启，系统会换掉任务的 base intent，
        // 残留的隐藏标志随之消失
        if (DataStore.hideFromRecents) SagerNet.setExcludeFromRecents(true)

        uiMode = resources.configuration.uiMode
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            ActivityCompat.recreate(this)
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar = throw NotImplementedError()

    override fun onSupportNavigateUp(): Boolean {
        // the toolbar X takes the back path, so guardUnsavedChanges covers it too;
        // without a guard the dispatcher's fallback just finishes
        if (!super.onSupportNavigateUp()) onBackPressedDispatcher.onBackPressed()
        return true
    }

    /**
     * Guard the back gesture and the toolbar X when there are unsaved edits. Predictive back
     * routes through [onBackPressedDispatcher] and skips `onBackPressed()` overrides, so
     * register here instead; [onSupportNavigateUp] feeds the X into the same dispatcher.
     */
    protected fun guardUnsavedChanges(isDirty: () -> Boolean, dialog: () -> DialogFragment) {
        onBackPressedDispatcher.addCallback(this) {
            if (isDirty()) dialog().showAllowingStateLoss(supportFragmentManager) else finish()
        }
    }

}
