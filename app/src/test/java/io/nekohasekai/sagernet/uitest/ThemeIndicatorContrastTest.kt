package io.nekohasekai.sagernet.uitest

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.ServiceButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** 状态标记与更新进度须在实际背景上清楚可辨，顶部及连接按钮仍保留旧版原色。 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class ThemeIndicatorContrastTest(private val id: Int, private val dark: Boolean, private val pure: Boolean) : UiTestBase() {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "id={0},dark={1},pure={2}")
        fun cases(): List<Array<Any>> = Theme.KEPT.flatMap { id ->
            listOf(arrayOf<Any>(id, false, false), arrayOf<Any>(id, true, false), arrayOf<Any>(id, true, true))
        }
    }

    @Test
    fun indicatorsRemainDistinctWithoutChangingHeaderOrButton() {
        RuntimeEnvironment.setQualifiers("en-rUS-w411dp-h914dp-port-${if (dark) "night" else "notnight"}-hdpi")
        DataStore.appTheme = id
        DataStore.nightTheme = if (dark) 1 else 2
        DataStore.pureBlack = pure
        Theme.currentNightMode = -1
        val main = ui.launch<MainActivity>().get()
        val failures = mutableListOf<String>()
        fun contrast(label: String, foreground: Int, background: Int) {
            val ratio = ColorUtils.calculateContrast(foreground, background)
            if (ratio < 3.0) failures += "$label：${"#%08X".format(foreground)} / ${"#%08X".format(background)} = $ratio"
        }

        val dialog = ContextThemeWrapper(main, Theme.getDialogTheme(id)).apply { Theme.applyDialog(this) }
        for (context in listOf(main, dialog)) {
            val inflater = LayoutInflater.from(context)
            val parent = FrameLayout(context)
            val profile = inflater.inflate(R.layout.layout_profile, parent, false) as MaterialCardView
            profile.isSelected = true
            val marker = (profile.findViewById<View>(R.id.selected_view).background as ColorDrawable).color
            contrast("节点选中标记", marker, profile.cardBackgroundColor.getColorForState(profile.drawableState, 0))

            for (layout in listOf(R.layout.layout_group_item, R.layout.layout_asset_item)) {
                val card = inflater.inflate(layout, parent, false) as MaterialCardView
                val progress = card.findViewById<LinearProgressIndicator>(R.id.subscription_update_progress)
                for (indicator in progress.indicatorColor) {
                    contrast("更新进度与轨道", indicator, progress.trackColor)
                    contrast("更新进度与卡片", indicator, card.cardBackgroundColor.defaultColor)
                }
            }
        }

        val legacy = JsonParser.parseString(File("src/test/resources/legacy-theme-colors.json").readText())
            .asJsonObject.getAsJsonArray("themes").single { it.asJsonObject.get("id").asInt == id }.asJsonObject
        assertEquals("顶部保留旧主色", Color.parseColor(legacy.get("colorPrimary").asString), main.getColorAttr(R.attr.appBarColor))
        val button = main.findViewById<ServiceButton>(R.id.fab)
        val expectedButton = Color.parseColor(legacy.get(if (id == Theme.BLACK) "colorAccent" else "colorPrimary").asString)
        assertEquals("连接按钮保留旧底色", expectedButton, button.backgroundTintList!!.defaultColor)
        val expectedForeground = if (ColorUtils.calculateContrast(Color.WHITE, expectedButton) >=
            ColorUtils.calculateContrast(Color.BLACK, expectedButton)) Color.WHITE else Color.BLACK
        assertEquals("连接按钮保留原前景色", expectedForeground, button.imageTintList!!.defaultColor)
        assertTrue("主题 $id，夜间 $dark，纯黑 $pure：\n${failures.joinToString("\n")}", failures.isEmpty())
    }
}
