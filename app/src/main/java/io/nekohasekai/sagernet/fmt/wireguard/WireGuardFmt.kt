package io.nekohasekai.sagernet.fmt.wireguard

import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma

// wireguard endpoints (sing-box 1.13+) want the reserved bytes as a JSON array
fun genReservedList(anyStr: String): List<Int>? {
    return try {
        val text = anyStr.trim()
        if (text.startsWith("[") != text.endsWith("]")) return null
        val list = text.removeSurrounding("[", "]").listByLineOrComma().map {
            it.trim().toInt()
        }
        if (list.size == 3 && list.all { it in 0..255 }) list else null
    } catch (e: Exception) {
        null
    }
}

fun buildSingBoxEndpointWireGuardBean(bean: WireGuardBean): SingBoxOptions.Endpoint_WireGuardOptions {
    return SingBoxOptions.Endpoint_WireGuardOptions().apply {
        type = "wireguard"
        address = bean.localAddress.listByLineOrComma()
        private_key = bean.privateKey
        mtu = bean.mtu
        peers = listOf(SingBoxOptions.Endpoint_WireGuardPeer().apply {
            address = bean.serverAddress
            port = bean.serverPort
            public_key = bean.peerPublicKey
            pre_shared_key = bean.peerPreSharedKey
            // 填了 AllowedIPs 就照用：目标不在范围内的包会被 WireGuard 丢弃，与官方客户端一致。
            // 留空时放行全部——sing-box 不接受空的 allowed_ips（"missing allowed ips for
            // peer"），旧的单 peer 出站隐含的就是默认路由。导入时已清洗，但旧版本存下的
            // 值与备份恢复的行没经过校验，这里同样把关，免得 sing-box 启动时才报不知所云的错
            val allowedIps = bean.peerAllowedIps.listByLineOrComma()
            if (!isWireGuardLocalAddressList(bean.peerAllowedIps)) {
                error("WireGuard allowed IPs must be CIDR prefixes (e.g. 0.0.0.0/0)")
            }
            allowed_ips = allowedIps.ifEmpty { listOf("0.0.0.0/0", "::/0") }
            if (bean.peerKeepalive > 0) {
                persistent_keepalive_interval = bean.peerKeepalive
            }
            if (bean.reserved.isNotBlank()) {
                reserved = genReservedList(bean.reserved)
                    ?: error("WireGuard reserved must contain exactly three bytes (0..255)")
            }
        })
    }
}
