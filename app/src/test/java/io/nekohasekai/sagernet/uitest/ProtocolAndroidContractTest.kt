package io.nekohasekai.sagernet.uitest

import android.os.Parcel
import io.nekohasekai.sagernet.bg.proto.BoxInstanceLifecycleTest.ShadowLifecycleLibcore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.toUri
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.toV2rayN
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.neko.NekoBean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.Config

/** 补普通 JVM 绕过的 Android Base64 / org.json 路径；仍使用已有、独立审查过的字段预期。 */
class ProtocolAndroidContractTest : UiTestBase() {
    @Test
    fun `SS 和 VMess 完整链接经实际 Base64 与订阅分派往返`() {
        val specs = ProtocolLinkCases.SPECS.filter { it.protocol in setOf("Shadowsocks", "VMess") }
        assertEquals(2, specs.size)
        for (spec in specs) {
            for (case in spec.cases) {
                val bean = case.bean()
                val link = when (bean) {
                    is ShadowsocksBean -> bean.toUri()
                    is VMessBean -> bean.toV2rayN()
                    else -> error("unexpected fixture")
                }
                val parsed = ui.bg { parseProxies(link) { false }.single() }
                assertEquals("${spec.protocol}/${case.id}", emptyList<String>(),
                    ProtocolInventory.diff(case.id, bean, parsed, spec.neverInLink.keys))
            }
        }
    }

    @Test
    fun `全部可分享类型的完整通用链接信封保留现行序列化字段`() {
        var checked = 0
        for (proto in ProtocolInventory.PROTOCOLS.filter { it.type in TypeMap.reversed }) {
            val variants = ProtocolLinkCases.SPECS.filter { it.beanClass == proto.beanClass }.flatMap { it.cases }
                .map { it.bean() }.ifEmpty {
                    listOf(proto.beanClass.getDeclaredConstructor().newInstance().apply {
                        initializeDefaultValues()
                        name = "合成名称 / ? # 😀"
                        serverAddress = "node.example.org"
                        serverPort = 1234
                    })
                }
            for (bean in variants) {
                if (bean is AnyTLSBean) bean.certificatePolicySource = AnyTLSBean.POLICY_PUBLISHED
                if (bean is NekoBean) bean.sharedStorage = JSONObject("""{"nested":{"text":"合成 / + 😀","values":[true,3,null]},"password":"synthetic"}""")
                val bytes = KryoConverters.serialize(bean)
                val parsed = parseUniversal(bean.toUniversalLink()) { fail("现行格式不读取迁移设置"); false }
                assertEquals(proto.beanClass, parsed.javaClass)
                assertArrayEquals(proto.name, bytes, KryoConverters.serialize(parsed))
                checked++
            }
        }
        assertTrue(checked >= TypeMap.reversed.size)
    }

    @Test
    @Config(shadows = [ShadowLifecycleLibcore::class])
    fun `Neko 的 JSON 经实体 Kryo 和 Parcel 深拷贝且损坏 JSON 保持既定降级`() {
        val bean = NekoBean().apply {
            initializeDefaultValues()
            name = "Neko fixture"
            plgId = "fixture.plugin"
            protocolId = "fixture.protocol"
            sharedStorage = JSONObject("""{"array":[1,{"password":"synthetic"}],"empty":null}""")
        }
        val entity = ProxyEntity(id = 42, groupId = 7).putBean(bean)
        val parcel = Parcel.obtain()
        try {
            entity.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val copied = ProxyEntity.CREATOR.createFromParcel(parcel)
            assertEquals(42L, copied.id)
            assertArrayEquals(KryoConverters.serialize(bean), KryoConverters.serialize(copied.requireBean()))
            (copied.requireBean() as NekoBean).sharedStorage.put("only-copy", true)
            assertFalse(bean.sharedStorage.has("only-copy"))
        } finally {
            parcel.recycle()
        }
        val previous = Logs.enabled
        try {
            Logs.enabled = true
            ShadowLifecycleLibcore.log = { fail("纯解码不能调用核心日志") }
            assertEquals(0, NekoBean.tryParseJSON("not json: synthetic secret").length())
        } finally {
            Logs.enabled = previous
            ShadowLifecycleLibcore.reset()
        }
    }
}
