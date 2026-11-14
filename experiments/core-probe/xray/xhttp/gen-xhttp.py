# XHTTP 回环与 run -test 探测用例（模拟器 arm64）。
# 材料与构件见上一级 probe_common.py：全部虚构（example.com / .net / .org、2001:db8::/32、127.0.0.1）。
# 本脚本只写 xhttp/test、xhttp/loopback 与 xhttp/loopback-gen。用法：python3 gen-xhttp.py
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from probe_common import (DEFAULT_ALPN, LOOPBACK_COLUMNS, SNI, TEST_COLUMNS, UDP_ARGS, block_out, direct_out, entry_inbound,
                          pinned_tls_client, reality_client, reality_server, reality_target, reset_dir, rule, server_inbound, tls_server,
                          trojan, vless, vmess, write_suite, xray_config)

TEST = os.path.join(HERE, "test")
LB = os.path.join(HERE, "loopback")
LBGEN = os.path.join(HERE, "loopback-gen")      # 生成器（Kotlin 的 buildXrayOutbound）真实输出 × 真服务端
GENOUT = os.path.join(HERE, "genout")           # Kotlin 导出器写出的样本；更新方法见 ../../README.md
for d in (TEST, LB, LBGEN):
    reset_dir(d)

# 规范 extra 直接取自 Kotlin 生成器的样本（canonicalXhttpExtra 的输出形态：规范键名、白名单顺序、范围两端相等写整数），
# 回环验证的就是生成器会写的内容；XrayConfigTest 另外断言 canonicalXhttpExtra 的结果就是这一份
EXTRA_CANON = json.load(open(os.path.join(GENOUT, "vless-tls.json")))["streamSettings"]["xhttpSettings"]["extra"]


def cli_xhttp(path="/xh", host=None, mode=None, extra=None):
    x = {"path": path}
    if host is not None:
        x["host"] = host
    if mode is not None:
        x["mode"] = mode
    if extra is not None:
        x["extra"] = extra
    return x


def stream(x, sec="none", sni=SNI, alpn=None, fp=None):
    s = {"network": "xhttp", "xhttpSettings": x}
    if sec == "tls":
        s.update({"security": "tls", "tlsSettings": pinned_tls_client(fp, sni, alpn)})
    elif sec == "reality":
        s.update({"security": "reality", "realitySettings": reality_client(fp, sni)})
    return s


def client_out(proto, strm, port=32000, flow=None, mux=None):
    if proto == "trojan":
        return trojan("127.0.0.1", port, stream=strm, mux=mux)
    if proto == "vmess":
        return vmess("127.0.0.1", port, stream=strm, mux=mux)
    return vless("127.0.0.1", port, stream=strm, flow=flow, mux=mux)


def server_in(proto, sx, sec="none", alpn=DEFAULT_ALPN, flow=None):
    strm = {"network": "xhttp", "xhttpSettings": sx}
    if sec == "tls":
        strm.update(tls_server(alpn=alpn))
    elif sec == "reality":
        strm.update(reality_server(show=True))
    return server_inbound(proto, strm, flow)


def lb(proto="vless", sec="none", sx=None, cx=None, cli_kw=None, strm_kw=None, srv_kw=None, udp_client=False, loglevel="info",
       server=True, mapping=None, server_flow=None):
    """服务端入站 in-s :32000（server=False 时不起，客户端拨向探针）+ 客户端出站 out-c；客户端入口 dokodemo :31000（或 socks :31080，服务端出站改投 UDP 回显）。
    mapping：None 不映射；"tcp" / "udp" / "tcp,udp" 起一个 dokodemo 模拟映射入站 :32500 → 127.0.0.1:32000，客户端拨 :32500。"""
    inb = [entry_inbound(udp_client)]
    sx = sx if sx is not None else {"path": "/xh"}
    cx = cx if cx is not None else cli_xhttp()
    strm = stream(cx, sec, **(strm_kw or {}))
    dial_port = 32000
    if mapping:
        dial_port = 32500
        inb.append({"tag": "in-map", "listen": "127.0.0.1", "port": 32500, "protocol": "dokodemo-door",
                    "settings": {"address": "127.0.0.1", "port": 32000, "network": mapping}})
    out = client_out(proto, strm, port=dial_port, **(cli_kw or {}))
    out["tag"] = "out-c"
    rules = [rule(["in-c"], "out-c")]
    outs = [block_out(), out]
    if server:
        inb.append(server_in(proto, sx, sec, flow=server_flow, **(srv_kw or {})))
        if sec == "reality":
            inb.append(reality_target())
        outs.append(direct_out(udp_client))
        rules.append(rule(["in-s", "in-target"] if sec == "reality" else ["in-s"], "direct"))
    if mapping:
        outs.append({"tag": "direct-map", "protocol": "freedom"})
        rules.append(rule(["in-map"], "direct-map"))
    return xray_config(inb, outs, rules, loglevel)


