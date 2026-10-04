package io.nekohasekai.sagernet.golden.collect

import io.nekohasekai.sagernet.fmt.wireguard.isWireGuardLocalAddressList
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.isIpAddressV6
import io.nekohasekai.sagernet.ktx.isLocalNameserverAddress
import io.nekohasekai.sagernet.ktx.isServerAddress
import io.nekohasekai.sagernet.ktx.parseNumericAddress
import io.nekohasekai.sagernet.ktx.splitHostPort
import io.nekohasekai.sagernet.ktx.unwrapIPV6Host
import io.nekohasekai.sagernet.ktx.usableNameservers
import io.nekohasekai.sagernet.ktx.wrapIPV6Host
import java.net.Inet6Address

// 地址判断 / 解析函数在 Android 上的旧结果。parseNumericAddress 走 Os.inet_pton，
// isWireGuardLocalAddressList 走 InetAddress.getByName，都不能在普通 JVM 上当对照；
// 以后换纯 Kotlin 实现时拿这份结果在 JVM 上逐条比对（plan.md D1）
object GoldenAddressCorpus {

    // 函数名 → 用在哪里（只收配置生成与其输入校验会走到的）
    private val functions = linkedMapOf<String, Pair<String, (String) -> Any?>>(
        "isIpAddress" to ("ktx/Nets.kt；ConfigBuilder 判断节点 / DNS 地址是否为域名，Trojan-Go / hysteria / naive 生成器的 SNI 回退" to { s -> s.isIpAddress() }),
        "isIpAddressV6" to ("ktx/Nets.kt；wrapIPV6Host 内部" to { s -> s.isIpAddressV6() }),
        "parseNumericAddress" to ("ktx/Utils.kt（Os.inet_pton）；makeDnsServer 校验 DNS 地址、isLocalNameserverAddress" to { s -> numeric(s.parseNumericAddress()) }),
        "unwrapIPV6Host" to ("ktx/Nets.kt；AbstractBean.initializeDefaultValues 去掉地址的方括号" to { s -> s.unwrapIPV6Host() }),
        "wrapIPV6Host" to ("ktx/Nets.kt；hysteria / naive 生成器与 displayAddress 拼 host:port" to { s -> s.wrapIPV6Host() }),
        "splitHostPort" to ("ktx/Nets.kt；makeDnsServer 拆 DNS 地址的 host 与端口" to { s -> s.splitHostPort()?.let { listOf(it.first, it.second) } }),
        "isLocalNameserverAddress" to ("ktx/Nets.kt；usableNameservers 过滤分组 DNS 里的回环地址" to { s -> s.isLocalNameserverAddress() }),
        "usableNameservers" to ("ktx/Nets.kt；分组「节点解析 DNS」逐行过滤" to { s -> s.usableNameservers() }),
        "isServerAddress" to ("ktx/Nets.kt；编辑器保存前的服务器地址校验" to { s -> isServerAddress(s) }),
        "isWireGuardLocalAddressList" to ("fmt/wireguard/WireGuardAddress.kt（InetAddress.getByName）；WireGuard allowed IPs 校验" to { s -> isWireGuardLocalAddressList(s) }),
    )

    private val inputs = listOf(
        // IPv4
        "192.0.2.1", "198.51.100.254", "203.0.113.0", "0.0.0.0", "255.255.255.255", "127.0.0.1",
        "256.0.0.1", "192.0.2.256", "1.2.3", "1.2.3.4.5", "1..2.3", "1.2.3.", ".1.2.3",
        "010.0.0.1", "192.168.01.1", "192.0.2.001", "00.0.0.0", "0x7f.0.0.1", "0177.0.0.1",
        "3232235777", "1.2.3.-4", "+1.2.3.4", "1.2.3.4%eth0", "١٩٢.٠.٢.١",
        // IPv6
        "2001:db8::1", "2001:DB8::A", "::", "::1", "2001:db8:0:0:0:0:0:1",
        "2001:0db8:0000:0000:0000:0000:0000:0001", "2001:db8:1:2:3:4:5:6", "2001:db8:1:2:3:4:5:6:7",
        "2001:db8::1::2", "2001:db8:::1", "2001:db8::g", "02001:db8::1", "2001:db8:1:2:3:4:5",
        "fe80::1%wlan0", "fe80::1%1", "fe80::1%", "::ffff:192.0.2.1", "::ffff:c000:201", "::192.0.2.1",
        "64:ff9b::192.0.2.1", "2001:db8::192.0.2.1", "::ffff:256.1.1.1", "1::2::3", ":", "%",
        // 方括号
        "[2001:db8::1]", "[2001:db8::1]:443", "[fe80::1%wlan0]", "[fe80::1%wlan0]:53", "[::1]:53",
        "[2001:db8::1", "2001:db8::1]", "[]", "[]:53", "[[2001:db8::1]]", "[192.0.2.1]", "[2001:db8::1]x",
        "[2001:db8::1]:", "[example.com]:53",
        // host:port 与 DNS 地址写法
        "192.0.2.1:53", "example.com:443", "example.com:", ":53", "127.0.0.1:53", "0.0.0.0:53",
        "udp://127.0.0.1:53", "tls://[::1]", "https://dns.example.net/dns-query", "https://[::1]/dns-query",
        "localhost", "LOCALHOST", "localhost:53", "system", "local", "dhcp://auto",
        // CIDR
        "192.0.2.0/24", "2001:db8::/32", "0.0.0.0/0", "::/0", "192.0.2.1/33", "192.0.2.1/032",
        "fe80::/64", "fe80::1%wlan0/64", "::ffff:192.0.2.1/128", "192.0.2.0/24\n2001:db8::/32",
        "192.0.2.0/24,example.com/24", "010.0.0.0/8",
        // 域名与其它非法值
        "example.com", "EXAMPLE.com", "sub.example.org.", "xn--fiqs8s.example", "a.b.c.d", "exa mple.com",
        "example.com/path", "1", "1234567890", "-1", "a",
        "", " ", "\t", " 192.0.2.1", "192.0.2.1 ", "192.0.2.1\n", " 2001:db8::1 ",
        // 多行（分组 DNS 的写法）
        "https://dns.example.net/dns-query#h3\nsystem\n127.0.0.1\n[::1]:53\nlocal\n192.0.2.53\n192.0.2.53",
        "# comment\n\ntls://dns.example.org\n  udp://192.0.2.53:5353  \nlocalhost",
    )

    private fun numeric(address: java.net.InetAddress?): Any? = address?.let {
        linkedMapOf(
            "class" to it.javaClass.simpleName,
            "hostAddress" to it.hostAddress,
            "bytes" to it.address.joinToString("") { b -> "%02x".format(b) },
            "scopeId" to (it as? Inet6Address)?.scopeId,
        )
    }

    fun build(): Map<String, Any?> = linkedMapOf(
        "formatVersion" to GoldenCollector.FORMAT_VERSION,
        "functions" to functions.mapValuesTo(LinkedHashMap()) { it.value.first },
        "entries" to inputs.map { input ->
            linkedMapOf(
                "input" to input,
                "results" to functions.mapValuesTo(LinkedHashMap()) { (_, entry) ->
                    try {
                        entry.second(input)
                    } catch (e: Exception) {
                        linkedMapOf("exception" to e.javaClass.name, "message" to e.message)
                    }
                },
            )
        },
    )
}
