package io.nekohasekai.sagernet.group

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
}