L = []


def l(slug, cfg, kind, expect, item, note="", args=""):
    L.append((slug, cfg, kind, args, expect, item, "-", note))


HOSTLOG = "HTTP version 1.1, host "
# ============ 1. Host 头（无 TLS）
# 1a 客户端 → 探针（看真正发出的请求）
l("dump-host-empty", lb(server=False), "dump", "dump:+Host: 127.0.0.1 && log:+" + HOSTLOG + "127.0.0.1", "1", "host 为空：Host = 拨号地址（应用里就是映射地址 127.0.0.1），不带端口")
l("dump-host-domain", lb(server=False, cx=cli_xhttp(host="node.example.com")), "dump", "dump:+Host: node.example.com && dump:-Host: 127.0.0.1", "1", "host 写域名：Host 头就是它")
l("dump-host-v6-raw", lb(server=False, cx=cli_xhttp(host="2001:db8::1")), "dump", "dump:-HOST= && log:+invalid port", "1", "host 写不带方括号的 IPv6：Xray 构造不出请求（invalid port），一个请求都发不出去 → 生成器必须写带方括号的形式")
l("dump-host-v6-bracket", lb(server=False, cx=cli_xhttp(host="[2001:db8::1]")), "dump", "dump:+Host: [2001:db8::1]", "1", "host 写带方括号的 IPv6")
d6 = lb(server=False)
d6["outbounds"][1]["settings"]["vnext"][0]["address"] = "::1"
l("dump-direct-v6-addr", d6, "dump", "dump:+Host: [::1]", "1", "对照：直连 IPv6 地址、host 为空时 Xray 自己发的 Host 形态（天然形态）", args="-listen [::1]:32000")
l("dump-host-with-port", lb(server=False, cx=cli_xhttp(host="node.example.com:8443")), "dump", "dump:+Host: node.example.com:8443", "1", "host 带端口：原样")
l("dump-extra-headers", lb(server=False, cx=cli_xhttp(extra=EXTRA_CANON)), "dump", "dump:+X-K2-Probe: k2-probe-value", "6", "extra.headers 真的发出去了")
# 1b 客户端 ↔ 真服务端，服务端 xhttp host 校验
SH = {"path": "/xh", "host": "node.example.com"}
l("host-srvhost-cli-empty", lb(sx=SH), "tcp", "FAIL", "1", "服务端设 host，客户端 host 为空 → Host=127.0.0.1 被拒")
l("host-srvhost-cli-same", lb(sx=SH, cx=cli_xhttp(host="node.example.com")), "tcp", "PASS", "1", "host=服务器地址 → 通")
l("host-srvnone-cli-empty", lb(), "tcp", "PASS", "1", "服务端不设 host：客户端 host 为空也通（多数节点的情形）")
l("host-srvnone-cli-domain", lb(cx=cli_xhttp(host="node.example.com")), "tcp", "PASS", "1", "服务端不设 host：写了 host 也通")
l("host-srv-loopbackip-cli-empty", lb(sx={"path": "/xh", "host": "127.0.0.1"}), "tcp", "PASS", "1", "对照：服务端 host 恰为 127.0.0.1，客户端为空 → 通（证明比较的是 Host 头）")
l("host-v6-raw-raw", lb(sx={"path": "/xh", "host": "2001:db8::1"}, cx=cli_xhttp(host="2001:db8::1")), "tcp", "FAIL && log:+invalid port", "1", "IPv6：两侧都写裸地址 → 客户端构造不出请求（invalid port）")
l("host-v6-br-br", lb(sx={"path": "/xh", "host": "[2001:db8::1]"}, cx=cli_xhttp(host="[2001:db8::1]")), "tcp", "FAIL && log:+failed to validate host", "1", "IPv6：两侧都写带方括号 → 服务端的 host 校验对不上（Xray 自己的限制：服务端 host 设成 IPv6 字面量，任何客户端写法都过不了）")
l("host-v6-srvraw-clibr", lb(sx={"path": "/xh", "host": "2001:db8::1"}, cx=cli_xhttp(host="[2001:db8::1]")), "tcp", "FAIL && log:+failed to validate host", "1", "IPv6：服务端裸、客户端带方括号")
l("host-v6-srvbr-cliraw", lb(sx={"path": "/xh", "host": "[2001:db8::1]"}, cx=cli_xhttp(host="2001:db8::1")), "tcp", "FAIL && log:+invalid port", "1", "IPv6：服务端带方括号、客户端裸")
l("host-v6-srvnone-cliraw", lb(cx=cli_xhttp(host="2001:db8::1")), "tcp", "FAIL && log:+invalid port", "1", "IPv6：服务端不设 host，客户端写裸地址 → 不通")
l("host-v6-srvnone-clibr", lb(cx=cli_xhttp(host="[2001:db8::1]")), "tcp", "PASS && log:+host [2001:db8::1]", "1", "IPv6：服务端不设 host，客户端写带方括号 → 通（生成器补 host 用的就是这种写法）")
l("host-vmess-same", lb(proto="vmess", sx=SH, cx=cli_xhttp(host="node.example.com")), "tcp", "PASS", "1", "VMess 同 VLESS")
l("host-vmess-empty", lb(proto="vmess", sx=SH), "tcp", "FAIL", "1", "VMess 同 VLESS")
l("host-trojan-same", lb(proto="trojan", sx=SH, cx=cli_xhttp(host="node.example.com")), "tcp", "PASS", "1", "Trojan（无 TLS 仅在私有地址放行）")
l("host-trojan-empty", lb(proto="trojan", sx=SH), "tcp", "FAIL", "1", "Trojan 同")

