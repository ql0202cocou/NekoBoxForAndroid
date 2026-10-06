package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import io.nekohasekai.sagernet.fmt.ExternalHop
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.effectiveAllowInsecure
import io.nekohasekai.sagernet.fmt.requireDistinctHops
import io.nekohasekai.sagernet.fmt.requireLocalAuth
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import moe.matsuri.nb4a.proxy.anytls.isCertificateFingerprint
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.echAsBase64
import moe.matsuri.nb4a.utils.listByLineOrComma

// Xray-core 已移除单独的 h2（带 TLS 的 "http"）与 quic 传输：固定版本对这类配置报「The feature HTTP transport
// … has been removed」/「The feature QUIC transport … has been removed」。选核由能力表决定（XRAY_TRANSPORT_H2 /
// XRAY_TRANSPORT_QUIC），这里只是生成器的最后一道防线：判定之外的调用方把这类节点交给 Xray 时报错，
// 不生成核心会拒绝的配置。VMess / VLESS / Trojan 共用
fun StandardV2RayBean.xrayLacksTransport(): Boolean =
    type == "quic" || (type == "http" && isTLS())

// Xray-core 在生成配置时无条件拒绝 allowInsecure（已移除的功能；固定版本 v26.9.30 的
// infra/conf/transport_security.go，写 false 不报错）。证书固定不受影响：pinnedPeerCertSha256 是官方给的替代，
// 本来就优先于 allowInsecure。REALITY 节点不算：REALITY 分支不写 allowInsecure（REALITY 不验证服务端证书）。
// 选核由能力表决定（XRAY_ALLOW_INSECURE），这里同 xrayLacksTransport 只是生成器的最后一道防线；
// 全局开关由调用方传入（buildXrayOutbound 取 ExternalCoreSettings）
fun StandardV2RayBean.xrayLacksAllowInsecure(globalAllowInsecure: Boolean): Boolean =
    isTLS() && realityPubKey.isNullOrBlank() && certificateFingerprint.isNullOrBlank() &&
        effectiveAllowInsecure(allowInsecure, globalAllowInsecure)

// 没有路由规则命中的流量走 outbounds 的第一项，所以第一项固定是 blackhole：漏了规则的入站只会丢流量，
// 不会串到某个节点
const val XRAY_BLOCK_TAG = "block"

// 一组跳实例的 Xray 配置（plan.md K0 做法 2）：每个跳实例一个本机 socks 入站，routing 按入站 tag 把它的
// TCP 与 UDP 都指到它自己的出站。outbounds 与 hops 一一对应，是 buildXrayOutbound 的结果。
// 规则指向不存在的出站、端口重复时 Xray 都不报错，tag 重复则整份配置启动失败：都在这里先保证。
// 每个入站都要求跳实例的本机 socks 凭据（ExternalHop.localAuth），拿不到就报错，不生成不认证的入站
fun buildXrayConfig(hops: List<ExternalHop>, outbounds: List<Map<String, Any?>>, settings: ExternalCoreSettings): String {
    require(hops.size == outbounds.size) { "${hops.size} hops but ${outbounds.size} outbounds" }
    requireDistinctHops(hops, setOf(XRAY_BLOCK_TAG))
    val auths = hops.map { it.requireLocalAuth() }
    // 用共用的 gson 直接序列化集合；它不输出值为 null 的键，与 org.json put(键, null) 删键的结果一致
    return gson.toJson(LinkedHashMap<String, Any?>().apply {
        put("log", LinkedHashMap<String, Any?>().apply {
            // 与 ConfigBuilder 的 sing-box 档位一致；Xray 没有 trace，最高到 debug
            put(
                "loglevel", when (settings.logLevel) {
                    2 -> "info"
                    3, 4 -> "debug"
                    else -> "warning"
                }
            )
            // 访问日志（每个连接的 accepted / rejected 行）不受 loglevel 控制：整体关掉，
            // 启动就绪探测因此也不留行
            put("access", "none")
        })
        put("inbounds", hops.mapIndexed { i, hop ->
            LinkedHashMap<String, Any?>().apply {
                put("tag", hop.inboundTag)
                put("listen", LOCALHOST)
                put("port", hop.localPort)
                put("protocol", "socks")
                put("settings", LinkedHashMap<String, Any?>().apply {
                    // auth 必须正好是小写的 "password"：别的值 run -test 照样通过，运行时却不要求认证
                    put("auth", "password")
                    put("accounts", listOf(linkedMapOf("user" to auths[i].username, "pass" to auths[i].password)))
                    put("udp", true)
                })
            }
        })
        put("outbounds", ArrayList<Any?>().apply {
            add(LinkedHashMap<String, Any?>().apply {
                put("tag", XRAY_BLOCK_TAG)
                put("protocol", "blackhole")
            })
            hops.forEachIndexed { i, hop ->
                add(LinkedHashMap<String, Any?>().apply {
                    put("tag", hop.outboundTag)
                    putAll(outbounds[i])
                })
            }
        })
        put("routing", LinkedHashMap<String, Any?>().apply {
            put("domainStrategy", "AsIs")
            put("rules", hops.map { hop ->
                LinkedHashMap<String, Any?>().apply {
                    put("type", "field")
                    put("inboundTag", listOf(hop.inboundTag))
                    put("outboundTag", hop.outboundTag)
                }
            })
        })
    })
}

