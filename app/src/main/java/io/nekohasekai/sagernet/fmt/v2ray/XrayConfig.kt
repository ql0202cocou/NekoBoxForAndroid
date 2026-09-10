package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.toStringPretty
import moe.matsuri.nb4a.utils.echAsBase64
import moe.matsuri.nb4a.utils.listByLineOrComma
import org.json.JSONArray
import org.json.JSONObject

// Xray-core dropped the standalone h2 ("http" over TLS) and quic transports; the
// shipped binary registers neither, so it answers such a config with "unknown
// transport protocol". sing-box still implements both, so these profiles have to
// run there — resolvedCore() routes them away from the Xray plugin.
fun VMessBean.xrayLacksTransport(): Boolean =
    type == "quic" || (type == "http" && isTLS())

// Builds an Xray-core client config for a VMess/VLESS profile:
// a local socks inbound chained from sing-box, and the profile as outbound.
fun buildXrayConfig(bean: VMessBean, port: Int): String {
    if (bean.xrayLacksTransport()) {
        error("xray-core no longer supports the ${bean.type} transport, use the sing-box core for this profile")
    }
    val user = JSONObject().apply {
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

    val outbound = JSONObject().apply {
        put("protocol", if (bean.isVLESS) "vless" else "vmess")
        put("settings", JSONObject().apply {
            put("vnext", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", bean.finalAddress)
                    put("port", bean.finalPort)
                    put("users", JSONArray().apply { put(user) })
                })
            })
        })
        put("streamSettings", buildXrayStreamSettings(bean))
        // xudp rides on xray mux; packetaddr is not supported by xray.
        // vision flow doesn't support mux; without mux VLESS carries UDP natively.
        if (!bean.isVisionFlow && (bean.enableMux || bean.packetEncoding == 2)) {
            put("mux", JSONObject().apply {
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

    return JSONObject().apply {
        put("log", JSONObject().apply {
            put("loglevel", if (DataStore.logLevel > 0) "debug" else "warning")
        })
        put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("listen", "127.0.0.1")
                put("port", port)
                put("protocol", "socks")
                put("settings", JSONObject().apply { put("udp", true) })
            })
        })
        put("outbounds", JSONArray().apply { put(outbound) })
    }.toStringPretty()
}

private fun buildXrayStreamSettings(bean: VMessBean): JSONObject {
    // 经 mapping 外核只能拨到本地地址，TLS SNI 需要显式兜底；
    // 与 sing-box 对齐：sni 为空时兜底为 serverAddress（IP 也一样）
    val sni = bean.sni.takeIf { it.isNotBlank() }
        ?: bean.serverAddress.takeIf { it.isNotBlank() }
    return JSONObject().apply {
        // transport
        when (bean.type) {
            "ws" -> {
                put("network", "ws")
                put("wsSettings", JSONObject().apply {
                    // Xray only reads early data from "?ed=N" in the path; the
                    // maxEarlyData/earlyDataHeaderName keys are silently ignored.
                    val earlyData = bean.resolveWsEarlyData()
                    var path = earlyData.path
                    earlyData.maxEarlyData?.let {
                        path += (if (path.contains("?")) "&" else "?") + "ed=$it"
                    }
                    put("path", path)
                    if (bean.host.isNotBlank()) {
                        put("headers", JSONObject().apply { put("Host", bean.host) })
                    }
                })
            }

            // h2 is rejected by xrayLacksTransport(); only the v2ray-style
            // tcp fake-http header form reaches here
            "http" -> {
                put("network", "tcp")
                put("tcpSettings", JSONObject().apply {
                    put("header", JSONObject().apply {
                        put("type", "http")
                        put("request", JSONObject().apply {
                            put("path", JSONArray().apply {
                                put(bean.path.takeIf { it.isNotBlank() } ?: "/")
                            })
                            if (bean.host.isNotBlank()) {
                                put("headers", JSONObject().apply {
                                    put("Host", JSONArray(bean.host.listByLineOrComma()))
                                })
                            }
                        })
                    })
                })
            }

            "grpc" -> {
                put("network", "grpc")
                put("grpcSettings", JSONObject().apply {
                    put("serviceName", bean.path)
                })
            }

            "httpupgrade" -> {
                put("network", "httpupgrade")
                // xray's json tag is all-lowercase; httpUpgradeSettings is
                // silently ignored, dropping path and Host
                put("httpupgradeSettings", JSONObject().apply {
                    if (bean.host.isNotBlank()) put("host", bean.host)
                    put("path", bean.path.takeIf { it.isNotBlank() } ?: "/")
                })
            }

            else -> put("network", "tcp")
        }

        // security
        val fp = bean.effectiveUtlsFingerprint()
        // Hidden REALITY fields survive disabling TLS in the editor. Honor the
        // security switch, matching buildSingBoxOutboundTLS, before using them.
        if (bean.security == "tls" && bean.realityPubKey.isNotBlank()) {
            bean.requireValidReality()
            require(isRealityMldsa65Verify(bean.realityMldsa65Verify)) {
                "Invalid REALITY mldsa65Verify: expected 2603 URL-safe Base64 characters (1952 bytes)"
            }
            put("security", "reality")
            put("realitySettings", JSONObject().apply {
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
            put("tlsSettings", JSONObject().apply {
                if (sni != null) put("serverName", sni)
                if (bean.alpn.isNotBlank()) {
                    put("alpn", JSONArray(bean.alpn.listByLineOrComma()))
                }
                if (bean.allowInsecure || DataStore.globalAllowInsecure) {
                    put("allowInsecure", true)
                }
                fp?.let { put("fingerprint", it) }
                if (bean.certificates.isNotBlank()) {
                    put("certificates", JSONArray().apply {
                        put(JSONObject().apply {
                            put("usage", "verify")
                            put("certificate", JSONArray(bean.certificates.lines()))
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