# ============ 2. TLS / REALITY 的 host 兜底（客户端拨 127.0.0.1，serverName 兜底）
l("tls-nohost-sni", lb(sec="tls", sx=SH), "tcp", "PASS && log:+" + "host node.example.com", "2", "TLS：host 为空，Host 取 serverName")
l("tls-nohost-sni-differs", lb(sec="tls", sx={"path": "/xh", "host": "other.example.com"}), "tcp", "FAIL", "2", "对照：服务端 host 与 SNI 不同 → 被拒（证明 Host 来自 SNI 而不是别的）")
l("tls-explicit-host", lb(sec="tls", sx={"path": "/xh", "host": "cdn.example.net"}, cx=cli_xhttp(host="cdn.example.net")), "tcp", "PASS", "2", "显式 host 优先于 SNI")
l("tls-explicit-host-vs-sni", lb(sec="tls", sx=SH, cx=cli_xhttp(host="cdn.example.net")), "tcp", "FAIL", "2", "显式 host ≠ 服务端 host → 被拒（显式 host 压过 SNI）")
a = lb(sec="tls", sx=SH)
a["outbounds"][1]["settings"]["vnext"][0]["address"] = "localhost"
l("tls-addr-localhost-sni", a, "tcp", "PASS", "2", "拨号地址是 localhost、serverName 是 node.example.com、host 为空：Host 取 serverName 而不是拨号地址（若 FAIL 则取地址）")
l("tls-nohost-nosni", lb(sec="tls", sx={"path": "/xh", "host": "127.0.0.1"}, strm_kw={"sni": None}), "tcp", "PASS", "2", "没有 serverName 时 Host 回落到拨号地址（应用里 sni 为空会兜底成 serverAddress，不会走到这里）")
l("reality-nohost-sni", lb(sec="reality", sx=SH), "tcp", "PASS && log:+" + "host node.example.com", "2", "REALITY：host 为空，Host 取 serverName")
l("reality-explicit-host", lb(sec="reality", sx={"path": "/xh", "host": "cdn.example.net"}, cx=cli_xhttp(host="cdn.example.net")), "tcp", "PASS", "2", "REALITY 显式 host")

