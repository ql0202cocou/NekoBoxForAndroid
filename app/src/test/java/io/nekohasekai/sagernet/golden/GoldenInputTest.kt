package io.nekohasekai.sagernet.golden

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.KryoConverters
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
                assertArrayEquals(where, bytes, KryoConverters.serialize(bean))
                profiles++
            }
        }
        assertTrue("基线里没有节点", profiles > 0)
    }
}
