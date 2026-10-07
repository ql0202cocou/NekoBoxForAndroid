package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.ClashImportException
import io.nekohasekai.sagernet.fmt.ClashImportSummary
import io.nekohasekai.sagernet.fmt.ClashNodeResult
import io.nekohasekai.sagernet.fmt.v2ray.UnsupportedTransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportBatchTest {

    @Test
    fun `各段结果按顺序合并，未认出节点的段跳过`() {
        val batch = ImportBatch<String>()
        batch.add(listOf("a", "b"))
        batch.add(null)
        batch.add(emptyList())
        batch.add(listOf("c"))
        assertEquals(listOf("a", "b", "c"), batch.result())
    }

    @Test
    fun `有段被拒但其余段有节点时照常返回`() {
        val batch = ImportBatch<String>()
        batch.reject(UnsupportedTransportException("xhttp"))
        batch.add(listOf("a"))
        batch.reject(UnsupportedTransportException("kcp"))
        batch.add(null)
        assertEquals(listOf("a"), batch.result())
    }

    @Test
    fun `一个节点都没有时抛出第一条被拒原因`() {
        val first = UnsupportedTransportException("xhttp")
        val batch = ImportBatch<String>()
        batch.add(null)
        batch.reject(first)
        batch.reject(UnsupportedTransportException("kcp"))
        batch.add(emptyList())
        val e = assertThrows(UnsupportedTransportException::class.java) { batch.result() }
        assertSame(first, e)
    }

    @Test
    fun `没有节点也没有被拒原因时返回空列表`() {
        val batch = ImportBatch<String>()
        batch.add(null)
        assertTrue(batch.result().isEmpty())
        assertTrue(ImportBatch<String>().result().isEmpty())
    }

    private fun clashRejected() =
        ClashImportException(ClashImportSummary.of(listOf(ClashNodeResult.UnknownType(0, "ssr", "a"))))

    @Test
    fun `Clash 一个节点都没导入的段与传输方式被拒同等处理`() {
        val batch = ImportBatch<String>()
        batch.reject(clashRejected())
        batch.add(listOf("a"))
        assertEquals(listOf("a"), batch.result())
    }

    @Test
    fun `两种被拒原因混在一起时抛出第一条`() {
        val first = clashRejected()
        val batch = ImportBatch<String>()
        batch.reject(first)
        batch.reject(UnsupportedTransportException("xhttp"))
        val e = assertThrows(ClashImportException::class.java) { batch.result() }
        assertSame(first, e)

        val transport = UnsupportedTransportException("kcp")
        val other = ImportBatch<String>()
        other.reject(transport)
        other.reject(clashRejected())
        assertSame(transport, assertThrows(UnsupportedTransportException::class.java) { other.result() })
    }
}
