package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * `res/color/` 里的颜色状态列表到 API 29 才能当 Drawable 加载，minSdk 24 的旧系统上加载布局直接崩溃
 * （`<item> tag requires a 'drawable' attribute`）。Robolectric（SDK 35）和 lint 都查不出，这里按文本钉住。
 * textColor、cardBackgroundColor、shape 的 solid 等颜色属性不受影响；要当背景用时先 getColor() 取色。
 */
class ColorStateListDrawableTest {

    private val main = File("src/main")

    @Test
    fun colorStateListsAreNotUsedAsDrawables() {
        val lists = File(main, "res/color").listFiles()!!.joinToString("|") { it.nameWithoutExtension }
        val inXml = Regex("""android:(background|foreground|src|drawable|divider|windowBackground)(="|">)@color/($lists)\b""")
        val inCode = Regex("""(setBackgroundResource|setImageResource|getDrawable)\([^)]*R\.color\.($lists)\b""")
        val misuses = File(main, "res").walk().filter { it.extension == "xml" }.flatMap { file ->
            inXml.findAll(file.readText()).map { "${file.name}: ${it.value}" }
        } + File(main, "java").walk().filter { it.extension == "kt" || it.extension == "java" }.flatMap { file ->
            inCode.findAll(file.readText()).map { "${file.name}: ${it.value}" }
        }
        assertEquals(emptyList<String>(), misuses.toList())
    }
}