# ============ 3. ALPN
# 3a 客户端只写 h3：往哪里拨（探针同时在 TCP 与 UDP 32000 上监听）
l("h3-observe-alpn-h3", lb(server=False, sec="tls", strm_kw={"alpn": ["h3"]}), "dumpudp", "dump:+ECHO-GOT && dump:-RAW|", "3", "alpn=[h3]：只往 UDP 发包（QUIC），TCP 探针没有连接")
l("h3-observe-alpn-h3-fp", lb(server=False, sec="tls", strm_kw={"alpn": ["h3"], "fp": "chrome"}), "dumpudp", "dump:+ECHO-GOT", "3", "alpn=[h3] 且写了 fingerprint=chrome（应用缺省会写）：仍走 QUIC")
l("h3-observe-alpn-h3-h2", lb(server=False, sec="tls", strm_kw={"alpn": ["h3", "h2"]}), "dumpudp", "dump:-ECHO-GOT && dump:+RAW|", "3", "alpn=[h3,h2]：不走 QUIC（只有单值 h3 才走）")
l("h3-observe-default", lb(server=False, sec="tls"), "dumpudp", "dump:-ECHO-GOT && dump:+RAW|", "3", "不写 alpn：不走 QUIC")
# 3b 真服务端
H3S = {"alpn": ["h3"]}
l("h3-direct", lb(sec="tls", sx=SH, srv_kw=H3S, strm_kw={"alpn": ["h3"]}), "tcp", "PASS && log:+HTTP version 3", "3", "服务端 alpn=[h3]，客户端直拨 UDP：QUIC 通")
l("h3-direct-fp", lb(sec="tls", sx=SH, srv_kw=H3S, strm_kw={"alpn": ["h3"], "fp": "chrome"}), "tcp", "PASS && log:+HTTP version 3", "3", "同上，写了 fingerprint=chrome")
l("h3-mapped-udp", lb(sec="tls", sx=SH, srv_kw=H3S, strm_kw={"alpn": ["h3"]}, mapping="tcp,udp"), "tcp", "PASS && log:+HTTP version 3", "3", "客户端拨一个 TCP+UDP 的转发入口 :32500 → 服务端 :32000（模拟映射入站 override_address/port）：QUIC 通")
l("h3-mapped-tcponly", lb(sec="tls", sx=SH, srv_kw=H3S, strm_kw={"alpn": ["h3"]}, mapping="tcp"), "tcp", "FAIL", "3", "对照：转发入口只有 TCP → QUIC 不通")
l("h3-mapped-udponly", lb(sec="tls", sx=SH, srv_kw=H3S, strm_kw={"alpn": ["h3"]}, mapping="udp"), "tcp", "PASS && log:+HTTP version 3", "3", "转发入口只有 UDP 也通（QUIC 不用 TCP）")
l("h3-client-h2-to-h3-server", lb(sec="tls", sx=SH, srv_kw=H3S, strm_kw={"alpn": ["h2"]}), "tcp", "FAIL", "3", "对照：服务端只开 h3，客户端走 h2 → 不通")
l("h3-server-h2h3", lb(sec="tls", sx=SH, srv_kw={"alpn": ["h3", "h2", "http/1.1"]}, strm_kw={"alpn": ["h2"]}), "tcp", "PASS", "3", "服务端 h3+h2，客户端 h2")
l("h2-explicit", lb(sec="tls", sx=SH, strm_kw={"alpn": ["h2"]}), "tcp", "PASS && log:+HTTP version 2", "3", "客户端 alpn=[h2]")
l("h2-default", lb(sec="tls", sx=SH), "tcp", "PASS && log:+HTTP version 2", "3", "客户端不写 alpn")
l("h1-alpn", lb(sec="tls", sx=SH, strm_kw={"alpn": ["http/1.1"]}), "tcp", "FAIL && log:+HTTP version 1.1 && log:+bogus greeting", "3", "客户端 alpn=[http/1.1]：Xray 客户端走 HTTP/1.1 over TLS，Xray 服务端只按 h2 处理（bogus greeting）→ 不通；只有前面是 CDN / 反代的服务端才可能收")

