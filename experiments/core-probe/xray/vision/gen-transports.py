# Vision 传输矩阵：VLESS + xtls-rprx-vision 跑在 RAW 以外的传输、或不带 TLS 时，核实两个核心的连接行为。
# 默认服务端是 Xray；矩阵后半另有 sing-box 独有传输与同核对照。客户端：
#   xray-*：同一个 Xray 进程里放客户端出站（入口 dokodemo :31000 / socks :31080）
#   sb-*：vendored sing-box（libcore/sing-box，标签同 libcore build_tags.sh）编的 linux/arm64 命令行，入口与应用的映射入站同形
#         （direct 入站 :31000，override 到 127.0.0.1:40000）；UDP 用 mixed 入站 :31080。出站按 ProtocolHandlers 的 vision 形状
#         （flow + packet_encoding xudp，不写 multiplex）。编译（在 libcore/ 下，GOTOOLCHAIN 取 .github/workflows/libcore.yml 固定的 Go 版本）：
#         source ./build_tags.sh && cd sing-box && CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags='-s -w -checklinkname=0' \
#         -tags="$LIBCORE_BUILD_TAGS" -o <输出> ./cmd/sing-box（sing-vmess 与 libcore/go.mod 的版本一致，Vision 的实现在它里面）
# 用法：python3 gen-transports.py（生成 transports/）；判读见 run-transports.sh
import copy, itertools, os, shutil, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from probe_common import (CA, FULL, LEAF, LEAF_KEY, P, PRIV, PUB, SID, U, UDP_ARGS, UUID, direct_out, entry_inbound, pinned_tls_client, reality_client,
                          reality_server, reality_target, tls_server, vless, write_json, write_manifest)

OUT = os.path.join(HERE, "transports")
shutil.rmtree(OUT, ignore_errors=True)
os.makedirs(OUT)

VISION = "xtls-rprx-vision"

# 传输：Xray 的 streamSettings 网络部分（服务端与 Xray 客户端同形）、sing-box 的 transport
NETS = {
    "tcp": ({"network": "tcp"}, None),
    "ws": ({"network": "ws", "wsSettings": {"path": "/ws"}}, {"type": "ws", "path": "/ws"}),
    "grpc": ({"network": "grpc", "grpcSettings": {"serviceName": "svc"}}, {"type": "grpc", "service_name": "svc"}),
    "httpupgrade": ({"network": "httpupgrade", "httpupgradeSettings": {"path": "/up"}}, {"type": "httpupgrade", "path": "/up"}),
    "tcphttp": ({"network": "tcp", "tcpSettings": {"header": {"type": "http", "request": {"path": ["/"]}}}}, None),
}


def server(net, sec, udp=False):
    s = copy.deepcopy(NETS[net][0])
    inb = []
    if sec == "tls":
        s.update(tls_server(alpn=None))
    elif sec == "reality":
        s.update(reality_server())
        inb.append(reality_target(alpn=None))
    inb.append({"tag": "in-s", "listen": "127.0.0.1", "port": 32000, "protocol": "vless",
                "settings": {"users": [{"id": UUID, "flow": VISION}], "decryption": "none"}, "streamSettings": s})
    return inb, direct_out("127.0.0.1:40053" if udp else None)


def xray_client_stream(net, sec):
    s = copy.deepcopy(NETS[net][0])
    if sec == "tls":
        s.update({"security": "tls", "tlsSettings": pinned_tls_client()})
    elif sec == "reality":
        s.update({"security": "reality", "realitySettings": reality_client("chrome")})
    return s


def xray_cfg(net, sec, udp=False):
    inb, direct = server(net, sec, udp)
    out = {"tag": "out-c", **vless("127.0.0.1", 32000, stream=xray_client_stream(net, sec), flow=VISION)}
    return {"log": {"loglevel": "debug", "access": "none"}, "inbounds": [entry_inbound(udp)] + inb,
            "outbounds": [{"tag": "block", "protocol": "blackhole"}, out, direct],
            "routing": {"rules": [{"type": "field", "inboundTag": ["in-c"], "outboundTag": "out-c"},
                                  {"type": "field", "inboundTag": ["in-s", "in-target"], "outboundTag": "direct"}]}}


