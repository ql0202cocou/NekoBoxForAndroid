package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.isWireGuardKey
import io.nekohasekai.sagernet.fmt.wireguard.isWireGuardLocalAddressList
import io.nekohasekai.sagernet.ktx.*

fun parseWireGuard(conf: String): List<WireGuardBean> {
    val ini = Ini(conf)
    val iface = ini["Interface"] ?: error("Missing 'Interface' selection")
    val bean = WireGuardBean().applyDefaultValues()
    val localAddresses = iface.getAll("Address")
    bean.localAddress = localAddresses.flatMap { it.split(",") }.joinToString("\n")
    require(isWireGuardLocalAddressList(bean.localAddress, allowEmpty = false)) {
        "Invalid WireGuard Interface Address"
    }
    bean.privateKey = iface["PrivateKey"].orEmpty()
    require(isWireGuardKey(bean.privateKey)) { "Invalid WireGuard Interface PrivateKey" }
    bean.mtu = iface["MTU"]?.toIntOrNull() ?: bean.mtu
    // The app has no per-profile DNS model; keep dropping the field but say so
    if (iface.getAll("DNS").isNotEmpty()) {
        Logs.w("WireGuard 'Interface' DNS field dropped")
    }
    val peers = ini.getAll("Peer")
    if (peers.isEmpty()) error("Missing 'Peer' selections")
    val beans = mutableListOf<WireGuardBean>()
    for (peer in peers) {
        val endpoint = peer["Endpoint"]
        if (endpoint.isNullOrBlank()) {
            continue
        }

        val peerBean = bean.clone() as WireGuardBean
        // A bare IPv6 endpoint (no brackets, several colons) cannot be told
        // apart from host:port, and an endpoint without a port is unusable
        val (host, portText) = endpoint.splitHostPort() ?: continue
        if (host.isBlank()) {
            Logs.w("WireGuard peer skipped: endpoint without host")
            continue
        }
        if (':' in host && !endpoint.startsWith("[")) {
            Logs.w("WireGuard peer skipped: bare IPv6 endpoint without brackets")
            continue
        }
        peerBean.serverAddress = host
        peerBean.serverPort = portText?.toIntOrNull()?.takeIf { it in 1..65535 } ?: continue
        peerBean.peerPublicKey = peer["PublicKey"] ?: continue
        peerBean.peerPreSharedKey = peer["PresharedKey"]
        if (!isWireGuardKey(peerBean.peerPublicKey) || !isWireGuardKey(peerBean.peerPreSharedKey.orEmpty(), allowEmpty = true)) {
            Logs.w("WireGuard peer skipped: invalid peer key")
            continue
        }
        peerBean.peerKeepalive = peer["PersistentKeepalive"]?.toIntOrNull() ?: 0
        peerBean.peerAllowedIps = peer.getAll("AllowedIPs").joinToString(",")
        if (!isWireGuardLocalAddressList(peerBean.peerAllowedIps, allowEmpty = peerBean.peerAllowedIps.isBlank())) {
            Logs.w("WireGuard peer skipped: invalid AllowedIPs")
            continue
        }
        beans.add(peerBean.applyDefaultValues())
    }
    if (beans.isEmpty()) error("Empty available peer list")
    return beans
}

// Minimal INI reader covering the WireGuard config syntax ini4j was used for:
// [section] headers, key = value (or key : value) pairs, ';' / '#' full-line
// comments and surrounding whitespace trimming. Names are case-sensitive, a
// repeated key inside one section keeps every value (get returns the last)
// and any other line is a parse error. Sections are kept in file order and
// repeated ones stay separate, because [Peer] is repeated once per peer;
// ini4j merged them under its default config, which silently collapsed a
// multi-peer profile into its last peer. ini4j's escape sequences and line
// continuations are not supported; WireGuard configs do not use them.
private class Ini(text: String) {

    private val sections = mutableListOf<Pair<String, Section>>()

    init {
        var current: Section? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[")) {
                require(line.length > 2 && line.endsWith("]")) { "Invalid WireGuard INI syntax" }
                val section = Section()
                sections.add(line.substring(1, line.length - 1).trim() to section)
                current = section
            } else {
                val separator = line.indexOfFirst { it == '=' || it == ':' }
                require(separator > 0 && current != null) { "Invalid WireGuard INI syntax" }
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                current.options.getOrPut(key) { mutableListOf() }.add(value)
            }
        }
    }

    // the first section with this name, for the ones WireGuard allows once
    operator fun get(name: String): Section? =
        sections.firstOrNull { (sectionName, _) -> sectionName == name }?.second

    // every section with this name, in file order
    fun getAll(name: String): List<Section> =
        sections.filter { (sectionName, _) -> sectionName == name }.map { it.second }

    class Section {
        val options = LinkedHashMap<String, MutableList<String>>()

        operator fun get(key: String): String? = options[key]?.last()

        fun getAll(key: String): List<String> = options[key].orEmpty()
    }
}