// Xray 配置里一个跳实例的出站（tag 由 buildXrayConfig 写入）：拨向 dialAddress:dialPort（跳实例的拨号目标），
// 其余与 K0 之前的单节点配置相同，节点本身的校验也在这里报错
fun buildXrayOutbound(
    bean: VMessBean,
    dialAddress: String,
    dialPort: Int,
    settings: ExternalCoreSettings,
): LinkedHashMap<String, Any?> {
    requireXrayStream(bean, settings.globalAllowInsecure)
    val user = LinkedHashMap<String, Any?>().apply {
        put("id", bean.uuid)
        if (bean.isVLESS) {
            put("encryption", "none")
            if (bean.encryption.isNotBlank() && bean.encryption != "auto") {
                put("flow", bean.encryption)
            }
        } else {
            put("alterId", bean.alterId)
            put("security", bean.encryption.takeIf { it.isNotBlank() } ?: "auto")
        }
    }

    return LinkedHashMap<String, Any?>().apply {
        put("protocol", if (bean.isVLESS) "vless" else "vmess")
        put("settings", LinkedHashMap<String, Any?>().apply {
            put("vnext", ArrayList<Any?>().apply {
                add(LinkedHashMap<String, Any?>().apply {
                    put("address", dialAddress)
                    put("port", dialPort)
                    put("users", ArrayList<Any?>().apply { add(user) })
                })
            })
        })
        put("streamSettings", buildXrayStreamSettings(bean))
        // packetEncoding = xudp 经 Xray 的 mux 配置表达（xudpConcurrency）；Xray 没有 packetaddr。另外 Xray 的 VMess / VLESS
        // 出站默认就把 53、443 端口以外的 UDP 改走 XUDP，与 packetEncoding 无关。vision 流控不支持 mux，整个不写。
        // 只开 mux 不开 xudp 时不写 xudpProxyUDP443，Xray 缺省拒绝 UDP/443（生成器缺口，未修；Trojan 的 mux 同样）。
        // 拒绝已在回环实测（K1b X2 L2-48…51，v26.3.27 与 v26.9.30 相同），拒绝记录只在 Xray 的 info 级
        if (!bean.isVisionFlow && (bean.enableMux || bean.packetEncoding == 2)) {
            put("mux", LinkedHashMap<String, Any?>().apply {
                put("enabled", true)
                // -1 leaves TCP un-muxed, so packetEncoding=xudp alone only moves UDP
                // onto xudp (sing-box packet_encoding semantics); mux.cool for TCP
                // needs an explicit enableMux
                put("concurrency", if (bean.enableMux) bean.xrayMuxConcurrency() else -1)
                if (bean.packetEncoding == 2) {
                    put("xudpConcurrency", 16)
                    // "allow": UDP/443 rides xudp like every other UDP flow, the same
                    // as sing-box's packet_encoding=xudp. Whether QUIC is blocked is
                    // the route rules' call (the default "Block QUIC" rule), not the
                    // core's — Xray's default "reject" silently overrode that here.
                    put("xudpProxyUDP443", "allow")
                }
            })
        }
    }
}

