package io.nekohasekai.sagernet.fmt.kryo

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.kryo.KryoSamples.Sample
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.ktx.byteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Kryo bean 格式的兼容测试：样本是历史提交的实现写出的真实字节（来源见各样本的 source 与
// kryo/README.md），不是当前实现自己写出再读回。期望值逐字段写在样本的 expected 里
class KryoCompatTest {

    private fun check(failures: List<String>) {
        assertTrue("${failures.size} 处不一致：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun readFailures(sample: Sample): List<String> = try {
        KryoSamples.diff(sample.id, KryoSamples.read(sample), sample.expected)
    } catch (e: Exception) {
        listOf("${sample.id}：读取抛出 $e")
    }

    @Test
    fun `有样本`() {
        assertTrue("没有读到任何样本", KryoSamples.samples.size > 100)
    }

    @Test
    fun `当前实现读旧字节，每个字段都符合当时写下的值或该版本分支的约定`() {
        val failures = ArrayList<String>()
        for (sample in KryoSamples.samples) {
            failures += readFailures(sample)
        }
        check(failures)
    }

    @Test
    fun `读进来再写出时各层版本号是当前值，读回字段不变`() {
        val failures = ArrayList<String>()
        for (sample in KryoSamples.samples) {
            try {
                val obj = KryoSamples.read(sample)
                val written = KryoSamples.versionsOf(obj)
                for ((layer, version) in written) {
                    val current = KryoSamples.currentVersion(layer)
                    if (version != current) failures += "${sample.id}：重新写出的 $layer 版本号是 $version，当前应为 $current"
                }
                // 样本里出现过的层，重新写出时都还在
                val lost = sample.versions.keys - written.keys
                if (lost.isNotEmpty()) failures += "${sample.id}：重新写出后缺少这些层 $lost"
                val again = KryoSamples.read(sample, KryoSamples.write(obj))
                failures += KryoSamples.diff("${sample.id}（重新写出后读回）", again, sample.expected)
            } catch (e: Exception) {
                failures += "${sample.id}：重新写出 / 读回抛出 $e"
            }
        }
        check(failures)
    }

    @Test
    fun `当前版本的样本往返后字节完全相同`() {
        val current = KryoSamples.samples.filter { it.current }
        assertTrue("没有当前版本的样本", current.isNotEmpty())
        for (sample in current) {
            assertArrayEquals(sample.toString(), sample.bytes, KryoSamples.write(KryoSamples.read(sample)))
        }
    }

    // StandardV2Ray 层版本号 < 7 是 K1 之前写下的数据：读出后带「未标注」标记（不进字节、不参与字段比较），
    // 交给备份恢复与通用链接导入去标注；当前实现写出的 v7 没有这个标记，重新写出再读回也没有
    @Test
    fun `StandardV2Ray 版本号小于 7 的样本读出后带未标注标记`() {
        val failures = ArrayList<String>()
        var legacy = 0
        var labeled = 0
        for (sample in KryoSamples.samples) {
            val version = sample.versions["StandardV2RayBean"] ?: continue
            val obj = KryoSamples.read(sample)
            val bean = (if (obj is ProxyEntity) obj.requireBean() else obj) as StandardV2RayBean
            val expected = version < 7
            if (expected) legacy++ else labeled++
            if (bean.legacyUnlabeled != expected) {
                failures += "${sample.id}：StandardV2Ray v$version 读出后 legacyUnlabeled 应为 $expected"
            }
            val again = KryoSamples.read(sample, KryoSamples.write(obj))
            val againBean = (if (again is ProxyEntity) again.requireBean() else again) as StandardV2RayBean
            if (againBean.legacyUnlabeled) failures += "${sample.id}：重新写出后读回仍带 legacyUnlabeled"
        }
        assertTrue("没有 v7 以前的样本", legacy > 0)
        assertTrue("没有 v7 的样本", labeled > 0)
        check(failures)
    }

    @Test
    fun `样本登记的版本号与字节开头一致`() {
        // 防止手工改样本时把 versions 写错：从样本字节开头重新取各层版本号（叶子类在前）
        val failures = ArrayList<String>()
        for (sample in KryoSamples.samples) {
            val head = when {
                sample.className == ProxyEntity::class.java.name -> listOf("ProxyEntity")
                sample.className == ProxyGroup::class.java.name ->
                    listOf(if (sample.form == "share") "ProxyGroup.export" else "ProxyGroup")

                else -> KryoSamples.layersOf(sample.className).filter { it != "AbstractBean" }
            }
            val input = sample.bytes.byteBuffer()
            for (layer in head) {
                val v = input.readInt()
                if (sample.versions[layer] != v) failures += "${sample.id}：字节里 $layer 的版本号是 $v，样本登记的是 ${sample.versions[layer]}"
            }
        }
        check(failures)
    }
}