# ============ 4. Vision
l("vision-tls-xhttp", lb(sec="tls", sx=SH, server_flow="xtls-rprx-vision", cli_kw={"flow": "xtls-rprx-vision"}), "tcp", "FAIL && log:+XTLS only supports TLS and REALITY directly for now", "4", "Vision + xhttp + TLS：每次连接都失败")
l("vision-reality-xhttp", lb(sec="reality", sx=SH, server_flow="xtls-rprx-vision", cli_kw={"flow": "xtls-rprx-vision"}), "tcp", "FAIL && log:+XTLS only supports TLS and REALITY directly for now", "4", "Vision + xhttp + REALITY：同")
l("vision-udp443-tls-xhttp", lb(sec="tls", sx=SH, server_flow="xtls-rprx-vision", cli_kw={"flow": "xtls-rprx-vision-udp443"}), "tcp", "FAIL && log:+XTLS only supports TLS and REALITY directly for now", "4", "xtls-rprx-vision-udp443 同")
l("novision-tls-xhttp", lb(sec="tls", sx=SH), "tcp", "PASS", "4", "对照：同样的节点不带 flow → 通")

# ============ 5. Mux.Cool 与 XUDP
MUXC = {"enabled": True, "concurrency": 8, "xudpProxyUDP443": "allow"}
XUDP = {"enabled": True, "concurrency": -1, "xudpConcurrency": 16, "xudpProxyUDP443": "allow"}
for proto in ("vless", "vmess", "trojan"):
    l(f"mux-cool-{proto}", lb(proto=proto, cli_kw={"mux": MUXC}, loglevel="debug"), "tcp", "FAIL", "5", f"{proto} + xhttp + Mux.Cool：TCP 能不能通（服务端的反应见日志）")
    l(f"mux-cool-tls-{proto}", lb(proto=proto, sec="tls", sx=SH, cli_kw={"mux": MUXC}, loglevel="debug"), "tcp", "FAIL", "5", f"{proto} + xhttp + TLS + Mux.Cool")
    l(f"mux-xudponly-tcp-{proto}", lb(proto=proto, sec="tls", sx=SH, cli_kw={"mux": XUDP}, loglevel="debug"), "tcp", "PASS", "5", f"{proto}：只开 XUDP（concurrency=-1），TCP 不复用 → 通")
l("mux-xudponly-udp-vless", lb(proto="vless", sec="tls", sx=SH, cli_kw={"mux": XUDP}, udp_client=True, loglevel="debug"), "udp", "UDP:REPLY", "5", "VLESS：XUDP 承载 UDP，经 xhttp → 服务端 → 回显",
  args=UDP_ARGS)
l("mux-xudponly-udp-vmess", lb(proto="vmess", sec="tls", sx=SH, cli_kw={"mux": XUDP}, udp_client=True, loglevel="debug"), "udp", "UDP:REPLY", "5", "VMess：同",
  args=UDP_ARGS)
l("nomux-udp-vless", lb(proto="vless", sec="tls", sx=SH, udp_client=True, loglevel="debug"), "udp", "UDP:REPLY", "5", "对照：不写 mux，UDP（5353）默认走 XUDP", args=UDP_ARGS)
l("mux-cool-udp-vless", lb(proto="vless", sec="tls", sx=SH, cli_kw={"mux": MUXC}, udp_client=True, loglevel="debug"), "udp", "UDP:REPLY", "5", "Mux.Cool 下的 UDP 仍由 XUDP 承载，能通（失败的是 Mux.Cool 里的 TCP 子连接）", args=UDP_ARGS)