// Trojan 节点的 Xray 出站（D10），tag 与拨号目标同 VMess / VLESS。settings.servers 只放一项（Xray 只许一个），
// 不写 flow（Xray 对 Trojan 的 flow 一律报已移除）；传输与 TLS / REALITY 与 VMess / VLESS 共用一套生成。
// Xray 对每个 Trojan 出站都打一条弃用警告，run -test 的退出码仍是 0，校验照常通过
fun buildXrayOutbound(
    bean: TrojanBean,
    dialAddress: String,
    dialPort: Int,
    settings: ExternalCoreSettings,
): LinkedHashMap<String, Any?> {
    requireXrayStream(bean, settings.globalAllowInsecure)
    // Xray 自己也拒绝空密码，在这里报出来，调用方能带上节点名
    if (bean.password.isNullOrEmpty()) error("Trojan password is empty")

    return LinkedHashMap<String, Any?>().apply {
        put("protocol", "trojan")
        put("settings", LinkedHashMap<String, Any?>().apply {
            put("servers", ArrayList<Any?>().apply {
                add(LinkedHashMap<String, Any?>().apply {
                    put("address", dialAddress)
                    put("port", dialPort)
                    put("password", bean.password)
                })
            })
        })
        put("streamSettings", buildXrayStreamSettings(bean))
        // 只有 enableMux 打开 Mux.Cool，写法同 VMess / VLESS。Trojan 没有 packet encoding：trojan:// 链接可能带进
        // packetEncoding，这里不读，不因它写 xudpConcurrency；Trojan 也没有 vision 流控
        if (bean.enableMux) {
            put("mux", LinkedHashMap<String, Any?>().apply {
                put("enabled", true)
                put("concurrency", bean.xrayMuxConcurrency())
            })
        }
    }
}

// Xray 不能表达的传输与生效的 allowInsecure：生成前报错（选核判定已排除这两类，这里兜底），VMess / VLESS / Trojan 共用
private fun requireXrayStream(bean: StandardV2RayBean, globalAllowInsecure: Boolean) {
    if (bean.xrayLacksTransport()) {
        error("xray-core no longer supports the ${bean.type} transport, use the sing-box core for this profile")
    }
    if (bean.xrayLacksAllowInsecure(globalAllowInsecure)) {
        error("xray-core no longer supports allowInsecure, use a certificate fingerprint or the sing-box core for this profile")
    }
}

// 开 mux 时 Mux.Cool 每条连接的子连接上限；没填（≤0）时取 8
private fun StandardV2RayBean.xrayMuxConcurrency(): Int = if (muxConcurrency > 0) muxConcurrency else 8

