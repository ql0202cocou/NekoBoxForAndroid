package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SOCKS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mldsa65VerifyUnsupportedTest {

    private fun <T : StandardV2RayBean> T.reality(
        security: String = "tls", pubKey: String = "pk", mldsa: String = "mldsa",
    ): T = apply {
        this.security = security
        realityPubKey = pubKey
        realityMldsa65Verify = mldsa
    }

    private fun neverCalled(): Int = error("字段未生效时不应读取核心")

    @Test
    fun `VLESS 在 Xray 上放行`() {
        assertNull(mldsa65VerifyUnsupported(TYPE_VMESS, VMessBean().reality()) { CORE_XRAY })
    }

    @Test
    fun `VLESS 落到 sing-box 时拒绝并建议换 Xray`() {
        val message = mldsa65VerifyUnsupported(TYPE_VMESS, VMessBean().reality()) { CORE_SING_BOX }
        assertNotNull(message)
        assertTrue(message!!.contains("switch this profile to the Xray core"))
        assertTrue(message.contains("clear mldsa65Verify"))
    }

    @Test
    fun `Trojan 拒绝且不建议换 Xray`() {
        val message = mldsa65VerifyUnsupported(TYPE_TROJAN, TrojanBean().reality()) { CORE_SING_BOX }
        assertNotNull(message)
        assertFalse(message!!.contains("switch"))
        assertTrue(message.contains("clear mldsa65Verify"))
    }

    @Test
    fun `关掉 TLS 后残留的 REALITY 字段不算`() {
        assertNull(mldsa65VerifyUnsupported(TYPE_VMESS, VMessBean().reality(security = "none")) { neverCalled() })
        assertNull(mldsa65VerifyUnsupported(TYPE_TROJAN, TrojanBean().reality(security = "none")) { neverCalled() })
    }

    @Test
    fun `没有公钥即未启用 REALITY 时放行`() {
        assertNull(mldsa65VerifyUnsupported(TYPE_VMESS, VMessBean().reality(pubKey = "")) { neverCalled() })
    }

    @Test
    fun `mldsa65Verify 为空时放行`() {
        assertNull(mldsa65VerifyUnsupported(TYPE_TROJAN, TrojanBean().reality(mldsa = " ")) { neverCalled() })
    }

    @Test
    fun `非 StandardV2RayBean 放行`() {
        assertNull(mldsa65VerifyUnsupported(TYPE_SOCKS, SOCKSBean()) { neverCalled() })
    }
}
