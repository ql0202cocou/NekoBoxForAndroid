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
            // Required since the move from the wireguard outbound to the endpoint:
            // sing-box rejects a peer with an empty allowed_ips ("missing allowed ips
            // for peer"). The single-peer outbound used to imply a default route.
            allowed_ips = listOf("0.0.0.0/0", "::/0")
            if (bean.reserved.isNotBlank()) {
                reserved = genReservedList(bean.reserved)
                    ?: error("WireGuard reserved must contain exactly three bytes (0..255)")
            }
        })
    }
}