# ============ 6. extra 往返
l("extra-canonical-vless", lb(sec="tls", sx=SH, cx=cli_xhttp(extra=EXTRA_CANON)), "tcp", "PASS", "6", "规范 extra（headers + xPaddingBytes + xmux）× 默认服务端：通")
l("extra-canonical-none", lb(cx=cli_xhttp(extra=EXTRA_CANON)), "tcp", "PASS", "6", "同，无 TLS")
l("extra-canonical-reality", lb(sec="reality", sx=SH, cx=cli_xhttp(extra=EXTRA_CANON)), "tcp", "PASS", "6", "同，REALITY")
l("extra-canonical-vmess", lb(proto="vmess", sec="tls", sx=SH, cx=cli_xhttp(extra=EXTRA_CANON)), "tcp", "PASS", "6", "同，VMess")
l("extra-canonical-trojan", lb(proto="trojan", sec="tls", sx=SH, cx=cli_xhttp(extra=EXTRA_CANON)), "tcp", "PASS", "6", "同，Trojan")
l("extra-padding-mismatch", lb(sec="tls", sx=SH, cx=cli_xhttp(extra={"xPaddingBytes": "2000-3000"})), "tcp", "FAIL", "6", "客户端 xPaddingBytes 在服务端范围之外 → 服务端拒（说明 extra 里的值真的生效了）")
l("extra-padding-match", lb(sec="tls", sx=dict(SH, xPaddingBytes="2000-3000"), cx=cli_xhttp(extra={"xPaddingBytes": "2000-3000"})), "tcp", "PASS", "6", "服务端同范围 → 通")
l("extra-xmux-conn", lb(sec="tls", sx=SH, cx=cli_xhttp(extra={"xmux": {"maxConnections": 2, "cMaxReuseTimes": 1}}), loglevel="info"), "tcp", "PASS", "6", "xmux maxConnections 路径可用")
l("extra-unknown-key", lb(sec="tls", sx=SH, cx=cli_xhttp(extra={"fooBar": 1, "xPaddingBytes": "200-400"})), "tcp", "PASS", "6", "extra 里的未知键被 Xray 静默忽略（所以必须由应用的白名单拒绝，不能指望 Xray）")
l("extra-key-lowercase", lb(sec="tls", sx=SH, cx=cli_xhttp(extra={"xpaddingbytes": "2000-3000"})), "tcp", "FAIL", "6", "小写键名也生效（大小写不敏感）→ 值超出服务端范围被拒；应用用规范键名重建，不依赖这点")

# ============ 7. mode × 安全层（服务端 auto）
for sec in ("none", "tls", "reality"):
    for mode in ("auto", "packet-up", "stream-up", "stream-one"):
        # 客户端 auto 实际怎么选：无 TLS 与 TLS 都是 packet-up，REALITY 是 stream-one
        resolved = ("stream-one" if sec == "reality" else "packet-up") if mode == "auto" else mode
        l(f"mode-{sec}-{mode}", lb(sec=sec, sx=SH if sec != "none" else {"path": "/xh"}, cx=cli_xhttp(mode=mode)), "tcp", "PASS && log:+mode " + resolved + ",", "7", f"{sec} × mode={mode}（实际 {resolved}）× 服务端 auto")
for proto in ("vmess", "trojan"):
    for sec in ("none", "tls", "reality"):
        l(f"proto-{proto}-{sec}", lb(proto=proto, sec=sec, sx=SH if sec != "none" else {"path": "/xh"}), "tcp", "PASS", "7", f"{proto} × {sec}（auto）")
for mode in ("packet-up", "stream-up", "stream-one"):
    # 服务端固定一种 mode 时，客户端 auto（TLS 下是 packet-up）只在服务端也是 packet-up 时通；其余要客户端显式写同一个 mode
    l(f"mode-srv-{mode}-cli-auto", lb(sec="tls", sx=dict(SH, mode=mode)), "tcp", "PASS" if mode == "packet-up" else "FAIL && log:+packet-up mode is not allowed", "7", f"服务端固定 {mode}，客户端 auto")
    l(f"mode-srv-{mode}-cli-same", lb(sec="tls", sx=dict(SH, mode=mode), cx=cli_xhttp(mode=mode)), "tcp", "PASS", "7", f"服务端固定 {mode}，客户端也写 {mode}")

# ============ run -test / pb 组
T = []


def t(slug, cfg, cmd, expect, item, note=""):
    T.append((slug, cfg, cmd, expect, item, "-", note))


