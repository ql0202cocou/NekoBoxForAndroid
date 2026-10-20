package io.nekohasekai.sagernet.uitest

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleRepository
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 截图用的夹具数据，经现有 Repository / DAO 写入真实的 Room 库。
 * 只用文档保留地址（192.0.2.x、198.51.100.x、example.com 等）与虚构凭据；时间戳固定，不读系统时钟。
 *
 * 内容：一个普通分组（7 个节点：未测 / 可用带延迟 / 失败、带流量、一个长名字）、
 * 一个订阅分组（3 个节点，带流量与到期日）、3 条路由规则。
 */
object UiFixtures {

    // 2026-01-15 08:00:00 UTC，订阅「上次更新」；到期日在其后约一年
    const val LAST_UPDATED_SECONDS = 1_768_464_000L
    const val EXPIRY_SECONDS = 1_800_000_000L

    const val NORMAL_GROUP = "Personal"
    const val SUBSCRIPTION_GROUP = "Example Subscription"
    const val LONG_NAME =
        "Frankfurt 04 | IEPL dedicated line | streaming unlock | 3x multiplier | long profile name"

    private const val FAKE_UUID = "00000000-0000-4000-8000-000000000001"
    private const val GB = 1024L * 1024 * 1024
    private const val MB = 1024L * 1024

    class Result(val normalGroup: ProxyGroup, val subscriptionGroup: ProxyGroup, val profiles: List<ProxyEntity>)

    /** 写入全部夹具。主题固定为粉色（PINK_SSR）、亮色，选中第一个分组的第一个节点 */
    fun install(
        theme: Int = Theme.PINK_SSR,
        nightTheme: Int = 2, // 2 = 始终亮色，见 Theme.getNightMode
    ): Result = onBackground {
        DataStore.appTheme = theme
        DataStore.nightTheme = nightTheme
        // 路由页首次读规则会补建一批默认规则（含随语言变化的内容），截图只要下面的 3 条
        DataStore.rulesFirstCreate = true

        val normal = GroupRepository.createGroup(ProxyGroup(name = NORMAL_GROUP))
        val subscription = GroupRepository.createGroup(
            ProxyGroup(
                name = SUBSCRIPTION_GROUP,
                type = GroupType.SUBSCRIPTION,
                subscription = SubscriptionBean().apply {
                    type = 1
                    link = "https://example.com/subscription?token=fake-token"
                    lastUpdated = LAST_UPDATED_SECONDS
                    bytesUsed = 18 * GB
                    bytesRemaining = 82 * GB
                    expiryDate = EXPIRY_SECONDS
                    username = "demo-user"
                },
            )
        )

        val profiles = ArrayList<ProxyEntity>()
        suspend fun add(group: ProxyGroup, bean: AbstractBean, status: Int = 0, ping: Int = 0, error: String? = null,
                        tx: Long = 0, rx: Long = 0) {
            val entity = ProfileRepository.createProfile(group.id, bean)
            if (status != 0) SagerDatabase.proxyDao.updateStatus(entity.id, status, ping, error)
            if (tx + rx != 0L) SagerDatabase.proxyDao.updateTraffic(entity.id, tx, rx)
            profiles += entity
        }

        // 普通分组：被选中的可用节点、带流量的可用节点、失败、未测、带流量的未测、长名字、HTTP
        add(normal, vmess("Tokyo 01 VMess", "192.0.2.10", 443), 1, 86, tx = 1_240 * MB, rx = 8_410 * MB)
        add(normal, shadowsocks("Singapore 02 SS", "192.0.2.20", 8388), 1, 142, tx = 36 * MB, rx = 210 * MB)
        add(normal, trojan("Osaka 03 Trojan", "198.51.100.30", 443), 3, 0, "dial tcp 198.51.100.30:443: i/o timeout")
        add(normal, socks("Local SOCKS5", "192.0.2.40", 1080))
        add(normal, hysteria("Seoul 05 Hysteria2", "192.0.2.50", 8443), 0, 0, tx = 5 * MB, rx = 12 * MB)
        add(normal, vmess(LONG_NAME, "192.0.2.60", 443), 1, 318)
        add(normal, http("Office HTTP proxy", "192.0.2.70", 8080))

        // 订阅分组
        add(subscription, vmess("Sub | Hong Kong 01", "203.0.113.11", 443), 1, 64, tx = 90 * MB, rx = 640 * MB)
        add(subscription, tuic("Sub | US West 02", "203.0.113.12", 443), 3, 0, "tuic: handshake failed")
        add(subscription, trojan("Sub | UK 03", "203.0.113.13", 443))

        // 配置页默认选中分组与节点
        DataStore.selectedGroup = normal.id
        DataStore.selectedProxy = profiles.first().id
        DataStore.currentProfile = profiles.first().id

        RuleRepository.createRule(
            RuleEntity(
                name = "Streaming via proxy", enabled = true, outbound = 0L,
                domains = "domain:video.example.com\ndomain:stream.example.org", port = "443",
            )
        )
        RuleRepository.createRule(
            RuleEntity(
                name = "Intranet direct", enabled = true, outbound = -1L,
                domains = "domain:intranet.example.com", ip = "192.0.2.0/24",
            )
        )
        RuleRepository.createRule(
            RuleEntity(
                name = "Block ads", enabled = false, outbound = -2L,
                domains = "domain:ads.example.net\ndomain:tracker.example.net", protocol = "quic",
            )
        )

        Result(normal, subscription, profiles)
    }

    /** SagerDatabase 不开 allowMainThreadQueries，写库要在后台线程；Dispatchers.IO 上的真线程，settle 会等到它挂起 */
    private fun <T> onBackground(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private fun vmess(name: String, address: String, port: Int) = VMessBean().apply {
        this.name = name
        serverAddress = address
        serverPort = port
        uuid = FAKE_UUID
        alterId = 0
        security = "tls"
        sni = "node.example.com"
        type = "ws"
        path = "/ws"
        host = "cdn.example.com"
    }

    private fun shadowsocks(name: String, address: String, port: Int) = ShadowsocksBean().apply {
        this.name = name
        serverAddress = address
        serverPort = port
        method = "aes-256-gcm"
        password = "fake-password"
    }

    private fun trojan(name: String, address: String, port: Int) = TrojanBean().apply {
        this.name = name
        serverAddress = address
        serverPort = port
        password = "fake-password"
        sni = "node.example.net"
    }

    private fun socks(name: String, address: String, port: Int) = SOCKSBean().apply {
        this.name = name
        serverAddress = address
        serverPort = port
        protocol = SOCKSBean.PROTOCOL_SOCKS5
        username = "demo"
        password = "fake-password"
    }

    private fun http(name: String, address: String, port: Int) = HttpBean().apply {
        this.name = name
        serverAddress = address
        serverPort = port
        username = "demo"
        password = "fake-password"
    }

    private fun hysteria(name: String, address: String, port: Int) = HysteriaBean().apply {
        this.name = name
        serverAddress = address
        serverPort = port
        protocolVersion = 2
        authPayload = "fake-password"
        sni = "node.example.net"
    }

    private fun tuic(name: String, address: String, port: Int) = TuicBean().apply {
        this.name = name
        serverAddress = address
        serverPort = port
        uuid = FAKE_UUID
        token = "fake-token"
        sni = "node.example.net"
    }
}
