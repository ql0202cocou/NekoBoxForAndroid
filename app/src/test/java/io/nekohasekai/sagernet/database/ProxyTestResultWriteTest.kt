package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.CoreTestNodes
import io.nekohasekai.sagernet.fmt.putBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

// 测速结果落库（updateTestResult）：库里这一行的配置仍是被测时的才写，测试期间配置变了、行被删了、
// 恢复备份后同一 id 换成了别的节点，都不写
class ProxyTestResultWriteTest {

    private fun entity(bean: AbstractBean) = ProxyEntity(id = 1, groupId = 1).putBean(bean)

    // 只实现 updateTestResult 用到的两个方法，别的调用直接抛异常，免得悄悄放过新增的读写
    private class FakeDao(private val row: ProxyEntity?) {
        val statusWrites = mutableListOf<List<Any?>>()
        val dao = Proxy.newProxyInstance(
            ProxyEntity.Dao::class.java.classLoader, arrayOf(ProxyEntity.Dao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getById" -> row?.takeIf { it.id == args[0] as Long }
                "updateStatus" -> { statusWrites += args.toList(); null }
                else -> throw UnsupportedOperationException("ProxyEntity.Dao.${method.name} 测试设施没有实现")
            }
        } as ProxyEntity.Dao
    }

    // 测速开始时读到的那一行，测完填上结果
    private fun tested(bean: AbstractBean) = entity(bean).also { it.status = 1; it.ping = 42; it.error = null }

    @Test
    fun `配置没变：写入结果`() {
        val fake = FakeDao(entity(CoreTestNodes.vmess()))
        assertTrue(fake.dao.updateTestResult(tested(CoreTestNodes.vmess())))
        assertEquals(listOf(listOf<Any?>(1L, 1, 42, null)), fake.statusWrites)
    }

    @Test
    fun `测试期间只改了节点名：照写`() {
        val fake = FakeDao(entity(CoreTestNodes.vmess { name = "renamed" }))
        assertTrue(fake.dao.updateTestResult(tested(CoreTestNodes.vmess())))
        assertEquals(1, fake.statusWrites.size)
    }

    @Test
    fun `测试期间配置变了：不写，保留配置变化时清零的状态`() {
        val fake = FakeDao(entity(CoreTestNodes.vmess { serverPort = 8443 }))
        assertFalse(fake.dao.updateTestResult(tested(CoreTestNodes.vmess())))
        assertTrue(fake.statusWrites.isEmpty())
    }

    @Test
    fun `恢复备份后同一 id 换成了别的节点：不写`() {
        val fake = FakeDao(entity(CoreTestNodes.trojan()))
        assertFalse(fake.dao.updateTestResult(tested(CoreTestNodes.vmess())))
        assertTrue(fake.statusWrites.isEmpty())
    }

    @Test
    fun `这一行已被删除：不写`() {
        val fake = FakeDao(null)
        assertFalse(fake.dao.updateTestResult(tested(CoreTestNodes.vmess())))
        assertTrue(fake.statusWrites.isEmpty())
    }
}