def tcfg(x, sec="tls", extra_keys=None):
    c = lb(sec=sec, server=False, cx=x)
    for k, v in (extra_keys or {}).items():
        c["outbounds"][1]["streamSettings"]["xhttpSettings"][k] = v
    return c


t("extra-object-ok", tcfg(cli_xhttp(extra=EXTRA_CANON)), "test", "OK", "6", "规范 extra 对象通过")
t("extra-string-err", tcfg(cli_xhttp(extra=json.dumps(EXTRA_CANON))), "test", "ERR:", "6", "extra 写成字符串（而不是对象）→ Xray 报错：生成器必须写对象")
t("extra-unknown-ok", tcfg(cli_xhttp(extra={"fooBar": 1})), "test", "OK", "6", "extra 未知键：run -test 通过（静默忽略）")
t("top-unknown-ok", tcfg(cli_xhttp(), extra_keys={"fooBar": 1}), "test", "OK", "6", "xhttpSettings 未知键：通过（静默忽略）")
t("headers-host-err", tcfg(cli_xhttp(extra={"headers": {"Host": "a.example.com"}})), "test", "ERR:", "6", "headers 含 Host → 报错（校验器也拒绝）")
t("headers-host-lower-err", tcfg(cli_xhttp(extra={"headers": {"host": "a.example.com"}})), "test", "ERR:", "6", "headers 含小写 host → 同")
t("xmux-both-err", tcfg(cli_xhttp(extra={"xmux": {"maxConnections": 2, "maxConcurrency": 4}})), "test", "ERR:", "6", "xmux maxConnections 与 maxConcurrency 同时设 → 报错（校验器提前拒绝）")
t("mode-bad-err", tcfg(cli_xhttp(mode="foo")), "test", "ERR:", "7", "未知 mode → 报错")
t("mode-empty-ok", tcfg(cli_xhttp(mode="")), "test", "OK", "7", "空 mode ≈ auto")
t("mode-upper-err", tcfg(cli_xhttp(mode="Stream-One")), "test", "ERR:unsupported mode: Stream-One", "7", "mode 区分大小写：写 Stream-One 整份配置起不来 → 应用必须写规范小写")
t("xmux-canon-pb", tcfg(cli_xhttp(extra=EXTRA_CANON)), "pb", 'PB:+"from": 700 && PB:+"to": 800 && PB:+"from": 1900 && PB:+"to": 2900 && PB:+"from": 8 && PB:+"to": 16 && PB:+"X-K2-Probe": "k2-probe-value"', "6", "规范 extra 的每一项都进了 pb（headers、xPaddingBytes、xmux 各字段）")
t("outer-wins-pb", tcfg(cli_xhttp(path="/outer", host="outer.example.com", mode="stream-one", extra={"path": "/inner", "host": "inner.example.com", "mode": "packet-up"})), "pb",
  'PB:+"path": "/outer" && PB:-/inner && PB:+"host": "outer.example.com"', "6", "extra 里的 host / path / mode 不生效：以外层为准（校验器因此拒绝它们）")
t("extra-case-pb", tcfg(cli_xhttp(extra={"XPADDINGBYTES": "300-500", "Headers": {"X-K2": "v"}})), "pb", 'PB:+"from": 300 && PB:+"X-K2"', "6", "extra 键名大小写不敏感")
t("padding-swap-pb", tcfg(cli_xhttp(extra={"xPaddingBytes": "500-300"})), "pb", 'PB:+"from": 300', "6", "from > to 被对调")
t("download-settings-pb", tcfg(cli_xhttp(extra={"downloadSettings": {"address": "dl.example.org", "port": 443, "network": "xhttp"}})), "pb", "PB:+dl.example.org", "6", "downloadSettings 被解析并生效（独立拨号目标）→ 校验器必须拒绝，生成器不写")
t("xhttp-reality", tcfg(cli_xhttp(path="/xh"), sec="reality"), "test", "OK", "2", "xhttp + REALITY 通过")
vis = tcfg(cli_xhttp())
vis["outbounds"][1]["settings"]["vnext"][0]["users"][0]["flow"] = "xtls-rprx-vision"
t("xhttp-vision-test", vis, "test", "OK", "4", "run -test 不拒绝 xhttp + Vision（只在运行时失败，所以必须靠应用的冲突规则挡）")
mx = tcfg(cli_xhttp())
mx["outbounds"][1]["mux"] = MUXC
t("xhttp-mux-test", mx, "test", "OK", "5", "run -test 不拒绝 xhttp + Mux.Cool（同上）")
h3 = lb(server=False, sec="tls", strm_kw={"alpn": ["h3"]})
t("xhttp-alpn-h3-test", h3, "test", "OK", "3", "alpn=[h3] 配置通过")
t("xhttp-reality-alpn-test", lb(server=False, sec="reality"), "test", "OK", "3", "REALITY 无 alpn 键")