def xray_server_cfg(net, sec, udp=False):
    inb, direct = server(net, sec, udp)
    return {"log": {"loglevel": "debug", "access": "none"}, "inbounds": inb, "outbounds": [direct],
            "routing": {"rules": [{"type": "field", "inboundTag": ["in-s", "in-target"], "outboundTag": "direct"}]}}


def sb_cfg(net, sec, udp=False):
    out = {"type": "vless", "tag": "out", "server": "127.0.0.1", "server_port": 32000, "uuid": UUID, "flow": VISION,
           "packet_encoding": "xudp"}
    if sec == "tls":
        out["tls"] = {"enabled": True, "server_name": "node.example.com", "certificate": CA}
    elif sec == "reality":
        out["tls"] = {"enabled": True, "server_name": "node.example.com", "utls": {"enabled": True, "fingerprint": "chrome"},
                      "reality": {"enabled": True, "public_key": PUB, "short_id": SID}}
    if NETS[net][1]:
        out["transport"] = NETS[net][1]
    if udp:
        inb = {"type": "mixed", "tag": "in", "listen": "127.0.0.1", "listen_port": 31080, "users": [{"username": U, "password": P}]}
    else:
        inb = {"type": "direct", "tag": "in", "listen": "127.0.0.1", "listen_port": 31000,
               "override_address": "127.0.0.1", "override_port": 40000}
    return {"log": {"level": "debug"}, "inbounds": [inb], "outbounds": [out], "route": {"final": "out"}}


CASES = []  # (id, kind, args, expect, note)
numbers = itertools.count(1)


def new_case(suffix, files):
    # files：配置文件名（不含 .json）→ 配置；编号按调用顺序
    cid = f"VT-{next(numbers):02d}-{suffix}"
    d = os.path.join(OUT, cid)
    os.makedirs(d)
    for name, cfg in files.items():
        write_json(os.path.join(d, name + ".json"), cfg)
    return cid


def case(core, net, sec, udp, expect, note):
    if core == "xray":
        files = {"xray": xray_cfg(net, sec, udp)}
    else:
        files = {"xray": xray_server_cfg(net, sec, udp), "sing-box": sb_cfg(net, sec, udp)}
    cid = new_case(f"{core}-{net}-{sec}" + ("-udp" if udp else ""), files)
    CASES.append((cid, core + ("-udp" if udp else "-tcp"), UDP_ARGS if udp else "-", expect, note))


for core in ("xray", "sb"):
    case(core, "tcp", "tls", False, "PASS", "对照：RAW + TLS")
    case(core, "tcp", "reality", False, "PASS" if core == "xray" else "FAIL",
         "对照：RAW + REALITY" if core == "xray" else
         "跨核负例：sing-box 去掉 X25519MLKEM768，被 Xray >=26.9.8 转发到 target；26.10.31 / 26.11.9 差分复核均失败")
    for net in ("ws", "grpc", "httpupgrade"):
        case(core, net, "tls", False, "FAIL", "待核实：非 RAW 传输 + TLS")
    case(core, "grpc", "reality", False, "FAIL", "待核实：gRPC + REALITY（Xray 允许 REALITY 配 gRPC）")
    case(core, "tcp", "none", False, "FAIL", "待核实：不带 TLS")
    case(core, "ws", "tls", True, "-", "UDP：只记录实际结果")
    case(core, "tcp", "tls", True, "UDP:REPLY", "对照：RAW + TLS 的 UDP")
case("xray", "tcphttp", "tls", False, "-", "Xray 的 tcp http 头伪装 + TLS：只记录实际结果（sing-box 没有这个形状）")

