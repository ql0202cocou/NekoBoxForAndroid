package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import io.nekohasekai.sagernet.fmt.ExternalHop
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.effectiveAllowInsecure
import io.nekohasekai.sagernet.fmt.requireDistinctHops
import io.nekohasekai.sagernet.fmt.requireLocalAuth
import moe.matsuri.nb4a.proxy.anytls.isCertificateFingerprint
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.echAsBase64
import moe.matsuri.nb4a.utils.listByLineOrComma

// Xray-core dropped the standalone h2 ("http" over TLS) and quic transports; the
// shipped binary registers neither, so it answers such a config with "unknown
// transport protocol". sing-box still implements both, so these profiles have to
// run there — resolvedCore() routes them away from the Xray plugin.
fun VMessBean.xrayLacksTransport(): Boolean =
    type == "quic" || (type == "http" && isTLS())

// Xray-core 自 2026-06-01 起在生成配置时拒绝 allowInsecure（已移除的功能，见固定版本 v26.3.27 的
// infra/conf/transport_internet.go）；sing-box 仍支持 "insecure"，所以这类节点改走 sing-box，与
// xrayLacksTransport 同样的回退。证书固定不受影响：pinnedPeerCertSha256 是官方给的替代，本来就优先于
// allowInsecure。全局开关由调用方传入：选核（coreForType）与 buildXrayConfig 共用
fun VMessBean.xrayLacksAllowInsecure(globalAllowInsecure: Boolean): Boolean =
    isTLS() && certificateFingerprint.isBlank() && effectiveAllowInsecure(allowInsecure, globalAllowInsecure)

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
    if (bean.xrayLacksTransport()) {
        error("xray-core no longer supports the ${bean.type} transport, use the sing-box core for this profile")
    }
    if (bean.xrayLacksAllowInsecure(settings.globalAllowInsecure)) {
        error("xray-core no longer supports allowInsecure, use a certificate fingerprint or the sing-box core for this profile")
    }
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
        put("streamSettings", buildXrayStreamSettings(bean, settings.globalAllowInsecure))
        // xudp rides on xray mux; packetaddr is not supported by xray.
        // vision flow doesn't support mux; without mux VLESS carries UDP natively.
        if (!bean.isVisionFlow && (bean.enableMux || bean.packetEncoding == 2)) {
            put("mux", LinkedHashMap<String, Any?>().apply {
                put("enabled", true)
                // -1 leaves TCP un-muxed, so packetEncoding=xudp alone only moves UDP
                // onto xudp (sing-box packet_encoding semantics); mux.cool for TCP
                // needs an explicit enableMux
                put(
                    "concurrency",
                    if (bean.enableMux) (if (bean.muxConcurrency > 0) bean.muxConcurrency else 8) else -1
                )
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

private fun buildXrayStreamSettings(bean: VMessBean, globalAllowInsecure: Boolean): Map<String, Any?> {
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
                    // Xray only reads early data from "?ed=N" in the path; the
                    // maxEarlyData/earlyDataHeaderName keys are silently ignored.
                    val earlyData = bean.resolveWsEarlyData()
                    var path = earlyData.path
                    earlyData.maxEarlyData?.let {
                        path += (if (path.contains("?")) "&" else "?") + "ed=$it"
                    }
                    put("path", path)
                    if (bean.host.isNotBlank()) {
                        put("headers", LinkedHashMap<String, Any?>().apply { put("Host", bean.host) })
                    }
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
                // xray's json tag is all-lowercase; httpUpgradeSettings is
                // silently ignored, dropping path and Host
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
                // post-quantum REALITY; Xray-only, sing-box 1.13 does not support it
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
                // Pinning wins over allowInsecure, the same policy as
                // buildMihomoConfig: Xray hex-decodes pinnedPeerCertSha256 like
                // mihomo and checks it against every served certificate with
                // InsecureSkipVerify on (transport/internet/tls/pin.go), so a
                // pin already skips the name/expiry checks allowInsecure is
                // used for.
                val certPin = bean.certificateFingerprint.takeIf { it.isNotBlank() }
                if (certPin != null) {
                    require(isCertificateFingerprint(certPin)) {
                        "Invalid certificate fingerprint: expected a SHA-256 digest of 64 hex characters (colons allowed)"
                    }
                    put("pinnedPeerCertSha256", certPin)
                } else if (effectiveAllowInsecure(bean.allowInsecure, globalAllowInsecure)) {
                    put("allowInsecure", true)
                }
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
