package io.nekohasekai.sagernet.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * U5e E1 的约定里编译、lint 都查不出的两条，扫源码钉住：
 * - 整个应用只有 ScrollHub 会对滚动视图调 setOnScrollChangeListener（View 只有这一个槽，
 *   别处再调会悄悄顶掉分发器，顶部栏抬升就收不到滚动了）；
 * - 主界面托管页面的底部留白只经 hostMainScrollable 设：padForSystemBars(bottomExtra = …) 只出现在
 *   它自己的定义与 hostMainScrollable 里，页面不再各写一遍。
 */
class MainContentHostSourceTest {

    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.toList()

    private fun filesContaining(text: String): List<String> =
        sources.filter { text in it.readText() }.map { it.name }.sorted()

    @Test
    fun onlyScrollHubSetsTheScrollChangeListener() {
        assertEquals(listOf("ScrollHub.kt"), filesContaining("setOnScrollChangeListener"))
    }

    @Test
    fun onlyTheEntryPassesBottomExtra() {
        assertEquals(listOf("MainContentHost.kt", "WindowInsetsListeners.kt"), filesContaining("bottomExtra"))
    }
}
