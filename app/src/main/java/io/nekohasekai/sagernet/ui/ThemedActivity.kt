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
            // The status bar sits on the colorPrimary AppBar, whose colour does not follow
            // day/night (Black theme is #2B2B2B in light mode, Yellow/Lime are light in dark
            // mode), so pick the icon colour from that background instead of the UI mode.
            // The navigation bar is over the themed surface, where the day/night default is right.
            val appBarLuminance = ColorUtils.calculateLuminance(getColorAttr(androidx.appcompat.R.attr.colorPrimary))
            val statusBarStyle = if (appBarLuminance < 0.5) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                // the dark scrim only ever applied below API 23, which minSdk 23 no longer reaches
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            }
            enableEdgeToEdge(statusBarStyle = statusBarStyle)
        }
        super.onCreate(savedInstanceState)

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
