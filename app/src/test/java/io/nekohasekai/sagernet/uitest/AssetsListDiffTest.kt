package io.nekohasekai.sagernet.uitest

import androidx.recyclerview.widget.ListUpdateCallback
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.AssetsActivity
import io.nekohasekai.sagernet.ui.diffAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * U4c c4 规则资源页：重读列表只发针对性通知（增 / 删 / 版本变化的那一行），无变化时不发任何通知；
 * 撤销删除仍按位置插回。不截图，所有平台都跑。
 */
class AssetsListDiffTest : UiTestBase() {

    private fun f(name: String) = File("/assets/$name")

    private fun events(
        old: List<File>, oldV: Map<String, String>, new: List<File>, newV: Map<String, String>,
    ): List<String> {
        val out = ArrayList<String>()
        diffAssets(old, oldV, new, newV).dispatchUpdatesTo(object : ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { out += "ins@$position×$count" }
            override fun onRemoved(position: Int, count: Int) { out += "rem@$position×$count" }
            override fun onMoved(fromPosition: Int, toPosition: Int) { out += "mov" }
            override fun onChanged(position: Int, count: Int, payload: Any?) { out += "chg@$position×$count" }
        })
        return out
    }

    private fun versions(vararg files: File) = files.associate { it.absolutePath to "v1" }

    @Test
    fun unchangedListEmitsNothing() {
        val l = listOf(f("geoip.db"), f("geosite.db"), f("a.db"))
        assertEquals(emptyList<String>(), events(l, versions(*l.toTypedArray()), l.toList(), versions(*l.toTypedArray())))
    }

    @Test
    fun addAndRemoveAreTargeted() {
        val base = listOf(f("geoip.db"), f("geosite.db"))
        val plus = base + f("a.db")
        assertEquals(listOf("ins@2×1"), events(base, versions(*base.toTypedArray()), plus, versions(*plus.toTypedArray())))
        assertEquals(listOf("rem@2×1"), events(plus, versions(*plus.toTypedArray()), base, versions(*base.toTypedArray())))
    }

    @Test
    fun versionChangeRebindsOnlyThatRow() {
        val l = listOf(f("geoip.db"), f("geosite.db"), f("a.db"))
        val newV = versions(*l.toTypedArray()) + (l[1].absolutePath to "v2")
        assertEquals(listOf("chg@1×1"), events(l, versions(*l.toTypedArray()), l.toList(), newV))
    }

    @Test
    fun activityReloadAndUndo() {
        val dir = app.assetsDir.apply { mkdirs() }
        val extra = File(dir, "zz-extra.db").apply { writeText("x") }
        try {
            val host = ui.launch<AssetsActivity>()
            UiSettle.settle()
            val adapter = host.get().adapter
            assertTrue(adapter.assets.any { it.name == extra.name })

            val seen = ArrayList<String>()
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() { seen += "reset" }
                override fun onItemRangeInserted(p: Int, c: Int) { seen += "ins" }
                override fun onItemRangeRemoved(p: Int, c: Int) { seen += "rem" }
                override fun onItemRangeChanged(p: Int, c: Int, payload: Any?) { seen += "chg" }
                override fun onItemRangeMoved(f: Int, t: Int, c: Int) { seen += "mov" }
            })

            // 无变化的重读：不发任何通知
            adapter.reloadAssets()
            UiSettle.settle()
            assertEquals(emptyList<String>(), seen)

            // 新增一个文件：只插入一行
            val added = File(dir, "zz-added.db").apply { writeText("y") }
            try {
                adapter.reloadAssets()
                UiSettle.settle()
                assertEquals(listOf("ins"), seen)
                assertTrue(adapter.assets.any { it.name == added.name })
            } finally {
                added.delete()
            }

            // 文件消失后重读：只删一行
            seen.clear()
            adapter.reloadAssets()
            UiSettle.settle()
            assertEquals(listOf("rem"), seen)
            assertTrue(adapter.assets.none { it.name == added.name })

            // 删除一行后撤销：按原位置插回
            val index = adapter.assets.indexOfFirst { it.name == extra.name }
            val before = adapter.assets.toList()
            val removedFile = adapter.assets[index]
            adapter.remove(index)
            adapter.undo(listOf(index to removedFile))
            assertEquals(before, adapter.assets.toList())
        } finally {
            extra.delete()
        }
    }
}