# sing-box 独有的传输（Xray 服务端已不支持）：服务端换成同一个 sing-box 命令行，用户不带 flow，只看客户端 Vision 初始化是否失败。
# 传输与生成器同形：http 带 TLS 是 h2、不带是 tcp 伪 HTTP 头；quic 必须带 TLS
SB_NETS = {"h2": ({"type": "http", "path": "/h2"}, "tls"), "quic": ({"type": "quic"}, "tls"), "httpheader": ({"type": "http", "path": "/"}, "none")}


def sb_server_cfg(transport, sec):
    inb = {"type": "vless", "tag": "in-s", "listen": "127.0.0.1", "listen_port": 32000, "users": [{"uuid": UUID}], "transport": transport}
    if sec == "tls":
        inb["tls"] = {"enabled": True, "server_name": "node.example.com", "certificate": LEAF, "key": LEAF_KEY}
    return {"log": {"level": "debug"}, "inbounds": [inb], "outbounds": [{"type": "direct", "tag": "direct"}], "route": {"final": "direct"}}


for name, (transport, sec) in SB_NETS.items():
    c = sb_cfg("tcp", sec)
    c["outbounds"][0]["transport"] = transport
    cid = new_case(f"sb-{name}-{sec}", {"sing-box-server": sb_server_cfg(transport, sec), "sing-box": c})
    # 对照：同一对配置去掉 flow，确认失败只来自 Vision
    c2 = copy.deepcopy(c)
    del c2["outbounds"][0]["flow"]
    cid2 = new_case(f"sb-{name}-{sec}-noflow", {"sing-box-server": sb_server_cfg(transport, sec), "sing-box": c2})
    CASES.append((cid, "sb-tcp", "-", "FAIL", f"sing-box 服务端，{name}：Vision 初始化应失败"))
    CASES.append((cid2, "sb-tcp", "-", "PASS", "对照：同一传输不带 flow"))

# sing-box 客户端配 httpupgrade 时 Vision 初始化不报错（只有 Xray 服务端拒收）：换成 sing-box 服务端、用户带 vision，看两端都是 sing-box 时能否通
srv = sb_server_cfg(NETS["httpupgrade"][1], "tls")
srv["inbounds"][0]["users"][0]["flow"] = VISION
cid = new_case("sb-httpupgrade-tls-sbserver", {"sing-box-server": srv, "sing-box": sb_cfg("httpupgrade", "tls")})
CASES.append((cid, "sb-tcp", "-", "-", "sing-box 客户端 + sing-box 服务端（带 vision），httpupgrade + TLS：只记录实际结果"))

# 与跨核负例 VT-11 对照：同一 sing-box 客户端改连 sing-box REALITY 服务端，确认 RAW + REALITY 的 Vision 本身可用。
srv = sb_server_cfg(None, "none")
del srv["inbounds"][0]["transport"]
srv["inbounds"][0]["users"][0]["flow"] = VISION
srv["inbounds"][0]["tls"] = {
    "enabled": True, "server_name": "node.example.com",
    "reality": {"enabled": True, "handshake": {"server": "127.0.0.1", "server_port": 32100},
                "private_key": PRIV, "short_id": [SID]},
}
srv["inbounds"].append({"type": "trojan", "tag": "target", "listen": "127.0.0.1", "listen_port": 32100,
                        "users": [{"password": "example-target-pass"}],
                        "tls": {"enabled": True, "certificate": FULL, "key": LEAF_KEY, "alpn": ["h2", "http/1.1"]}})
cid = new_case("sb-tcp-reality-sbserver", {"sing-box-server": srv, "sing-box": sb_cfg("tcp", "reality")})
CASES.append((cid, "sb-tcp", "-", "PASS", "同核对照：sing-box 客户端与服务端，RAW + REALITY + Vision"))

write_manifest(OUT, ("id", "kind", "args", "expect", "note"), CASES)
print(len(CASES), "cases")
