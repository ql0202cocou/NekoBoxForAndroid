package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import java.util.Random

// 能力表、冻结规则与升级标注的测试共用的节点构造。取值全部虚构；格式类字段（REALITY 公钥、mldsa65Verify、
// 证书指纹）给合法值，免得生成器的格式校验混进选核结论
object CoreTestNodes {

    val REALITY_KEY = "A".repeat(43)
    const val SHORT_ID = "0123abcd"
    val MLDSA65 = "A".repeat(2603)
    val PIN = "ab".repeat(32)
    const val CERT = "-----BEGIN CERTIFICATE-----\nMIIBexampleexample\n-----END CERTIFICATE-----"
    const val ECH = "AEX+DQBBexampleexampleexample"
    const val UUID = "00000000-0000-0000-0000-000000000001"

    // 一个节点：实体的 type / core、bean、全局「允许不安全」
    data class Node(val type: Int, val core: Int, val bean: AbstractBean, val global: Boolean) {
        fun entity(): ProxyEntity = ProxyEntity(id = 1, groupId = 1, userOrder = 1).putBean(bean).also { it.core = core }

        // bean 深拷贝，改动不影响原节点
        fun cloned(): Node = Node(type, core, bean.clone(), global)
    }

    fun node(bean: AbstractBean, core: Int = ProxyEntity.CORE_AUTO, global: Boolean = false): Node {
        val entity = ProxyEntity().putBean(bean)
        return Node(entity.type, core, bean, global)
    }

    fun vless(block: VMessBean.() -> Unit = {}): VMessBean = VMessBean().apply {
        name = "vless"
        serverAddress = "node.example.com"
        serverPort = 443
        uuid = UUID
        alterId = -1
        initializeDefaultValues()
        security = "tls"
        sni = "node.example.com"
        block()
    }

    fun vmess(block: VMessBean.() -> Unit = {}): VMessBean = VMessBean().apply {
        name = "vmess"
        serverAddress = "node.example.net"
        serverPort = 443
        uuid = UUID
        alterId = 0
        initializeDefaultValues()
        security = "tls"
        sni = "node.example.net"
        block()
    }

    fun trojan(block: TrojanBean.() -> Unit = {}): TrojanBean = TrojanBean().apply {
        name = "trojan"
        serverAddress = "node.example.org"
        serverPort = 443
        password = "fake-password"
        initializeDefaultValues()
        sni = "node.example.org"
        block()
    }

    fun anytls(block: AnyTLSBean.() -> Unit = {}): AnyTLSBean = AnyTLSBean().apply {
        name = "anytls"
        serverAddress = "203.0.113.10"
        serverPort = 8443
        password = "fake-password"
        initializeDefaultValues()
        sni = "anytls.example.net"
        block()
    }

    fun StandardV2RayBean.reality(mldsa: Boolean = false) {
        security = "tls"
        realityPubKey = REALITY_KEY
        realityShortId = SHORT_ID
        if (mldsa) realityMldsa65Verify = MLDSA65
    }

    // ---- 随机节点（固定种子）

    // 编辑器下拉框的指纹（arrays.xml 的 utls_fingerprint_entry），加上只有某个核心认、或谁都不认的名字
    val UTLS_SAMPLES = listOf(
        "", "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized",
        "randomizednoalpn", "hellochrome_131", "chrome120", "chrome_psk", "none", "unsafe", "Chrome", "netscape",
    )
    val VLESS_FLOW_SAMPLES = listOf("", "auto", "xtls-rprx-vision", "xtls-rprx-vision-udp443", "xtls-rprx-direct")
    val TRANSPORT_SAMPLES = listOf("tcp", "http", "ws", "quic", "grpc", "httpupgrade")
    val CORE_SAMPLES = listOf(0, 1, 2, 3, 4, -1)
    val WS_PATH_SAMPLES = listOf("/ws", "/ws?ed=2048", "", "/ws?a=1&ed=1024", "/path?a=1")
    val WS_HEADER_SAMPLES = listOf("", "", "Sec-WebSocket-Protocol", "sec-websocket-protocol", "X-Early-Data")

    private fun <T> Random.pick(values: List<T>): T = values[nextInt(values.size)]
    private fun Random.chance(percent: Int) = nextInt(100) < percent

