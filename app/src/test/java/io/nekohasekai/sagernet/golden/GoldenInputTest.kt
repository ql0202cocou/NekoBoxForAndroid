package io.nekohasekai.sagernet.golden

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.kryo.KryoSamples
import io.nekohasekai.sagernet.fmt.putByteArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

// 模拟器采集的基线（app/src/test/resources/golden/）里，input.json 存的 bean Kryo 字节
// 必须能在 JVM 上用生产代码重建：R1b 的黄金测试要拿它当输入。基线已经入库，找不到时失败，不跳过
class GoldenInputTest {

    private fun inputFiles(): List<File> {
        val root = javaClass.classLoader?.getResource("golden")?.toURI()?.let(::File) ?: return emptyList()
        return root.resolve("scenarios").listFiles().orEmpty()
            .map { File(it, "input.json") }
            .filter { it.isFile }
            .sortedBy { it.path }
    }

    @Test
    fun `input json 里的 bean 都能用生产代码从 Kryo 字节重建`() {
        val inputs = inputFiles()
        assertTrue("找不到黄金测试基线", inputs.isNotEmpty())
        var profiles = 0
        for (file in inputs) {
            val scenario = file.parentFile!!.name
            val input = JsonParser.parseString(file.readText()).asJsonObject
            assertEquals("$scenario 场景 id", scenario, input["id"].asString)
            for (element in input.getAsJsonArray("profiles")) {
                val row = element as JsonObject
                val where = "$scenario 节点 ${row["id"].asLong}"
                val bytes = Base64.getDecoder().decode(row["beanKryoBase64"].asString)
                val entity = ProxyEntity(type = row["type"].asInt)
                // 与分享链接 / 备份记录同一条严格解析路径：字节损坏直接抛异常
                entity.putByteArray(bytes)
                val bean = entity.requireBean()
                assertEquals(where, row["beanClass"].asString, bean.javaClass.name)
                // 重新序列化得到同样的字节，说明字段全部读回，没有错位或丢失
                val rewritten = KryoConverters.serialize(bean)
                if (layerVersions(row["beanClass"].asString, bytes) == layerVersions(row["beanClass"].asString, rewritten)) {
                    assertArrayEquals(where, bytes, rewritten)
                } else {
                    // 基线是旧版本的实现采集的（StandardV2Ray 层 Kryo 7 → 8，K2）：字节里的版本号比当前低，重新写出的字节
                    // 不可能相同。改查两件事——旧字节的各层版本号都不高于当前；重新写出的字节读回后字段与原来逐项相同
                    // （没有错位或丢失）。模拟器重新采集基线后所有输入都是当前版本，上面逐字节相同的检查随之恢复，这里不再走到
                    val old = layerVersions(row["beanClass"].asString, bytes)
                    for ((layer, version) in old) {
                        assertTrue("$where $layer 版本号 $version 高于当前", version <= KryoSamples.currentVersion(layer))
                    }
                    val again = ProxyEntity(type = row["type"].asInt).apply { putByteArray(rewritten) }.requireBean()
                    assertEquals(where, KryoSamples.fieldsOf(bean), KryoSamples.fieldsOf(again))
                }
                profiles++
            }
        }
        assertTrue("基线里没有节点", profiles > 0)
    }

    // 字节里各层（含 AbstractBean 的 extraVersion）写入的版本号
    private fun layerVersions(beanClass: String, bytes: ByteArray): Map<String, Int> =
        KryoSamples.versionsInBytes(beanClass, "storage", bytes)
}
