package io.nekohasekai.sagernet.fmt.kryo

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.kryo.KryoSamples.Sample
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

    // 已知读不对的样本（knownIssue）单独处理：若某天读对了，提示删掉标记
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
            val diff = readFailures(sample)
            if (sample.knownIssue == null) {
                failures += diff
            } else if (diff.isEmpty()) {
                failures += "${sample.id}：标记为已知问题（${sample.knownIssue}），现在却读对了；确认修复后删掉 knownIssue"
            }
        }
        check(failures)
    }

    @Test
    fun `读进来再写出时各层版本号是当前值，读回字段不变`() {
        val failures = ArrayList<String>()
        for (sample in KryoSamples.samples) {
            if (sample.knownIssue != null) continue
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