    private fun Random.standard(bean: StandardV2RayBean, vless: Boolean) {
        bean.type = pick(TRANSPORT_SAMPLES)
        bean.security = if (chance(80)) "tls" else "none"
        bean.sni = if (chance(80)) "sni.example.com" else ""
        bean.alpn = if (chance(30)) "h2,http/1.1" else ""
        if (chance(45)) {
            bean.realityPubKey = REALITY_KEY
            bean.realityShortId = if (chance(70)) SHORT_ID else ""
        }
        if (chance(30)) bean.realityMldsa65Verify = MLDSA65
        if (chance(30)) bean.certificateFingerprint = PIN
        if (chance(25)) bean.certificates = CERT
        bean.allowInsecure = chance(30)
        bean.utlsFingerprint = if (chance(40)) "" else pick(UTLS_SAMPLES)
        bean.enableECH = chance(30)
        bean.echConfig = if (chance(50)) ECH else ""
        bean.enableMux = chance(35)
        bean.muxType = nextInt(3)
        bean.muxPadding = chance(30)
        bean.muxConcurrency = pick(listOf(1, 4, 8))
        bean.packetEncoding = nextInt(3)
        if (bean.type == "ws") {
            bean.path = pick(WS_PATH_SAMPLES)
            bean.host = if (chance(50)) "cdn.example.net" else ""
            bean.wsMaxEarlyData = if (chance(50)) 2048 else 0
            bean.earlyDataHeaderName = pick(WS_HEADER_SAMPLES)
        } else {
            bean.path = if (chance(50)) "/path" else ""
            bean.host = if (chance(30)) "host.example.net" else ""
        }
        if (vless) bean.encryption = pick(VLESS_FLOW_SAMPLES)
    }

    // VMess / VLESS / Trojan / AnyTLS 的存量节点：手动核心取全部取值（含非法值），全局「允许不安全」两种。
    // 生成后经 Kryo 往返（与读库一样），不落库的字段（例如关掉 TLS 后的 TLS 字段）回到缺省值
    fun randomSelectable(random: Random): Node {
        val core = random.pick(CORE_SAMPLES)
        val global = random.chance(25)
        val bean: AbstractBean = when (random.nextInt(4)) {
            0 -> VMessBean().apply {
                alterId = -1
                random.standard(this, vless = true)
            }

            1 -> VMessBean().apply {
                alterId = 0
                encryption = random.pick(listOf("auto", "aes-128-gcm", "zero", "none"))
                random.standard(this, vless = false)
            }

            2 -> TrojanBean().apply {
                password = "fake-password"
                random.standard(this, vless = false)
            }

            else -> AnyTLSBean().apply {
                password = "fake-password"
                sni = if (random.chance(80)) "anytls.example.net" else ""
                alpn = if (random.chance(30)) "h2" else ""
                if (random.chance(30)) certificates = CERT
                if (random.chance(30)) certificateFingerprint = PIN
                utlsFingerprint = if (random.chance(40)) "" else random.pick(UTLS_SAMPLES)
                allowInsecure = random.chance(30)
                echConfig = if (random.chance(25)) ECH else ""
                enableECH = random.chance(30)
            }
        }
        bean.serverAddress = "node.example.com"
        bean.serverPort = 443
        if (bean is VMessBean) bean.uuid = UUID
        bean.initializeDefaultValues()
        return node(bean.clone(), core, global)
    }

    // 其余协议（不能选核），用于跨协议检查与冻结规则的对照
    fun randomOther(random: Random): Node {
        val core = random.pick(CORE_SAMPLES)
        val global = random.chance(25)
        val bean: AbstractBean = when (random.nextInt(10)) {
            0 -> SOCKSBean()
            1 -> HttpBean().apply {
                random.standard(this, vless = false)
                type = "tcp"
            }

            2 -> ShadowsocksBean().apply { method = "aes-128-gcm"; password = "fake-password" }
            3 -> TrojanGoBean().apply { password = "fake-password"; if (random.chance(30)) sni = "tg.example.org" }
            4 -> MieruBean()
            5 -> NaiveBean()
            6 -> HysteriaBean().apply {
                protocolVersion = random.pick(listOf(1, 2))
                protocol = random.nextInt(3)
                serverPorts = "443"
                if (random.chance(40)) certificateFingerprint = PIN
                allowInsecure = random.chance(30)
            }

            7 -> SSHBean()
            8 -> TuicBean().apply { if (random.chance(40)) certificateFingerprint = PIN }
            else -> ShadowTLSBean().apply {
                random.standard(this, vless = false)
                type = "tcp"
            }
        }
        bean.serverAddress = "198.51.100.7"
        bean.serverPort = 443
        bean.initializeDefaultValues()
        return node(bean.clone(), core, global)
    }
}