# ============ 生成器真实输出 × 真服务端（loopback-gen/）
# genout/<proto>-<sec>.json 是 Kotlin 生成器对「XHTTP 节点」写出的客户端出站（XrayConfig.kt 的 buildXrayOutbound，
# serverAddress=node.example.com，extra=EXTRA_CANON 的等价输入，经映射所以拨 127.0.0.1:32000）。这里把它原样替换进回环配置的 out-c，
# 服务端 xhttp host 固定设成 node.example.com：无 TLS 时只有生成器补的 host 能过校验
G = []


def gen_case(slug, proto, sec, expect, note, sx=SH, **kw):
    out = json.load(open(os.path.join(GENOUT, slug + ".json")))
    cfg = lb(proto=proto, sec=sec, sx=sx, **kw)
    cfg["outbounds"][1] = dict(out, tag="out-c")
    G.append((slug, cfg, "tcp", "-", expect, "gen", "-", note))


for sec in ("none", "tls", "reality"):
    for proto in ("vless", "vmess", "trojan"):
        mode = "stream-one" if sec == "reality" else "packet-up"
        gen_case(f"{proto}-{sec}", proto, sec, "PASS && log:+mode " + mode + ", && log:+host node.example.com",
                 f"生成器输出 {proto} × {sec}（服务端设 host 校验）")
gen_case("vless-none-v6", "vless", "none", "PASS && log:+host [2001:db8::1]", "IPv6 服务器、无 TLS：补的 host 带方括号（服务端不设 host）", sx={"path": "/xh"})
gen_case("vless-tls-h3", "vless", "tls", "PASS && log:+HTTP version 3", "alpn=h3：生成器输出 × 服务端 h3 × TCP+UDP 转发入口", srv_kw={"alpn": ["h3"]}, mapping="tcp,udp")
for m in ("packet-up", "stream-up", "stream-one"):
    gen_case(f"vless-tls-{m}", "vless", "tls", "PASS && log:+mode " + m + ",", f"mode={m}")

# 样本与用例一一对应：缺样本时上面的 open 已报错，这里挡导出器多写、却没有用例的样本
extra_samples = {f.removesuffix(".json") for f in os.listdir(GENOUT)} - {g[0] for g in G}
assert not extra_samples, f"genout/ 里这些样本没有用例：{sorted(extra_samples)}"

# v26.11.9 复核 v26.10.31 引入的行为；追加编号，不改应用 extra 白名单。
for sec in ("none", "tls"):
    l(f"options-uplink-{sec}", lb(sec=sec, cx=cli_xhttp(mode="packet-up", extra={"uplinkHTTPMethod": "OPTIONS"})),
      "tcp", "PASS", "20", "不带 Access-Control-Request-Method 的 OPTIONS 作为正常上行")
for sec in ("tls", "reality"):
    l(f"h2params-client-ignored-{sec}", lb(sec=sec, cx=cli_xhttp(extra={"h2Params": "invalid-type"})),
      "tcp", "PASS", "20", "客户端连不可解码为 HTTP2Config 的 h2Params 也忽略；仅服务端 ListenXH 读取")

write_suite(LBGEN, "XG", LOOPBACK_COLUMNS, G)
write_suite(TEST, "XT", TEST_COLUMNS, T)
write_suite(LB, "XL", LOOPBACK_COLUMNS, L)
print(len(T), "test configs,", len(L), "loopback configs,", len(G), "generator-output configs")