private fun buildXrayStreamSettings(bean: StandardV2RayBean): Map<String, Any?> {
    // 经 mapping 外核只能拨到本地地址，TLS SNI 需要显式兜底；
    // 与 sing-box 对齐：sni 为空时兜底为 serverAddress（IP 也一样）
    val sni = bean.sni.takeIf { it.isNotBlank() }
        ?: bean.serverAddress.takeIf { it.isNotBlank() }
    return LinkedHashMap<String, Any?>().apply {
        // transport；存量节点的未知传输方式明确报错，不退成 TCP
        when (requireV2RayTransport(bean.type)) {
            "ws" -> {
                put("network", "ws")
                put("wsSettings", LinkedHashMap<String, Any?>().apply {
                    // Xray 只从路径里的「?ed=N」读 early data 上限；maxEarlyData / earlyDataHeaderName 这两个键会被静默忽略
                    val earlyData = bean.resolveWsEarlyData()
                    var path = earlyData.path
                    earlyData.maxEarlyData?.let {
                        path += (if (path.contains("?")) "&" else "?") + "ed=$it"
                    }
                    put("path", path)
                    // Host 写独立的 host 键：写在 headers 里会打一条弃用警告，再被迁到 host（infra/conf/transport_method.go
                    // 的 WebSocketConfig.Build，v26.9.30），两种写法发出的 Host 头相同
                    if (bean.host.isNotBlank()) put("host", bean.host)
                })
            }

            // h2 is rejected by xrayLacksTransport(); only the v2ray-style
            // tcp fake-http header form reaches here
            "http" -> {
                put("network", "tcp")
                put("tcpSettings", LinkedHashMap<String, Any?>().apply {
                    put("header", LinkedHashMap<String, Any?>().apply {
                        put("type", "http")
                        put("request", LinkedHashMap<String, Any?>().apply {
                            put("path", ArrayList<Any?>().apply {
                                add(bean.path.takeIf { it.isNotBlank() } ?: "/")
                            })
                            if (bean.host.isNotBlank()) {
                                put("headers", LinkedHashMap<String, Any?>().apply {
                                    put("Host", bean.host.listByLineOrComma())
                                })
                            }
                        })
                    })
                })
            }

            "grpc" -> {
                put("network", "grpc")
                put("grpcSettings", LinkedHashMap<String, Any?>().apply {
                    put("serviceName", bean.path)
                })
            }

            "httpupgrade" -> {
                put("network", "httpupgrade")
                // 键名大小写不敏感，只有拼错的键才丢；保持全小写只是与上游文档一致
                put("httpupgradeSettings", LinkedHashMap<String, Any?>().apply {
                    if (bean.host.isNotBlank()) put("host", bean.host)
                    put("path", bean.path.takeIf { it.isNotBlank() } ?: "/")
                })
            }

            "tcp" -> put("network", "tcp")

            // quic 已被 xrayLacksTransport() 拦下
            else -> error("can't reach")
        }

        // security
        val fp = bean.effectiveUtlsFingerprint()
        // Hidden REALITY fields survive disabling TLS in the editor. Honor the
        // security switch, matching buildSingBoxOutboundTLS, before using them.
        if (bean.security == "tls" && bean.realityPubKey.isNotBlank()) {
            requireValidReality(bean.realityPubKey, bean.realityShortId)
            require(isRealityMldsa65Verify(bean.realityMldsa65Verify)) {
                "Invalid REALITY mldsa65Verify: expected 2603 URL-safe Base64 characters (1952 bytes)"
            }
            put("security", "reality")
            put("realitySettings", LinkedHashMap<String, Any?>().apply {
                if (sni != null) put("serverName", sni)
                put("publicKey", bean.realityPubKey)
                if (bean.realityShortId.isNotBlank()) put("shortId", bean.realityShortId)
                // 抗量子的 REALITY 校验，只有 Xray 有；sing-box 1.14.2 不支持
                if (bean.realityMldsa65Verify.isNotBlank()) {
                    put("mldsa65Verify", bean.realityMldsa65Verify)
                }
                fp?.let { put("fingerprint", it) }
            })
        } else if (bean.security == "tls") {
            put("security", "tls")
            put("tlsSettings", LinkedHashMap<String, Any?>().apply {
                if (sni != null) put("serverName", sni)
                if (bean.alpn.isNotBlank()) {
                    put("alpn", bean.alpn.listByLineOrComma())
                }
                // 证书固定优先于 allowInsecure，取舍与 buildMihomoConfig 相同。Xray 与 mihomo 一样按十六进制解码
                // pinnedPeerCertSha256，比对在 transport/internet/tls/config.go 的 verifyPeerCert / verifyChain 里
                // （pin.go 只负责求哈希）：命中叶子证书时跳过校验；命中链上 IsCA 的证书时仍按 serverName 与有效期
                // 校验；命中非 CA 的中间证书不算命中
                val certPin = bean.certificateFingerprint.takeIf { it.isNotBlank() }
                if (certPin != null) {
                    require(isCertificateFingerprint(certPin)) {
                        "Invalid certificate fingerprint: expected a SHA-256 digest of 64 hex characters (colons allowed)"
                    }
                    put("pinnedPeerCertSha256", certPin)
                }
                // 从不写 allowInsecure：Xray 已移除这个功能（见 xrayLacksAllowInsecure），能力表也不让这类节点走 Xray；
                // 没有证书指纹而 allowInsecure 生效的节点在 requireXrayStream 已报错，到不了这里
                fp?.let { put("fingerprint", it) }
                if (bean.certificates.isNotBlank()) {
                    put("certificates", ArrayList<Any?>().apply {
                        add(LinkedHashMap<String, Any?>().apply {
                            put("usage", "verify")
                            put("certificate", bean.certificates.lines())
                        })
                    })
                }
                // presence of echConfigList enables ECH; blank means "query DNS", leave that to sing-box
                if (bean.enableECH && bean.echConfig.isNotBlank()) {
                    put("echConfigList", bean.echConfig.echAsBase64())
                }
            })
        }
    }
}
